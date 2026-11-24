//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.DmlGrammar
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.semantics.NameBinding
import com.felipebz.zpa.atlas.model.PlaceShapes
import com.felipebz.zpa.atlas.model.QueryItem
import com.felipebz.zpa.atlas.model.Status
import java.util.*

/**
 * The select items of the queries of one file, and the items of a `RETURNING` or of a call's argument in static SQL
 * ([item]): the column each is, read as is or through value functions, followed through inline views and `WITH`
 * subqueries, or the shape of another expression.
 */
internal class SelectItems(
    private val plsql: PlSqlNames,
    private val columnReads: ColumnReads,
    private val lineage: ViewLineage,
    private val nameBinding: (AstNode) -> NameBinding,
    private val expressions: ExpressionShapes
) {
    /** The select list of [block], a `*` expanded to the columns of a relation the dictionary lists completely. */
    fun selectItems(statement: Statement, block: AstNode): List<QueryItem> {
        val result = ArrayList<QueryItem>()
        for (column in block.getChildren(DmlGrammar.SELECT_COLUMN)) {
            val sources = columnReads.starSources(statement, column)
            if (sources == null) {
                result += item(
                    statement,
                    column.firstChild,
                    column.children.drop(1).lastOrNull { it.type == PlSqlGrammar.IDENTIFIER_NAME },
                    block
                )
                continue
            }
            for (source in sources) {
                val relation = source.target?.resolved?.relation?.takeIf { it.isColumnsComplete }
                if (relation == null) {
                    // An inline view or `WITH` subquery: each of its columns, as its items read it.
                    val inner = lineage.sourceSlots(statement, source)
                    if (inner == null) result += QueryItem(null, null, null, opaque = true)
                    else inner.forEach { (name, slots) ->
                        result += through(
                            name,
                            slots.map { slotItem(statement, it) },
                            emptyList()
                        )
                    }
                    continue
                }
                for (field in relation.rowFields.sortedBy { it.position.asInt }) {
                    result += QueryItem(
                        field.name.exact(),
                        source.target.resolved.entityKey + "." + field.name.exact(),
                        null
                    )
                }
            }
        }
        return result
    }

    /**
     * One select or `RETURNING` item: the column it is, read as is or through value functions ([ExpressionShapes.valueChain]), the shape
     * of another expression of one column, or neither. A name of a column of an inline view or `WITH` subquery reads
     * what that column's items read ([viewItems]): the same item as reading them directly.
     */
    fun item(statement: Statement, expression: AstNode, alias: AstNode?, block: AstNode? = null): QueryItem {
        val segments = plsql.nameSegments(expression)
        val name = alias?.let { normalizeIdentifier(it.tokenOriginalValue) } ?: segments?.last()
            ?.let { normalizeIdentifier(it) }

        fun column(node: AstNode) = columnOf(statement, node)
        if (segments != null) {
            column(expression)?.let { return QueryItem(name, it, null) }
            viewItems(statement, expression)?.let { return through(name, it, emptyList()) }
            return QueryItem(name, null, null, notColumn = notColumn(statement, expression, block))
        }
        viewExpression(statement, expression, name)?.let { return it }
        val reads: Map<String, List<AstNode>> = buildMap<String, MutableList<AstNode>> {
            fun visit(node: AstNode) {
                statement.columnReads[node]?.forEach { read -> read.entityKey?.let { getOrPut(it) { ArrayList() } += node } }
                node.children.forEach(::visit)
            }
            visit(expression)
        }
        // A column read only where it never lands in the value (the search text of a REPLACE) is not read into the target.
        val landing = reads.mapValues { (_, nodes) -> nodes.filter { !expressions.inDroppedArgument(expression, it) } }.filterValues { it.isNotEmpty() }
        if (landing.isEmpty() && reads.isNotEmpty()) return QueryItem(name, null, null, notColumn = "NO_COLUMN")
        if (landing.isEmpty()) return QueryItem(name, null, null, notColumn = notColumn(statement, expression, block))
        // A column passed to a function the index follows, whose result is the value, is followed through that function.
        val calls = expressions.followedCalls(expression)
        val kept = if (calls.isEmpty()) landing
            else landing.mapValues { (_, nodes) -> nodes.filter { !expressions.inCall(expression, it, calls) } }.filterValues { it.isNotEmpty() }
        // Counted as before where the reader does not follow the function's result.
        if (kept.isEmpty()) return readItem(name, expression, landing, emptyList(), ::column).copy(routine = true)
        return readItem(name, expression, kept, calls, ::column)
    }

    private fun readItem(
        name: String?, expression: AstNode, kept: Map<String, List<AstNode>>, calls: List<AstNode>, column: (AstNode) -> String?
    ): QueryItem {
        // A column only compared (a DECODE's expression, a CASE's condition, NVL2's first argument) never lands in the value.
        val given = kept.filterValues { nodes -> nodes.any { !expressions.inTestedArgument(expression, it) } }
        if (given.isEmpty() && kept.size > 1) return QueryItem(name, null, null, combined = expressions.severalShape(expression, kept.values.flatten()))
        if (given.size > 1) {
            return eachRead(name, expression, given) ?: QueryItem(name, null, null, combined = expressions.expressionShape(expression))
        }
        val key = given.keys.singleOrNull() ?: kept.keys.single()
        fun isKey(node: AstNode) = plsql.nameSegments(node) != null && column(node) == key && !expressions.inCall(expression, node, calls)
        val functions = expressions.valueChain(expression, ::isKey)
        if (functions != null) return QueryItem(name, key, null, functions = functions)
        // The column read more than once, each where the value may come from (`nvl(code, lower(code))`): each read.
        val nodes = kept.getValue(key)
        if (nodes.size > 1) eachRead(name, expression, mapOf(key to nodes))?.let { return it }
        return QueryItem(name, null, expressions.valueShape(expression, ::isKey))
    }

    /**
     * The item named [name] that [expression] is, each of its column reads ([reads], by column) followed on its own
     * ([ExpressionShapes.valueChain]): a part of a concatenation, an argument `NVL`, `NVL2` or `COALESCE` may give back,
     * text a `REPLACE` puts in. Each column a read leads to is a place, once per chain of functions; the first is the
     * item, the others its [QueryItem.parts]. A column no read of which is followed (`to_char(amount)`) is told by the
     * shape the item would have with only that column ([QueryItem.expression]: `code || to_char(amount)` is
     * `CONCATENATION`, `nvl(code, to_char(amount))` is `NVL_TO_CHAR`); one only tested (`amount` of
     * `nvl2(amount, code, note)`) gives nothing. Null when no read is followed.
     */
    private fun eachRead(name: String?, expression: AstNode, reads: Map<String, List<AstNode>>): QueryItem? {
        val chains = reads.toSortedMap().map { (key, nodes) ->
            key to nodes.mapNotNull { node -> expressions.valueChain(expression) { it === node } }.distinct()
        }
        val followed = chains.flatMap { (key, list) -> list.map { QueryItem(name, key, null, functions = it) } }
        if (followed.isEmpty()) return null
        // A column only tested (the first argument of NVL2) gives nothing: the others are the value.
        val shape = chains.firstOrNull { (key, list) -> list.isEmpty() && reads.getValue(key).any { !expressions.inTestedArgument(expression, it) } }
            ?.let { (key, _) ->
                val nodes = reads.getValue(key)
                expressions.valueShape(expression, { node -> nodes.any { it === node } })
            }
        return followed.first().copy(expression = shape, parts = followed.drop(1))
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

    // A function's result read through a view is not followed.
    private fun slotItem(statement: Statement, slot: Slot): QueryItem = when (slot) {
        is Slot.Item -> item(statement, slot.column.firstChild, null).copy(routine = false)
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
            first.expression != null || first.combined != null -> QueryItem(
                name,
                null,
                splitShape(expressions.expressionShape(outer), first)
            )

            else -> QueryItem(name, null, null, notColumn = first.notColumn ?: "NO_COLUMN")
        }
    }

    /**
     * [shape], of an expression that reaches the view column whose item is [inner] through value functions: for `NVL`,
     * `NVL2` and `COALESCE`, followed by where following stops in [inner] ([stopName]).
     */
    private fun splitShape(shape: String, inner: QueryItem) =
        if (shape in PlaceShapes.SPLIT_SHAPES) shape + "_" + stopName(inner) else shape

    /**
     * Where following a value stops in the item [inner], an expression: its shape, without a split family's own name
     * (`NVL_TO_CHAR` stops at `TO_CHAR`); `SEVERAL_COLUMNS` for an expression of several columns.
     */
    private fun stopName(inner: QueryItem): String {
        val shape = inner.expression ?: return if (inner.combined != null) "SEVERAL_COLUMNS" else "OTHER"
        return PlaceShapes.SPLIT_SHAPES.firstOrNull { shape.startsWith(it + "_") }?.let { shape.removePrefix(it + "_") }
            ?: shape
    }

    /**
     * An expression item ([expression]) that reads a column of an inline view or `WITH` subquery: told as the same
     * expression of the columns that view column reads would be. A single name read through value functions is those
     * columns with the functions; several names, each followed on its own (a part of a concatenation, an argument of
     * `NVL`), are the columns of each; any other expression is its shape, by how many columns it reads. Null when the expression reads no view column.
     */
    private fun viewExpression(statement: Statement, expression: AstNode, name: String?): QueryItem? {
        val leaves = ArrayList<Pair<AstNode, List<QueryItem>>>()
        viewLeaves(statement, expression, leaves)
        if (leaves.none { (node, _) -> statement.columnReads[node] == null }) return null
        val chains = leaves.map { (node, _) -> expressions.valueChain(expression) { it === node } }
        if (leaves.size == 1 && chains.single() != null) return through(
            name,
            leaves.single().second,
            chains.single()!!,
            expression
        )
        if (leaves.size > 1 && chains.all { it != null }) {
            val all = leaves.zip(chains).map { (leaf, chain) -> through(name, leaf.second, chain!!, expression) }
                .filter { it.columnKey != null }
                .flatMap { listOf(it.copy(parts = emptyList())) + it.parts }
            if (all.isNotEmpty()) return all.first().copy(parts = all.drop(1))
        }
        return viewExpressionShape(name, expression, leaves)
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
     * reads ([leaves], each with its items): its shape, combined when they read several columns; else why none reads a column.
     */
    private fun viewExpressionShape(
        name: String?,
        expression: AstNode,
        leaves: List<Pair<AstNode, List<QueryItem>>>
    ): QueryItem {
        val inner = leaves.flatMap { it.second }
        val columns = inner.flatMap { listOf(it) + it.parts }.mapNotNullTo(HashSet()) { it.columnKey }
        return when {
            columns.size > 1 -> QueryItem(name, null, null, combined = expressions.expressionShape(expression))
            columns.size == 1 || inner.any { it.expression != null || it.combined != null } -> {
                val reached =
                    { inner.firstOrNull { it.expression != null || it.combined != null }?.let(::stopName) ?: "OTHER" }
                QueryItem(
                    name,
                    null,
                    expressions.valueShape(expression, { node -> leaves.any { it.first === node } }, reached)
                )
            }

            else -> QueryItem(name, null, null, notColumn = inner.firstNotNullOfOrNull { it.notColumn } ?: "NO_COLUMN")
        }
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
        if (expressions.subtree(expression)
                .any { statement.columnReads[it].orEmpty().isNotEmpty() }
        ) return "COLUMN_UNRESOLVED"
        // A bare name that no declaration has and that SQL may take as a column, of a source whose columns are not known.
        val bare = plsql.nameSegments(expression)?.singleOrNull()?.let { normalizeIdentifier(it) }
        val unresolved =
            bare != null && bare !in SQL_PSEUDOCOLUMNS && (nameBinding(expression) as? NameBinding.NothingLocal)?.sqlColumnPossible == true
        return if (unresolved) "COLUMN_UNRESOLVED" else "NO_COLUMN"
    }

    /**
     * Whether [expression] names a column of an inline view of [block]'s `FROM`: by its alias (`v.code`), or by a name
     * alone when every relation of the `FROM` is an inline view.
     */
    private fun readsInlineView(expression: AstNode, block: AstNode): Boolean {
        val from = block.getFirstChildOrNull(DmlGrammar.FROM_CLAUSE) ?: return false
        val items = from.getDescendants(DmlGrammar.DML_TABLE_EXPRESSION_CLAUSE).filter { item ->
            generateSequence(item.parentOrNull) { it.parentOrNull }.takeWhile { it !== from }
                .none { it.type == DmlGrammar.SELECT_EXPRESSION }
        }
        val views = items.filter { it.hasDirectChildren(DmlGrammar.SELECT_EXPRESSION) }
        if (views.isEmpty()) return false
        val aliases = views.mapNotNull { view ->
            view.getFirstChildOrNull(DmlGrammar.ALIAS)?.let { normalizeIdentifier(it.lastChild.tokenOriginalValue) }
        }.toSet()
        val names = expressions.subtree(expression)
            .filter { it.type == PlSqlGrammar.VARIABLE_NAME || it.type == PlSqlGrammar.MEMBER_EXPRESSION }
        return names.any { name ->
            val segments = plsql.nameSegments(name) ?: return@any false
            when {
                segments.size >= 2 -> name.type == PlSqlGrammar.MEMBER_EXPRESSION && normalizeIdentifier(segments.first()) in aliases
                else -> name.parentOrNull?.type != PlSqlGrammar.MEMBER_EXPRESSION && views.size == items.size &&
                    normalizeIdentifier(segments.single()) !in PSEUDO_COLUMNS && nameBinding(name) !is NameBinding.Local
            }
        }
    }

    /** The column [node] reads in [statement], when it is a column read that names one. */
    fun columnOf(statement: Statement, node: AstNode) =
        statement.columnReads[node].orEmpty().singleOrNull()
            ?.takeIf { it.entityKey != null && it.status in COLUMN_STATUSES }?.entityKey

    companion object {
        private val PSEUDO_COLUMNS = setOf(
            "ROWNUM",
            "LEVEL",
            "ROWID",
            "SYSDATE",
            "SYSTIMESTAMP",
            "USER",
            "CURRENT_DATE",
            "CURRENT_TIMESTAMP",
            "UID"
        )

        /** The statuses of a column read that names the column: an unresolved or ambiguous one names none. */
        private val COLUMN_STATUSES = setOf(Status.RESOLVED, Status.UNVERIFIED, Status.EXTERNAL, Status.PARTIAL_SOURCE)
    }
}
