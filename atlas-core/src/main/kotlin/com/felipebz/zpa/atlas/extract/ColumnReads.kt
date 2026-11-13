//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.api.semantics.ColumnBinding
import com.felipebz.zpa.api.semantics.ColumnUncertainty
import com.felipebz.zpa.api.semantics.NameBinding
import com.felipebz.zpa.atlas.model.OccurrenceFact
import com.felipebz.zpa.atlas.model.Role
import com.felipebz.zpa.atlas.model.SourceSpan
import com.felipebz.zpa.atlas.model.Status

/**
 * Column reads of static SQL: zpa decides which column of which source a name reads (`columnBinding`); the atlas names
 * the entity of that column. The names of a dynamic SQL text, which zpa does not decide, go to [DynamicTextColumns].
 */
internal class ColumnReads(
    private val state: FileState,
    private val resolver: RelationResolver,
    private val scopes: SqlScopes,
    private val plsql: PlSqlNames,
    /** What zpa decides a name of the file's static SQL reads. */
    private val columnBinding: (AstNode) -> ColumnBinding,
    /** What zpa binds a name of the file to. */
    private val nameBinding: (AstNode) -> NameBinding
) {
    private val columnSources = ColumnSources(scopes, plsql)
    private val implicit = ImplicitColumns(columnSources, scopes, plsql)
    private val dynamicTexts = DynamicTextColumns(state, resolver, scopes, plsql, columnSources, ::has, ::read)

    /** Whether [source] has [column]: null when its columns are not known (or the dictionary cannot tell). */
    fun has(source: ColumnSource, column: String): Boolean? = when {
        source.target != null -> resolver.hasColumn(source.target.resolved, column)
        source.viewLike -> source.viewColumns?.let { column in it }
        else -> null
    }

    /** A name of static SQL that is not a written column: what zpa decides it reads. */
    fun sqlName(statement: Statement, name: AstNode) {
        if (name in statement.writtenColumns) return
        if (statement.context.dynamic != null) return dynamicTexts.sqlName(statement, name)
        when (val binding = columnBinding(name)) {
            is ColumnBinding.Column -> columnRead(statement, name, binding)
            // A column of an inline view or `WITH` query: its select item's reads follow it. `t.*`, `REF(t)`: read elsewhere.
            is ColumnBinding.QueryColumn, is ColumnBinding.Source -> Unit
            is ColumnBinding.Pseudocolumn -> if (isSequenceName(name)) plsql.valueReference(name, statement.context, statement.id, sql = true)
            is ColumnBinding.SelectItem -> repeatItem(statement, name, binding.item)
            is ColumnBinding.Joined -> binding.columns.forEach { if (it is ColumnBinding.Column) columnRead(statement, name, it) }
            is ColumnBinding.Ambiguous -> statement.occurrence(name, null, COLUMN_READ, Status.AMBIGUOUS, "AMBIGUOUS_COLUMN", candidates(statement, binding))
            is ColumnBinding.PlSqlName -> plsql.valueReference(name, statement.context, statement.id, sql = false)
            is ColumnBinding.Unknown -> undecided(statement, name, binding)
            // A member of an expression (`(t.obj).attr`): not a name; the names inside it are answered.
            is ColumnBinding.Unavailable -> plsql.valueReference(name, statement.context, statement.id, sql = false)
        }
    }

    /**
     * The callee of a method call no project subprogram takes (`q.col.method(…)`): a read of the column it is called on,
     * spanning `q.col`. A callee zpa does not read as a column is a call, unless its first name is a qualifier of the
     * statement (`alias.method(…)`, a method of the row): false only for a call.
     */
    fun methodColumn(statement: Statement, callee: AstNode, segments: List<String>): Boolean {
        if (statement.context.dynamic != null) return dynamicTexts.qualifiedColumn(statement, callee, segments, method = true)
        when (val binding = columnBinding(callee)) {
            is ColumnBinding.Unavailable -> return scopes.isSqlQualified(callee, segments)
            is ColumnBinding.Column -> target(statement, binding)?.let { target ->
                val parts = callee.children.filter { it.type != PlSqlPunctuator.DOT }
                // The column's segment: the one after the alias, or after `schema.t`.
                val last = parts.drop(1).firstOrNull { normalizeIdentifier(it.tokenOriginalValue) == binding.name } ?: parts[1]
                val text = callee.children.takeWhile { it !== last }.joinToString("") { writtenText(it) } + writtenText(last)
                read(statement, callee, target, binding.name, spanOf(parts.first(), last), text)
            }
            is ColumnBinding.Unknown -> state.undecidedSqlNames++
            else -> Unit
        }
        return true
    }

    /** The atlas's relation for the source zpa names, when the statement resolved one (never for `DUAL` or a `WITH` name). */
    private fun target(statement: Statement, column: ColumnBinding.Column): Target? = statement.targets[column.source.node]

    /** A read of the column zpa names, with the atlas's key and status for it; a column zpa knows is missing included. */
    private fun columnRead(statement: Statement, name: AstNode, column: ColumnBinding.Column) {
        target(statement, column)?.let { read(statement, name, it, column.name, spanOf(name), writtenText(name)) }
    }

    private fun read(statement: Statement, name: AstNode, target: Target, column: String, span: SourceSpan, written: String) {
        val resolved = resolver.resolveColumn(target.resolved, column)
        statement.occurrence(name, resolved.entityKey, COLUMN_READ, resolved.status, resolved.reason, span = span, written = written)
    }

    /** `seq.NEXTVAL`, `seq.CURRVAL`: a sequence, read by the PL/SQL names. */
    private fun isSequenceName(name: AstNode): Boolean =
        name.type == PlSqlGrammar.MEMBER_EXPRESSION && plsql.nameSegments(name)?.last()?.let { normalizeIdentifier(it) in PSEUDOCOLUMNS } == true

    /** The reads of a select item named in `ORDER BY`: those of a plain column item, none for an expression. */
    private fun itemReads(statement: Statement, item: AstNode): List<OccurrenceFact> =
        if (plsql.nameSegments(item.firstChild) != null) statement.columnReads[item.firstChild].orEmpty() else emptyList()

    /** An `ORDER BY` name of a select item: the item's reads, repeated at the name. */
    private fun repeatItem(statement: Statement, name: AstNode, item: AstNode) {
        for (read in itemReads(statement, item)) {
            state.occurrences += read.copy(span = spanOf(name), written = writtenText(name))
            statement.columnReads.getOrPut(name) { ArrayList() } += state.occurrences.last()
        }
    }

    /** The entities an ambiguous name may read: the candidate columns, the reads of the candidate select items. */
    private fun candidates(statement: Statement, ambiguous: ColumnBinding.Ambiguous): List<String> = ambiguous.candidates.flatMap { candidate ->
        when (candidate) {
            is ColumnBinding.Column -> listOfNotNull(target(statement, candidate)?.let { resolver.resolveColumn(it.resolved, candidate.name).entityKey })
            is ColumnBinding.SelectItem -> itemReads(statement, candidate.item).flatMap { listOfNotNull(it.entityKey) + it.candidateKeys }
            else -> emptyList()
        }
    }.distinct()

    /**
     * A name zpa leaves undecided. When the only doubt is a source whose columns are not known, the name is one of
     * that source's columns or the PL/SQL name: a project entity PL/SQL finds for it is kept, `UNVERIFIED`
     * (`COLUMN_NOT_EXCLUDED`); else, when zpa proves that no declaration of the file takes it, the columns zpa names
     * as its candidates are read, never `RESOLVED`: one column
     * (`COLUMN_CANDIDATE`), or one occurrence with several as candidates (`COLUMN_CANDIDATES`). A column of an inline
     * view or `WITH` query reads nothing of its own, as when decided. Any other name is counted in `SQL_NAME_UNDECIDED`:
     * a missing column behind a qualifier a PL/SQL name may take is not read, as code that compiles reads that name.
     */
    private fun undecided(statement: Statement, name: AstNode, unknown: ColumnBinding.Unknown) {
        if (!unknown.reasons.all { it in COLUMN_OR_PLSQL }) {
            state.undecidedSqlNames++
            return
        }
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
        // A declaration of the file may take the name, or zpa cannot tell: no column is read.
        if (nameBinding(name) !is NameBinding.NothingLocal) {
            state.undecidedSqlNames++
            return
        }
        val columns = unknown.candidates.mapNotNull { candidate ->
            (candidate as? ColumnBinding.Column)?.let { column -> target(statement, column)?.let { resolver.resolveColumn(it.resolved, column.name) } }
        }.distinctBy { it.entityKey }
        when (columns.size) {
            0 -> if (unknown.candidates.none { it is ColumnBinding.QueryColumn }) state.undecidedSqlNames++
            // A column of a relation the dictionary lacks, or behind a link, keeps that weaker status.
            1 -> statement.occurrence(name, columns.single().entityKey, COLUMN_READ, candidateStatus(columns.single().status), "COLUMN_CANDIDATE")
            else -> statement.occurrence(name, null, COLUMN_READ, Status.UNVERIFIED, "COLUMN_CANDIDATES", columns.mapNotNull { it.entityKey })
        }
    }

    private fun candidateStatus(status: Status) = if (status == Status.RESOLVED) Status.UNVERIFIED else status

    /** The sources a `*` or `t.*` select item covers, or null when the item is not one. */
    fun starSources(statement: Statement, selectColumn: AstNode): List<ColumnSource>? = columnSources.starSources(statement, selectColumn)

    /**
     * The column sources that can capture [name] in [scope]: those it owns, except that an `INSERT` target is one only
     * for `RETURNING` (any position for a qualified name), and a `SELECT_EXPRESSION` lends its `ORDER BY` the sources
     * of its first query block.
     */
    fun scopeSources(statement: Statement, scope: AstNode, name: AstNode, qualified: Boolean): List<ColumnSource> =
        columnSources.scopeSources(statement, scope, name, qualified)

    /**
     * Every column written or read without being named (`INSERT` without a column list, `SET ROW`, `*`): by dictionary
     * position, at [at] (the target's range for writes).
     */
    fun implicitColumns(statement: Statement, target: Target, roles: Set<Role>, at: AstNode = target.reference, written: WriteSource? = null) =
        implicit.implicitColumns(statement, target, roles, at, written)

    /**
     * `*` and `t.*` in a select list: every column of the sources they cover, implicit reads at the `*` for a complete
     * dictionary relation, one `COLUMNS_UNKNOWN` read of a named relation whose columns are unknown.
     */
    fun selectStar(statement: Statement, selectColumn: AstNode) = implicit.selectStar(statement, selectColumn)

    /**
     * `REF(x)` or `VALUE(x)` whose single argument is the alias of a source of an enclosing SQL scope (innermost first):
     * the function name and that source, or null. Oracle takes only an alias here, not a relation name.
     */
    fun correlationCall(statement: Statement, call: AstNode): Pair<String, ColumnSource>? = implicit.correlationCall(statement, call)

    /** The argument of a [correlationCall]: an alias, never a column or a PL/SQL name. */
    fun isCorrelationArgument(statement: Statement, name: AstNode): Boolean = implicit.isCorrelationArgument(statement, name)

    /** `VALUE(x)` outside the left of `SET`: every column of the relation [x] names, at the call. */
    fun correlationValue(statement: Statement, call: AstNode) = implicit.correlationValue(statement, call)
}

/** The doubts of an undecided name that leave it a column or the PL/SQL name, nothing else. */
private val COLUMN_OR_PLSQL = setOf(ColumnUncertainty.SOURCE_COLUMNS_UNKNOWN, ColumnUncertainty.STAR_NOT_COVERED)
