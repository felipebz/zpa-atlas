//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.*
import com.felipebz.zpa.api.project.ProjectParameterMode
import com.felipebz.zpa.api.semantics.NameBinding
import com.felipebz.zpa.api.semantics.NameDeclarationKind
import com.felipebz.zpa.atlas.model.*

/**
 * The `INTO`s that read select items into PL/SQL targets ([IntoFact]): `SELECT … INTO`, `FETCH … INTO` of an explicit
 * cursor, `RETURNING … INTO`, with or without `BULK COLLECT`, and the assignments `target := r.f` of a field of a
 * record, which is a row record when it is a cursor `FOR` loop record or a `%ROWTYPE` variable (decided after the
 * scan). A select item is a column when it is the column itself, written bare or qualified (the column read static SQL
 * records at it), or a `*` over a relation whose columns the dictionary lists; an expression of one column is counted by
 * its shape. A target is the entity zpa binds its name to (the value use the walk records), else the declaration
 * without an entity zpa binds it to, with its own type.
 *
 * Other reads of a column into PL/SQL are counted, not recorded ([FileState.placesLeftOut]): dynamic `INTO`, a field
 * of a `%ROWTYPE` or cursor loop record passed to a routine, a column passed to a routine in static SQL, and a `FETCH`
 * from a cursor whose query is not known.
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
        if (root.parentOrNull?.type == PlSqlGrammar.CURSOR_DECLARATION || isLoopQuery(root)) {
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

    /**
     * `target := r.f`, or an expression of one such field (`upper(r.f)`): kept to be decided once the file is left,
     * when what `r` is is known ([intos]). Through value functions only ([valueChain]), the field is read as is.
     */
    fun assignment(node: AstNode, context: Context) {
        val at = node.children.indexOfFirst { it.type == PlSqlPunctuator.ASSIGNMENT }
        if (at <= 0) return
        val value = node.children.getOrNull(at + 1) ?: return
        val field = if (isFieldOfName(value)) value else value.getDescendants(PlSqlGrammar.MEMBER_EXPRESSION).singleOrNull(::isFieldOfName) ?: return
        val functions = valueChain(value) { it === field }
        assignments += Assignment(node.firstChild, spanOf(node.children[at]), field, if (functions == null) expressionShape(value) else null,
            functions.orEmpty(), context)
    }

    private class Assignment(
        val target: AstNode, val site: SourceSpan, val field: AstNode, val expression: String?, val functions: List<String>, val context: Context
    )

    private val assignments = ArrayList<Assignment>()

    /** Whether [query] is the query of a cursor `FOR` loop (`for r in (select …)`). */
    private fun isLoopQuery(query: AstNode): Boolean {
        var node = query.parentOrNull
        repeat(6) {
            if (node == null) return false
            if (node!!.type == PlSqlGrammar.ITERATOR) return true
            node = node!!.parentOrNull
        }
        return false
    }

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
        if (!calledRoutine(before)) return
        arguments(node).filter(::isFieldOfName).forEach { rowFieldCandidates += ARGUMENT to it }
        val call = state.occurrences.subList(before, state.occurrences.size).lastOrNull { Role.CALL in it.roles && it.entityKey != null } ?: return
        for (argument in arguments(node)) {
            // The walk keeps the mode at the argument's expression node, which may wrap or be wrapped by this one.
            val span = spanOf(argument)
            val mode = (generateSequence(argument) { it.parentOrNull }.take(3) + generateSequence(argument) { it.children.firstOrNull() }.take(4))
                .filter { it.hasToken() && spanOf(it) == span }.firstNotNullOfOrNull { state.argumentModes[it] } ?: continue
            if (mode != ProjectParameterMode.OUT && mode != ProjectParameterMode.IN_OUT) continue
            val parameter = call.arguments.firstOrNull { it.span == span }?.parameterKey ?: continue
            outArguments += OutArgument(argument, parameter, context)
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
        val wanted = normalizeIdentifier(callee.tokenOriginalValue)
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
        fun parametersOf(subprogram: AstNode) =
            subprogram.getFirstChildOrNull(PlSqlGrammar.PARAMETER_DECLARATIONS)?.getChildren(PlSqlGrammar.PARAMETER_DECLARATION).orEmpty()
        fun isOut(parameter: AstNode) = parameter.hasDirectChildren(PlSqlKeyword.OUT)
        val subprogram = subprograms.singleOrNull()
        if (subprogram == null) {
            for (parameter in subprograms.flatMap(::parametersOf).filter(::isOut)) undecidedCalls += spanOf(node) to localKey(parameter)
            return
        }
        val parameters = parametersOf(subprogram)
        val written = node.getFirstChildOrNull(PlSqlGrammar.ARGUMENTS)?.getChildren(PlSqlGrammar.ARGUMENT).orEmpty()
        for ((index, argument) in written.withIndex()) {
            val label = argument.takeIf { it.hasDirectChildren(PlSqlPunctuator.ASSOCIATION) }?.firstChild?.let { normalizeIdentifier(it.tokenOriginalValue) }
            val parameter = if (label == null) parameters.getOrNull(index)
                else parameters.firstOrNull { normalizeIdentifier(it.firstChild.tokenOriginalValue) == label }
            if (parameter == null || !isOut(parameter)) continue
            outArguments += OutArgument(argument.lastChild, localKey(parameter), context)
        }
    }

    /** Where a nested subprogram's parameter is named in the file: `local:line:column`. */
    private fun localKey(parameter: AstNode): String = parameter.firstChild.token.let { "$LOCAL_PREFIX${it.line}:${it.column}" }

    private class OutArgument(val value: AstNode, val parameterKey: String, val context: Context)

    /** The calls of overloaded nested subprograms, with each `OUT` parameter they may be for. */
    private val undecidedCalls = ArrayList<Pair<SourceSpan, String>>()

    private val outArguments = ArrayList<OutArgument>()

    /** The `r.f` values assigned or passed, with what they are counted as when `r` is a row record. */
    private val rowFieldCandidates = ArrayList<Pair<String, AstNode>>()

    /** Whether the walk recorded a call of a routine (an entity) since occurrence [before]. */
    private fun calledRoutine(before: Int): Boolean =
        state.occurrences.subList(before, state.occurrences.size).any { Role.CALL in it.roles && it.entityKey != null }

    private fun arguments(call: AstNode): List<AstNode> =
        call.getFirstChildOrNull(PlSqlGrammar.ARGUMENTS)?.getChildren(PlSqlGrammar.ARGUMENT).orEmpty().map { it.lastChild }

    private fun isFieldOfName(value: AstNode) =
        value.type == PlSqlGrammar.MEMBER_EXPRESSION && value.numberOfChildren == 3 && value.firstChild.type == PlSqlGrammar.VARIABLE_NAME

    /** Counts the [rowFieldCandidates] whose record zpa binds to the record of a cursor `FOR` loop, or to a variable or parameter of the file declared `%ROWTYPE`. */
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

    /**
     * One select or `RETURNING` item: the column it is, read as is or through value functions ([valueChain]), the shape
     * of another expression of one column, or neither.
     */
    private fun item(statement: Statement, expression: AstNode, alias: AstNode?): QueryItem {
        val segments = plsql.nameSegments(expression)
        val name = alias?.let { normalizeIdentifier(it.tokenOriginalValue) } ?: segments?.last()?.let { normalizeIdentifier(it) }
        fun column(node: AstNode) =
            statement.columnReads[node].orEmpty().singleOrNull()?.takeIf { it.entityKey != null && it.status in COLUMN_STATUSES }?.entityKey
        if (segments != null) return QueryItem(name, column(expression), null)
        val columns = HashSet<String>()
        fun visit(node: AstNode) {
            statement.columnReads[node]?.let { reads -> reads.mapNotNullTo(columns) { it.entityKey } }
            node.children.forEach(::visit)
        }
        visit(expression)
        if (columns.size != 1) return QueryItem(name, null, null)
        var leaf: String? = null
        val functions = valueChain(expression) { node -> plsql.nameSegments(node) != null && column(node)?.also { leaf = it } != null }
        return if (functions != null && leaf != null) QueryItem(name, leaf, null, functions = functions)
            else QueryItem(name, null, expressionShape(expression))
    }

    /**
     * The value functions [expression] reads one value through, outer first, down to the node [isValue] accepts; null
     * when it is anything else. A value function gives back one of its arguments, or that argument trimmed or in
     * another case: `NVL(a, b)` and `COALESCE(a, b, …)` (any argument), `NVL2(e1, e2, e3)` (`e2` or `e3`), `MAX(x)` and
     * `MIN(x)` (with `DISTINCT`, `ALL`, `KEEP (…)`, `OVER (…)`), `TRIM`, `LTRIM`, `RTRIM`, `UPPER`, `LOWER` (`x`). Exactly
     * one of the arguments that may be the value must lead to the node.
     */
    private fun valueChain(expression: AstNode, isValue: (AstNode) -> Boolean): List<String>? {
        var node = expression
        while (node.type == PlSqlGrammar.BRACKED_EXPRESSION && node.numberOfChildren == 3) node = node.children[1]
        if (isValue(node)) return emptyList()
        fun through(function: String, candidates: List<AstNode>): List<String>? {
            val chains = candidates.mapNotNull { valueChain(it, isValue) }
            return chains.singleOrNull()?.let { listOf(function) + it }
        }
        return when (node.type) {
            PlSqlGrammar.POSTFIX_EXPRESSION -> node.firstChild.takeIf { call ->
                call.type == PlSqlGrammar.METHOD_CALL && plsql.nameSegments(call.firstChild)?.singleOrNull()?.uppercase() in setOf("MAX", "MIN") &&
                    node.children.drop(1).all { it.type == DmlGrammar.KEEP_CLAUSE || it.type == DmlGrammar.ANALYTIC_CLAUSE }
            }?.let { valueChain(it, isValue) }
            SingleRowSqlFunctionsGrammar.SINGLE_ROW_SQL_FUNCTION -> node.firstChild.takeIf { it.type == SingleRowSqlFunctionsGrammar.TRIM_EXPRESSION }?.let { trim ->
                val parts = trim.children.filter { it.type != PlSqlPunctuator.LPARENTHESIS && it.type != PlSqlPunctuator.RPARENTHESIS }.drop(1)
                val from = parts.indexOfFirst { it.type == PlSqlKeyword.FROM }
                val value = if (from >= 0) parts.getOrNull(from + 1) else parts.singleOrNull()
                value?.let { through("TRIM", listOf(it)) }
            }
            PlSqlGrammar.METHOD_CALL -> {
                val function = plsql.nameSegments(node.firstChild)?.singleOrNull()?.uppercase() ?: return null
                val arguments = node.getChildren(PlSqlGrammar.ARGUMENTS).singleOrNull()?.getChildren(PlSqlGrammar.ARGUMENT)?.map { it.lastChild }
                    ?: return null
                val candidates = when (function) {
                    "NVL" -> arguments.takeIf { it.size == 2 }
                    "COALESCE" -> arguments.takeIf { it.size >= 2 }
                    "NVL2" -> arguments.takeIf { it.size == 3 }?.drop(1)
                    "MAX", "MIN", "UPPER", "LOWER", "TRIM" -> arguments.takeIf { it.size == 1 }
                    "LTRIM", "RTRIM" -> arguments.takeIf { it.size in 1..2 }?.take(1)
                    else -> null
                } ?: return null
                through(function, candidates)
            }
            else -> null
        }
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
            "UPPER", "LOWER" -> if (function == "UPPER") "UPPER" else "OTHER"
            "TRIM", "LTRIM", "RTRIM" -> "TRIM"
            "SUBSTR", "SUBSTRB" -> "SUBSTR"
            "MAX", "MIN" -> "MAX_MIN"
            "TO_CHAR" -> "TO_CHAR"
            else -> "OTHER"
        }
    }

    /**
     * The `INTO`s of the file and its assignments of a record's field, their targets and records decided, and the row
     * record fields passed counted: call once the file is left, when the field uses are recorded. The expression items an
     * `INTO` reads are counted after the scan, with the cursors of other files.
     */
    fun intos(shapes: TypeShapes): List<IntoFact> {
        rowFields(shapes)
        if (pending.isEmpty() && assignments.isEmpty() && outArguments.isEmpty() && undecidedCalls.isEmpty()) return emptyList()
        val bySpan = state.occurrences.filter { it.entityKey != null }.groupBy { it.span }
        val intos = pending.mapNotNullTo(ArrayList()) { into ->
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
                        return@mapNotNullTo null
                    }
                    branches = listOf(items)
                }
            }
            IntoFact(into.source, into.bulk, into.context.unitIndex, into.context.container, into.statementId, branches, cursorKey, targets)
        }
        for (assignment in assignments) {
            val row = record(assignment.field, bySpan, shapes) ?: continue
            val target = target(assignment.target, bySpan, shapes).copy(span = assignment.site)
            intos += IntoFact(PlaceSource.ASSIGNMENT, false, assignment.context.unitIndex, assignment.context.container, null, emptyList(), null,
                listOf(target), row, normalizeIdentifier(assignment.field.lastChild.tokenOriginalValue), assignment.expression, assignment.functions)
        }
        // The argument passed for an OUT or IN OUT parameter, as an INTO target: it takes what the parameter is given.
        for (argument in outArguments) {
            intos += IntoFact(PlaceSource.OUT_ARGUMENT, false, argument.context.unitIndex, argument.context.container, null, emptyList(), null,
                listOf(target(argument.value, bySpan, shapes)), parameterKey = argument.parameterKey)
        }
        // A call of an overloaded nested subprogram: no target, only the parameters it may be for.
        for ((span, parameter) in undecidedCalls) {
            intos += IntoFact(PlaceSource.OUT_ARGUMENT, false, 0, null, null, emptyList(), null,
                listOf(IntoTarget(span, "", null, emptyList(), false, null, null, OUT_CALL_NOT_DECIDED)), parameterKey = parameter)
        }
        return intos
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
        while (cursor.type != PlSqlGrammar.VARIABLE_NAME && cursor.type != PlSqlGrammar.MEMBER_EXPRESSION) cursor = cursor.firstChild ?: return unknown
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
     * (`TARGET_SHAPE_NOT_FOLLOWED`).
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
        // A nested subprogram's parameter, whose calls this file binds; not an object type method's, whose calls the
        // index does not record.
        val localKey = declaration.takeIf { it.type == PlSqlGrammar.PARAMETER_DECLARATION && !it.hasAncestor(PlSqlGrammar.CREATE_TYPE_BODY) }
            ?.let(::localKey)
        return IntoTarget(span, written, null, segments.drop(1), element, shape, DeclaredTexts.of("", declaration)?.datatype, null, localKey)
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
        const val DYNAMIC_INTO = "DYNAMIC_INTO"
        const val RECORD_ASSIGNMENT = "RECORD_ASSIGNMENT"
        const val ARGUMENT = "ARGUMENT"
        const val FETCH_CURSOR_NOT_KNOWN = "FETCH_CURSOR_NOT_KNOWN"
        const val OUT_CALL_NOT_DECIDED = "OUT_CALL_NOT_DECIDED"

        /** The prefix of the key of a nested subprogram's parameter ([IntoTarget.localKey]). */
        const val LOCAL_PREFIX = "local:"

        /** The reason of a call of a subprogram nested in a routine, which has no entity. */
        private const val LOCAL_SUBPROGRAM = "LOCAL_SUBPROGRAM"

        /** A target whose shape the index does not follow (an element of an element). */
        private const val TARGET_SHAPE_NOT_FOLLOWED = "TARGET_SHAPE_NOT_FOLLOWED"

        /** The statuses of a column read that names the column: an unresolved or ambiguous one names none. */
        private val COLUMN_STATUSES = setOf(Status.RESOLVED, Status.UNVERIFIED, Status.EXTERNAL, Status.PARTIAL_SOURCE)
    }
}
