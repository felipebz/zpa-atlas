//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.DmlGrammar
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.dynamicsql.DynamicSqlText
import com.felipebz.zpa.atlas.model.PlaceShapes
import com.felipebz.zpa.atlas.model.QueryItem
import com.felipebz.zpa.atlas.model.TypeShape
import java.util.*

/**
 * The `OPEN`s of the cursor variables of one file: the queries a `FETCH` of a variable may read, those of the `OPEN`s
 * that may run last before it ([reachingOpens], [opened]), and the kind of the variable ([cursorKind]).
 */
internal class CursorOpens(private val state: FileState) {
    /** The text an `OPEN … FOR` a string opens its cursor variable for, and its `DYNAMIC` occurrence, which says what became of it. */
    private class OpenText(val text: DynamicSqlText?, val occurrence: Int)

    private val openTexts = IdentityHashMap<AstNode, OpenText>()

    /** The queries of texts whose hole in the `FROM` hides the column an item reads. */
    private val hiddenByHoles: MutableSet<AstNode> = Collections.newSetFromMap(IdentityHashMap())

    /** `OPEN cv FOR <string>` ([open]): its [text] when zpa parsed it (null when not), and its `DYNAMIC` occurrence's position. */
    fun openText(open: AstNode, text: DynamicSqlText?, occurrence: Int) {
        openTexts[open] = OpenText(text, occurrence)
    }

    /** Marks the query [query] of a text: a hole in its `FROM` may hide the column an item reads. */
    fun hide(query: AstNode) {
        hiddenByHoles += query
    }

    /**
     * What a FETCH reads of an `OPEN`: its query's items, or why they are not followed (a shape after
     * [PlaceShapes.FETCH_CURSOR_NOT_KNOWN]).
     */
    sealed interface Opened {
        class Items(val items: List<QueryItem>) : Opened
        class NotFollowed(val shape: String) : Opened
    }

