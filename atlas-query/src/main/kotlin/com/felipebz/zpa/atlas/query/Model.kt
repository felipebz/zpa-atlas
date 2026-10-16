package com.felipebz.zpa.atlas.query

import kotlinx.serialization.Serializable

/** The run an answer comes from; every API response carries it. */
@Serializable
data class RunStamp(
    val finishedAt: String,
    val atlasVersion: String,
    val zpaVersion: String,
    val compilationSchema: String?,
    val metadataOrigin: String?
)

/** A query result with the run it was read from. */
@Serializable
data class Answer<T>(val run: RunStamp, val result: T)

@Serializable
data class Count(val value: String, val count: Long)

@Serializable
data class RunSummary(
    val schemaVersion: Int,
    val sourcesRoot: String,
    val startedAt: String,
    val filesByParseStatus: List<Count>,
    val occurrencesByStatus: List<Count>,
    val coverageByKind: List<Count>
)

@Serializable
data class EntitySummary(val key: String, val kind: String, val display: String, val external: Boolean)

@Serializable
data class SearchHit(val key: String, val kind: String, val display: String, val external: Boolean, val usages: Long)

/** One-based lines, zero-based columns, exclusive end column, as stored. */
@Serializable
data class Span(val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int)

@Serializable
data class Location(val path: String, val span: Span, val roles: List<String>, val status: String, val written: String)

@Serializable
data class RelationLink(val kind: String, val direction: String, val entity: EntitySummary)

@Serializable
data class Overload(val entity: EntitySummary, val body: Boolean, val self: Boolean)

@Serializable
data class UsageSummary(val byRole: List<Count>, val byStatus: List<Count>, val asCandidate: Long)

@Serializable
data class EntityDetail(
    val key: String,
    val kind: String,
    val schema: String?,
    val name: String,
    val display: String,
    val external: Boolean,
    val parent: EntitySummary?,
    val children: List<EntitySummary>,
    val relations: List<RelationLink>,
    val definitions: List<Location>,
    val overloads: List<Overload>,
    val usage: UsageSummary
)

enum class Scope { SELF, IMPLEMENTATIONS, CHILDREN }

@Serializable
data class UnitRef(val kind: String, val name: String)

@Serializable
data class CallArgument(val ordinal: Int, val label: String?, val parameter: EntitySummary?, val span: Span)

@Serializable
data class OccurrenceRow(
    val id: Long,
    val path: String,
    val span: Span,
    val written: String,
    val roles: List<String>,
    val status: String,
    val reason: String?,
    val origin: String,
    /** The entity in scope this row hit (the occurrence entity, or the candidate). */
    val matched: EntitySummary,
    val viaCandidate: Boolean,
    val entity: EntitySummary?,
    val container: EntitySummary?,
    val unit: UnitRef,
    val statementKind: String?,
    val candidates: List<EntitySummary>,
    val arguments: List<CallArgument>
)

@Serializable
data class Facets(val byRole: List<Count>, val byStatus: List<Count>)

@Serializable
data class OccurrencePage(val rows: List<OccurrenceRow>, val nextAfter: Long?, val total: Long, val facets: Facets)

@Serializable
data class SourceLine(val number: Int, val text: String)

@Serializable
data class SourceSnippet(val path: String, val lines: List<SourceLine>, val stale: Boolean)

/** A request the index cannot answer as asked (an unknown role, a limit out of range, a path outside the index). */
class InvalidQueryException(message: String) : RuntimeException(message)

/** The file was written by an indexer with another schema version. */
class SchemaMismatchException(val found: Int, val expected: Int) :
    RuntimeException("The index has schema version $found; this server reads version $expected. Re-run `zpa-atlas index` to rebuild it, or use the matching zpa-atlas version.")
