//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.*
import com.felipebz.zpa.api.dynamicsql.DynamicSqlText
import com.felipebz.zpa.api.project.ProjectParameterMode
import com.felipebz.zpa.api.semantics.NameBinding
import com.felipebz.zpa.api.semantics.NameDeclarationKind
import com.felipebz.zpa.atlas.model.*
import java.util.*

/**
 * The `INTO`s that read select items into PL/SQL targets ([IntoFact]): `SELECT … INTO`, `FETCH … INTO` of an explicit
 * cursor, `RETURNING … INTO`, with or without `BULK COLLECT`, and the assignments `target := r.f` of a field of a
 * record, which is a row record when it is a cursor `FOR` loop record or a `%ROWTYPE` variable (decided after the
 * scan). A select item is a column when it is the column itself, written bare or qualified (the column read static SQL
 * records at it), or a `*` over a relation whose columns the dictionary lists; an expression of one column is counted by
 * its shape. A target is the entity zpa binds its name to (the value use the walk records), else the declaration
 * without an entity zpa binds it to, with its own type; a Forms host name (`:BLOCK.ITEM`) is the module's object its
 * use names.
 *
 * Other reads of a column into PL/SQL are counted, not recorded ([FileState.placesLeftOut]): dynamic `INTO`, a field
 * of a `%ROWTYPE` or cursor loop record passed to a routine, a column passed to a routine in static SQL, and a `FETCH`
 * from a cursor whose query is not known.
 */
