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
import com.felipebz.zpa.api.squid.SemanticAstNode
import com.felipebz.zpa.atlas.model.*

/**
 * A column source of an SQL scope: a named relation ([target]), an inline view or `WITH` name ([viewColumns],
 * null when its select list has an unnamed item), or anything else (a table function, a collection), never
 * complete.
 */
internal class ColumnSource(
    /** The node in the `FROM` clause (or the target's reference). */
    val node: AstNode,
    /** The alias, else the relation or `WITH` name; null for an inline view or a function without alias. */
    val qualifier: String?,
    val target: Target?,
    val viewLike: Boolean,
    val viewColumns: Set<String>?,
    /** The query of an inline view or `WITH` name, which gives the values of its columns. */
    val query: AstNode? = null,
    /** The column names of a `WITH` column list or `DUAL`, in order. */
    val listed: List<String>? = null,
    /** The alias written for the source, if any. */
    val alias: String? = null
)

/** The column sources of the SQL scopes of a statement, cached in [Statement.sources]. */
internal class ColumnSources(
    private val scopes: SqlScopes,
    private val plsql: PlSqlNames
) {
    /**
     * The column sources that can capture [name] in [scope]: those it owns, except that an `INSERT` target is one only
     * for `RETURNING` (any position for a qualified name), and a `SELECT_EXPRESSION` lends its `ORDER BY` the sources
     * of its first query block.
     */
    fun scopeSources(statement: Statement, scope: AstNode, name: AstNode, qualified: Boolean): List<ColumnSource> = when (scope.type) {
        DmlGrammar.SELECT_EXPRESSION -> {
            val orderBy = name.getFirstAncestorOrNull(DmlGrammar.ORDER_BY_CLAUSE)
            val block = scope.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK)
            if (orderBy != null && orderBy.parentOrNull === scope && block != null) sourcesOf(statement, block) else sourcesOf(statement, scope)
        }
        DmlGrammar.INSERT_EXPRESSION ->
            if (qualified || name.getFirstAncestorOrNull(DmlGrammar.RETURNING_INTO_CLAUSE)?.let { scopes.sqlScope(it) === scope } == true) sourcesOf(statement, scope)
            else emptyList()
        else -> sourcesOf(statement, scope)
    }

    /** The sources [scope] owns directly: its `FROM` relations, the targets of `UPDATE`, `DELETE`, `INSERT`, `MERGE`. */
    private fun sourcesOf(statement: Statement, scope: AstNode): List<ColumnSource> = statement.sources.getOrPut(scope) {
        val result = ArrayList<ColumnSource>()
        if (scope.type == DmlGrammar.MERGE_EXPRESSION) {
            scope.getFirstChildOrNull(DmlGrammar.TABLE_REFERENCE)?.let { reference ->
                result += namedSource(statement, reference, scope.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { normalizeIdentifier(it.tokenOriginalValue) })
            }
        }
        if (scope.type == DmlGrammar.INSERT_EXPRESSION) {
            for (clause in scope.getDescendants(DmlGrammar.INSERT_INTO_CLAUSE)) {
                if (scopes.sqlScope(clause) !== scope) continue
                val reference = clause.getFirstChildOrNull(DmlGrammar.TABLE_REFERENCE) ?: continue
                result += namedSource(statement, reference, clause.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { normalizeIdentifier(it.tokenOriginalValue) })
            }
        }
        for (clause in scope.getDescendants(DmlGrammar.DML_TABLE_EXPRESSION_CLAUSE)) {
            if (scopes.sqlScope(clause) !== scope) continue
            val alias = clause.getFirstChildOrNull(DmlGrammar.ALIAS)?.let { normalizeIdentifier(it.tokenOriginalValue) }
            val reference = clause.getFirstChildOrNull(DmlGrammar.TABLE_REFERENCE)
            val subquery = clause.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)
            result += when {
                reference != null -> namedSource(statement, reference, alias)
                subquery != null -> ColumnSource(clause, alias, null, viewLike = true, viewColumns = selectNames(statement, subquery), query = subquery, alias = alias)
                else -> ColumnSource(clause, alias, null, viewLike = false, viewColumns = null, alias = alias)
            }
        }
        result
    }

    /** A relation reference as a source: a `WITH` name, `DUAL` (one column, `DUMMY`), or the relation [target] resolved. */
    private fun namedSource(statement: Statement, reference: AstNode, alias: String?): ColumnSource {
        val written = scopes.written(reference)
        if (written.link == null && written.schema == null && written.name in statement.localNames) {
            val factoring = statement.root.getDescendants(DmlGrammar.SUBQUERY_FACTORING_CLAUSE)
                .firstOrNull { normalizeIdentifier(it.firstChild.tokenOriginalValue) == written.name }
            return ColumnSource(reference, alias ?: written.name, null, viewLike = true, viewColumns = factoring?.let { withColumns(statement, it) },
                query = factoring?.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION), listed = factoring?.let { withList(it) }?.ifEmpty { null }, alias = alias)
        }
        if (written.link == null && written.name == "DUAL" && (written.schema == null || written.schema == "SYS")) {
            return ColumnSource(reference, alias ?: written.name, null, viewLike = true, viewColumns = setOf("DUMMY"), listed = listOf("DUMMY"), alias = alias)
        }
        val target = statement.targets[reference] ?: return ColumnSource(reference, alias ?: written.name, null, viewLike = false, viewColumns = null, alias = alias)
        val writtenAlias = alias ?: target.alias
        return ColumnSource(reference, writtenAlias ?: written.name, target, viewLike = false, viewColumns = null, alias = writtenAlias)
    }

    /** The column names of a `WITH` name: its column list, else the names of its subquery's select list. */
    private fun withColumns(statement: Statement, factoring: AstNode): Set<String>? {
        val listed = withList(factoring)
        if (listed.isNotEmpty()) return listed.toHashSet()
        return factoring.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)?.let { selectNames(statement, it) }
    }

    /** The column list of a `WITH` name, empty without one. */
    private fun withList(factoring: AstNode): List<String> =
        factoring.children.drop(1).takeWhile { it.type != PlSqlKeyword.AS }.filter { it.type == PlSqlGrammar.IDENTIFIER_NAME }
            .map { normalizeIdentifier(it.tokenOriginalValue) }

    /**
     * The names of a subquery's select list (the first branch of a compound query): an alias, the last segment of a
     * plain column, or every column of the complete dictionary relations a `*` or `t.*` covers; null when an item has no
     * known name (an expression without alias, a `*` over a source whose columns are unknown).
     */
    private fun selectNames(statement: Statement, subquery: AstNode): Set<String>? {
        val block = subquery.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK) ?: return null
        val names = HashSet<String>()
        for (column in block.getChildren(DmlGrammar.SELECT_COLUMN)) {
            val star = starSources(statement, column)
            if (star != null) {
                if (star.isEmpty()) return null
                for (source in star) {
                    val relation = source.target?.resolved?.relation?.takeIf { it.isColumnsComplete } ?: return null
                    relation.rowFields.forEach { names += it.name.exact() }
                }
                continue
            }
            names += columnAlias(column) ?: plsql.nameSegments(column.firstChild)?.last()?.let { normalizeIdentifier(it) } ?: return null
        }
        return names
    }

    fun columnAlias(selectColumn: AstNode): String? {
        val children = selectColumn.children
        return children.lastOrNull()?.takeIf { children.size > 1 && it.type == PlSqlGrammar.IDENTIFIER_NAME }?.let { normalizeIdentifier(it.tokenOriginalValue) }
    }

    /** The sources a `*` or `t.*` select item covers, or null when the item is not one. */
    fun starSources(statement: Statement, selectColumn: AstNode): List<ColumnSource>? {
        val item = selectColumn.firstChild
        val block = scopes.sqlScope(selectColumn) ?: return null
        return when {
            item.tokenValue == "*" && item.type is PlSqlPunctuator -> sourcesOf(statement, block)
            item.type == PlSqlGrammar.OBJECT_REFERENCE && item.lastChild.tokenValue == "*" -> {
                val qualifier = item.children.filter { it.type != PlSqlPunctuator.DOT && it.tokenValue != "*" }
                    .flatMap { tokensOf(it) }.filter { it.type !is PlSqlPunctuator }.map { normalizeIdentifier(it.originalValue) }
                sourcesOf(statement, block).filter { source ->
                    source.qualifier != null && source.qualifier == qualifier.singleOrNull() ||
                        source.target != null && qualifier.size == 2 && source.qualifier == source.target.written.name && scopes.qualifierMatches(qualifier, source.target)
                }.take(1)
            }
            else -> null
        }
    }
}
