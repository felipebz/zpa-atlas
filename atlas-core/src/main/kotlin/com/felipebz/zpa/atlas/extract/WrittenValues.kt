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
import com.felipebz.zpa.atlas.model.WrittenValueFact

/** The value expressions of the columns a write clause lists: per row, one per column position; or why there are none. */
internal sealed interface ColumnValues {
    class Rows(val rows: List<List<AstNode>>) : ColumnValues {
        /** The expressions of the column at [position], one per row. */
        fun of(position: Int): List<AstNode> = rows.map { it[position] }
    }

    class None(val reason: String) : ColumnValues
}

/** Where the values of a write clause come from, paired with its columns once their count is known. */
internal sealed interface WriteSource {
    fun forColumns(count: Int): ColumnValues

    /** `VALUES (…)[, (…)]`: the items of each row, by position. */
    class Rows(private val rows: List<List<AstNode>>) : WriteSource {
        override fun forColumns(count: Int): ColumnValues =
            if (rows.isNotEmpty() && rows.all { it.size == count }) ColumnValues.Rows(rows) else ColumnValues.None(WrittenValues.COUNT_MISMATCH)
    }

    /**
     * A query: its select list items by position, only for one query block without a set operator or `*`; the alias of
     * an item is not part of its value. A block that is only a parenthesized query (`(select …)`, at any depth) is that
     * query.
     */
    class Query(private val query: AstNode?) : WriteSource {
        override fun forColumns(count: Int): ColumnValues {
            var select = query?.let { WrittenValues.subquery(it) } ?: return ColumnValues.None(WrittenValues.QUERY_SHAPE)
            var block: AstNode
            while (true) {
                val blocks = select.getChildren(DmlGrammar.QUERY_BLOCK)
                if (blocks.size != 1 || select.children.any { !it.hasChildren() && it.tokenValue.uppercase() in SET_OPERATORS }) return ColumnValues.None(WrittenValues.SET_OR_STAR)
                block = blocks.single()
                // `(select …)`: the block holds the parentheses and the query inside them.
                select = block.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)?.takeIf { !block.hasDirectChildren(PlSqlKeyword.SELECT) } ?: break
            }
            val items = block.getChildren(DmlGrammar.SELECT_COLUMN)
            if (!block.hasDirectChildren(PlSqlKeyword.SELECT) || items.isEmpty()) return ColumnValues.None(WrittenValues.QUERY_SHAPE)
            if (items.any { isStar(it) }) return ColumnValues.None(WrittenValues.SET_OR_STAR)
            if (items.size != count) return ColumnValues.None(WrittenValues.COUNT_MISMATCH)
            return ColumnValues.Rows(listOf(items.map { it.firstChild }))
        }

        private fun isStar(item: AstNode): Boolean {
            val first = item.firstChild
            return first.tokenValue == "*" && first.type is PlSqlPunctuator ||
                first.type == PlSqlGrammar.OBJECT_REFERENCE && first.lastChild.tokenValue == "*"
        }
    }

    class None(val reason: String) : WriteSource {
        override fun forColumns(count: Int): ColumnValues = ColumnValues.None(reason)
    }
}

/** How the values written to columns are found in a DML statement, and the reasons a column write has none. */
internal object WrittenValues {
    const val SET_ROW = "SET ROW"
    const val RECORD = "record"
    const val SET_OR_STAR = "set operator or *"
    const val COUNT_MISMATCH = "count mismatch"
    /** A query the pairing does not read: an empty select list, a block that is not a plain `SELECT`. */
    const val QUERY_SHAPE = "query shape"
    const val COLUMNS_INCOMPLETE = "columns not complete"
    const val OUT_ARGUMENT = "OUT argument"
    const val INTO_WITHOUT_QUERY = "INTO without a query"

    /**
     * The expressions of [values] for the column at [position], classified, one per row; none, counted under their
     * reason, when the statement gives none.
     */
    fun facts(values: ColumnValues, position: Int, context: Context, state: FileState): List<WrittenValueFact> = when (values) {
        is ColumnValues.Rows -> values.of(position).mapIndexed { index, node ->
            val value = ExpressionValues.of(node, context::hasHoles)
            WrittenValueFact(index + 1, value.kind, value.value, spanOf(node))
        }
        is ColumnValues.None -> {
            unvalued(state, values.reason)
            emptyList()
        }
    }

    fun unvalued(state: FileState, reason: String) {
        state.unvaluedWrites.merge(reason, 1, Int::plus)
    }