internal class IntoClauses(
    private val state: FileState,
    private val plsql: PlSqlNames,
    private val columnReads: ColumnReads,
    private val lineage: ViewLineage,
    private val nameBinding: (AstNode) -> NameBinding
) {
    private class Pending(
        val source: PlaceSource, val bulk: Boolean, val context: Context, val statementId: Int?, val branches: List<List<QueryItem>>,
        val cursor: AstNode?, val targets: List<AstNode>, val fetch: AstNode? = null
    )

    private val pending = ArrayList<Pending>()

    /** The select items of [statement]'s cursor query, its `INTO` or `RETURNING INTO`, and the columns it passes to routines. */
    fun statement(statement: Statement) {
        if (statement.context.dynamic != null) {
            dynamicQuery(statement)
            return
        }
        val root = statement.root
        if (root.parentOrNull?.type == PlSqlGrammar.CURSOR_DECLARATION || root.parentOrNull?.type == PlSqlGrammar.OPEN_FOR_STATEMENT || isLoopQuery(root)) {
            branches(root).firstOrNull()?.let { state.cursorItems[root] = selectItems(statement, it) }
        }
        when (root.type) {
            DmlGrammar.SELECT_EXPRESSION -> selectInto(statement, root)
            DmlGrammar.INSERT_EXPRESSION, DmlGrammar.UPDATE_EXPRESSION, DmlGrammar.DELETE_EXPRESSION -> returningInto(statement, root)
        }
        if (root.parentOrNull?.type == PlSqlGrammar.CURSOR_DECLARATION) fetchedParameters(statement, root)
        passedColumns(statement)
    }

    /**
     * The main query of a text a cursor variable may be opened for: its items, what a FETCH of it reads, unless an item
     * is a hole; the query is marked when a hole in its `FROM` may hide the column an item reads.
     */
    private fun dynamicQuery(statement: Statement) {
        val root = statement.root
        if (root.type != DmlGrammar.SELECT_EXPRESSION ||
            generateSequence(root.parentOrNull) { it.parentOrNull }.any { it.type == DmlGrammar.SELECT_EXPRESSION }) return
        val block = branches(root).firstOrNull()
            ?.takeIf { block -> block.getChildren(DmlGrammar.SELECT_COLUMN).none { statement.context.hasHoles(it) } } ?: return
        val items = selectItems(statement, block)
        state.cursorItems[root] = items
        if (hidesColumns(statement, block)) hiddenByHoles += root
    }

    /**
     * Whether a hole in the `FROM` of [block] may hide the column an item reads: a name in an item that reads no column
     * of what is known there may be a column of what the hole names.
     */
    private fun hidesColumns(statement: Statement, block: AstNode): Boolean =
        block.getFirstChildOrNull(DmlGrammar.FROM_CLAUSE)?.let(statement.context::hasHoles) == true &&
            block.getChildren(DmlGrammar.SELECT_COLUMN).any { column ->
                val value = column.firstChild
                (sequenceOf(value) + value.getDescendants(PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.MEMBER_EXPRESSION).asSequence())
                    .any { plsql.nameSegments(it) != null && statement.columnReads[it].isNullOrEmpty() }
            }

    /** `SELECT … INTO` ([root]): kept with its items and targets, the parameters it selects, and the values written into the targets. */
    private fun selectInto(statement: Statement, root: AstNode) {
        val into = root.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK)?.getFirstChildOrNull(DmlGrammar.INTO_CLAUSE) ?: return
        pending += Pending(PlaceSource.SELECT_INTO, isBulk(into), statement.context, statement.id,
            branches(root).map { selectItems(statement, it) }, null, targetsOf(into))
        for (block in branches(root)) {
            val values = block.getChildren(DmlGrammar.SELECT_COLUMN).map { it.firstChild }
            selectedParameters(statement, values, into)
            // A function called in a select item: its result is written into the item's target.
            val targets = targetsOf(into)
            if (!isBulk(into) && targets.size == values.size) {
                for ((index, value) in values.withIndex()) valueWrites += ValueWrite(value, spanOf(value), targets[index], statement.context, sql = true)
            }
        }
    }

    /** `RETURNING … INTO` of an `INSERT`, `UPDATE` or `DELETE` ([root]): kept with its items and targets, and the parameters it returns. */
    private fun returningInto(statement: Statement, root: AstNode) {
        val returning = root.getFirstDescendantOrNull(DmlGrammar.RETURNING_INTO_CLAUSE) ?: return
        val into = returning.getFirstChild(DmlGrammar.INTO_CLAUSE)
        val values = returning.children
            .filter { it.type != PlSqlKeyword.RETURNING && it.type != PlSqlKeyword.RETURN && it.type != PlSqlPunctuator.COMMA && it !== into }
        val items = values.map { item(statement, it, null) }
        pending += Pending(PlaceSource.RETURNING, isBulk(into), statement.context, statement.id, listOf(items), null, targetsOf(into))
        selectedParameters(statement, values, into)
    }

    /** The parameters the cursor query [root] selects: what a FETCH reads of them is not followed. */
    private fun fetchedParameters(statement: Statement, root: AstNode) {
        for (block in branches(root)) for (column in block.getChildren(DmlGrammar.SELECT_COLUMN)) {
            for (name in parameterNames(column.firstChild)) {
                if (columnOf(statement, name) == null) flows += Flow(name, spanOf(column), statement.context, shape = ARGUMENT_FETCHED)
            }
        }
    }

    /**
     * The routine's `IN` parameters a `SELECT … INTO` or `RETURNING … INTO` reads ([values], the items in order), as is or
     * through value functions: each is written as a column in the same position would be, into its own target, or into
     * the single target's field or element ([intos] keeps the position). A parameter in an item that is another
     * expression, or in an `INTO` whose targets do not match the items, is written in a way not followed.
     */
    private fun selectedParameters(statement: Statement, values: List<AstNode>, into: AstNode) {
        val targets = targetsOf(into)
        val single = targets.size == 1
        for ((index, value) in values.withIndex()) {
            for ((name, functions) in parameterChains(value, parameterNames(value).filter { columnOf(statement, it) == null })) {
                flows += when {
                    functions == null -> Flow(name, spanOf(value), statement.context, shape = ARGUMENT_EXPRESSION + argumentShape(value))
                    !single && targets.size != values.size -> Flow(name, spanOf(value), statement.context, shape = ARGUMENT_INTO_NOT_FOLLOWED)
                    else -> Flow(name, spanOf(value), statement.context, target = targets[if (single) 0 else index], functions = functions,
                        bulk = isBulk(into), item = if (single) index to values.size else null)
                }
            }
        }
    }

    /**
     * A routine whose body is not PL/SQL the index reads: a call spec (`LANGUAGE JAVA`, `LANGUAGE C`, `EXTERNAL`), an
     * aggregate (`AGGREGATE USING`) or a polymorphic table function (`PIPELINED … USING`). Its parameters are marked so;
     * what its callers give them is counted at each call, as for a routine outside the index.
     */
    fun function(node: AstNode, key: String?) {
        if (node.type != PlSqlGrammar.FUNCTION_DECLARATION && node.type != PlSqlGrammar.CREATE_FUNCTION) return
        val returns = returnKey(key ?: localKey(node.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) ?: return, true))
        functions[node] = returns
        if (key?.startsWith(FORMS_PREFIX) == true) moduleFunctions += returns
    }

    /** The functions of the file, by their declaration: the key of what each returns ([returnKey]). */
    private val functions = IdentityHashMap<AstNode, String>()

    /** What the function program units of the Forms module the file is return ([returnKey]): their calls are followed as a routine's. */
    private val moduleFunctions = HashSet<String>()

    /** The nested functions the calls of the file name, by the call: the key of what each returns. */
    private val localFunctions = IdentityHashMap<AstNode, String>()

    /** The key of what the function [function] (its entity key, or its nested declaration's [localKey]) returns. */
    private fun returnKey(function: String) = "$function.$RETURN_SUFFIX"

    /** What the function around [node] returns ([returnKey]); null outside a function. */
    private fun enclosingReturn(node: AstNode): String? = generateSequence(node.parentOrNull) { it.parentOrNull }.firstNotNullOfOrNull { functions[it] }

    fun routine(node: AstNode, context: Context) {
        val outside = node.hasDirectChildren(PlSqlGrammar.CALL_SPECIFICATION) || node.hasDirectChildren(PlSqlKeyword.AGGREGATE) ||
            node.children.any { it.hasDirectChildren(PlSqlKeyword.PIPELINED) && it.hasDirectChildren(PlSqlKeyword.USING) } ||
            node.hasDirectChildren(PlSqlKeyword.PIPELINED) && node.hasDirectChildren(PlSqlKeyword.USING)
        if (!outside) return
        // The routine's parameters, by the routine's key (the context is the routine's).
        val routine = context.container ?: return
        for (parameter in node.getFirstChildOrNull(PlSqlGrammar.PARAMETER_DECLARATIONS)?.getChildren(PlSqlGrammar.PARAMETER_DECLARATION).orEmpty()) {
            val name = parameter.firstChild
            flows += Flow(name, spanOf(name), context, shape = BODY_NOT_PLSQL, fromKey = "$routine.${normalizeIdentifier(name.tokenOriginalValue)}")
        }
    }

    /** `FETCH c INTO …`: the cursor's query decides the items (its own file's, for a cursor with an entity). */
    fun fetch(node: AstNode, context: Context) {
        if (context.dynamic != null) return
        val into = node.getFirstChildOrNull(DmlGrammar.INTO_CLAUSE) ?: return
        val cursor = node.children.firstOrNull { it.type == PlSqlGrammar.MEMBER_EXPRESSION || it.type == PlSqlGrammar.VARIABLE_NAME } ?: return
        pending += Pending(PlaceSource.FETCH, isBulk(into), context, null, emptyList(), cursor, targetsOf(into), node)
    }

    /**
     * What a `FETCH` ([fetch]) reads from its cursor variable [cursor], and the entity of its type when it names one.
     * zpa binds no name of a `FETCH` or `OPEN`: the variable is the declaration of that name nearest the `FETCH`. The
     * kind: `STRONG` for a `REF CURSOR` type of this file with `RETURN`, `OPENED_STATIC` when the routine opens it `FOR`
     * static queries only, `OPENED_DYNAMIC` when it opens it `FOR` a string, `PARAMETER` for a parameter it does not
     * open (the routine or block that declares the variable); null for anything else.
     */
    private fun cursorKind(cursor: AstNode, shapes: TypeShapes): Pair<String?, String?> {
        val declaration = cursorDeclaration(cursor) ?: return null to null
        var shape = shapes.of(declaration, null)
        if (shape is TypeShape.OutParameter) shape = shape.type
        val type = (shape as? TypeShape.Named)?.typeKey
        if (shape?.let(::isStrongCursor) == true) return "STRONG" to type
        // The routine or block that declares the variable (its parameters, or its declare section).
        val scope = declaration.parentOrNull?.parentOrNull
        val opens = scope?.getDescendants(PlSqlGrammar.OPEN_FOR_STATEMENT).orEmpty().filter { open ->
            open.children.firstOrNull { it.type == PlSqlGrammar.MEMBER_EXPRESSION || it.type == PlSqlGrammar.VARIABLE_NAME }
                ?.let(::cursorDeclaration) === declaration
        }
        return when {
            opens.isEmpty() -> if (declaration.type == PlSqlGrammar.PARAMETER_DECLARATION) "PARAMETER" else null
            opens.all { it.hasDirectChildren(DmlGrammar.SELECT_EXPRESSION) } -> "OPENED_STATIC"
            else -> "OPENED_DYNAMIC"
        } to type
    }

    /** The text an `OPEN … FOR` a string opens its cursor variable for, and its `DYNAMIC` occurrence, which says what became of it. */
    private class OpenText(val text: DynamicSqlText?, val occurrence: Int)

    private val openTexts = IdentityHashMap<AstNode, OpenText>()

    /** The queries of texts whose hole in the `FROM` hides the column an item reads. */
    private val hiddenByHoles: MutableSet<AstNode> = Collections.newSetFromMap(IdentityHashMap())

    /** `OPEN cv FOR <string>` ([open]): its [text] when zpa parsed it (null when not), and its `DYNAMIC` occurrence's position. */
    fun openText(open: AstNode, text: DynamicSqlText?, occurrence: Int) {
        openTexts[open] = OpenText(text, occurrence)
    }

    /** What a FETCH reads of an `OPEN`: its query's items, or why they are not followed (a shape after [FETCH_CURSOR_NOT_KNOWN]). */
    private sealed interface Opened {
        class Items(val items: List<QueryItem>) : Opened
        class NotFollowed(val shape: String) : Opened
    }

    /**
     * The items of the query [open] opens its cursor variable for: a static query's, or those of a string's text zpa
     * parsed, read as the same static query would be. A text not parsed, one an item of which is a hole, or one that may
     * be partial, is not followed: by the reason of the `OPEN`'s `DYNAMIC` occurrence.
     */
    private fun opened(open: AstNode): Opened {
        open.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)?.let { query ->
            return state.cursorItems[query]?.let { Opened.Items(it) } ?: Opened.NotFollowed("OPENED_STATIC")
        }
        val opened = openTexts[open] ?: return Opened.NotFollowed("OPENED_DYNAMIC")
        val reason = state.occurrences[opened.occurrence].reason ?: "EXPRESSION"
        val root = opened.text?.root
        val query = root?.let { if (it.type == DmlGrammar.SELECT_EXPRESSION) it else it.getFirstDescendantOrNull(DmlGrammar.SELECT_EXPRESSION) }
        if (query != null && query in hiddenByHoles) return Opened.NotFollowed(TEXT_HOLE)
        val items = query?.takeIf { !reason.endsWith(HOLES_DROPPED) }?.let { state.cursorItems[it] }
        return if (items != null) Opened.Items(items) else Opened.NotFollowed("OPENED_DYNAMIC_$reason")
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
    private fun reachingOpens(fetch: AstNode, declaration: AstNode): List<AstNode> {
        val scope = declaration.parentOrNull?.parentOrNull ?: return emptyList()
        val found = ArrayList<AstNode>()
        var current: AstNode = listedStatement(fetch) ?: return emptyList()
        while (true) {
            val list = current.parent
            val at = list.children.indexOfFirst { it === current }
            if (lastOpens(list.children.subList(0, at), declaration, found)) return found
            var owner = list.parent
            if (owner.type in LOOPS) found += list.children.subList(at + 1, list.children.size).flatMap { lastOpensIn(it, declaration, found).first }
            if (owner.type == PlSqlGrammar.EXCEPTION_HANDLER) {
                val section = generateSequence(owner) { it.parentOrNull }.first { it.type == PlSqlGrammar.STATEMENTS_SECTION }
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
        node.children.firstOrNull { it.type == PlSqlGrammar.MEMBER_EXPRESSION || it.type == PlSqlGrammar.VARIABLE_NAME }?.let(::cursorDeclaration) === declaration

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
    private fun lastOpensIn(statement: AstNode, declaration: AstNode, found: MutableList<AstNode>): Pair<List<AstNode>, Boolean> {
        val node = if (statement.type == PlSqlGrammar.STATEMENT && statement.numberOfChildren == 1) statement.firstChild else statement
        return when {
            opensCursor(node, declaration) -> listOf(node) to true
            node.type == PlSqlGrammar.IF_STATEMENT || node.type == PlSqlGrammar.CASE_STATEMENT -> lastOpensOfBranches(node, declaration, found)
            node.type == PlSqlGrammar.BLOCK_STATEMENT &&
                node.getFirstChildOrNull(PlSqlGrammar.STATEMENTS_SECTION)?.hasDirectChildren(PlSqlGrammar.EXCEPTION_HANDLERS) == false ->
                lastOpensApart(node.getFirstChild(PlSqlGrammar.STATEMENTS_SECTION).getFirstChild(PlSqlGrammar.STATEMENTS).children, declaration, found)
            else -> node.getDescendants(PlSqlGrammar.OPEN_FOR_STATEMENT).filter { opensCursor(it, declaration) } to false
        }
    }

    /**
     * The `OPEN`s of [declaration] that may run last in the branches of the `IF` or `CASE` [node], those of each branch in
     * turn; true when there is an `ELSE` and every branch always runs one.
     */
    private fun lastOpensOfBranches(node: AstNode, declaration: AstNode, found: MutableList<AstNode>): Pair<List<AstNode>, Boolean> {
        val branches = node.getChildren(PlSqlGrammar.STATEMENTS) +
            node.getChildren(PlSqlGrammar.ELSIF_CLAUSE, PlSqlGrammar.ELSE_CLAUSE).flatMap { it.getChildren(PlSqlGrammar.STATEMENTS) }
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
    private fun lastOpensApart(statements: List<AstNode>, declaration: AstNode, found: MutableList<AstNode>): Pair<List<AstNode>, Boolean> {
        val before = found.size
        val always = lastOpens(statements, declaration, found)
        val opens = ArrayList(found.subList(before, found.size))
        found.subList(before, found.size).clear()
        return opens to always
    }

    /** The variable or parameter declaration of the simple name [cursor] nearest it, in the blocks and routines around it. */
    private fun cursorDeclaration(cursor: AstNode): AstNode? {
        if (cursor.type != PlSqlGrammar.VARIABLE_NAME) return null
        val name = normalizeIdentifier(cursor.tokenOriginalValue)
        for (scope in generateSequence(cursor.parentOrNull) { it.parentOrNull }) {
            val declarations = scope.getChildren(PlSqlGrammar.DECLARE_SECTION).flatMap { it.getChildren(PlSqlGrammar.VARIABLE_DECLARATION) } +
                scope.getChildren(PlSqlGrammar.PARAMETER_DECLARATIONS).flatMap { it.getChildren(PlSqlGrammar.PARAMETER_DECLARATION) }
            declarations.firstOrNull { normalizeIdentifier(it.firstChild.tokenOriginalValue) == name }?.let { return it }
        }
        return null
    }

    /** `EXECUTE IMMEDIATE … INTO` or `RETURNING INTO`: counted, one per target. */
    fun dynamic(node: AstNode) {
        for (into in node.getDescendants(DmlGrammar.INTO_CLAUSE)) state.leftOut(DYNAMIC_INTO, targetsOf(into).size)
    }

    /**
     * `target := r.f`, or an expression of one such field (`upper(r.f)`): kept to be decided once the file is left,
     * when what `r` is is known ([intos]). Through value functions only ([valueChain]), the field is read as is.
     */
    fun assignment(node: AstNode, context: Context) {
        val at = node.children.indexOfFirst { it.type == PlSqlPunctuator.ASSIGNMENT }
        if (at <= 0) return
        val value = node.children.getOrNull(at + 1) ?: return
        valueWrites += ValueWrite(value, spanOf(node.children[at]), node.firstChild, context)
        // A field only in an argument that never lands in the value (the search text of a REPLACE) is not assigned.
        val fields = (if (isFieldOfName(value)) listOf(value) else value.getDescendants(PlSqlGrammar.MEMBER_EXPRESSION).filter(::isFieldOfName))
            .filter { !inDroppedArgument(value, it) }
        val field = fields.singleOrNull()
        if (field == null) {
            // Several fields, each a part of a concatenation (`r.code || r.note`), or one the value and the others text put
            // into it (the replacement of a REPLACE): a place for each.
            val chains = fields.map { f -> valueChain(value) { it === f } }
            if (fields.isEmpty() || chains.any { it == null } || chains.count { CONCATENATED !in it!! } > 1) return
            for ((f, chain) in fields.zip(chains)) assignments += Assignment(node.firstChild, spanOf(node.children[at]), f, null, chain!!, context)
            return
        }
        val functions = valueChain(value) { it === field }
        assignments += Assignment(node.firstChild, spanOf(node.children[at]), field, if (functions == null) value else null,
            functions.orEmpty(), context)
    }

    private class Assignment(
        val target: AstNode, val site: SourceSpan, val field: AstNode, val expression: AstNode?, val functions: List<String>, val context: Context
    )

    private val assignments = ArrayList<Assignment>()

    /** `RETURN expr`: its value leaves the routine, which the index does not follow ([intos]). */
    fun returnStatement(node: AstNode, context: Context) {
        val value = node.children.firstOrNull { it.type != PlSqlGrammar.LABELS && it.type != PlSqlKeyword.RETURN && it.type != PlSqlPunctuator.SEMICOLON }
            ?: return
        valueWrites += ValueWrite(value, spanOf(node), null, context)
    }

    /**
     * A value written in PL/SQL: assigned to [target] (`target := value`, at the `:=`), or returned (null [target]). The
     * routine's `IN` parameters it writes are told once the file is left, when its calls are known ([parameterFlows]).
     */
    private class ValueWrite(val value: AstNode, val site: SourceSpan, val target: AstNode?, val context: Context, val sql: Boolean = false)

    private val valueWrites = ArrayList<ValueWrite>()

    /**
     * A column, or a record's field, passed to a routine's `IN` parameter ([parameterKey], null when the call does not
     * tie the argument to one), through [functions]; [reason] when the call is not followed.
     */
    private class Pass(
        val argument: AstNode, val column: String?, val field: AstNode?, val functions: List<String>, val parameterKey: String?,
        val reason: String?, val context: Context, val statementId: Int?, val call: SourceSpan
    )

    private val passes = ArrayList<Pass>()

    /**
     * A routine's `IN` parameter ([parameter], its name in the code) written somewhere: into [target], passed on to
     * [passedTo], or written in a way the index does not follow ([shape]), through [functions].
     */
    private class Flow(
        val parameter: AstNode, val site: SourceSpan, val context: Context, val target: AstNode? = null, val passedTo: String? = null,
        val shape: String? = null, val functions: List<String> = emptyList(), val fromKey: String? = null, val bulk: Boolean = false,
        /** For the single target of an `INTO`: the parameter's item and the number of items. */
        val item: Pair<Int, Int>? = null,
        /** For a value passed on to a parameter ([passedTo]): the call it is an argument of. */
        val call: SourceSpan? = null,
        /** For a function's result ([fromKey] what it returns): the call whose result it is. */
        val resultOf: SourceSpan? = null
    )

    private val flows = ArrayList<Flow>()

    /**
     * Where the results of the functions a value calls go: written into [target], passed on to [passedTo] (a parameter,
     * or what the function around returns; [argumentOf] the call, for a parameter), or, passed where it is not
     * followed, counted by [reason].
     */
    private data class ResultDestination(
        val target: AstNode? = null, val passedTo: String? = null, val reason: String? = null, val argumentOf: SourceSpan? = null
    )

    /**
     * Whether [name] is a whole name zpa binds to a parameter (not the record of `p.f`, not a called routine). In SQL
     * the name may still be a column of the statement's tables ([NameBinding.ColumnOrLocal]): [intos] decides by them.
     */
    private fun isParameterName(name: AstNode) =
        name.type == PlSqlGrammar.VARIABLE_NAME && name.parentOrNull?.type != PlSqlGrammar.MEMBER_EXPRESSION &&
            parameterDeclaration(name)?.kind == NameDeclarationKind.PARAMETER

    /** The declaration zpa binds [name] to, in PL/SQL or in SQL where a column may take the name instead. */
    private fun parameterDeclaration(name: AstNode) = when (val binding = nameBinding(name)) {
        is NameBinding.Local -> binding.declaration
        is NameBinding.ColumnOrLocal -> binding.declaration
        else -> null
    }

    /** The names of parameters in [value], [value] itself included. */
    private fun parameterNames(value: AstNode) = (sequenceOf(value) + value.getDescendants(PlSqlGrammar.VARIABLE_NAME).asSequence()).filter(::isParameterName).toList()

    /** Whether [query] is the query of a cursor `FOR` loop (`for r in (select …)`). */
    private fun isLoopQuery(query: AstNode): Boolean =
        generateSequence(query.parentOrNull) { it.parentOrNull }.take(6).any { it.type == PlSqlGrammar.ITERATOR }

    /**
     * A PL/SQL call resolved to a routine: a field of a `%ROWTYPE` or cursor loop record it is passed is counted per
     * argument; an argument for an `OUT` or `IN OUT` parameter is kept, its target decided once the file is left.
     */
    fun call(node: AstNode, before: Int, context: Context) {
        if (node.type != PlSqlGrammar.METHOD_CALL) return
        val calls = state.occurrences.subList(before, state.occurrences.size).filter { Role.CALL in it.roles }
        // A subprogram nested in the routine has no entity: its call is bound here, in the file that declares both.
        if (calls.none { it.entityKey != null } && calls.any { it.reason == LOCAL_SUBPROGRAM }) {
            localCall(node, context)
            return
        }
        val resolved = calls.lastOrNull { it.entityKey != null && it.status in CALLED_STATUSES }
        if (resolved == null) {
            // A call the index does not decide: what it is passed is not followed (a built-in function is no routine).
            val reason = calls.firstNotNullOfOrNull(::unresolvedCall)
            if (reason != null) for (argument in arguments(node)) passedValue(argument, null, reason, context, spanOf(node))
            return
        }
        for (argument in arguments(node)) resolvedArgument(argument, node, resolved, context)
    }

    /**
     * An argument of the call [node] resolved to [resolved]: for an `OUT` or `IN OUT` parameter, kept with the parameter
     * the call ties it to; for any other, passed as a value ([passedValue]).
     */
    private fun resolvedArgument(argument: AstNode, node: AstNode, resolved: OccurrenceFact, context: Context) {
        // The walk keeps the mode at the argument's expression node, which may wrap or be wrapped by this one.
        val span = spanOf(argument)
        val mode = (generateSequence(argument) { it.parentOrNull }.take(3) + generateSequence(argument) { it.children.firstOrNull() }.take(4))
            .filter { it.hasToken() && spanOf(it) == span }.firstNotNullOfOrNull { state.argumentModes[it] }
        val parameter = resolved.arguments.firstOrNull { it.span == span }?.parameterKey
        if (mode == ProjectParameterMode.OUT || mode == ProjectParameterMode.IN_OUT) {
            if (parameter != null) outArguments += OutArgument(argument, parameter, context, spanOf(node))
            return
        }
        passedValue(argument, parameter, if (parameter == null) notTied(resolved) else null, context, spanOf(node))
    }

    /**
     * An argument of a PL/SQL call for an `IN` parameter ([parameter]): a record's field, read as is or through value
     * functions, is a column's value passed to the routine (decided after the scan); one of the routine's own `IN`
     * parameters is passed on. [call] is the call's span: what comes back out of the routine goes back to it.
     */
    private fun passedValue(argument: AstNode, parameter: String?, reason: String?, context: Context, call: SourceSpan) {
        callArguments += CallArgument(argument, parameter, reason, context, call)
        val field = if (isFieldOfName(argument)) argument
            else argument.getDescendants(PlSqlGrammar.MEMBER_EXPRESSION).singleOrNull { isFieldOfName(it) && !inDroppedArgument(argument, it) }
        if (field != null) {
            valueChain(argument) { it === field }?.let { passes += Pass(argument, null, field, it, parameter, reason, context, null, call) }
            return
        }
        for (name in parameterNames(argument)) {
            val functions = valueChain(argument) { it === name } ?: continue
            flows += if (reason == null && parameter != null) Flow(name, spanOf(argument), context, passedTo = parameter, functions = functions, call = call)
                else Flow(name, spanOf(argument), context, shape = reason ?: ARGUMENT_NOT_TIED)
        }
    }

    /**
     * A call of a subprogram nested in a routine: the subprogram of that name in the nearest declarations around the
     * call; its arguments bound to its parameters by position, then by name. When several subprograms of that name are
     * declared there (overloads), the call is kept for each of their `OUT` parameters as not decided, counted
     * (`OUT_CALL_NOT_DECIDED`) when a column's value reaches one of them.
     */
    private fun localCall(node: AstNode, context: Context) {
        val callee = node.firstChild.takeIf { it.type == PlSqlGrammar.VARIABLE_NAME } ?: return
        val subprograms = nearestSubprograms(node, normalizeIdentifier(callee.tokenOriginalValue))
        val subprogram = subprograms.singleOrNull()
        if (subprogram == null) {
            undecidedLocalCall(node, subprograms, context)
            return
        }
        if (subprogram.type == PlSqlGrammar.FUNCTION_DECLARATION) {
            subprogram.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { localFunctions[node] = returnKey(localKey(it, true)) }
        }
        val parameters = parametersOf(subprogram)
        val written = node.getFirstChildOrNull(PlSqlGrammar.ARGUMENTS)?.getChildren(PlSqlGrammar.ARGUMENT).orEmpty()
        for ((index, argument) in written.withIndex()) {
            val parameter = boundParameter(argument, index, parameters)
            if (parameter != null && isOutParameter(parameter)) outArguments += OutArgument(argument.lastChild, localKey(parameter), context, spanOf(node))
            // A nested subprogram's IN parameter: followed by where it is declared in the file.
            else passedValue(argument.lastChild, parameter?.let(::localKey), if (parameter == null) ARGUMENT_NOT_TIED else null, context, spanOf(node))
        }
    }

    /** The procedures and functions named [wanted] in the nearest declarations around [node] that declare any so named. */
    private fun nearestSubprograms(node: AstNode, wanted: String): List<AstNode> {
        var scope = node.parentOrNull
        var subprograms = emptyList<AstNode>()
        while (scope != null && subprograms.isEmpty()) {
            subprograms = scope.children.asSequence()
                .flatMap { if (it.type == PlSqlGrammar.DECLARE_SECTION) it.children.asSequence() else sequenceOf(it) }
                .filter { (it.type == PlSqlGrammar.PROCEDURE_DECLARATION || it.type == PlSqlGrammar.FUNCTION_DECLARATION) &&
                    it.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { name -> normalizeIdentifier(name.tokenOriginalValue) } == wanted }
                .toList()
            scope = scope.parentOrNull
        }
        return subprograms
    }

    /**
     * A call [node] of a nested subprogram not decided among [subprograms] (overloads, or none): kept for each of their
     * `OUT` parameters; with overloads, its arguments are not followed.
     */
    private fun undecidedLocalCall(node: AstNode, subprograms: List<AstNode>, context: Context) {
        for (parameter in subprograms.flatMap(::parametersOf).filter(::isOutParameter)) undecidedCalls += spanOf(node) to localKey(parameter)
        if (subprograms.isNotEmpty()) for (argument in arguments(node)) passedValue(argument, null, ARGUMENT_LOCAL_SUBPROGRAM, context, spanOf(node))
    }

    /** The parameter declarations of [subprogram]. */
    private fun parametersOf(subprogram: AstNode) =
        subprogram.getFirstChildOrNull(PlSqlGrammar.PARAMETER_DECLARATIONS)?.getChildren(PlSqlGrammar.PARAMETER_DECLARATION).orEmpty()

    /** Whether the parameter declaration [parameter] is `OUT` or `IN OUT`. */
    private fun isOutParameter(parameter: AstNode) = parameter.hasDirectChildren(PlSqlKeyword.OUT)

    /** The parameter among [parameters] the written argument [argument], at [index], is for: by its name when it has one, else by position. */
    private fun boundParameter(argument: AstNode, index: Int, parameters: List<AstNode>): AstNode? {
        val label = argument.takeIf { it.hasDirectChildren(PlSqlPunctuator.ASSOCIATION) }?.firstChild?.let { normalizeIdentifier(it.tokenOriginalValue) }
        return if (label == null) parameters.getOrNull(index)
            else parameters.firstOrNull { normalizeIdentifier(it.firstChild.tokenOriginalValue) == label }
    }

    /**
     * The functions [value] calls whose result is the value, or a part of it: each one's result going to [to], through
     * the value functions that lead to the call; a call within any other expression is counted by that expression's
     * shape (`RETURNED_EXPRESSION_*`). A call inside the arguments of another routine is that routine's argument.
     */
    private fun resultFlows(value: AstNode, site: SourceSpan, context: Context, to: ResultDestination, called: Map<SourceSpan, OccurrenceFact>) {
        val (target, passedTo, reason, argumentOf) = to
        if (target == null && passedTo == null && reason == null) return
        for ((call, result) in resultCalls(value, called)) {
            if (inDroppedArgument(value, call)) continue
            val chain = valueChain(value) { it === call }
            val of = spanOf(call)
            flows += when {
                chain == null -> Flow(call, site, context, shape = RETURNED_EXPRESSION + argumentShape(value), fromKey = result, resultOf = of)
                reason != null -> Flow(call, site, context, shape = reason, fromKey = result, resultOf = of)
                target != null -> Flow(call, site, context, target = target, functions = chain, fromKey = result, resultOf = of)
                else -> Flow(call, site, context, passedTo = passedTo, functions = chain, fromKey = result, resultOf = of, call = argumentOf)
            }
        }
    }

    /**
     * The calls of functions in [value], not counting those inside another call's arguments: each with the key of what
     * the function returns ([returnKey]). A function is a routine call the index resolves (with or without an argument
     * list), a nested function of the file or a function program unit of the Forms module the file is.
     */
    private fun resultCalls(value: AstNode, called: Map<SourceSpan, OccurrenceFact>): List<Pair<AstNode, String>> {
        val result = ArrayList<Pair<AstNode, String>>()
        fun visit(node: AstNode) {
            val key = when (node.type) {
                PlSqlGrammar.METHOD_CALL -> localFunctions[node] ?: called[spanOf(node.firstChild)]?.entityKey
                PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.MEMBER_EXPRESSION ->
                    called[spanOf(node)]?.takeIf { node.parentOrNull?.type != PlSqlGrammar.METHOD_CALL || node.parentOrNull?.firstChild !== node }?.entityKey
                else -> null
            }
            if (key != null && (key.startsWith(LOCAL_PREFIX) || ROUTINE_KEY.containsMatchIn(key) || returnKey(key) in moduleFunctions)) {
                result += node to (if (key.startsWith(LOCAL_PREFIX)) key else returnKey(key))
                return
            }
            // The arguments of any other routine's call belong to that call.
            if (node.type == PlSqlGrammar.METHOD_CALL && called[spanOf(node.firstChild)] != null) return
            node.children.forEach(::visit)
        }
        visit(value)
        return result
    }

    /**
     * The variables and record fields [write] gives on, as is or through value functions: what `RETURN` gives back (of
     * the function whose result is [returned]), or what is written into an `OUT` or `IN OUT` parameter of the
     * subprogram ([ValueWrite.target]). Whatever column's value they hold goes there. Within any other expression, counted
     * by its shape (`RETURNED_EXPRESSION_*`) when a column's value reaches them: the value leaves the routine either way.
     * A variable passed to a routine (one of [routineCalls], or a nested function) is that call's argument, not this.
     */
    private fun heldVariables(
        write: ValueWrite, returned: String?, parameters: List<AstNode>, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes,
        routineCalls: Set<SourceSpan>
    ) {
        val names = (sequenceOf(write.value) + write.value.getDescendants(PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.MEMBER_EXPRESSION).asSequence())
            .filter { (it.type == PlSqlGrammar.VARIABLE_NAME || it.type == PlSqlGrammar.MEMBER_EXPRESSION) && plsql.isValueName(it) && plsql.nameSegments(it) != null }
            .filter { name -> parameters.none { it === name } && generateSequence(name.parentOrNull) { it.parentOrNull }.takeWhile { it !== write.value.parentOrNull }
                .none { it.type == PlSqlGrammar.METHOD_CALL && (it in localFunctions || spanOf(it.firstChild) in routineCalls) } }
            .filter { !inDroppedArgument(write.value, it) }
            .toList()
        for (name in names) {
            val held = target(name, bySpan, shapes).takeIf { it.entityKey != null && !it.element } ?: continue
            val key = held.entityKey + held.path.joinToString("") { ".$it" }
            val chain = valueChain(write.value) { it === name }
            flows += when {
                chain == null -> Flow(name, write.site, write.context, shape = RETURNED_EXPRESSION + argumentShape(write.value), fromKey = key)
                returned == null -> Flow(name, write.site, write.context, target = write.target, functions = chain, fromKey = key)
                else -> Flow(name, write.site, write.context, passedTo = returned, functions = chain, fromKey = key)
            }
        }
    }

    /** Whether [target], a whole name, is an `OUT` or `IN OUT` parameter of the subprogram the code at it is in. */
    private fun isOwnOutParameter(target: AstNode, shapes: TypeShapes): Boolean {
        if (!isParameterName(target)) return false
        val declaration = ownDeclaration(target, target, shapes) ?: return false
        return declaration.type == PlSqlGrammar.PARAMETER_DECLARATION && declaration.hasDirectChildren(PlSqlKeyword.OUT)
    }

    /**
     * The declaration of the variable or parameter [target] names (the record of a field, the collection of an element),
     * when it is declared in the subprogram [site] is in, the innermost one: null for any other.
     */
    private fun ownDeclaration(target: AstNode, site: AstNode, shapes: TypeShapes): AstNode? {
        val name = generateSequence(target) { if (it.type in WHOLE_OF) it.firstChild else null }.last()
        val declared = (nameBinding(name) as? NameBinding.Local)?.declaration?.takeIf { it.fileId == null } ?: return null
        val declaration = shapes.localDeclaration(declared.line, declared.column) ?: return null
        val routine = subprogramOf(site) ?: return null
        return declaration.takeIf { subprogramOf(it) === routine }
    }

    /** The innermost subprogram [node] is in. */
    private fun subprogramOf(node: AstNode): AstNode? = generateSequence(node.parentOrNull) { it.parentOrNull }.firstOrNull { it.type in SUBPROGRAMS }

    /** The key of the nested subprogram's parameter zpa binds [name] to, which has no entity ([localKey]); null for any other. */
    private fun localParameterKey(name: AstNode, shapes: TypeShapes): String? {
        val declaration = parameterDeclaration(name)?.takeIf { it.fileId == null } ?: return null
        return shapes.localDeclaration(declaration.line, declaration.column)?.takeIf { it.type == PlSqlGrammar.PARAMETER_DECLARATION }?.let(::localKey)
    }

    /** Where a nested subprogram's parameter is named in the file: `local:line:column`. */
    private fun localKey(parameter: AstNode, name: Boolean = false): String =
        (if (name) parameter else parameter.firstChild).token.let { "$LOCAL_PREFIX${it.line}:${it.column}" }

    /** An argument for an `OUT` or `IN OUT` parameter ([parameterKey]) of the [call] at that span. */
    private class OutArgument(val value: AstNode, val parameterKey: String, val context: Context, val call: SourceSpan)

    /**
     * An argument for an `IN` parameter ([parameter], or the [reason] it is not followed) of the [call] at that span: the
     * results of the functions it calls go there.
     */
    private class CallArgument(val argument: AstNode, val parameter: String?, val reason: String?, val context: Context, val call: SourceSpan)

    private val callArguments = ArrayList<CallArgument>()

    /** The calls of overloaded nested subprograms, with each `OUT` parameter they may be for. */
    private val undecidedCalls = ArrayList<Pair<SourceSpan, String>>()

    private val outArguments = ArrayList<OutArgument>()


    private fun arguments(call: AstNode): List<AstNode> =
        call.getFirstChildOrNull(PlSqlGrammar.ARGUMENTS)?.getChildren(PlSqlGrammar.ARGUMENT).orEmpty().map { it.lastChild }

    private fun isFieldOfName(value: AstNode) =
        value.type == PlSqlGrammar.MEMBER_EXPRESSION && value.numberOfChildren == 3 && value.firstChild.type == PlSqlGrammar.VARIABLE_NAME

    /**
     * Why a call the index has no routine for is not followed: among candidates (`ARGUMENT_ROUTINE_UNDECIDED`), or
     * qualified by a package or schema the sources do not have (`ARGUMENT_ROUTINE_OUTSIDE`); null for an unqualified
     * name, which is a built-in function (`upper`, `count`) as often as a routine outside the index.
     */
    private fun unresolvedCall(call: OccurrenceFact): String? = when {
        call.status == Status.AMBIGUOUS -> ARGUMENT_ROUTINE_UNDECIDED
        call.status == Status.EXTERNAL -> ARGUMENT_ROUTINE_OUTSIDE
        '.' in call.written -> ARGUMENT_ROUTINE_OUTSIDE
        else -> null
    }

    /**
     * Why a resolved call does not tie an argument to a parameter: an object type's constructor (its key names the type,
     * with no parameter list), whose arguments go to attributes (`ARGUMENT_CONSTRUCTOR`); else `ARGUMENT_NOT_TIED`.
     */
    private fun notTied(call: OccurrenceFact) =
        if (call.entityKey?.let(ROUTINE_KEY::containsMatchIn) == true) ARGUMENT_NOT_TIED else ARGUMENT_CONSTRUCTOR

    /** The column [node] reads in [statement], when it is a column read that names one. */
    private fun columnOf(statement: Statement, node: AstNode) =
        statement.columnReads[node].orEmpty().singleOrNull()?.takeIf { it.entityKey != null && it.status in COLUMN_STATUSES }?.entityKey

    /**
     * In static SQL, a column read passed to a routine (not a built-in SQL function), as is or through value functions:
     * kept with the `IN` parameter the call ties it to, or with why it is not followed (the routine is not decided, it
     * is outside the index, or the call does not tie the argument to a parameter).
     */
    private fun passedColumns(statement: Statement) {
        val calls = state.occurrencesOf(statement.id).filter { Role.CALL in it.roles }.associateBy { it.span }
        if (calls.isEmpty()) return
        for (call in statement.root.getDescendants(PlSqlGrammar.METHOD_CALL)) {
            val occurrence = calls[spanOf(call.firstChild)] ?: continue
            for (argument in arguments(call)) {
                val item = item(statement, argument, null)
                for (part in listOf(item) + item.parts) {
                val column = part.columnKey ?: continue
                val functions = part.functions
                val parameter = occurrence.arguments.firstOrNull { it.span == spanOf(argument) }?.parameterKey
                val reason = when {
                    occurrence.entityKey == null || occurrence.status !in CALLED_STATUSES -> unresolvedCall(occurrence) ?: continue
                    parameter == null -> notTied(occurrence)
                    else -> null
                }
                passes += Pass(argument, column, null, functions, parameter, reason, statement.context, statement.id, spanOf(call))
                }
            }
        }
    }

    /**
     * The parameters among [names] in [value], once each, with the value functions [value] reads it through (null when
     * it is another expression): a parameter named twice (`upper(p) || nvl(p, '?')`) is told by its first name the
     * value is read through.
     */
    private fun parameterChains(value: AstNode, names: List<AstNode>): List<Pair<AstNode, List<String>?>> =
        names.groupBy { parameterDeclaration(it) }.values.map { same ->
            same.firstNotNullOfOrNull { name -> valueChain(value) { it === name }?.let { name to it } } ?: (same.first() to null)
        }

    /** The query blocks of a compound query, in order, parenthesized branches flattened. */
    private fun branches(query: AstNode): List<AstNode> = query.getChildren(DmlGrammar.QUERY_BLOCK).flatMap { block ->
        if (block.firstChild.type == PlSqlPunctuator.LPARENTHESIS) block.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)?.let { branches(it) }.orEmpty()
        else listOf(block)
    }

    private fun isBulk(into: AstNode) = into.hasDirectChildren(PlSqlKeyword.BULK)

    private fun targetsOf(into: AstNode): List<AstNode> = into.children
        .filter { it.type != PlSqlKeyword.BULK && it.type != PlSqlKeyword.COLLECT && it.type != PlSqlKeyword.INTO && it.type != PlSqlPunctuator.COMMA }
        .map { if (it.type == PlSqlGrammar.OBJECT_REFERENCE && it.numberOfChildren == 1) it.firstChild else it }

    /** The select list of [block], a `*` expanded to the columns of a relation the dictionary lists completely. */
    private fun selectItems(statement: Statement, block: AstNode): List<QueryItem> {
        val result = ArrayList<QueryItem>()
        for (column in block.getChildren(DmlGrammar.SELECT_COLUMN)) {
            val sources = columnReads.starSources(statement, column)
            if (sources == null) {
                result += item(statement, column.firstChild, column.children.drop(1).lastOrNull { it.type == PlSqlGrammar.IDENTIFIER_NAME }, block)
                continue
            }
            for (source in sources) {
                val relation = source.target?.resolved?.relation?.takeIf { it.isColumnsComplete }
                if (relation == null) {
                    // An inline view or `WITH` subquery: each of its columns, as its items read it.
                    val inner = lineage.sourceSlots(statement, source)
                    if (inner == null) result += QueryItem(null, null, null, opaque = true)
                    else inner.forEach { (name, slots) -> result += through(name, slots.map { slotItem(statement, it) }, emptyList()) }
                    continue
                }
                for (field in relation.rowFields.sortedBy { it.position.asInt }) {
                    result += QueryItem(field.name.exact(), source.target.resolved.entityKey + "." + field.name.exact(), null)
                }
            }
        }
        return result
    }

    /**
     * One select or `RETURNING` item: the column it is, read as is or through value functions ([valueChain]), the shape
     * of another expression of one column, or neither. A name of a column of an inline view or `WITH` subquery reads
     * what that column's items read ([viewItems]): the same item as reading them directly.
     */
    private fun item(statement: Statement, expression: AstNode, alias: AstNode?, block: AstNode? = null): QueryItem {
        val segments = plsql.nameSegments(expression)
        val name = alias?.let { normalizeIdentifier(it.tokenOriginalValue) } ?: segments?.last()?.let { normalizeIdentifier(it) }
        fun column(node: AstNode) = columnOf(statement, node)
        if (segments != null) {
            column(expression)?.let { return QueryItem(name, it, null) }
            viewItems(statement, expression)?.let { return through(name, it, emptyList()) }
            return QueryItem(name, null, null, notColumn = notColumn(statement, expression, block))
        }
        viewExpression(statement, expression, name)?.let { return it }
        val reads = HashMap<String, MutableList<AstNode>>()
        fun visit(node: AstNode) {
            statement.columnReads[node]?.forEach { read -> read.entityKey?.let { reads.getOrPut(it) { ArrayList() } += node } }
            node.children.forEach(::visit)
        }
        visit(expression)
        // A column read only where it never lands in the value (the search text of a REPLACE) is not read into the target.
        val columns = reads.filterValues { nodes -> nodes.any { !inDroppedArgument(expression, it) } }.keys
        if (columns.isEmpty() && reads.isNotEmpty()) return QueryItem(name, null, null, notColumn = "NO_COLUMN")
        if (columns.size > 1) {
            // Several columns, each a part of a concatenation, or one the value and the others text put into it (the
            // replacement of a REPLACE): the item is each of them.
            val chains = columns.sorted().map { key -> key to valueChain(expression) { node -> plsql.nameSegments(node) != null && column(node) == key } }
            if (chains.all { it.second != null } && chains.count { CONCATENATED !in it.second!! } <= 1) {
                val (first, chain) = chains.first()
                return QueryItem(name, first, null, functions = chain!!, parts = chains.drop(1).map { (key, f) -> QueryItem(name, key, null, functions = f!!) })
            }
            return QueryItem(name, null, null, combined = expressionShape(expression))
        }
        if (columns.isEmpty()) return QueryItem(name, null, null, notColumn = notColumn(statement, expression, block))
        var leaf: String? = null
        val functions = valueChain(expression) { node -> plsql.nameSegments(node) != null && column(node)?.also { leaf = it } != null }
        return if (functions != null && leaf != null) QueryItem(name, leaf, null, functions = functions)
            else QueryItem(name, null, expressionShape(expression))
    }

    /**
     * What the name [name] of a column of an inline view or `WITH` subquery reads: the item of every branch at its
     * position, as [item] tells it (a column, an expression of one, a constant), followed through nested views; null
     * when [name] is not such a column, or when the view cannot be followed.
     */
    private fun viewItems(statement: Statement, name: AstNode): List<QueryItem>? {
        if (statement.columnReads[name] != null || !following.add(name)) return null
        try {
            return lineage.viewSlots(statement, name)?.map { slotItem(statement, it) }
        } finally {
            following.remove(name)
        }
    }

    /** The names being followed through views, against a `WITH` that reads itself. */
    private val following: MutableSet<AstNode> = Collections.newSetFromMap(IdentityHashMap())

    private fun slotItem(statement: Statement, slot: Slot): QueryItem = when (slot) {
        is Slot.Item -> item(statement, slot.column.firstChild, null)
        is Slot.Star -> QueryItem(slot.name, slot.key, null)
        else -> QueryItem(slot.name, null, null, notColumn = "NO_COLUMN")
    }

    /**
     * The item named [name] that reads [inner] (the items of a view's column, one per branch) through the value
     * functions [chain]: each column of them, with [chain] before its own functions, the first as the item and the
     * others as its [QueryItem.parts]. Without a column: the inner item itself when read as is, else the shape of the
     * outer [outer] expression (an expression of an expression), or [QueryItem.notColumn] when none reads a column.
     */
    private fun through(name: String?, inner: List<QueryItem>, chain: List<String>, outer: AstNode? = null): QueryItem {
        val columns = inner.flatMap { listOf(it) + it.parts }.filter { it.columnKey != null }
        if (columns.isNotEmpty()) {
            val all = columns.map { QueryItem(name, it.columnKey, null, functions = chain + it.functions) }
            return all.first().copy(parts = all.drop(1))
        }
        val first = inner.firstOrNull { it.expression != null || it.combined != null } ?: inner.firstOrNull()
            ?: return QueryItem(name, null, null, notColumn = "NO_COLUMN")
        return when {
            chain.isEmpty() || outer == null -> first.copy(name = name)
            first.expression != null || first.combined != null -> QueryItem(name, null, expressionShape(outer))
            else -> QueryItem(name, null, null, notColumn = first.notColumn ?: "NO_COLUMN")
        }
    }

    /**
     * An expression item ([expression]) that reads a column of an inline view or `WITH` subquery: told as the same
     * expression of the columns that view column reads would be. A single name read through value functions is those
     * columns with the functions; several names, each a part of a concatenation, are the columns of each; any other
     * expression is its shape, by how many columns it reads. Null when the expression reads no view column.
     */
    private fun viewExpression(statement: Statement, expression: AstNode, name: String?): QueryItem? {
        val leaves = ArrayList<Pair<AstNode, List<QueryItem>>>()
        viewLeaves(statement, expression, leaves)
        if (leaves.none { (node, _) -> statement.columnReads[node] == null }) return null
        val chains = leaves.map { (node, _) -> valueChain(expression) { it === node } }
        if (leaves.size == 1 && chains.single() != null) return through(name, leaves.single().second, chains.single()!!, expression)
        if (leaves.size > 1 && chains.all { it != null && CONCATENATED in it }) {
            val all = leaves.zip(chains).map { (leaf, chain) -> through(name, leaf.second, chain!!, expression) }.filter { it.columnKey != null }
                .flatMap { listOf(it.copy(parts = emptyList())) + it.parts }
            if (all.isNotEmpty()) return all.first().copy(parts = all.drop(1))
        }
        return viewExpressionShape(name, expression, leaves.flatMap { it.second })
    }

    /**
     * Adds to [leaves] the names in [node], outer first, that are a column or a column of an inline view or `WITH`
     * subquery ([viewItems]), each with the items it reads; the names under such a name are not looked at.
     */
    private fun viewLeaves(statement: Statement, node: AstNode, leaves: MutableList<Pair<AstNode, List<QueryItem>>>) {
        if (plsql.nameSegments(node) != null) {
            columnOf(statement, node)?.let { leaves += node to listOf(QueryItem(null, it, null)); return }
            if (statement.columnReads[node] == null) viewItems(statement, node)?.let { leaves += node to it; return }
        }
        node.children.forEach { viewLeaves(statement, it, leaves) }
    }

    /**
     * The item named [name] that [expression] is when it is no value function or concatenation of the view columns it
     * reads ([inner], their items): its shape, combined when they read several columns; else why none reads a column.
     */
    private fun viewExpressionShape(name: String?, expression: AstNode, inner: List<QueryItem>): QueryItem {
        val columns = inner.flatMap { listOf(it) + it.parts }.mapNotNullTo(HashSet()) { it.columnKey }
        return when {
            columns.size > 1 -> QueryItem(name, null, null, combined = expressionShape(expression))
            columns.size == 1 || inner.any { it.expression != null || it.combined != null } -> QueryItem(name, null, expressionShape(expression))
            else -> QueryItem(name, null, null, notColumn = inner.firstNotNullOfOrNull { it.notColumn } ?: "NO_COLUMN")
        }
    }

    /**
     * The value functions [expression] reads one value through, outer first, down to the node [isValue] accepts; null
     * when it is anything else. A value function gives back one of its arguments, or that argument trimmed or in
     * another case: `NVL(a, b)` and `COALESCE(a, b, …)` (any argument), `NVL2(e1, e2, e3)` (`e2` or `e3`), `MAX(x)` and
     * `MIN(x)` (with `DISTINCT`, `ALL`, `KEEP (…)`, `OVER (…)`), `TRIM`, `LTRIM`, `RTRIM`, `UPPER`, `LOWER` (`x`). `SUBSTR`
     * and `SUBSTRB` give back a part of their first argument, with their start and length when literal ([Substrings]).
     * `REPLACE` and `REGEXP_REPLACE` give back their first argument with text taken out or put in, and put their
     * replacement text (the third) into other text, as a concatenation does. The search text or pattern, the position,
     * occurrence and match arguments of `REGEXP_REPLACE`, and the start and length of `SUBSTR`, never land in the value
     * ([inDroppedArgument]). Exactly one of the arguments that may be the value must lead to the node.
     */
    private fun valueChain(expression: AstNode, isValue: (AstNode) -> Boolean): List<String>? {
        var node = expression
        while (node.type == PlSqlGrammar.BRACKED_EXPRESSION && node.numberOfChildren == 3) node = node.children[1]
        if (isValue(node)) return emptyList()
        return when (node.type) {
            PlSqlGrammar.POSTFIX_EXPRESSION -> node.firstChild.takeIf { call ->
                call.type == PlSqlGrammar.METHOD_CALL && plsql.nameSegments(call.firstChild)?.singleOrNull()?.uppercase() in setOf("MAX", "MIN") &&
                    node.children.drop(1).all { it.type == DmlGrammar.KEEP_CLAUSE || it.type == DmlGrammar.ANALYTIC_CLAUSE }
            }?.let { valueChain(it, isValue) }
            SingleRowSqlFunctionsGrammar.SINGLE_ROW_SQL_FUNCTION ->
                node.firstChild.takeIf { it.type == SingleRowSqlFunctionsGrammar.TRIM_EXPRESSION }?.let { trimChain(it, isValue) }
            // A concatenation: the value is one of its parts, with other text around it.
            PlSqlGrammar.CONCATENATION_EXPRESSION ->
                node.children.filter { it.type != PlSqlGrammar.CONCATENATION_OPERATOR }.firstNotNullOfOrNull { valueChain(it, isValue) }
                    ?.let { listOf(CONCATENATED) + it }
            PlSqlGrammar.METHOD_CALL -> callChain(node, isValue)
            else -> null
        }
    }

    /** [valueChain] of the `TRIM` expression [trim] (`TRIM(x)`, `TRIM(… FROM x)`): `TRIM`, then the chain of `x`. */
    private fun trimChain(trim: AstNode, isValue: (AstNode) -> Boolean): List<String>? {
        val parts = trim.children.filter { it.type != PlSqlPunctuator.LPARENTHESIS && it.type != PlSqlPunctuator.RPARENTHESIS }.drop(1)
        val from = parts.indexOfFirst { it.type == PlSqlKeyword.FROM }
        val value = if (from >= 0) parts.getOrNull(from + 1) else parts.singleOrNull()
        return value?.let { chainThrough("TRIM", listOf(it), isValue) }
    }

    /** [valueChain] of the call [call]: `CONCAT`, `REPLACE`, `REGEXP_REPLACE`, `SUBSTR` and `SUBSTRB`, or a value function of one of its arguments. */
    private fun callChain(call: AstNode, isValue: (AstNode) -> Boolean): List<String>? {
        val function = plsql.nameSegments(call.firstChild)?.singleOrNull()?.uppercase() ?: return null
        if (function == "CONCAT") {
            val arguments = call.getChildren(PlSqlGrammar.ARGUMENTS).singleOrNull()?.getChildren(PlSqlGrammar.ARGUMENT)?.map { it.lastChild }
                ?.takeIf { it.size == 2 } ?: return null
            return arguments.firstNotNullOfOrNull { valueChain(it, isValue) }?.let { listOf(CONCATENATED) + it }
        }
        val arguments = call.getChildren(PlSqlGrammar.ARGUMENTS).singleOrNull()?.getChildren(PlSqlGrammar.ARGUMENT)?.map { it.lastChild }
            ?: return null
        if (function in REPLACES) return replaceChain(function, arguments, isValue)
        if (function in Substrings.NAMES) {
            if (arguments.size !in 2..3) return null
            return valueChain(arguments[0], isValue)?.let { listOf(Substrings.entry(function, arguments.drop(1).map(::wholeNumber))) + it }
        }
        val candidates = when (function) {
            "NVL" -> arguments.takeIf { it.size == 2 }
            "COALESCE" -> arguments.takeIf { it.size >= 2 }
            "NVL2" -> arguments.takeIf { it.size == 3 }?.drop(1)
            "MAX", "MIN", "UPPER", "LOWER", "TRIM" -> arguments.takeIf { it.size == 1 }
            "LTRIM", "RTRIM" -> arguments.takeIf { it.size in 1..2 }?.take(1)
            else -> null
        } ?: return null
        return chainThrough(function, candidates, isValue)
    }

    /**
     * [valueChain] of `REPLACE` or `REGEXP_REPLACE` ([function]) of [arguments]: through its first argument, or through
     * the replacement text it puts into other text; null for another number of arguments, or when both lead to the value.
     */
    private fun replaceChain(function: String, arguments: List<AstNode>, isValue: (AstNode) -> Boolean): List<String>? {
        if (arguments.size !in REPLACES.getValue(function)) return null
        val value = valueChain(arguments[0], isValue)?.let { listOf(function) + it }
        val put = arguments.getOrNull(2)?.let { valueChain(it, isValue) }?.let { listOf(function, CONCATENATED) + it }
        return listOfNotNull(value, put).singleOrNull()
    }

    /** [function], then the chain of the one of [candidates] that leads to the value; null unless exactly one does. */
    private fun chainThrough(function: String, candidates: List<AstNode>, isValue: (AstNode) -> Boolean): List<String>? {
        val chains = candidates.mapNotNull { valueChain(it, isValue) }
        return chains.singleOrNull()?.let { listOf(function) + it }
    }

    /** The value of [expression] when it is a whole-number literal (`5`, `-3`); null for anything else (`p_len`, `2.5`, a constant). */
    private fun wholeNumber(expression: AstNode): Int? {
        val value = ExpressionValues.of(expression)
        return if (value.kind == ValueKind.NUMBER) value.value?.toIntOrNull() else null
    }

    /**
     * Why the select item [expression] of the query block [block] reads no column the index knows ([QueryItem.notColumn]):
     * `INLINE_VIEW` when it names a column of an inline view (`v.code` from `(select code from orders) v`),
     * `COLUMN_UNRESOLVED` when it reads columns the index does not resolve (or is a bare name SQL may take as a column of
     * a source whose columns are not known), else `NO_COLUMN` (a literal, a PL/SQL value,
     * `ROWNUM`, `LEVEL`, a function of those, `extractValue(value(t), …)` over `table(…)`).
     */
    private fun notColumn(statement: Statement, expression: AstNode, block: AstNode?): String {
        if (block != null && readsInlineView(expression, block)) return "INLINE_VIEW"
        if (subtree(expression).any { statement.columnReads[it].orEmpty().isNotEmpty() }) return "COLUMN_UNRESOLVED"
        // A bare name that no declaration has and that SQL may take as a column, of a source whose columns are not known.
        val bare = plsql.nameSegments(expression)?.singleOrNull()?.let { normalizeIdentifier(it) }
        val unresolved = bare != null && bare !in SQL_PSEUDOCOLUMNS && (nameBinding(expression) as? NameBinding.NothingLocal)?.sqlColumnPossible == true
        return if (unresolved) "COLUMN_UNRESOLVED" else "NO_COLUMN"
    }

    /**
     * Whether [node], inside [expression], is in an argument whose value never lands in the result: the search text or
     * pattern of `REPLACE` or `REGEXP_REPLACE`, the position, occurrence and match arguments of `REGEXP_REPLACE`, or the
     * start and length of `SUBSTR` and `SUBSTRB`.
     */
    private fun inDroppedArgument(expression: AstNode, node: AstNode): Boolean {
        for (argument in generateSequence(node) { it.parentOrNull }.takeWhile { it !== expression }) {
            if (argument.type != PlSqlGrammar.ARGUMENT) continue
            val call = argument.parentOrNull?.parentOrNull?.takeIf { it.type == PlSqlGrammar.METHOD_CALL } ?: continue
            val function = plsql.nameSegments(call.firstChild)?.singleOrNull()?.uppercase()
            if (function !in REPLACES && function !in Substrings.NAMES) continue
            val index = argument.parent.getChildren(PlSqlGrammar.ARGUMENT).indexOfFirst { it === argument }
            if ((function in Substrings.NAMES && index >= 1) || (function in REPLACES && (index == 1 || index > 2))) return true
        }
        return false
    }

    /** [node] and every node under it. */
    private fun subtree(node: AstNode): Sequence<AstNode> = sequenceOf(node) + node.children.asSequence().flatMap(::subtree)

    /**
     * Whether [expression] names a column of an inline view of [block]'s `FROM`: by its alias (`v.code`), or by a name
     * alone when every relation of the `FROM` is an inline view.
     */
    private fun readsInlineView(expression: AstNode, block: AstNode): Boolean {
        val from = block.getFirstChildOrNull(DmlGrammar.FROM_CLAUSE) ?: return false
        val items = from.getDescendants(DmlGrammar.DML_TABLE_EXPRESSION_CLAUSE).filter { item ->
            generateSequence(item.parentOrNull) { it.parentOrNull }.takeWhile { it !== from }.none { it.type == DmlGrammar.SELECT_EXPRESSION }
        }
        val views = items.filter { it.hasDirectChildren(DmlGrammar.SELECT_EXPRESSION) }
        if (views.isEmpty()) return false
        val aliases = views.mapNotNull { view -> view.getFirstChildOrNull(DmlGrammar.ALIAS)?.let { normalizeIdentifier(it.lastChild.tokenOriginalValue) } }.toSet()
        val names = subtree(expression).filter { it.type == PlSqlGrammar.VARIABLE_NAME || it.type == PlSqlGrammar.MEMBER_EXPRESSION }
        return names.any { name ->
            val segments = plsql.nameSegments(name) ?: return@any false
            when {
                segments.size >= 2 -> name.type == PlSqlGrammar.MEMBER_EXPRESSION && normalizeIdentifier(segments.first()) in aliases
                else -> name.parentOrNull?.type != PlSqlGrammar.MEMBER_EXPRESSION && views.size == items.size &&
                    normalizeIdentifier(segments.single()) !in PSEUDO_COLUMNS && nameBinding(name) !is NameBinding.Local
            }
        }
    }

    /**
     * The shape of an expression a parameter is written within: as [expressionShape] says it, apart from a `CASE`
     * (which may give the value back as is) and `LENGTH` and its kin (which give a number, not the value).
     */
    private fun argumentShape(expression: AstNode): String {
        var node = expression
        while (node.type == PlSqlGrammar.BRACKED_EXPRESSION && node.numberOfChildren == 3) node = node.children[1]
        if (node.type == PlSqlGrammar.CASE_EXPRESSION) return "CASE"
        if (node.type == PlSqlGrammar.METHOD_CALL && plsql.nameSegments(node.firstChild)?.singleOrNull()?.uppercase() in LENGTH_FUNCTIONS) return "LENGTH"
        return expressionShape(expression)
    }

    /**
     * The outer function or operator of an expression item: `NVL`, `NVL2`, `UPPER`, `TRIM`, `SUBSTR`, `MAX_MIN`,
     * `TO_CHAR`, `CONCATENATION`, `OTHER`.
     */
    private fun expressionShape(expression: AstNode): String {
        var node = expression
        while (node.type == PlSqlGrammar.BRACKED_EXPRESSION && node.numberOfChildren == 3) node = node.children[1]
        if (node.type == PlSqlGrammar.CONCATENATION_EXPRESSION) return "CONCATENATION"
        val function = if (node.type == PlSqlGrammar.METHOD_CALL) plsql.nameSegments(node.firstChild)?.last()?.uppercase() else node.tokenValue.uppercase()
        return when (function) {
            "NVL" -> "NVL"
            "NVL2" -> "NVL2"
            "UPPER" -> "UPPER"
            "TRIM", "LTRIM", "RTRIM" -> "TRIM"
            "SUBSTR", "SUBSTRB" -> "SUBSTR"
            "MAX", "MIN" -> "MAX_MIN"
            "TO_CHAR" -> "TO_CHAR"
            else -> "OTHER_" + otherShape(node)
        }
    }

    /**
     * What an expression of no family of its own is, by its outer function or operator: `CASE`, `ARITHMETIC` (`+`, `-`,
     * `*`, `/`, `**`), the name of an Oracle built-in ([OracleBuiltins]: `DECODE`, `TO_NUMBER`, `LOWER`, `CAST`), `ELEMENT`
     * (an element of a collection variable or parameter, `v(i)`), `ROUTINE` (any other call: a function of the code or of
     * another schema, resolved or not), or `OTHER`.
     */
    private fun otherShape(expression: AstNode): String {
        var node = expression
        while (node.type == PlSqlGrammar.POSTFIX_EXPRESSION || node.numberOfChildren == 1) node = node.firstChild
        return when {
            node.type == PlSqlGrammar.CASE_EXPRESSION -> "CASE"
            node.type in ARITHMETIC -> "ARITHMETIC"
            node.type == PlSqlGrammar.METHOD_CALL -> {
                val segments = plsql.nameSegments(node.firstChild) ?: return "OTHER"
                val called = segments.singleOrNull()?.let { normalizeIdentifier(it) }
                when {
                    called == null || isRoutineCall(node.firstChild) -> "ROUTINE"
                    // A collection's element (`v(i)`), a variable or parameter zpa binds the name to.
                    (nameBinding(node.firstChild) as? NameBinding.Local)?.declaration?.kind.let {
                        it == NameDeclarationKind.VARIABLE || it == NameDeclarationKind.PARAMETER
                    } -> "ELEMENT"
                    OracleBuiltins.isBuiltin(called) && WORD.matches(called) -> called
                    else -> "ROUTINE"
                }
            }
            // A built-in written with its own syntax: `CAST(… AS …)`, `EXTRACT(… FROM …)`.
            node.numberOfChildren > 1 && node.children[1].tokenValue == "(" -> node.firstChild.tokenValue.uppercase().takeIf { WORD.matches(it) } ?: "OTHER"
            else -> "OTHER"
        }
    }

    /** Whether the called name [name] is a routine the index resolves (a built-in is not one). */
    private fun isRoutineCall(name: AstNode): Boolean {
        return state.occurrencesAt(spanOf(name)).any { Role.CALL in it.roles && it.entityKey != null && it.status != Status.UNRESOLVED }
    }

    /** Whether [shape], followed through type names of this file, is a `REF CURSOR` type with `RETURN`. */
    private fun isStrongCursor(shape: TypeShape): Boolean = when (shape) {
        is TypeShape.Unknown -> shape.strongCursor
        is TypeShape.Named -> shape.local?.let(::isStrongCursor) == true
        else -> false
    }

    /**
     * The `INTO`s of the file and its assignments of a record's field, their targets and records decided, and the row
     * record fields passed counted: call once the file is left, when the field uses are recorded. The expression items an
     * `INTO` reads are counted after the scan, with the cursors of other files.
     */
    fun intos(shapes: TypeShapes): List<IntoFact> {
        if (pending.isEmpty() && assignments.isEmpty() && outArguments.isEmpty() && undecidedCalls.isEmpty() && passes.isEmpty() &&
            valueWrites.isEmpty() && flows.isEmpty()) return emptyList()
        val bySpan = state.occurrences.filter { it.entityKey != null }.groupBy { it.span }
        val intos = pending.mapNotNullTo(ArrayList()) { pendingInto(it, bySpan, shapes) }
        val callers = state.occurrences.filter { Role.CALL in it.roles && it.entityKey != null && it.status in CALLED_STATUSES }
            .mapTo(HashSet()) { it.span }
        assignments.mapNotNullTo(intos) { assignmentInto(it, callers, bySpan, shapes) }
        // The argument passed for an OUT or IN OUT parameter, as an INTO target: it takes what the parameter is given.
        outArguments.mapTo(intos) { argument ->
            IntoFact(PlaceSource.OUT_ARGUMENT, false, argument.context.unitIndex, argument.context.container, null, emptyList(), null,
                listOf(target(argument.value, bySpan, shapes)), parameterKey = argument.parameterKey, call = argument.call,
                keeps = ownDeclaration(argument.value, argument.value, shapes) != null)
        }
        passes.mapNotNullTo(intos) { passInto(it, bySpan, shapes) }
        // What a routine writes its IN parameters into, passes them on to, or writes them in a way not followed.
        val routineCalls = state.occurrences.filter { Role.CALL in it.roles && it.entityKey != null }.mapTo(HashSet()) { it.span }
        val called = state.occurrences.filter { Role.CALL in it.roles && it.entityKey != null && it.status in CALLED_STATUSES }
            .associateBy { it.span }
        for (write in valueWrites) writeFlows(write, bySpan, shapes, called, routineCalls)
        // The results of the functions an argument calls, passed to the parameter.
        for (argument in callArguments) {
            val reason = argument.reason ?: ARGUMENT_NOT_TIED.takeIf { argument.parameter == null }
            val to = ResultDestination(passedTo = argument.parameter, reason = reason, argumentOf = argument.call)
            resultFlows(argument.argument, spanOf(argument.argument), argument.context, to, called)
        }
        val statusBySpan = state.occurrences.groupBy { it.span }
        flows.mapNotNullTo(intos) { flowInto(it, bySpan, statusBySpan, shapes) }
        // A call of an overloaded nested subprogram: no target, only the parameters it may be for.
        undecidedCalls.mapTo(intos) { (span, parameter) ->
            IntoFact(PlaceSource.OUT_ARGUMENT, false, 0, null, null, emptyList(), null,
                listOf(IntoTarget(span, "", null, emptyList(), false, null, null, OUT_CALL_NOT_DECIDED)), parameterKey = parameter, call = span)
        }
        return intos
    }

    /**
     * The `INTO` [into] with its targets decided, and the items of its cursor for a `FETCH`: those of the `OPEN`s of a
     * cursor variable that may run last before it, else those of the cursor's own query. Null when no such `OPEN` is
     * followed.
     */
    private fun pendingInto(into: Pending, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): IntoFact? {
        val targets = into.targets.map { target(it, bySpan, shapes) }
        // A cursor variable: the queries of the OPENs that may run last before the FETCH, when there are some.
        val variable = into.cursor?.let(::cursorDeclaration)
        val opens = variable?.let { reachingOpens(into.fetch!!, it) }.orEmpty()
        if (opens.isNotEmpty()) return openedFetch(into, opens, targets, shapes)
        val cursor = into.cursor
            ?: return IntoFact(into.source, into.bulk, into.context.unitIndex, into.context.container, into.statementId, into.branches, null, targets)
        val cursorKey = bySpan[spanOf(cursor)]?.firstOrNull()?.entityKey
        if (cursorKey != null) {
            val (kind, type) = cursorKind(cursor, shapes)
            return IntoFact(into.source, into.bulk, into.context.unitIndex, into.context.container, into.statementId, into.branches, cursorKey, targets,
                cursorKind = kind, cursorType = type)
        }
        val declaration = local(cursor, shapes)
        val query = declaration?.takeIf { it.type == PlSqlGrammar.CURSOR_DECLARATION }?.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)
        val items = query?.let { state.cursorItems[it] }
        // Not known: counted once the types of other files are known ([ColumnPlaces]), by the cursor it is.
        if (items == null) {
            val (kind, type) = cursorKind(cursor, shapes)
            return IntoFact(into.source, into.bulk, into.context.unitIndex, into.context.container, null, emptyList(), null,
                targets, cursorKind = kind, cursorType = type)
        }
        return IntoFact(into.source, into.bulk, into.context.unitIndex, into.context.container, into.statementId, listOf(items), null, targets)
    }

    /**
     * The `FETCH` [into] from a cursor variable, its [targets] decided, that [opens] may have opened: the items of each
     * query followed, the targets counted once when one is not; null when none is followed.
     */
    private fun openedFetch(into: Pending, opens: List<AstNode>, targets: List<IntoTarget>, shapes: TypeShapes): IntoFact? {
        val (kind, type) = cursorKind(into.cursor!!, shapes)
        val read = opens.map(::opened)
        // One of them not followed: the FETCH's targets counted once, by the first such.
        read.firstNotNullOfOrNull { it as? Opened.NotFollowed }?.let { state.leftOut(FETCH_CURSOR_NOT_KNOWN + it.shape, into.targets.size) }
        val queries = read.mapNotNull { (it as? Opened.Items)?.items }
        if (queries.isEmpty()) return null
        return IntoFact(into.source, into.bulk, into.context.unitIndex, into.context.container, into.statementId, queries, null, targets,
            cursorKind = kind ?: "OPENED", cursorType = type)
    }

    /**
     * The assignment of a record's field [assignment], its target and record decided; null when the record is no row
     * record, or when the field is passed to a function the index follows (one of [callers], or a nested function).
     */
    private fun assignmentInto(
        assignment: Assignment, callers: Set<SourceSpan>, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes
    ): IntoFact? {
        // A field passed to a function the index follows is that call's argument, its result what is assigned.
        if (assignment.expression != null && generateSequence(assignment.field.parentOrNull) { it.parentOrNull }
                .takeWhile { it !== assignment.expression.parentOrNull }
                .any { it.type == PlSqlGrammar.METHOD_CALL && (it in localFunctions || spanOf(it.firstChild) in callers) }) return null
        val row = record(assignment.field, bySpan, shapes) ?: return null
        val target = target(assignment.target, bySpan, shapes).copy(span = assignment.site)
        return IntoFact(PlaceSource.ASSIGNMENT, false, assignment.context.unitIndex, assignment.context.container, null, emptyList(), null,
            listOf(target), row, normalizeIdentifier(assignment.field.lastChild.tokenOriginalValue), assignment.expression?.let(::expressionShape), assignment.functions)
    }

    /** A column, or a record's field, passed to a routine's `IN` parameter ([pass]); null for a field of no row record. */
    private fun passInto(pass: Pass, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): IntoFact? {
        val argument = IntoTarget(spanOf(pass.argument), writtenText(pass.argument), null, emptyList(), false, null, null, pass.reason)
        if (pass.field != null) {
            val row = record(pass.field, bySpan, shapes) ?: return null
            return IntoFact(PlaceSource.IN_ARGUMENT, false, pass.context.unitIndex, pass.context.container, pass.statementId, emptyList(), null,
                listOf(argument), row, normalizeIdentifier(pass.field.lastChild.tokenOriginalValue), null, pass.functions, pass.parameterKey, call = pass.call)
        }
        return IntoFact(PlaceSource.IN_ARGUMENT, false, pass.context.unitIndex, pass.context.container, pass.statementId,
            listOf(listOf(QueryItem(null, pass.column, null, functions = pass.functions))), null, listOf(argument),
            functions = pass.functions, parameterKey = pass.parameterKey, call = pass.call)
    }

    /**
     * What the value [write] writes of the functions it calls ([called]), of the routine's `IN` parameters and, given
     * back or written into an `OUT` parameter, of the variables it holds: into its target, or to what the function around
     * it returns. Nothing for a `RETURN` outside a function.
     */
    private fun writeFlows(
        write: ValueWrite, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes, called: Map<SourceSpan, OccurrenceFact>,
        routineCalls: Set<SourceSpan>
    ) {
        // A returned value goes to what the function around it returns.
        val returned = if (write.target == null) enclosingReturn(write.value) ?: return else null
        resultFlows(write.value, write.site, write.context, ResultDestination(target = write.target, passedTo = returned), called)
        if (write.sql) return
        val names = writtenParameters(write, routineCalls)
        for ((name, functions) in parameterChains(write.value, names)) {
            flows += when {
                functions == null -> Flow(name, write.site, write.context, shape = ARGUMENT_EXPRESSION + argumentShape(write.value))
                returned != null -> Flow(name, write.site, write.context, passedTo = returned, functions = functions)
                else -> Flow(name, write.site, write.context, target = write.target, functions = functions)
            }
        }
        if (returned != null || write.target != null && isOwnOutParameter(write.target, shapes)) heldVariables(write, returned, names, bySpan, shapes, routineCalls)
    }

    /**
     * The names of the routine's parameters the value [write] writes: not in an argument that never lands in the value,
     * and not passed to a routine inside it ([routineCalls]), which is that call's argument ([call]).
     */
    private fun writtenParameters(write: ValueWrite, routineCalls: Set<SourceSpan>): List<AstNode> =
        parameterNames(write.value).filter { name ->
            !inDroppedArgument(write.value, name) && generateSequence(name.parentOrNull) { it.parentOrNull }.takeWhile { it !== write.value.parentOrNull }
                .none { it.type == PlSqlGrammar.METHOD_CALL && spanOf(it.firstChild) in routineCalls }
        }

    /**
     * The `IN` parameter [flow] tells, as an [IntoFact] from the parameter; null when the parameter has no key. A name in
     * SQL a column may take instead of the parameter is the parameter only when the index resolves the name, the
     * statement's tables' columns known and none of them so named: else it is counted as such.
     */
    private fun flowInto(
        flow: Flow, bySpan: Map<SourceSpan, List<OccurrenceFact>>, statusBySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes
    ): IntoFact? {
        val from = flow.fromKey ?: bySpan[spanOf(flow.parameter)]?.firstOrNull()?.entityKey ?: localParameterKey(flow.parameter, shapes) ?: return null
        val columnPossible = flow.fromKey == null && flow.shape == null && nameBinding(flow.parameter) is NameBinding.ColumnOrLocal &&
            statusBySpan[spanOf(flow.parameter)].orEmpty().none { it.status == Status.RESOLVED }
        if (columnPossible) {
            return IntoFact(PlaceSource.IN_ARGUMENT, false, flow.context.unitIndex, flow.context.container, null, emptyList(), null,
                listOf(IntoTarget(flow.site, "", null, emptyList(), false, null, null, null)), expression = ARGUMENT_COLUMN_POSSIBLE,
                fromParameter = from)
        }
        val targets = flow.target?.let { listOf(target(it, bySpan, shapes).copy(span = flow.site)) }
            ?: listOf(IntoTarget(flow.site, "", null, emptyList(), false, null, null, null))
        val branches = flow.item?.let { (index, size) ->
            listOf(List(size) { if (it == index) QueryItem(null, from, null, functions = flow.functions) else QueryItem(null, null, null) })
        }.orEmpty()
        return IntoFact(PlaceSource.IN_ARGUMENT, flow.bulk, flow.context.unitIndex, flow.context.container, null, branches, null, targets,
            expression = flow.shape, functions = flow.functions, parameterKey = flow.passedTo, fromParameter = from, call = flow.call,
            resultOf = flow.resultOf, keeps = flow.target != null && ownDeclaration(flow.target, flow.parameter, shapes) != null)
    }

    /**
     * The record of the field `r.f`: a cursor `FOR` loop record, its row the loop's query or cursor, or the variable or
     * parameter zpa binds `r` to; null when `r` names no record (a package, or a record type's field entity).
     */
    private fun record(field: AstNode, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): IntoTarget? {
        val name = field.firstChild
        val declaration = (nameBinding(name) as? NameBinding.Local)?.declaration
        if (declaration?.kind == NameDeclarationKind.LOOP_INDEX) {
            val iterator = declaration.takeIf { it.fileId == null }?.let { shapes.iterand(it.line, it.column) }?.parentOrNull ?: return null
            return IntoTarget(spanOf(name), writtenText(name), null, emptyList(), false, loopRow(iterator, bySpan, shapes), null, null)
        }
        val whole = target(field, bySpan, shapes)
        if (whole.entityKey == null && whole.local == null) return null
        // The field itself has an entity (a field of a record type): not a row record.
        if (whole.path.size != 1) return null
        return whole.copy(span = spanOf(name), written = writtenText(name), path = emptyList())
    }

    /** The row of a cursor `FOR` loop's record: its query's items, or the cursor it opens (`for r in c`, `for r in c(1)`). */
    private fun loopRow(iterator: AstNode, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): TypeShape {
        val unknown = TypeShape.Anchored(true, null, null, emptyList(), "CURSOR_QUERY_UNKNOWN")
        iterator.getFirstDescendantOrNull(DmlGrammar.SELECT_EXPRESSION)?.let { query ->
            return state.cursorItems[query]?.let { TypeShape.CursorRow(it) } ?: unknown
        }
        var cursor = iterator.children.dropWhile { it.type != PlSqlKeyword.IN }.drop(1).firstOrNull() ?: return unknown
        while (cursor.type != PlSqlGrammar.VARIABLE_NAME && cursor.type != PlSqlGrammar.MEMBER_EXPRESSION) cursor = cursor.firstChildOrNull ?: return unknown
        (local(cursor, shapes)?.takeIf { it.type == PlSqlGrammar.CURSOR_DECLARATION } ?: enclosingCursor(iterator, cursor))
            ?.let { shapes.of(it, null) }?.let { return it }
        return bySpan[spanOf(cursor)]?.firstOrNull()?.entityKey?.let { TypeShape.Anchored(true, it, null, emptyList(), null) } ?: unknown
    }

    /**
     * The cursor a loop names (`for r in c`), which zpa does not bind: the declaration of that name in the nearest
     * declarations around the loop (a block's, a routine's, the package body's), or null.
     */
    private fun enclosingCursor(from: AstNode, name: AstNode): AstNode? {
        if (name.type != PlSqlGrammar.VARIABLE_NAME) return null
        val wanted = normalizeIdentifier(name.tokenOriginalValue)
        var scope = from.parentOrNull
        while (scope != null) {
            scope.children.asSequence()
                .flatMap { if (it.type == PlSqlGrammar.DECLARE_SECTION) it.children.asSequence() else sequenceOf(it) }
                .firstOrNull { it.type == PlSqlGrammar.CURSOR_DECLARATION && normalizeIdentifier(it.getFirstChild(PlSqlGrammar.IDENTIFIER_NAME).tokenOriginalValue) == wanted }
                ?.let { return it }
            scope = scope.parentOrNull
        }
        return null
    }

    /** The declaration of this file without an entity that zpa binds [name] (its first segment) to, or null. */
    private fun local(name: AstNode, shapes: TypeShapes): AstNode? {
        val first = if (name.type == PlSqlGrammar.MEMBER_EXPRESSION) name.firstChild else name
        val declaration = when (val binding = nameBinding(first)) {
            is NameBinding.Local -> binding.declaration
            is NameBinding.ColumnOrLocal -> binding.declaration
            else -> return null
        }
        if (declaration.fileId != null) return null
        return shapes.localDeclaration(declaration.line, declaration.column)
    }

    /**
     * A target as zpa binds it: a field with an entity at its last segment, else the entity of the whole name (the
     * segments after the one it names are fields), else a declaration without an entity, with its own type. A field
     * reached through a collection element (`a(i).f`, `a(i).r.f`, and `a(i).f(j)` for an element of it) is the field
     * zpa binds its last segment to, as the index records its use; when that field has no entity (a field of an element
     * that is a `%ROWTYPE`), the collection itself, its element and fields as steps of the path ([IntoTarget.ELEMENT]).
     * An element of an element (`a(i)(j)`, `a(i).f(j)(k)`) is a shape the index does not follow
     * (`TARGET_SHAPE_NOT_FOLLOWED`). A member of a Forms module's package (`pks.g_code`), whose use is the package's,
     * is the member ([FileState.formsMembers]).
     */
    private fun target(node: AstNode, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): IntoTarget {
        val span = spanOf(node)
        val written = writtenText(node)
        if (node.type == PlSqlGrammar.OBJECT_REFERENCE) {
            val head = node.firstChild
            val (field, element) = fieldThroughElement(node)?.takeUnless { head.type == PlSqlGrammar.METHOD_CALL && isElementOfElement(head) }
                ?: return IntoTarget(span, written, null, emptyList(), false, null, null, TARGET_SHAPE_NOT_FOLLOWED)
            bySpan[spanOf(field)]?.firstOrNull()?.entityKey?.let { return IntoTarget(span, written, it, emptyList(), element, null, null, null) }
            return throughElements(node, bySpan, shapes)?.copy(span = span, written = written)
                ?: IntoTarget(span, written, null, emptyList(), element, null, null, "FIELD_UNDECIDED")
        }
        val element = node.type == PlSqlGrammar.METHOD_CALL
        if (element && isElementOfElement(node)) return IntoTarget(span, written, null, emptyList(), true, null, null, TARGET_SHAPE_NOT_FOLLOWED)
        val name = if (element) node.firstChild else node
        val segments = plsql.nameSegments(name)?.map { normalizeIdentifier(it) }
            ?: return formsTarget(span, written, name, element) ?: IntoTarget(span, written, null, emptyList(), element, null, null, "TARGET_NOT_A_NAME")
        if (segments.size > 1) {
            val last = name.children.last { it.type == PlSqlGrammar.IDENTIFIER_NAME || it.type == PlSqlGrammar.VARIABLE_NAME }
            bySpan[spanOf(last)]?.firstOrNull()?.entityKey?.let { return IntoTarget(span, written, it, emptyList(), element, null, null, null) }
        }
        state.formsMembers[spanOf(name)]?.let { return IntoTarget(span, written, it.key, it.fields, element, null, null, null) }
        bySpan[spanOf(name)]?.firstOrNull()?.entityKey?.let { key ->
            val own = key.substringAfterLast('.').substringBefore('@').substringBefore('#')
            val at = segments.indexOfLast { it == own }
            return IntoTarget(span, written, key, if (at >= 0) segments.drop(at + 1) else segments.drop(1), element, null, null, null)
        }
        val declaration = local(name, shapes)
            ?: return IntoTarget(span, written, null, emptyList(), element, null, null, "TARGET_UNDECIDED")
        val shape = shapes.of(declaration, null) ?: TypeShape.Unknown("DECLARATION_UNKNOWN")
        // A nested subprogram's parameter, whose calls this file binds; not an object type method's, whose calls the
        // index does not record.
        val localKey = declaration.takeIf { it.type == PlSqlGrammar.PARAMETER_DECLARATION && !it.hasAncestor(PlSqlGrammar.CREATE_TYPE_BODY) }
            ?.let(::localKey)
        return IntoTarget(span, written, null, segments.drop(1), element, shape, DeclaredTexts.of("", declaration)?.datatype, null, localKey)
    }

    /**
     * A Forms host name (`:BLOCK.ITEM`, `:ITEM`, `:PARAMETER.NAME`, `:GLOBAL.NAME`) as the module's names resolve its use
     * ([FormsReferences]): the object it names, else, ambiguous or missing, the use's reason; null for any other target
     * (`:SYSTEM.NAME` among them).
     */
    private fun formsTarget(span: SourceSpan, written: String, name: AstNode, element: Boolean): IntoTarget? {
        val use = state.occurrencesAt(spanOf(name)).firstOrNull { use ->
            use.entityKey?.startsWith(FORMS_PREFIX) == true || use.reason in FORMS_MISSING ||
                use.status == Status.AMBIGUOUS && use.candidateKeys.isNotEmpty() && use.candidateKeys.all { it.startsWith(FORMS_PREFIX) }
        } ?: return null
        return IntoTarget(span, written, use.entityKey, emptyList(), element, null, null, use.reason)
    }

    /**
     * `a(i).f`, `a(i).r.f`, `a(i).f(j)` followed from the collection `a`: its target, then an element step and the
     * written fields and elements after it; null when `a` is not a target the index knows.
     */
    private fun throughElements(reference: AstNode, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): IntoTarget? {
        val head = reference.firstChild.takeIf { it.type == PlSqlGrammar.METHOD_CALL } ?: return null
        val collection = target(head.firstChild, bySpan, shapes).takeIf { it.reason == null && !it.element } ?: return null
        val steps = ArrayList(collection.path)
        steps += IntoTarget.ELEMENT
        fun visit(node: AstNode): Boolean = when (node.type) {
            PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.IDENTIFIER_NAME -> steps.add(normalizeIdentifier(node.tokenOriginalValue))
            PlSqlGrammar.OBJECT_REFERENCE, PlSqlGrammar.MEMBER_EXPRESSION -> node.children.filter { it.type != PlSqlPunctuator.DOT }.all(::visit)
            PlSqlGrammar.METHOD_CALL -> visit(node.firstChild) && steps.add(IntoTarget.ELEMENT)
            else -> false
        }
        if (!reference.children.drop(1).filter { it.type != PlSqlPunctuator.DOT }.all(::visit)) return null
        return collection.copy(path = steps)
    }

    /**
     * The last name of a target that goes through a collection element (`a(i).f`, `a(i).r.f`: zpa parses it as an
     * object reference, the element then the rest), and whether the target is an element of what it names (`a(i).f(j)`);
     * null for any other shape, an element of an element among them.
     */
    private fun fieldThroughElement(reference: AstNode): Pair<AstNode, Boolean>? {
        var tail = reference.lastChild
        var element = false
        while (true) {
            tail = when (tail.type) {
                PlSqlGrammar.OBJECT_REFERENCE, PlSqlGrammar.MEMBER_EXPRESSION -> tail.lastChild
                PlSqlGrammar.METHOD_CALL -> {
                    if (isElementOfElement(tail)) return null
                    element = true
                    tail.firstChild
                }
                PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.IDENTIFIER_NAME -> return tail to element
                else -> return null
            }
        }
    }

    /** Whether the call [call] is an element of an element (`a(i)(j)`): two argument lists, or a call of a call. */
    private fun isElementOfElement(call: AstNode) =
        call.getChildren(PlSqlGrammar.ARGUMENTS).size > 1 || call.firstChild.type == PlSqlGrammar.METHOD_CALL

    companion object {
        /** The subprograms whose own declarations [ownDeclaration] tells apart, a nested one included. */
        private val SUBPROGRAMS = setOf(
            PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION, PlSqlGrammar.CREATE_PROCEDURE, PlSqlGrammar.CREATE_FUNCTION
        )

        /** What a target goes through to the name it starts with: a field, an element, an object reference. */
        private val WHOLE_OF = setOf(PlSqlGrammar.MEMBER_EXPRESSION, PlSqlGrammar.METHOD_CALL, PlSqlGrammar.OBJECT_REFERENCE)

        const val DYNAMIC_INTO = "DYNAMIC_INTO"
        const val RECORD_ASSIGNMENT = "RECORD_ASSIGNMENT"
        const val ARGUMENT_ROUTINE_UNDECIDED = "ARGUMENT_ROUTINE_UNDECIDED"
        const val ARGUMENT_ROUTINE_OUTSIDE = "ARGUMENT_ROUTINE_OUTSIDE"
        const val ARGUMENT_NOT_TIED = "ARGUMENT_NOT_TIED"
        /** The prefix of the shape of a function's result written within an expression ([resultFlows]). */
        const val RETURNED_EXPRESSION = "RETURNED_EXPRESSION_"
        /** The last segment of the key of what a function returns: `<function key>.#RETURN`. */
        const val RETURN_SUFFIX = "#RETURN"
        /** The prefix of the shape of an expression a parameter is written within ([argumentShape]). */
        const val ARGUMENT_EXPRESSION = "ARGUMENT_EXPRESSION_"
        private val LENGTH_FUNCTIONS = setOf("LENGTH", "LENGTHB", "LENGTHC", "LENGTH2", "LENGTH4")
        const val ARGUMENT_LOCAL_SUBPROGRAM = "ARGUMENT_LOCAL_SUBPROGRAM"
        const val ARGUMENT_CONSTRUCTOR = "ARGUMENT_CONSTRUCTOR"
        const val ARGUMENT_FETCHED = "ARGUMENT_FETCHED"
        const val ARGUMENT_COLUMN_POSSIBLE = "ARGUMENT_COLUMN_POSSIBLE"
        /** Marks a parameter of a routine whose body is not PL/SQL ([routine]); never counted itself. */
        const val BODY_NOT_PLSQL = "BODY_NOT_PLSQL"
        const val ARGUMENT_INTO_NOT_FOLLOWED = "ARGUMENT_INTO_NOT_FOLLOWED"
        /** The prefix of the shape of a `FETCH` from a cursor whose query is not known, by the cursor it is. */
        const val FETCH_CURSOR_NOT_KNOWN = "FETCH_CURSOR_NOT_KNOWN_"

        /** After [FETCH_CURSOR_NOT_KNOWN]: a text whose hole in the `FROM` hides the column an item reads. */
        const val TEXT_HOLE = "TEXT_HOLE"

        /** The functions that give back their first argument with text taken out or put in, by the number of arguments they take. */
        private val REPLACES = mapOf("REPLACE" to 2..3, "REGEXP_REPLACE" to 2..6)

        /** The end of the reason of a dynamic text parsed only once its holes were left out: it may be partial. */
        private const val HOLES_DROPPED = "HOLES_DROPPED"

        private val LOOPS = setOf(PlSqlGrammar.LOOP_STATEMENT, PlSqlGrammar.FOR_STATEMENT, PlSqlGrammar.WHILE_STATEMENT)
        private val ARITHMETIC = setOf(
            PlSqlGrammar.ADDITIVE_EXPRESSION, PlSqlGrammar.MULTIPLICATIVE_EXPRESSION, PlSqlGrammar.UNARY_EXPRESSION, PlSqlGrammar.EXPONENTIATION_EXPRESSION
        )
        private val WORD = Regex("[A-Z][A-Z0-9_]*")
        private val PSEUDO_COLUMNS = setOf("ROWNUM", "LEVEL", "ROWID", "SYSDATE", "SYSTIMESTAMP", "USER", "CURRENT_DATE", "CURRENT_TIMESTAMP", "UID")
        const val OUT_CALL_NOT_DECIDED = "OUT_CALL_NOT_DECIDED"

        /** The prefix of the key of a nested subprogram's parameter ([IntoTarget.localKey]). */
        const val LOCAL_PREFIX = "local:"

        /** The reason of a call of a subprogram nested in a routine, which has no entity. */
        private const val LOCAL_SUBPROGRAM = "LOCAL_SUBPROGRAM"

        /** A target whose shape the index does not follow (an element of an element). */
        private const val TARGET_SHAPE_NOT_FOLLOWED = "TARGET_SHAPE_NOT_FOLLOWED"

        /** The statuses of a column read that names the column: an unresolved or ambiguous one names none. */
        /** A routine's key ends with its parameter list. */
        private val ROUTINE_KEY = Regex("""\)(@BODY)?(#\d+)?$""")

        /** The statuses of a call that ties its arguments to the routine's parameters. */
        private val CALLED_STATUSES = setOf(Status.RESOLVED, Status.PARTIAL_SOURCE)

        private val COLUMN_STATUSES = setOf(Status.RESOLVED, Status.UNVERIFIED, Status.EXTERNAL, Status.PARTIAL_SOURCE)

        /** The reasons of a Forms host name whose block, item or parameter the module does not have ([formsTarget]). */
        private val FORMS_MISSING = setOf(Reason.NO_SUCH_BLOCK.name, Reason.NO_SUCH_ITEM.name, Reason.NO_SUCH_PARAMETER.name)
    }
}
