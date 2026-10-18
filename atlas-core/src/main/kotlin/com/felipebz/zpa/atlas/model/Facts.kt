//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.model

import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym

/** One-based lines, zero-based columns, exclusive end column (the ZPA token convention). */
data class SourceSpan(val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int) : Comparable<SourceSpan> {
    fun contains(line: Int, col: Int): Boolean =
        (line > startLine || (line == startLine && col >= startCol)) && (line < endLine || (line == endLine && col < endCol))

    override fun compareTo(other: SourceSpan): Int = ORDER.compare(this, other)

    companion object {
        private val ORDER = compareBy<SourceSpan>({ it.startLine }, { it.startCol }, { it.endLine }, { it.endCol })
    }
}

/** Entities are referenced by [key] until the writer assigns ids. */
data class EntityFact(
    val key: String,
    val kind: EntityKind,
    val schemaName: String?,
    val name: String,
    val parentKey: String?,
    val display: String,
    val external: Boolean,
    /** Lower wins when several files produce the same key (a declaration beats a name only mentioned). */
    val precedence: Int
)

data class RelationFact(val fromKey: String, val kind: RelationKind, val toKey: String)

data class UnitFact(
    val kind: UnitKind,
    val name: String,
    val entityKey: String?,
    val span: SourceSpan,
    /** The schema the unit compiles in, or null when neither written nor configured. */
    val compilationSchema: String?
)

data class StatementFact(
    /** Index in the file's statement list; resolved to a database id by the writer. */
    val localId: Int,
    val unitIndex: Int,
    val containerKey: String?,
    val kind: StatementKind,
    val span: SourceSpan,
    val normalizedHash: String
)

data class OccurrenceFact(
    val unitIndex: Int,
    val containerKey: String?,
    val statementLocalId: Int?,
    val span: SourceSpan,
    val written: String,
    val entityKey: String?,
    val roles: Set<Role>,
    val status: Status,
    val origin: Origin,
    val reason: String?,
    /** For `AMBIGUOUS`: every candidate entity. */
    val candidateKeys: List<String> = emptyList(),
    /** For `CALL`: one per written actual, in written order. */
    val arguments: List<CallArgumentFact> = emptyList(),
    /**
     * The span of the name [written] comes from, when it is not [span]: a definition or declaration spans the whole
     * declaration. Null when the name is the span or is not known (a file the parser rejected).
     */
    val nameSpan: SourceSpan? = null,
    /** For a column write: the value written, one per row of the statement (several for a multi-row `INSERT`). */
    val writtenValues: List<WrittenValueFact> = emptyList()
)

/** The value a column write writes in row [row] of its statement, classified as call arguments are. */
data class WrittenValueFact(val row: Int, val valueKind: ValueKind, val value: String?, val span: SourceSpan)

/** One written actual of a call: its 1-based [ordinal], its label when named, and the formal it binds to when proven. */
data class CallArgumentFact(
    val ordinal: Int,
    val label: String?,
    val parameterKey: String?,
    val span: SourceSpan,
    /** What the argument passes, and its value for a literal, a name or a bind. */
    val valueKind: ValueKind,
    val value: String?
)

data class CoverageFact(val kind: String, val message: String)

/**
 * One event a trigger fires on: `INSERT`, `UPDATE`, `DELETE`, or a DDL or database event (`LOGON`, `CREATE`). An
 * `UPDATE OF a, b` gives one fact per column, with the column entity when it resolves.
 */
data class TriggerEventFact(val triggerKey: String, val timing: TriggerTiming, val level: TriggerLevel?, val event: String, val columnKey: String?)

/** Everything one file contributes, produced off the writer thread. */
class FileFacts(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    /** The charset the file was decoded with (`UTF-8`, `windows-1252`, or the forced one). */
    val encoding: String,
    val parseStatus: ParseStatus,
    val recoveredRegions: Int,
    val compilationSchema: String?,
    val units: List<UnitFact>,
    val statements: List<StatementFact>,
    val occurrences: List<OccurrenceFact>,
    val entities: List<EntityFact>,
    val relations: List<RelationFact>,
    val coverage: List<CoverageFact>,
    /** The dictionary relations the file resolved: their entities and columns are added once per run. */
    val dictionaryRelations: List<ProjectExternalRelation> = emptyList(),
    /** The synonyms the file followed: their entities and `SYNONYM_FOR` relations are added once per run. */
    val dictionarySynonyms: List<DictionarySynonym> = emptyList(),
    val triggerEvents: List<TriggerEventFact> = emptyList()
)
