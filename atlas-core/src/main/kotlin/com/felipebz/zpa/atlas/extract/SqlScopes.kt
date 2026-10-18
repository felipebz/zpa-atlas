//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.DmlGrammar
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.atlas.model.*
import java.util.IdentityHashMap

/**
 * One static SQL statement being walked: its `WITH` names, the relations its references resolved, the column sources of
 * its scopes, its column reads and the view lineage computed over it. Its occurrences carry the statement id.
 */
internal class Statement(val root: AstNode, val context: Context, val id: Int, private val state: FileState) {
    val localNames: Set<String> = root.getDescendants(DmlGrammar.SUBQUERY_FACTORING_CLAUSE)
        .map { normalizeIdentifier(it.firstChild.tokenOriginalValue) }.toSet()

    /** The relation of every `TABLE_REFERENCE` that [SqlStatements.target] resolved. */
    val targets = IdentityHashMap<AstNode, Target>()

    /** The column sources each SQL scope owns directly. */
    val sources = IdentityHashMap<AstNode, List<ColumnSource>>()

    /** The column reads emitted at each name node, so that an `ORDER BY` name can repeat the read of its select item. */
    val columnReads = IdentityHashMap<AstNode, MutableList<OccurrenceFact>>()

    /** The select list of each query block with its stars expanded, for view column lineage. */
    val slots = IdentityHashMap<AstNode, List<Slot>>()

    /** The columns (`n:NAME`, `p:POSITION`) of each query whose lineage is being followed, to stop at recursive `WITH` names. */
    val following = IdentityHashMap<AstNode, MutableSet<String>>()

    /**
     * The values a column write writes: the expressions of [values] for one column, classified, one per row; none,
     * counted under their reason, when the statement gives none.
     */
    fun writtenValues(values: ColumnValues, position: Int): List<WrittenValueFact> = WrittenValues.facts(values, position, context, state)

    fun unvalued(reason: String) = WrittenValues.unvalued(state, reason)

    fun occurrence(
        node: AstNode, entityKey: String?, roles: Set<Role>, status: Status, reason: String?, candidates: List<String> = emptyList(),
        span: SourceSpan = spanOf(node), written: String = writtenText(node), values: List<WrittenValueFact> = emptyList()
    ) {
        val fact = OccurrenceFact(context.unitIndex, context.container, id, span, written, entityKey,
            roles, status(status, context.partial), context.origin, reason, candidates, writtenValues = values)
        state.occurrences += fact
        if (roles == COLUMN_READ) columnReads.getOrPut(node) { ArrayList() } += fact
    }
}

internal class Target(val reference: AstNode, val written: WrittenRelation, val resolved: ResolvedRelation, val alias: String?, val schema: String?)

/** The SQL scopes of a name, and the qualifiers (aliases, relation names, `WITH` names) each scope owns. */
internal class SqlScopes {
    /** SQL qualifiers (aliases, relation names, WITH names) owned by each SQL scope node. */
    private val scopeQualifiers = IdentityHashMap<AstNode, Set<String>>()

    /** Whether the first segment of a name in SQL is an alias, relation name or WITH name of an enclosing SQL scope. */
    fun isSqlQualified(name: AstNode, segments: List<String>): Boolean {
        if (segments.size < 2) return false
        val first = normalizeIdentifier(segments[0])
        var scope = sqlScope(name)
        while (scope != null) {
            if (first in qualifiersOf(scope)) return true
            scope = sqlScope(scope)
        }
        return false
    }

    fun sqlScope(node: AstNode): AstNode? = node.getFirstAncestorOrNull(*SQL_SCOPES)

    /** The qualifiers [scope] owns directly (as zpa-core's `sqlScopeQualifiers`): not those of its nested scopes. */
    private fun qualifiersOf(scope: AstNode): Set<String> = scopeQualifiers.getOrPut(scope) {
        val result = HashSet<String>()
        for (node in scope.getDescendants(DmlGrammar.ALIAS, DmlGrammar.TABLE_REFERENCE, DmlGrammar.SUBQUERY_FACTORING_CLAUSE, DmlGrammar.INSERT_INTO_CLAUSE)) {
            if (sqlScope(node) !== scope) continue
            when (node.type) {
                DmlGrammar.ALIAS -> result += normalizeIdentifier(node.tokenOriginalValue)
                DmlGrammar.TABLE_REFERENCE -> result += written(node).name
                DmlGrammar.SUBQUERY_FACTORING_CLAUSE -> result += normalizeIdentifier(node.firstChild.tokenOriginalValue)
                else -> node.getChildren(PlSqlGrammar.IDENTIFIER_NAME).forEach { result += normalizeIdentifier(it.tokenOriginalValue) }
            }
        }
        if (scope.type == DmlGrammar.MERGE_EXPRESSION) scope.getChildren(PlSqlGrammar.IDENTIFIER_NAME).forEach { result += normalizeIdentifier(it.tokenOriginalValue) }
        result
    }

    fun written(reference: AstNode): WrittenRelation {
        val children = reference.children
        val remote = children.indexOfFirst { it.type == PlSqlPunctuator.REMOTE }
        val local = (if (remote >= 0) children.subList(0, remote) else children).filter { it.type == PlSqlGrammar.IDENTIFIER_NAME }
            .map { normalizeIdentifier(it.tokenOriginalValue) }
        val link = if (remote >= 0) children.subList(remote + 1, children.size).filter { it.type == PlSqlGrammar.IDENTIFIER_NAME }
            .joinToString(".") { normalizeIdentifier(it.tokenOriginalValue) } else null
        return WrittenRelation(local.getOrNull(local.size - 2), local.last(), link)
    }

    fun qualifierMatches(qualifier: List<String>, target: Target): Boolean = when (qualifier.size) {
        1 -> qualifier[0] == (target.alias ?: target.written.name)
        2 -> target.alias == null && qualifier[1] == target.written.name && qualifier[0] == target.schema
        else -> false
    }
}

private val SQL_SCOPES = arrayOf(
    DmlGrammar.QUERY_BLOCK, DmlGrammar.SELECT_EXPRESSION, DmlGrammar.UPDATE_EXPRESSION,
    DmlGrammar.DELETE_EXPRESSION, DmlGrammar.INSERT_EXPRESSION, DmlGrammar.MERGE_EXPRESSION
)