    /**
     * The items of the query [open] opens its cursor variable for: a static query's, or those of a string's text zpa
     * parsed, read as the same static query would be. A text not parsed, one an item of which is a hole, or one that may
     * be partial, is not followed: by the reason of the `OPEN`'s `DYNAMIC` occurrence.
     */
    fun opened(open: AstNode): Opened {
        open.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)?.let { query ->
            return state.cursorItems[query]?.let { Opened.Items(it) } ?: Opened.NotFollowed("OPENED_STATIC")
        }
        val opened = openTexts[open] ?: return Opened.NotFollowed("OPENED_DYNAMIC")
        val reason = state.occurrences[opened.occurrence].reason ?: "EXPRESSION"
        val root = opened.text?.root
        val query =
            root?.let { if (it.type == DmlGrammar.SELECT_EXPRESSION) it else it.getFirstDescendantOrNull(DmlGrammar.SELECT_EXPRESSION) }
        if (query != null && query in hiddenByHoles) return Opened.NotFollowed(TEXT_HOLE)
        val items = query?.takeIf { !reason.endsWith(HOLES_DROPPED) }?.let { state.cursorItems[it] }
        return if (items != null) Opened.Items(items) else Opened.NotFollowed("OPENED_DYNAMIC_$reason")
    }

    /**
     * What a `FETCH` ([IntoClauses.fetch]) reads from its cursor variable [cursor], and the entity of its type when it
     * names one. zpa binds no name of a `FETCH` or `OPEN`: the variable is the declaration of that name nearest the
     * `FETCH`. The kind: `STRONG` for a `REF CURSOR` type of this file with `RETURN`, `OPENED_STATIC` when the routine
     * opens it `FOR` static queries only, `OPENED_DYNAMIC` when it opens it `FOR` a string, `PARAMETER` for a parameter it
     * does not open (the routine or block that declares the variable); null for anything else. A `FETCH` that reads the
     * queries of the `OPEN`s that may run last before it ([reachingOpens]) is told `OPENED` when this gives no kind.
     */
    fun cursorKind(cursor: AstNode, shapes: TypeShapes): Pair<String?, String?> {
        val declaration = cursorDeclaration(cursor) ?: return null to null
        var shape = shapes.of(declaration, null)
        if (shape is TypeShape.OutParameter) shape = shape.type
        val type = (shape as? TypeShape.Named)?.typeKey
        if (shape?.let(::isStrongCursor) == true) return "STRONG" to type
        // The routine or block that declares the variable (its parameters, or its declare section).
        val scope = declaration.parentOrNull?.parentOrNull
        val opens =
            scope?.getDescendants(PlSqlGrammar.OPEN_FOR_STATEMENT).orEmpty().filter { opensCursor(it, declaration) }
        return when {
            opens.isEmpty() -> if (declaration.type == PlSqlGrammar.PARAMETER_DECLARATION) "PARAMETER" else null
            opens.all { it.hasDirectChildren(DmlGrammar.SELECT_EXPRESSION) } -> "OPENED_STATIC"
            else -> "OPENED_DYNAMIC"
        } to type
    }

    /** Whether [shape], followed through type names of this file, is a `REF CURSOR` type with `RETURN`. */
    private fun isStrongCursor(shape: TypeShape): Boolean = when (shape) {
        is TypeShape.Unknown -> shape.strongCursor
        is TypeShape.Named -> shape.local?.let(::isStrongCursor) == true
        else -> false
    }

    /**
     * The `OPEN`s of the cursor variable [declaration] that may run last before the `FETCH` [fetch]: the one just before
     * it in straight code; in an `IF` or `CASE` before it, the last one of each branch, with those before the `IF` or
     * `CASE` when a branch (or a missing `ELSE`) opens none; in a block with no exception handler, as in straight code;
     * in a loop or a block with one before it, the last ones of its body, with those before it, as the body may not run
     * them. In a loop around the `FETCH`, also those after it, from the
     * turn before; in an exception handler, any of the block's body. Empty when none is found up to the routine or
     * block that declares the variable.
     */
    fun reachingOpens(fetch: AstNode, declaration: AstNode): List<AstNode> {
        val scope = declaration.parentOrNull?.parentOrNull ?: return emptyList()
        val found = ArrayList<AstNode>()
        var current: AstNode = listedStatement(fetch) ?: return emptyList()
        while (true) {
            val list = current.parent
            val at = list.children.indexOfFirst { it === current }
            if (lastOpens(list.children.subList(0, at), declaration, found)) return found
            var owner = list.parent
            if (owner.type in LOOPS) found += list.children.subList(at + 1, list.children.size)
                .flatMap { lastOpensIn(it, declaration, found).first }
            if (owner.type == PlSqlGrammar.EXCEPTION_HANDLER) {
                val section =
                    generateSequence(owner) { it.parentOrNull }.first { it.type == PlSqlGrammar.STATEMENTS_SECTION }
                found += section.getChildren(PlSqlGrammar.STATEMENTS).flatMap { body ->
                    body.getDescendants(PlSqlGrammar.OPEN_FOR_STATEMENT).filter { opensCursor(it, declaration) }
                }
                owner = section
            }
            if (owner.type == PlSqlGrammar.STATEMENTS_SECTION && owner.parentOrNull === scope) return found
            current = listedStatement(owner) ?: return found
        }
    }

    /** The statement of a statement list that is [node] or holds it, or null outside one. */
    private fun listedStatement(node: AstNode): AstNode? =
        generateSequence(node) { it.parentOrNull }.firstOrNull { it.parentOrNull?.type == PlSqlGrammar.STATEMENTS }

    /** Whether [node] is an `OPEN … FOR` of the cursor variable [declaration]. */
    private fun opensCursor(node: AstNode, declaration: AstNode) = node.type == PlSqlGrammar.OPEN_FOR_STATEMENT &&
        node.children.firstOrNull { it.type == PlSqlGrammar.MEMBER_EXPRESSION || it.type == PlSqlGrammar.VARIABLE_NAME }
            ?.let(::cursorDeclaration) === declaration

    /** Adds to [found] the `OPEN`s of [declaration] among [statements] that may run last, last first; true when one always runs. */
    private fun lastOpens(statements: List<AstNode>, declaration: AstNode, found: MutableList<AstNode>): Boolean {
        for (statement in statements.asReversed()) {
            val (opens, definite) = lastOpensIn(statement, declaration, found)
            found += opens
            if (definite) return true
        }
        return false
    }

    /** The `OPEN`s of [declaration] in [statement] that may run last, and whether one always runs ([lastOpens]). */
    private fun lastOpensIn(
        statement: AstNode,
        declaration: AstNode,
        found: MutableList<AstNode>
    ): Pair<List<AstNode>, Boolean> {
        val node =
            if (statement.type == PlSqlGrammar.STATEMENT && statement.numberOfChildren == 1) statement.firstChild else statement
        return when {
            opensCursor(node, declaration) -> listOf(node) to true
            node.type == PlSqlGrammar.IF_STATEMENT || node.type == PlSqlGrammar.CASE_STATEMENT -> lastOpensOfBranches(
                node,
                declaration,
                found
            )

            node.type == PlSqlGrammar.BLOCK_STATEMENT &&
                node.getFirstChildOrNull(PlSqlGrammar.STATEMENTS_SECTION)
                    ?.hasDirectChildren(PlSqlGrammar.EXCEPTION_HANDLERS) == false ->
                lastOpensApart(
                    node.getFirstChild(PlSqlGrammar.STATEMENTS_SECTION).getFirstChild(PlSqlGrammar.STATEMENTS).children,
                    declaration,
                    found
                )

            else -> node.getDescendants(PlSqlGrammar.OPEN_FOR_STATEMENT)
                .filter { opensCursor(it, declaration) } to false
        }
    }

    /**
     * The `OPEN`s of [declaration] that may run last in the branches of the `IF` or `CASE` [node], those of each branch in
     * turn; true when there is an `ELSE` and every branch always runs one.
     */
    private fun lastOpensOfBranches(
        node: AstNode,
        declaration: AstNode,
        found: MutableList<AstNode>
    ): Pair<List<AstNode>, Boolean> {
        val branches = node.getChildren(PlSqlGrammar.STATEMENTS) +
            node.getChildren(PlSqlGrammar.ELSIF_CLAUSE, PlSqlGrammar.ELSE_CLAUSE)
                .flatMap { it.getChildren(PlSqlGrammar.STATEMENTS) }
        val opens = ArrayList<AstNode>()
        var always = node.hasDirectChildren(PlSqlGrammar.ELSE_CLAUSE)
        for (branch in branches) {
            val (last, definite) = lastOpensApart(branch.children, declaration, found)
            opens += last
            always = always && definite
        }
        return opens to always
    }

    /** [lastOpens] of [statements], the `OPEN`s it finds given back apart instead of left in [found]. */
    private fun lastOpensApart(
        statements: List<AstNode>,
        declaration: AstNode,
        found: MutableList<AstNode>
    ): Pair<List<AstNode>, Boolean> {
        val before = found.size
        val always = lastOpens(statements, declaration, found)
        val opens = ArrayList(found.subList(before, found.size))
        found.subList(before, found.size).clear()
        return opens to always
    }

    /** The variable or parameter declaration of the simple name [cursor] nearest it, in the blocks and routines around it. */
    fun cursorDeclaration(cursor: AstNode): AstNode? {
        if (cursor.type != PlSqlGrammar.VARIABLE_NAME) return null
        val name = normalizeIdentifier(cursor.tokenOriginalValue)
        for (scope in generateSequence(cursor.parentOrNull) { it.parentOrNull }) {
            val declarations = scope.getChildren(PlSqlGrammar.DECLARE_SECTION)
                .flatMap { it.getChildren(PlSqlGrammar.VARIABLE_DECLARATION) } +
                scope.getChildren(PlSqlGrammar.PARAMETER_DECLARATIONS)
                    .flatMap { it.getChildren(PlSqlGrammar.PARAMETER_DECLARATION) }
            declarations.firstOrNull { normalizeIdentifier(it.firstChild.tokenOriginalValue) == name }
                ?.let { return it }
        }
        return null
    }

    companion object {
        /** After [PlaceShapes.FETCH_CURSOR_NOT_KNOWN]: a text whose hole in the `FROM` hides the column an item reads. */
        private const val TEXT_HOLE = "TEXT_HOLE"

        /** The end of the reason of a dynamic text parsed only once its holes were left out: it may be partial. */
        private const val HOLES_DROPPED = "HOLES_DROPPED"

        private val LOOPS = setOf(PlSqlGrammar.LOOP_STATEMENT, PlSqlGrammar.FOR_STATEMENT, PlSqlGrammar.WHILE_STATEMENT)
    }
}
