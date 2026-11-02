//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.DmlGrammar
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.api.semantics.NameBinding
import com.felipebz.zpa.api.semantics.NameDeclarationKind
import com.felipebz.zpa.atlas.model.*

/**
 * The `INTO`s that read select items into PL/SQL targets ([IntoFact]): `SELECT … INTO`, `FETCH … INTO` of an explicit
 * cursor, `RETURNING … INTO`, with or without `BULK COLLECT`. A select item is a column when it is the column itself,
 * written bare or qualified (the column read static SQL records at it), or a `*` over a relation whose columns the
 * dictionary lists; an expression of one column is counted by its shape. A target is the entity zpa binds its name
 * to (the value use the walk records), else the declaration without an entity zpa binds it to, with its own type.
 *
 * Other reads of a column into PL/SQL are counted, not recorded ([FileState.placesLeftOut]): dynamic `INTO`, a field
 * of a `%ROWTYPE` or cursor loop record assigned (`l_x := r.code`) or passed to a routine, a column passed to a routine
 * in static SQL, and a `FETCH` from a cursor whose query is not known.
 */
internal class IntoClauses(
    private val state: FileState,
    private val plsql: PlSqlNames,
    private val columnReads: ColumnReads,
    private val nameBinding: (AstNode) -> NameBinding
) {
    private class Pending(
        val source: PlaceSource, val bulk: Boolean, val context: Context, val statementId: Int?, val branches: List<List<QueryItem>>,
        val cursor: AstNode?, val targets: List<AstNode>
    )

    private val pending = ArrayList<Pending>()

    /** The select items of [statement]'s cursor query, its `INTO` or `RETURNING INTO`, and the columns it passes to routines. */
    fun statement(statement: Statement) {
        val root = statement.root
        if (statement.context.dynamic != null) return
        if (root.parentOrNull?.type == PlSqlGrammar.CURSOR_DECLARATION) {
            branches(root).firstOrNull()?.let { state.cursorItems[root] = selectItems(statement, it) }
        }
        when (root.type) {
            DmlGrammar.SELECT_EXPRESSION -> root.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK)?.getFirstChildOrNull(DmlGrammar.INTO_CLAUSE)?.let { into ->
                pending += Pending(PlaceSource.SELECT_INTO, isBulk(into), statement.context, statement.id,
                    branches(root).map { selectItems(statement, it) }, null, targetsOf(into))
            }
            DmlGrammar.INSERT_EXPRESSION, DmlGrammar.UPDATE_EXPRESSION, DmlGrammar.DELETE_EXPRESSION ->
                root.getFirstDescendantOrNull(DmlGrammar.RETURNING_INTO_CLAUSE)?.let { returning ->
                    val into = returning.getFirstChild(DmlGrammar.INTO_CLAUSE)
                    val items = returning.children.filter { it.type != PlSqlKeyword.RETURNING && it.type != PlSqlKeyword.RETURN && it.type != PlSqlPunctuator.COMMA && it !== into }
                        .map { item(statement, it, null) }
                    pending += Pending(PlaceSource.RETURNING, isBulk(into), statement.context, statement.id, listOf(items), null, targetsOf(into))
                }
        }
        passedColumns(statement)
    }

    /** `FETCH c INTO …`: the cursor's query decides the items (its own file's, for a cursor with an entity). */
    fun fetch(node: AstNode, context: Context) {
        if (context.dynamic != null) return
        val into = node.getFirstChildOrNull(DmlGrammar.INTO_CLAUSE) ?: return
        val cursor = node.children.firstOrNull { it.type == PlSqlGrammar.MEMBER_EXPRESSION || it.type == PlSqlGrammar.VARIABLE_NAME } ?: return
        pending += Pending(PlaceSource.FETCH, isBulk(into), context, null, emptyList(), cursor, targetsOf(into))
    }

    /** `EXECUTE IMMEDIATE … INTO` or `RETURNING INTO`: counted, one per target. */
    fun dynamic(node: AstNode) {
        for (into in node.getDescendants(DmlGrammar.INTO_CLAUSE)) state.leftOut(DYNAMIC_INTO, targetsOf(into).size)
    }

    /** `l_x := r.code`, `r` a `%ROWTYPE` or cursor loop record: counted once the file is left ([rowFields]). */
    fun assignment(node: AstNode) {
        val value = node.children.dropWhile { it.type != PlSqlPunctuator.ASSIGNMENT }.drop(1).firstOrNull() ?: return
        if (isFieldOfName(value)) rowFieldCandidates += RECORD_ASSIGNMENT to value
    }

    /** A PL/SQL call resolved to a routine that is passed a field of a `%ROWTYPE` or cursor loop record: counted per argument. */
    fun call(node: AstNode, before: Int) {
        if (node.type != PlSqlGrammar.METHOD_CALL || !calledRoutine(before)) return
        arguments(node).filter(::isFieldOfName).forEach { rowFieldCandidates += ARGUMENT to it }
    }

    /** The `r.f` values assigned or passed, with what they are counted as when `r` is a row record. */
    private val rowFieldCandidates = ArrayList<Pair<String, AstNode>>()

    /** Whether the walk recorded a call of a routine (an entity) since occurrence [before]. */
    private fun calledRoutine(before: Int): Boolean =
        state.occurrences.subList(before, state.occurrences.size).any { Role.CALL in it.roles && it.entityKey != null }

    private fun arguments(call: AstNode): List<AstNode> =
        call.getFirstChildOrNull(PlSqlGrammar.ARGUMENTS)?.getChildren(PlSqlGrammar.ARGUMENT).orEmpty().map { it.lastChild }

    private fun isFieldOfName(value: AstNode) =
        value.type == PlSqlGrammar.MEMBER_EXPRESSION && value.numberOfChildren == 3 && value.firstChild.type == PlSqlGrammar.VARIABLE_NAME

    /**
     * Counts the [rowFieldCandidates] whose record zpa binds to the record of a cursor `FOR` loop, or to a variable or
     * parameter of the file declared `%ROWTYPE`.
     */
    private fun rowFields(shapes: TypeShapes) {
        for ((shape, value) in rowFieldCandidates) {
            val declaration = (nameBinding(value.firstChild) as? NameBinding.Local)?.declaration ?: continue
            val row = declaration.kind == NameDeclarationKind.LOOP_INDEX || declaration.fileId == null &&
                shapes.localDeclaration(declaration.line, declaration.column)?.getFirstChildOrNull(PlSqlGrammar.DATATYPE)?.firstChild
                    ?.let { it.type == PlSqlGrammar.ANCHORED_DATATYPE && it.lastChild.type == PlSqlKeyword.ROWTYPE } == true
            if (row) state.leftOut(shape)
        }
    }

    /** In static SQL, a column read passed as is to a routine (not a built-in SQL function): counted per argument. */
    private fun passedColumns(statement: Statement) {
        val calls = state.occurrences.filter { it.statementLocalId == statement.id && Role.CALL in it.roles && it.entityKey != null }.mapTo(HashSet()) { it.span }
        if (calls.isEmpty()) return
        for (call in statement.root.getDescendants(PlSqlGrammar.METHOD_CALL)) {
            if (spanOf(call.firstChild) !in calls) continue
            state.leftOut(ARGUMENT, arguments(call).count { argument -> statement.columnReads[argument].orEmpty().any { it.entityKey != null } })
        }
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
                result += item(statement, column.firstChild, column.children.drop(1).lastOrNull { it.type == PlSqlGrammar.IDENTIFIER_NAME })
                continue
            }
            for (source in sources) {
                val relation = source.target?.resolved?.relation?.takeIf { it.isColumnsComplete }
                if (relation == null) {
                    result += QueryItem(null, null, null, opaque = true)
                    continue
                }
                for (field in relation.rowFields.sortedBy { it.position.asInt }) {
                    result += QueryItem(field.name.exact(), source.target.resolved.entityKey + "." + field.name.exact(), null)
                }
            }
        }
        return result
    }

    /** One select or `RETURNING` item: the column it is, the shape of an expression of one column, or neither. */
    private fun item(statement: Statement, expression: AstNode, alias: AstNode?): QueryItem {
        val segments = plsql.nameSegments(expression)
        val name = alias?.let { normalizeIdentifier(it.tokenOriginalValue) } ?: segments?.last()?.let { normalizeIdentifier(it) }
        if (segments != null) {
            val read = statement.columnReads[expression].orEmpty().singleOrNull()?.takeIf { it.entityKey != null && it.status in COLUMN_STATUSES }
            return QueryItem(name, read?.entityKey, null)
        }
        val columns = HashSet<String>()
        fun visit(node: AstNode) {
            statement.columnReads[node]?.let { reads -> reads.mapNotNullTo(columns) { it.entityKey } }
            node.children.forEach(::visit)
        }
        visit(expression)
        return QueryItem(name, null, if (columns.size == 1) expressionShape(expression) else null)
    }

    /** The outer function or operator of an expression item: `NVL`, `UPPER`, `TRIM`, `SUBSTR`, `MAX_MIN`, `TO_CHAR`, `CONCATENATION`, `OTHER`. */
    private fun expressionShape(expression: AstNode): String {
        var node = expression
        while (node.type == PlSqlGrammar.BRACKED_EXPRESSION && node.numberOfChildren == 3) node = node.children[1]
        if (node.type == PlSqlGrammar.CONCATENATION_EXPRESSION) return "CONCATENATION"
        val function = if (node.type == PlSqlGrammar.METHOD_CALL) plsql.nameSegments(node.firstChild)?.last()?.uppercase() else node.tokenValue.uppercase()
        return when (function) {
            "NVL", "NVL2" -> "NVL"
            "UPPER", "LOWER" -> if (function == "UPPER") "UPPER" else "OTHER"
            "TRIM", "LTRIM", "RTRIM" -> "TRIM"
            "SUBSTR", "SUBSTRB" -> "SUBSTR"
            "MAX", "MIN" -> "MAX_MIN"
            "TO_CHAR" -> "TO_CHAR"
            else -> "OTHER"
        }
    }

    /**
     * The `INTO`s of the file, their targets decided, and the row record fields assigned or passed counted: call once
     * the file is left, when the field uses are recorded. The expression items an `INTO` reads are counted after the
     * scan, with the cursors of other files.
     */
    fun intos(shapes: TypeShapes): List<IntoFact> {
        rowFields(shapes)
        if (pending.isEmpty()) return emptyList()
        val bySpan = state.occurrences.filter { it.entityKey != null }.groupBy { it.span }
        return pending.mapNotNull { into ->
            val targets = into.targets.map { target(it, bySpan, shapes) }
            var cursorKey: String? = null
            var branches = into.branches
            if (into.cursor != null) {
                val cursor = into.cursor
                cursorKey = bySpan[spanOf(cursor)]?.firstOrNull()?.entityKey
                if (cursorKey == null) {
                    val declaration = local(cursor, shapes)
                    val query = declaration?.takeIf { it.type == PlSqlGrammar.CURSOR_DECLARATION }?.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)
                    val items = query?.let { state.cursorItems[it] }
                    if (items == null) {
                        state.leftOut(FETCH_CURSOR_NOT_KNOWN, targets.size)
                        return@mapNotNull null
                    }
                    branches = listOf(items)
                }
            }
            IntoFact(into.source, into.bulk, into.context.unitIndex, into.context.container, into.statementId, branches, cursorKey, targets)
        }
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
     * zpa binds its last segment to, as the index records its use. An element of an element (`a(i)(j)`, `a(i).f(j)(k)`)
     * is a shape the index does not follow (`TARGET_SHAPE_NOT_FOLLOWED`).
     */
    private fun target(node: AstNode, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): IntoTarget {
        val span = spanOf(node)
        val written = writtenText(node)
        if (node.type == PlSqlGrammar.OBJECT_REFERENCE) {
            val (field, element) = fieldThroughElement(node)
                ?: return IntoTarget(span, written, null, emptyList(), false, null, null, TARGET_SHAPE_NOT_FOLLOWED)
            val key = bySpan[spanOf(field)]?.firstOrNull()?.entityKey
            return IntoTarget(span, written, key, emptyList(), element, null, null, if (key == null) "FIELD_UNDECIDED" else null)
        }
        val element = node.type == PlSqlGrammar.METHOD_CALL
        if (element && isElementOfElement(node)) return IntoTarget(span, written, null, emptyList(), true, null, null, TARGET_SHAPE_NOT_FOLLOWED)
        val name = if (element) node.firstChild else node
        val segments = plsql.nameSegments(name)?.map { normalizeIdentifier(it) }
            ?: return IntoTarget(span, written, null, emptyList(), element, null, null, "TARGET_NOT_A_NAME")
        if (segments.size > 1) {
            val last = name.children.last { it.type == PlSqlGrammar.IDENTIFIER_NAME || it.type == PlSqlGrammar.VARIABLE_NAME }
            bySpan[spanOf(last)]?.firstOrNull()?.entityKey?.let { return IntoTarget(span, written, it, emptyList(), element, null, null, null) }
        }
        bySpan[spanOf(name)]?.firstOrNull()?.entityKey?.let { key ->
            val own = key.substringAfterLast('.').substringBefore('@').substringBefore('#')
            val at = segments.indexOfLast { it == own }
            return IntoTarget(span, written, key, if (at >= 0) segments.drop(at + 1) else segments.drop(1), element, null, null, null)
        }
        val declaration = local(name, shapes)
            ?: return IntoTarget(span, written, null, emptyList(), element, null, null, "TARGET_UNDECIDED")
        val shape = shapes.of(declaration, null) ?: TypeShape.Unknown("DECLARATION_UNKNOWN")
        return IntoTarget(span, written, null, segments.drop(1), element, shape, DeclaredTexts.of("", declaration)?.datatype, null)
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
        const val DYNAMIC_INTO = "DYNAMIC_INTO"
        const val RECORD_ASSIGNMENT = "RECORD_ASSIGNMENT"
        const val ARGUMENT = "ARGUMENT"
        const val FETCH_CURSOR_NOT_KNOWN = "FETCH_CURSOR_NOT_KNOWN"

        /** A target whose shape the index does not follow (an element of an element). */
        private const val TARGET_SHAPE_NOT_FOLLOWED = "TARGET_SHAPE_NOT_FOLLOWED"

        /** The statuses of a column read that names the column: an unresolved or ambiguous one names none. */
        private val COLUMN_STATUSES = setOf(Status.RESOLVED, Status.UNVERIFIED, Status.EXTERNAL, Status.PARTIAL_SOURCE)
    }
}