    /**
     * What a write to a variable or a correlation column writes, from where [target] stands: the right side of `:=`;
     * the select list item at its position among the `INTO` targets of a `SELECT … INTO`; else nothing, an `INTO` of a
     * `FETCH`, `EXECUTE IMMEDIATE` or `RETURNING` (no query to pair) or an `OUT` actual of a call.
     */
    fun assigned(target: AstNode): Pair<ColumnValues, Int> {
        val parent = target.parentOrNull ?: return ColumnValues.None(OUT_ARGUMENT) to 0
        if (parent.type == PlSqlGrammar.ASSIGNMENT_STATEMENT) {
            val children = parent.children
            val assignment = children.indexOfFirst { it.type == PlSqlPunctuator.ASSIGNMENT }
            val value = children.getOrNull(assignment + 1)
            if (value != null && children.indexOf(target) < assignment) return ColumnValues.Rows(listOf(listOf(value))) to 0
        }
        if (parent.type == DmlGrammar.INTO_CLAUSE) {
            val block = parent.parentOrNull?.takeIf { it.type == DmlGrammar.QUERY_BLOCK } ?: return ColumnValues.None(INTO_WITHOUT_QUERY) to 0
            val targets = parent.children.filter { it.type !is PlSqlPunctuator && it.type !is PlSqlKeyword }
            return WriteSource.Query(block.parent).forColumns(targets.size) to targets.indexOf(target)
        }
        return ColumnValues.None(OUT_ARGUMENT) to 0
    }

    /** The query inside [node], through the parentheses and the unary wrapper the parser puts around a subquery. */
    fun subquery(node: AstNode): AstNode? {
        var current = node
        while (current.type != DmlGrammar.SELECT_EXPRESSION) {
            current = when (current.type) {
                PlSqlGrammar.UNARY_EXPRESSION -> current.children.singleOrNull { it.type !is PlSqlPunctuator } ?: return null
                PlSqlGrammar.BRACKED_EXPRESSION -> current.children.getOrNull(1)?.takeIf { current.numberOfChildren == 3 } ?: return null
                else -> return null
            }
        }
        return current
    }

    /** The right side of `SET col = expr` or `SET (a, b) = (subquery)`. */
    fun setValue(updateColumn: AstNode): AstNode? {
        val children = updateColumn.children
        val equals = children.indexOfFirst { it.type == PlSqlPunctuator.EQUALS }
        return if (equals >= 0) children.getOrNull(equals + 1) else null
    }

    /**
     * The rows of `VALUES (…)` and of the rows that follow it (`, (…)`, siblings of the clause), or a record when the
     * clause has no parentheses (`VALUES r`).
     */
    fun valuesClause(clause: AstNode): WriteSource {
        val inside = clause.children.drop(1)
        if (inside.firstOrNull()?.type != PlSqlPunctuator.LPARENTHESIS) return WriteSource.None(RECORD)
        val following = generateSequence(clause.nextSiblingOrNull) { it.nextSiblingOrNull }.toList()
        return WriteSource.Rows(rows(inside + following))
    }

    /** The row after `VALUES` in a `MERGE … INSERT`. */
    fun mergeValues(insert: AstNode): WriteSource {
        val children = insert.children
        val values = children.indexOfFirst { it.type == PlSqlKeyword.VALUES }
        if (values < 0) return WriteSource.None(RECORD)
        val after = children.drop(values + 1)
        if (after.firstOrNull()?.type != PlSqlPunctuator.LPARENTHESIS) return WriteSource.None(RECORD)
        return WriteSource.Rows(rows(after))
    }

    /** Parenthesized rows separated by commas, from the first; anything else ends them. */
    private fun rows(nodes: List<AstNode>): List<List<AstNode>> {
        val result = ArrayList<List<AstNode>>()
        var index = 0
        while (index < nodes.size && nodes[index].type == PlSqlPunctuator.LPARENTHESIS) {
            val row = ArrayList<AstNode>()
            index++
            while (index < nodes.size && nodes[index].type != PlSqlPunctuator.RPARENTHESIS) {
                if (nodes[index].type != PlSqlPunctuator.COMMA) row += nodes[index]
                index++
            }
            result += row
            index++
            if (index + 1 < nodes.size && nodes[index].type == PlSqlPunctuator.COMMA && nodes[index + 1].type == PlSqlPunctuator.LPARENTHESIS) index++ else break
        }
        return result
    }

    /** The query an `INSERT … SELECT` takes its rows from: the first one beside the `INTO` clause or one of its owners. */
    fun insertQuery(intoClause: AstNode): AstNode? {
        var owner = intoClause.parentOrNull
        while (owner != null) {
            owner.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)?.let { return it }
            if (owner.type == DmlGrammar.INSERT_EXPRESSION) return null
            owner = owner.parentOrNull
        }
        return null
    }
}

private val SET_OPERATORS = setOf("UNION", "INTERSECT", "MINUS", "EXCEPT")
