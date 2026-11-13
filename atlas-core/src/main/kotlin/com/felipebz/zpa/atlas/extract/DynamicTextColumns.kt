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
import com.felipebz.zpa.api.dynamicsql.DynamicSqlText
import com.felipebz.zpa.api.squid.SemanticAstNode
import com.felipebz.zpa.atlas.model.SourceSpan
import com.felipebz.zpa.atlas.model.Status
import java.util.*

/**
 * Column reads of the names of a dynamic SQL text, which zpa's `columnBinding` does not decide: the scopes of the
 * text's statement are walked here. A qualified name looks for the source its qualifier names; an unqualified one walks
 * the scopes outward from the innermost query block.
 */
internal class DynamicTextColumns(
    private val state: FileState,
    private val resolver: RelationResolver,
    private val scopes: SqlScopes,
    private val plsql: PlSqlNames,
    private val columnSources: ColumnSources,
    private val has: (ColumnSource, String) -> Boolean?,
    private val read: (Statement, AstNode, Target, String, SourceSpan, String) -> Unit
) {
    /** A name of the text: a column read when an SQL scope captures it, else a PL/SQL name. */
    fun sqlName(statement: Statement, name: AstNode) {
        if (isWrittenColumn(name) || isStarQualifier(name) || statement.context.hasHoles(name)) return
        val attribute = name.type == PlSqlGrammar.MEMBER_EXPRESSION && name.hasDirectChildren(PlSqlPunctuator.MOD)
        val segments = (if (attribute) null else plsql.nameSegments(name)) ?: run {
            plsql.valueReference(name, statement.context, statement.id, sql = true)
            return
        }
        if (segments.size >= 2) {
            val sequence = segments.size <= 3 && normalizeIdentifier(segments.last()) in PSEUDOCOLUMNS
            if (sequence || !qualifiedColumn(statement, name, segments)) plsql.valueReference(name, statement.context, statement.id, sql = true)
            return
        }
        unqualifiedColumn(statement, name, normalizeIdentifier(segments.single()))
    }

    /**
     * `q.col`, `schema.t.col`: a read of the source the qualifier names; false when no SQL source or qualifier matches.
     * A complete source that lacks the column does not capture it: the same qualifier in an enclosing scope is tried,
     * then the PL/SQL name (a record of an enclosing cursor loop named like the alias); only when PL/SQL finds nothing
     * either is it a read of the missing column of the innermost source.
     *
     * With [method], [name] is the callee of a method call on the column (`q.col.method(…)`): its last segment is the
     * method, and the read spans `q.col` only; there is no PL/SQL fallback (the call already resolved to nothing).
     */
    fun qualifiedColumn(statement: Statement, name: AstNode, segments: List<String>, method: Boolean = false): Boolean {
        val names = segments.map { normalizeIdentifier(it) }.let { if (method) it.dropLast(1) else it }
        val lacking = when (val capture = qualifiedCapture(statement, name, names, method)) {
            QualifiedCapture.Read -> return true
            is QualifiedCapture.Lacking -> capture
            null -> return scopes.isSqlQualified(name, segments)
        }
        val target = lacking.target
        val column = lacking.column
        if (method) {
            val first = name.firstChild
            if ((first as? SemanticAstNode)?.symbol == null) qualifiedRead(statement, name, target, column, lacking.index, method)
            return true
        }
        val before = state.occurrences.size
        plsql.valueReference(name, statement.context, statement.id, sql = false)
        val first = if (name.type == PlSqlGrammar.VARIABLE_NAME) name else name.firstChild
        if (state.occurrences.size == before && (first as? SemanticAstNode)?.symbol == null) read(statement, name, target, column, spanOf(name), writtenText(name))
        return true
    }

    /** What the scopes make of a qualified name: a read done, the first source lacking the column, or null for no match. */
    private sealed interface QualifiedCapture {
        /** The name was captured (and read when it is a column of a known relation). */
        object Read : QualifiedCapture

        /** The innermost matching source is complete but has no [column]. */
        class Lacking(val target: Target, val column: String, val index: Int) : QualifiedCapture
    }

    /**
     * Walks the scopes from the innermost out, reading the column from the first source whose qualifier matches and
     * that has it; a complete source without the column passes the name on to the next scope.
     */
    private fun qualifiedCapture(statement: Statement, name: AstNode, names: List<String>, method: Boolean): QualifiedCapture? {
        var lacking: QualifiedCapture.Lacking? = null
        var scope = scopes.sqlScope(name)
        scopes@ while (scope != null) {
            val current: AstNode = scope
            for (source in columnSources.scopeSources(statement, current, name, qualified = true)) {
                val columnIndex = qualifierIndex(source, names) ?: continue
                val column = names.getOrNull(columnIndex) ?: return QualifiedCapture.Read
                if (column in SQL_PSEUDOCOLUMNS || source.target == null) return QualifiedCapture.Read
                if (resolver.hasColumn(source.target.resolved, column) == false) {
                    lacking = lacking ?: QualifiedCapture.Lacking(source.target, column, columnIndex)
                    scope = scopes.sqlScope(current)
                    continue@scopes
                }
                qualifiedRead(statement, name, source.target, column, columnIndex, method)
                return QualifiedCapture.Read
            }
            scope = scopes.sqlScope(current)
        }
        return lacking
    }

    /** The index of the column segment when [source] matches the qualifier of [names] (`q.col`, `schema.t.col`), else null. */
    private fun qualifierIndex(source: ColumnSource, names: List<String>): Int? = when {
        source.qualifier != null && source.qualifier == names[0] -> 1
        names.size >= 3 && source.target != null && source.qualifier == source.target.written.name &&
            scopes.qualifierMatches(names.subList(0, 2), source.target) -> 2
        else -> null
    }

    /** A read of [column] by a qualified name; with [method], the read spans the segments up to the column only. */
    private fun qualifiedRead(statement: Statement, name: AstNode, target: Target, column: String, columnIndex: Int, method: Boolean) {
        if (!method) return read(statement, name, target, column, spanOf(name), writtenText(name))
        val parts = name.children.filter { it.type != PlSqlPunctuator.DOT }
        val last = parts[columnIndex]
        val text = name.children.takeWhile { it !== last }.joinToString("") { writtenText(it) } + writtenText(last)
        read(statement, name, target, column, spanOf(parts.first(), last), text)
    }

    /**
     * An unqualified name: captured by SQL (a pseudocolumn, the name of an `ORDER BY` select item), else a column of
     * exactly one source of the innermost scope that has it, else the next scope out while every source of this one is
     * complete. A scope with a source of unknown columns leaves the name undecided; a name no scope captures is a
     * PL/SQL name. In `MERGE … INSERT VALUES` the target takes precedence over the `USING` source.
     */
    private fun unqualifiedColumn(statement: Statement, name: AstNode, column: String) {
        if (column in SQL_PSEUDOCOLUMNS || orderByItem(statement, name, column)) return
        var scope = scopes.sqlScope(name)
        while (scope != null) {
            val sources = mergeValuesSources(name, scope, column, columnSources.scopeSources(statement, scope, name, qualified = false))
            if (sources.isNotEmpty()) {
                val known = sources.map { has(it, column) }
                val matches = sources.filterIndexed { index, _ -> known[index] == true }
                when {
                    matches.size == 1 -> {
                        matches.single().target?.let { read(statement, name, it, column, spanOf(name), writtenText(name)) }
                        return
                    }
                    matches.size > 1 -> {
                        val named = matches.mapNotNull { it.target }
                        if (isJoinedColumn(scope, column)) {
                            named.forEach { read(statement, name, it, column, spanOf(name), writtenText(name)) }
                        } else {
                            val candidates = named.mapNotNull { resolver.resolveColumn(it.resolved, column).entityKey }.distinct()
                            statement.occurrence(name, null, COLUMN_READ, Status.AMBIGUOUS, "AMBIGUOUS_COLUMN", candidates)
                        }
                        return
                    }
                    known.any { it == null } -> {
                        undecided(statement, name, scope, column, sources)
                        return
                    }
                }
            }
            scope = scopes.sqlScope(scope)
        }
        plsql.valueReference(name, statement.context, statement.id, sql = false)
    }

    /**
     * A name that may be a column of a source with unknown columns: a project entity PL/SQL finds is kept as
     * `UNVERIFIED` (`COLUMN_NOT_EXCLUDED`); a name the text declares, a parameterless SQL function, or a name an
     * enclosing scope has as a known column is counted; else the column of the only source when it is a named
     * relation; else nothing (counted).
     */
    private fun undecided(statement: Statement, name: AstNode, scope: AstNode, column: String, sources: List<ColumnSource>) {
        val before = state.occurrences.size
        plsql.valueReference(name, statement.context, statement.id, sql = false)
        val added = state.occurrences.subList(before, state.occurrences.size)
        if (added.isNotEmpty() && added.all { it.entityKey != null || it.candidateKeys.isNotEmpty() }) {
            for (index in added.indices) {
                added[index] = added[index].copy(status = status(Status.UNVERIFIED, statement.context.partial), reason = "COLUMN_NOT_EXCLUDED")
            }
            return
        }
        state.removeOccurrencesFrom(before)
        // A dynamic text binds nothing of the code around it: only the text's own declarations bind.
        val bound = statement.context.dynamic?.let { column in declaredNames(it) } == true || column in SQL_PARAMETERLESS_FUNCTIONS
        val single = sources.singleOrNull()?.target
        if (!bound && single != null && !isKnownOutside(statement, name, scope, column)) {
            read(statement, name, single, column, spanOf(name), writtenText(name))
        } else {
            state.undecidedSqlNames++
        }
    }

    private val dynamicDeclarations = IdentityHashMap<DynamicSqlText, Set<String>>()

    /**
     * The names [text] declares anywhere in it: variables and constants, cursors, parameters of cursors and
     * subprograms, loop indexes. Where the declaration sits is not checked, so a name declared once stays undecided.
     */
    private fun declaredNames(text: DynamicSqlText): Set<String> = dynamicDeclarations.getOrPut(text) {
        val names = HashSet<String>()
        for (node in text.root.getDescendants(*DECLARING)) {
            node.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { names += normalizeIdentifier(it.tokenOriginalValue) }
        }
        for (forall in text.root.getDescendants(PlSqlGrammar.FORALL_STATEMENT)) {
            forall.getFirstChildOrNull(PlSqlKeyword.FORALL)?.nextSibling?.let { names += normalizeIdentifier(it.tokenOriginalValue) }
        }
        names
    }

    /**
     * Whether a scope enclosing [scope] has a source known to have [column] (a correlated name we cannot place). The
     * query of an inline view (not `LATERAL`) or of a `WITH` name sees no scope around it: the walk stops there.
     */
    private fun isKnownOutside(statement: Statement, name: AstNode, scope: AstNode, column: String): Boolean {
        var current = scope
        var outer = scopes.sqlScope(scope)
        while (outer != null) {
            if (isUncorrelated(current)) return false
            if (columnSources.scopeSources(statement, outer, name, qualified = false).any { has(it, column) == true }) return true
            current = outer
            outer = scopes.sqlScope(outer)
        }
        return false
    }

    /** Whether [scope] is the query (or a query block of the query) of an inline view that is not `LATERAL`, or of a `WITH` name. */
    private fun isUncorrelated(scope: AstNode): Boolean {
        val query = if (scope.type == DmlGrammar.SELECT_EXPRESSION) scope else scope.parentOrNull?.takeIf { it.type == DmlGrammar.SELECT_EXPRESSION } ?: return false
        val holder = query.parentOrNull ?: return false
        return holder.type == DmlGrammar.SUBQUERY_FACTORING_CLAUSE ||
            holder.type == DmlGrammar.DML_TABLE_EXPRESSION_CLAUSE && !holder.hasDirectChildren(PlSqlKeyword.LATERAL)
    }

    /**
     * An `ORDER BY` name (bare, or inside an expression) that names a select item of the query it orders (the first
     * branch of a compound query): an alias, or the last segment of a plain column without alias. An expression item
     * gives nothing; a plain column item repeats the item's read; several items are `AMBIGUOUS` (ORA-00960).
     */
    private fun orderByItem(statement: Statement, name: AstNode, column: String): Boolean {
        val orderBy = name.getFirstAncestorOrNull(DmlGrammar.ORDER_BY_CLAUSE) ?: return false
        val owner = orderBy.parentOrNull ?: return false
        val block = if (owner.type == DmlGrammar.QUERY_BLOCK) owner else owner.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK) ?: return false
        if (scopes.sqlScope(name) !== owner && scopes.sqlScope(name) !== block) return false
        val items = block.getChildren(DmlGrammar.SELECT_COLUMN).filter { item ->
            (columnSources.columnAlias(item) ?: plsql.nameSegments(item.firstChild)?.last()?.let { normalizeIdentifier(it) }) == column
        }
        if (items.isEmpty()) return false
        val reads = items.map { item -> if (plsql.nameSegments(item.firstChild) != null) statement.columnReads[item.firstChild].orEmpty() else emptyList() }
        if (items.size > 1) {
            val candidates = reads.flatten().flatMap { listOfNotNull(it.entityKey) + it.candidateKeys }.distinct()
            statement.occurrence(name, null, COLUMN_READ, Status.AMBIGUOUS, "AMBIGUOUS_COLUMN", candidates)
            return true
        }
        for (read in reads.single()) {
            state.occurrences += read.copy(span = spanOf(name), written = writtenText(name))
            statement.columnReads.getOrPut(name) { ArrayList() } += state.occurrences.last()
        }
        return true
    }

    /**
     * The sources of a name in the `VALUES` of a `MERGE` insert: the target when it has the column (Oracle reads it
     * and rejects the statement, ORA-38101), else only the `USING` source; [sources] anywhere else.
     */
    private fun mergeValuesSources(name: AstNode, scope: AstNode, column: String, sources: List<ColumnSource>): List<ColumnSource> {
        if (scope.type != DmlGrammar.MERGE_EXPRESSION) return sources
        val insert = name.getFirstAncestorOrNull(DmlGrammar.MERGE_INSERT_CLAUSE)?.takeIf { scopes.sqlScope(it) === scope } ?: return sources
        var node = name
        while (node.parentOrNull !== insert) node = node.parent
        val children = insert.children
        if (children.indexOf(node) < children.indexOfFirst { it.type == PlSqlKeyword.VALUES }) return sources
        val target = sources.firstOrNull() ?: return sources
        return when (has(target, column)) {
            true -> listOf(target)
            false -> sources.drop(1)
            null -> sources
        }
    }

    /** A column of a `USING` join, or any column when [scope] has a `NATURAL` join: one read per joined relation. */
    private fun isJoinedColumn(scope: AstNode, column: String): Boolean =
        scope.getDescendants(DmlGrammar.ON_OR_USING_EXPRESSION, DmlGrammar.INNER_CROSS_JOIN_CLAUSE, DmlGrammar.OUTER_JOIN_CLAUSE).any { node ->
            scopes.sqlScope(node) === scope && when (node.type) {
                DmlGrammar.ON_OR_USING_EXPRESSION -> node.hasDirectChildren(PlSqlKeyword.USING) &&
                    node.getChildren(PlSqlGrammar.IDENTIFIER_NAME).any { normalizeIdentifier(it.tokenOriginalValue) == column }
                else -> node.hasDirectChildren(PlSqlKeyword.NATURAL)
            }
        }

    /** A written column: an `INSERT` column list, the left side of `SET`, a `MERGE` insert list (emitted as writes). */
    private fun isWrittenColumn(name: AstNode): Boolean {
        var node = name
        while (node.parentOrNull?.type == PlSqlGrammar.BRACKED_EXPRESSION || node.parentOrNull?.type == PlSqlGrammar.MULTIPLE_VALUE_EXPRESSION) node = node.parent
        val parent = node.parentOrNull ?: return false
        return when (parent.type) {
            DmlGrammar.INSERT_COLUMNS -> true
            DmlGrammar.UPDATE_COLUMN -> parent.firstChild === node
            DmlGrammar.MERGE_INSERT_CLAUSE -> {
                val children = parent.children
                val index = children.indexOf(node)
                index > children.indexOfFirst { it.type == PlSqlKeyword.INSERT } && index < children.indexOfFirst { it.type == PlSqlKeyword.VALUES }
            }
            else -> false
        }
    }

    /** The `t` of `t.*`: read through `selectStar`. */
    private fun isStarQualifier(name: AstNode): Boolean {
        val parent = name.parentOrNull ?: return false
        return parent.type == PlSqlGrammar.OBJECT_REFERENCE && parent.firstChild === name && parent.lastChild.tokenValue == "*"
    }
}

/** The nodes whose `IDENTIFIER_NAME` declares a value name a SQL statement may use. */
private val DECLARING = arrayOf(
    PlSqlGrammar.VARIABLE_DECLARATION, PlSqlGrammar.CURSOR_DECLARATION, PlSqlGrammar.PARAMETER_DECLARATION,
    PlSqlGrammar.CURSOR_PARAMETER_DECLARATION, PlSqlGrammar.ITERAND_DECLARATION
)

/** SQL functions called without parentheses: PL/SQL names, never a column the scope could not exclude. */
private val SQL_PARAMETERLESS_FUNCTIONS = setOf(
    "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIMESTAMP", "LOCALTIMESTAMP", "USER", "UID", "DBTIMEZONE", "SESSIONTIMEZONE"
)
