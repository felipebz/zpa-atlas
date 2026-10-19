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
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.model.*

/**
 * A view being walked: its entity, its dictionary relation (null for a view only the sources know) and its query,
 * whose statement gives the column definitions.
 */
internal class ViewScope(val key: String, val relation: ProjectExternalRelation?, val create: AstNode, val query: AstNode?)

/**
 * One position of a query block's select list with stars expanded: an item ([Item]), a column of a complete
 * relation a star covers ([Star]), a column of an inline view or `WITH` subquery a star covers ([Inner]), a column
 * without a base that a star covers (`DUAL`, a `WITH … AS (VALUES …)`), or a star over columns that cannot be
 * listed ([Opaque]), whose width is unknown. [name] is the column name the position gives, null for an expression
 * without alias.
 */
internal sealed class Slot(val name: String?) {
    class Item(name: String?, val column: AstNode) : Slot(name)
    class Star(name: String, val key: String) : Slot(name)
    class Inner(name: String?, val query: AstNode, val position: Int) : Slot(name)
    class Constant(name: String) : Slot(name)
    object Opaque : Slot(null)
}

/**
 * The column definitions of a view written in the sources, and their lineage: the columns each view column's value is
 * computed from (`VIEW_BASE`).
 */
internal class ViewLineage(
    private val state: FileState,
    private val scopes: SqlScopes,
    private val columnReads: ColumnReads,
    private val plsql: PlSqlNames
) {
    /**
     * The definition sites of the select list of [query]'s first query block (parentheses unwrapped): aliases, column
     * names, and the columns a `*` expands to, following an inline view into its own select list. Returns the star
     * items of this list.
     */
    private fun selectSites(statement: Statement, query: AstNode, sites: MutableMap<String, Pair<AstNode, Set<Role>>>): List<AstNode> {
        var block = query.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK)
        while (block != null && block.firstChild.type == PlSqlPunctuator.LPARENTHESIS) {
            block = block.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)?.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK)
        }
        val stars = ArrayList<AstNode>()
        for (item in block?.getChildren(DmlGrammar.SELECT_COLUMN).orEmpty()) {
            val name = itemNameNode(item)
            if (name != null) {
                sites.putIfAbsent(normalizeIdentifier(name.tokenOriginalValue), name to VIEW_COLUMN_DEFINITION)
                continue
            }
            val expression = item.firstChild
            val sources = columnReads.starSources(statement, item) ?: continue
            stars += expression
            for (source in sources) {
                val expanded = source.target?.resolved?.relation?.takeIf { it.isColumnsComplete }
                val inline = source.node.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)
                when {
                    expanded != null -> for (field in expanded.rowFields) sites.putIfAbsent(field.name.exact(), expression to VIEW_STAR_DEFINITION)
                    inline != null -> selectSites(statement, inline, sites)
                }
            }
        }
        return stars
    }

    /** The node naming a select item: its alias, else the last name of a column reference; null for anything else. */
    private fun itemNameNode(item: AstNode): AstNode? {
        val alias = item.children.drop(1).firstOrNull { it.type == PlSqlGrammar.IDENTIFIER_NAME || it.type == PlSqlKeyword.BEGIN || it.type == PlSqlKeyword.END }
        if (alias != null) return alias
        val expression = item.firstChild
        if (expression.type != PlSqlGrammar.VARIABLE_NAME && expression.type != PlSqlGrammar.MEMBER_EXPRESSION) return null
        return expression.getLastChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) ?: expression.getFirstDescendantOrNull(PlSqlGrammar.IDENTIFIER_NAME)
    }

    /**
     * The `DEFINITION` of every dictionary column of the view, where the `CREATE VIEW` text names it: the column list,
     * matched by name; otherwise the first query block of the main query (the branch that names the columns of a
     * compound query), matched by name: an alias, a column reference's name, or a `*` (`DEFINITION,IMPLICIT`) for the
     * columns it expands to, or the sites inside the inline view it expands. A single star of the list also takes every
     * column still without a site. A column with no site gets a `VIEW_COLUMN_UNMATCHED` note.
     *
     * A column with a site also gets its lineage: a `VIEW_BASE` relation to every column its value is computed from
     * ([columnAt], [columnByName]), or a `VIEW_LINEAGE_UNKNOWN` note when its source cannot be followed.
     */
    fun viewColumns(statement: Statement, scope: ViewScope) {
        val relation = scope.relation ?: return
        val columns = relation.rowFields.sortedBy { it.position.asInt }
        val sites = HashMap<String, Pair<AstNode, Set<Role>>>()
        val list = scope.create.getChildren(PlSqlGrammar.VIEW_COLUMN_DEFINITION, PlSqlGrammar.MATERIALIZED_VIEW_COLUMN_DEFINITION)
            .mapNotNull { it.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) }
        val listed = list.map { normalizeIdentifier(it.tokenOriginalValue) }
        if (list.isNotEmpty()) {
            // The names of the list are the view's column names; the database may disagree with the file.
            list.forEachIndexed { index, name -> sites.putIfAbsent(listed[index], name to VIEW_COLUMN_DEFINITION) }
        } else {
            val stars = scope.query?.let { selectSites(statement, it, sites) }.orEmpty()
            // Every column no other item names can only come from a single star (TABLE(...), incomplete columns).
            stars.singleOrNull()?.let { star -> columns.forEach { sites.putIfAbsent(it.name.exact(), star to VIEW_STAR_DEFINITION) } }
        }
        for (column in columns) {
            val name = column.name.exact()
            val (site, roles) = sites[name] ?: run {
                state.coverage += CoverageFact("VIEW_COLUMN_UNMATCHED", "${scope.key}: column $name has no site in the CREATE VIEW text")
                continue
            }
            val columnKey = scope.key + "." + name
            state.occurrences += OccurrenceFact(statement.context.unitIndex, scope.key, null, spanOf(site), writtenText(site), columnKey,
                roles, status(Status.RESOLVED, statement.context.partial), statement.context.origin, null)
            val query = scope.query ?: continue
            val bases = if (list.isNotEmpty()) columnAt(statement, query, listed.indexOf(name)) else columnByName(statement, query, name)
            if (bases == null) {
                state.coverage += CoverageFact("VIEW_LINEAGE_UNKNOWN", "${scope.key}: column $name comes from a source whose columns cannot be followed")
            } else {
                for (base in bases) if (base != columnKey) state.relations += RelationFact(columnKey, RelationKind.VIEW_BASE, base)
            }
        }
    }

    /** The query blocks of a compound query, in order, parenthesized branches flattened. */
    private fun branches(query: AstNode): List<AstNode> = query.getChildren(DmlGrammar.QUERY_BLOCK).flatMap { block ->
        if (block.firstChild.type == PlSqlPunctuator.LPARENTHESIS) block.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)?.let { branches(it) }.orEmpty()
        else listOf(block)
    }

    /** The select list of [block] with its stars expanded, by position. */
    private fun slots(statement: Statement, block: AstNode): List<Slot> = statement.slots.getOrPut(block) {
        val result = ArrayList<Slot>()
        for (item in block.getChildren(DmlGrammar.SELECT_COLUMN)) {
            val sources = columnReads.starSources(statement, item)
            if (sources == null) {
                result += Slot.Item(itemNameNode(item)?.let { normalizeIdentifier(it.tokenOriginalValue) }, item)
                continue
            }
            for (source in sources) {
                val relation = source.target?.resolved?.relation?.takeIf { it.isColumnsComplete }
                when {
                    relation != null -> for (field in relation.rowFields.sortedBy { it.position.asInt }) {
                        result += Slot.Star(field.name.exact(), source.target.resolved.entityKey + "." + field.name.exact())
                    }
                    source.query != null -> result += innerSlots(statement, source.query, source.listed)
                    source.listed != null -> source.listed.forEach { result += Slot.Constant(it) }
                    else -> result += Slot.Opaque
                }
            }
        }
        result
    }

    /** The columns of an inline view or `WITH` subquery a star covers: its column list, else its first branch's names. */
    private fun innerSlots(statement: Statement, query: AstNode, listed: List<String>?): List<Slot> {
        if (listed != null) return listed.mapIndexed { index, name -> Slot.Inner(name, query, index) }
        val inner = slots(statement, branches(query).firstOrNull() ?: return listOf(Slot.Opaque))
        if (inner.any { it is Slot.Opaque }) return listOf(Slot.Opaque)
        return inner.mapIndexed { index, slot -> Slot.Inner(slot.name, query, index) }
    }

    /**
     * The base columns of the column named [name] of [query]: found by name in its first branch, then by position in
     * every branch of a compound query. Null when it cannot be followed (no such name, or a branch whose select list
     * cannot be expanded to that position).
     */
    private fun columnByName(statement: Statement, query: AstNode, name: String): Set<String>? = guarded(statement, query, "n:$name") {
        val branches = branches(query)
        val first = branches.firstOrNull()?.let { slots(statement, it) }
        val index = first?.indexOfFirst { it.name == name } ?: -1
        when {
            first == null || index < 0 -> null
            branches.size == 1 -> bases(statement, first[index])
            first.subList(0, index).any { it is Slot.Opaque } -> null
            else -> columnAt(statement, query, index)
        }
    }

    /** The base columns of the column at [position] of [query]: the union over every branch; null when a branch cannot tell. */
    private fun columnAt(statement: Statement, query: AstNode, position: Int): Set<String>? {
        if (position < 0) return emptySet()
        return guarded(statement, query, "p:$position") {
            val result = HashSet<String>()
            for (branch in branches(query)) {
                val slots = slots(statement, branch)
                if (slots.size <= position || slots.subList(0, position + 1).any { it is Slot.Opaque }) return@guarded null
                result += bases(statement, slots[position]) ?: return@guarded null
            }
            result
        }
    }

    /** A recursive `WITH` reaches the column it is computing again: that path adds nothing. */
    private fun guarded(statement: Statement, query: AstNode, column: String, body: () -> Set<String>?): Set<String>? {
        val active = statement.following.getOrPut(query) { HashSet() }
        if (!active.add(column)) return emptySet()
        try {
            return body()
        } finally {
            active.remove(column)
        }
    }

    private fun bases(statement: Statement, slot: Slot): Set<String>? = when (slot) {
        is Slot.Item -> itemBases(statement, slot.column)
        is Slot.Star -> setOf(slot.key)
        is Slot.Inner -> columnAt(statement, slot.query, slot.position)
        is Slot.Constant -> emptySet()
        Slot.Opaque -> null
    }

    /**
     * Every column read inside a select item, at any depth (a `CASE` condition, the arguments, `PARTITION BY` and
     * `ORDER BY` of a function, a scalar subquery's `WHERE`), plus the base columns of the names that refer to a
     * column of an inline view or `WITH` subquery. Ambiguous and unresolved reads give nothing. Null when a name refers
     * to a source whose columns cannot be followed.
     */
    private fun itemBases(statement: Statement, item: AstNode): Set<String>? {
        val result = HashSet<String>()
        var known = true
        fun visit(node: AstNode) {
            val reads = statement.columnReads[node]
            if (reads != null) {
                for (read in reads) if (read.entityKey != null && read.status in LINEAGE_STATUSES) result += read.entityKey
                return
            }
            if (node.type in NAMED_DATATYPES) return
            // `REF(x)` has no base column; `VALUE(x)` has every column of the relation, when the dictionary lists them.
            columnReads.correlationCall(statement, node)?.let { (function, source) ->
                if (function == "VALUE") {
                    val relation = source.target?.resolved?.relation?.takeIf { it.isColumnsComplete }
                    if (relation == null) known = false
                    else relation.rowFields.forEach { result += source.target.resolved.entityKey + "." + it.name.exact() }
                }
                return
            }
            if (makeRefRelation(node) != null) {
                node.getFirstChild(PlSqlGrammar.ARGUMENTS)?.getChildren(PlSqlGrammar.ARGUMENT)?.drop(1)?.forEach(::visit)
                return
            }
            if ((node.type == PlSqlGrammar.VARIABLE_NAME || node.type == PlSqlGrammar.MEMBER_EXPRESSION) && plsql.isValueName(node) && plsql.nameSegments(node) != null) {
                val followed = nameLineage(statement, node)
                if (followed == null) known = false else result += followed
                return
            }
            node.children.forEach(::visit)
        }
        visit(item)
        return if (known) result else null
    }

    /**
     * The base columns of a name without a column read: a column of an inline view or `WITH` subquery, found as
     * [ColumnReads.qualifiedColumn] and [ColumnReads.unqualifiedColumn] find a source. Nothing for a pseudocolumn, a
     * PL/SQL name, or a column of a named relation; null for a column of a source whose columns cannot be followed
     * (`TABLE(...)`).
     */
    private fun nameLineage(statement: Statement, name: AstNode): Set<String>? {
        val segments = plsql.nameSegments(name)?.map { normalizeIdentifier(it) } ?: return emptySet()
        val column = segments.last()
        if (segments.size > 2 || column in SQL_PSEUDOCOLUMNS || segments.size == 2 && column in PSEUDOCOLUMNS) return emptySet()
        var scope = scopes.sqlScope(name)
        while (scope != null) {
            val sources = columnReads.scopeSources(statement, scope, name, qualified = segments.size == 2)
            if (segments.size == 2) {
                sources.firstOrNull { it.qualifier == segments[0] }?.let { return follow(statement, it, column) }
            } else if (sources.isNotEmpty()) {
                val known = sources.map { columnReads.has(it, column) }
                val matches = sources.filterIndexed { index, _ -> known[index] == true }
                val open = sources.filterIndexed { index, _ -> known[index] == null }
                when {
                    matches.size == 1 -> return follow(statement, matches.single(), column)
                    matches.size > 1 -> return emptySet()
                    open.size == 1 -> return follow(statement, open.single(), column)
                    open.size > 1 -> return emptySet()
                }
            }
            scope = scopes.sqlScope(scope)
        }
        return emptySet()
    }

    /** The base columns of [column] of [source]: nothing for a named relation (its reads are occurrences) or `DUAL`. */
    private fun follow(statement: Statement, source: ColumnSource, column: String): Set<String>? = when {
        source.target != null -> emptySet()
        source.query != null -> source.listed?.let { columnAt(statement, source.query, it.indexOf(column)) } ?: columnByName(statement, source.query, column)
        source.viewLike -> emptySet()
        else -> null
    }
}

private val VIEW_COLUMN_DEFINITION = setOf(Role.DEFINITION)
private val VIEW_STAR_DEFINITION = setOf(Role.DEFINITION, Role.IMPLICIT)

/** The statuses of a column read whose column is a base column of the select item it is in. */
private val LINEAGE_STATUSES = setOf(Status.RESOLVED, Status.UNVERIFIED, Status.EXTERNAL, Status.PARTIAL_SOURCE)
