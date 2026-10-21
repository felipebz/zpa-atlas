//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
data class EntitySummary(
    val key: String,
    val kind: String,
    val display: String,
    val external: Boolean,
    /** The entity's own name (`entity.name`), the end of [display]: `P_A`, `RUN_JOB`, `NOTIFY_REQ@FAR.EXAMPLE.COM`. */
    val name: String
)

@Serializable
data class SearchHit(val key: String, val kind: String, val display: String, val external: Boolean, val usages: Long, val name: String)

/** One-based lines, zero-based columns, exclusive end column, as stored. */
@Serializable
data class Span(val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int)

@Serializable
data class Location(val path: String, val span: Span, val roles: List<String>, val status: String, val written: String)

@Serializable
data class RelationLink(val kind: String, val direction: String, val entity: EntitySummary)

@Serializable
data class Overload(val entity: EntitySummary, val body: Boolean, val self: Boolean)

/** A `CONTAINS` child and its uses (occurrences that are not a definition or declaration). */
@Serializable
data class Member(val entity: EntitySummary, val usages: Long)

@Serializable
data class UsageSummary(val byRole: List<Count>, val byStatus: List<Count>, val asCandidate: Long)

/** One event of a trigger: its [timing] (`INSTEAD_OF` for INSTEAD OF), level (null for compound and system triggers) and column for `UPDATE OF`. */
@Serializable
data class TriggerEvent(val trigger: EntitySummary, val timing: String, val level: String?, val event: String, val column: EntitySummary?)

@Serializable
data class EntityDetail(
    val key: String,
    val kind: String,
    val schema: String?,
    val name: String,
    val display: String,
    val external: Boolean,
    val parent: EntitySummary?,
    val children: List<Member>,
    val relations: List<RelationLink>,
    val definitions: List<Location>,
    val overloads: List<Overload>,
    val usage: UsageSummary,
    /** For a trigger, its events; for a table or view, the events of the triggers on it; for a column, the `UPDATE`
     * events that fire when it is updated (`UPDATE OF` naming it, or `UPDATE` without a column list). */
    val triggerEvents: List<TriggerEvent> = emptyList(),
    /** For a view, the relations its query reads (`SQL_SELECT` occurrences inside it, every level of the query). */
    val reads: List<EntitySummary> = emptyList(),
    /** For a table, view or object table, the views whose query reads it. */
    val views: List<EntitySummary> = emptyList(),
    /** For a view column, the columns its value is computed from (`VIEW_BASE` targets, one hop: a table column or
     * another view's column). */
    val baseColumns: List<EntitySummary> = emptyList(),
    /** For a column, the view columns computed from it (`VIEW_BASE` sources, one hop). */
    val viewColumns: List<EntitySummary> = emptyList()
)

/**
 * What "who uses" covers besides the entity itself: [IMPLEMENTATIONS] its `IMPLEMENTS` partners, [CHILDREN] its
 * `CONTAINS` descendants, [VIEWS] the view columns computed from them and the views reading them, transitively.
 */
enum class Scope { SELF, IMPLEMENTATIONS, CHILDREN, VIEWS }

/**
 * Which usages to keep: [DIRECT] the rows whose matched entity is the entity, its partners or its contents;
 * [THROUGH_VIEWS] the rows [Scope.VIEWS] added ([OccurrenceRow.viaView]).
 */
enum class UsagePath { DIRECT, THROUGH_VIEWS }

/**
 * The scopes "who uses" adds by default for an entity of [kind], as the entity page does: a subprogram's spec or body,
 * a package's spec or body and members, a type's members.
 */
fun defaultUsageScopes(kind: String): List<Scope> = listOfNotNull(
    Scope.IMPLEMENTATIONS.takeIf { kind in setOf("PROCEDURE", "FUNCTION", "PACKAGE") },
    Scope.CHILDREN.takeIf { kind in setOf("PACKAGE", "TYPE") }
)

/** Why the impact walk stops at a node. */
enum class ImpactStop {
    /** Code outside the node's own cycle uses it: the nodes one hop further, nodes already reached, or the entity. */
    FOLLOWED,

    /**
     * Nothing in the index uses the node but itself, or the routines it forms a cycle with (routines that call each
     * other, the entity asked for included): it is called from outside the index (Forms, jobs, other systems) or not
     * at all. A [FOLLOWED] node can be called from outside the index too. A DML trigger is an entry point when nothing
     * in the index writes its table in a way that fires it.
     */
    ENTRY_POINT,

    /**
     * A trigger the walk does not follow: a system trigger (DDL or database events), which no write fires, or any
     * trigger when triggers are not followed. [ImpactNode.trigger] gives its table and events.
     */
    TRIGGER,

    /** Code of the package as a whole (its initialization, a declaration): following every user of the package would list too much. */
    PACKAGE_LEVEL,

    /** The node is at the depth limit: its uses were not read. */
    DEPTH_LIMIT,

    /** Code outside any routine, view or package, such as a script: nothing calls it. The node is the file. */
    NO_CONTAINER
}

/**
 * One use on a shortest path to a node: of [uses] (the entity asked for at depth 1), at [path] line [line]. [fires]
 * is set when the use is a write that fires the trigger [uses]: the trigger's events it fires, such as `INSERT`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ImpactVia(
    val uses: String,
    val occurrence: Long,
    val path: String,
    val line: Int,
    val status: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fires: String? = null
)

/** One event of a trigger node: as [TriggerEvent] without the trigger. */
@Serializable
data class ImpactTriggerEvent(val timing: String, val level: String?, val event: String, val column: EntitySummary?)

/** The table a trigger node is on, and the events that fire it. */
@Serializable
data class ImpactTrigger(val table: EntitySummary?, val events: List<ImpactTriggerEvent>)

/**
 * One node of an impact graph: a routine, view, trigger, type or package ([entity]), or for [ImpactStop.NO_CONTAINER]
 * a file ([path], with its [uses] and [firstLine], counted over the uses of every node reached, not only those on its
 * shortest path). [depth] is the shortest path from the entity; [certain] is true when
 * some path uses only `RESOLVED` uses.
 */
@Serializable
data class ImpactNode(
    val kind: String,
    val entity: EntitySummary? = null,
    val path: String? = null,
    val uses: Int? = null,
    val firstLine: Int? = null,
    val depth: Int,
    val via: ImpactVia,
    val certain: Boolean,
    val stop: String,
    val trigger: ImpactTrigger? = null
)

/**
 * The transitive impact of [entity]: [levels] counts the nodes per depth (from 1), [stops] per stop reason, both over
 * the whole graph; [total] counts the nodes the stop filter keeps, and [nodes] is the page from [offset].
 */
@Serializable
data class ImpactGraph(
    val entity: EntitySummary,
    val included: List<String>,
    val maxDepth: Int,
    val levels: List<Int>,
    val stops: List<Count>,
    val total: Int,
    val offset: Int,
    val nodes: List<ImpactNode>,
    val nextOffset: Int?
)

@Serializable
data class UnitRef(val kind: String, val name: String)

@Serializable
data class CallArgument(
    val ordinal: Int,
    val label: String?,
    val parameter: EntitySummary?,
    val span: Span,
    /** `STRING`, `NUMBER`, `NULL`, `BOOLEAN`, `NAME`, `BIND` or `EXPRESSION`. */
    val valueKind: String,
    /** The literal's value, or the name or bind as written; null for `NULL` and `EXPRESSION`. */
    val value: String?
)

/** The arguments bound to a parameter that pass the same value: [value] null for `NULL`, `BIND` and `EXPRESSION`. */
@Serializable
data class ValueGroup(val valueKind: String, val value: String?, val count: Long)

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
    /** The entity in scope this row hit (the occurrence entity, or the candidate); null for a query row without entity. */
    val matched: EntitySummary?,
    val viaCandidate: Boolean,
    /** [matched] is a view column or view the [Scope.VIEWS] scope added, not the entity asked about or its partners and children. */
    val viaView: Boolean,
    val entity: EntitySummary?,
    val container: EntitySummary?,
    val unit: UnitRef,
    val statementKind: String?,
    val candidates: List<EntitySummary>,
    val arguments: List<CallArgument>,
    /** For a column write: the value written, one per row of its statement. */
    val values: List<WrittenValue>
)

/** The value a column write writes in [row] of its statement; [value] as for [CallArgument.value]. */
@Serializable
data class WrittenValue(val row: Int, val valueKind: String, val value: String?)

/**
 * Counts over a set of occurrences: roles split (an occurrence counts once under each of its roles). [byOrigin],
 * [byEntityKind] (the kind of the matched entity; occurrences without one are left out), [byUnitKind] and [byReason]
 * (occurrences without a reason are left out) are filled by the occurrence query only.
 */
@Serializable
data class Facets(
    val byRole: List<Count>,
    val byStatus: List<Count>,
    val byOrigin: List<Count> = emptyList(),
    val byEntityKind: List<Count> = emptyList(),
    val byUnitKind: List<Count> = emptyList(),
    val byReason: List<Count> = emptyList()
)

@Serializable
data class OccurrencePage(val rows: List<OccurrenceRow>, val nextAfter: Long?, val total: Long, val facets: Facets)

/** The size and facets of the occurrences a query matches. */
@Serializable
data class QuerySummary(val total: Long, val facets: Facets)

/**
 * A query over every occurrence of the index. Every filter is optional; set filters combine with AND, the values of
 * one filter with OR. Patterns (`name`, `path`, `value`) take `*` as a wildcard; `name` and patterns are
 * case-insensitive, an exact `value` is not.
 */
data class OccurrenceQuery(
    /** The occurrence's entity has this key; with [inside], or is one of its `CONTAINS` descendants. */
    val entity: String? = null,
    val inside: Boolean = false,
    val entityKinds: Set<String> = emptySet(),
    /**
     * The entity's name, or a dotted name as in the header search: the last segment is the entity's name, each earlier
     * one the next parent up, and the first may be the schema instead (`st_active`, `company.st_active`,
     * `hr.company.st_active`). At most four segments; each is case-insensitive and exact, or a pattern with `*`.
     */
    val name: String? = null,
    /** The entity's schema, exact. */
    val schema: String? = null,
    val external: Boolean? = null,
    /** The occurrence is inside this entity; with [containerInside], or inside one of its `CONTAINS` descendants. */
    val container: String? = null,
    /** The typed form of [container]: every entity this dotted name matches, as for [name], is a container. */
    val containerName: String? = null,
    /** [container] and [containerName] include their `CONTAINS` descendants. */
    val containerInside: Boolean = true,
    val unitKinds: Set<String> = emptySet(),
    /** The file path: a prefix, or with `*` a pattern of the whole path. */
    val path: String? = null,
    /** The occurrence has any of these roles; empty matches every role, definitions and declarations included. */
    val roles: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val reasons: Set<String> = emptySet(),
    val origins: Set<String> = emptySet(),
    val statementKinds: Set<String> = emptySet(),
    /**
     * A written value or call argument of this kind and, for `STRING`, `NUMBER`, `BOOLEAN` and `NAME`, [value]: exact,
     * or with `*` as a wildcard. A [value] without a kind matches a row of any of those four kinds. `NAME` and `BOOLEAN` values are stored upper case, so [value] is upper-cased for them;
     * a `STRING` value matches case-sensitively, exact or as a pattern (where `?` and `[` are themselves); a `NUMBER`
     * matches as written, a pattern case-insensitively.
     */
    val valueKind: String? = null,
    val value: String? = null,
    /** An `AMBIGUOUS` occurrence matches the entity filters through any of its candidates. */
    val candidates: Boolean = true
) {
    val hasEntityFilter: Boolean get() = entity != null || entityKinds.isNotEmpty() || name != null || schema != null || external != null
}

/** What occurrences can be grouped by. */
enum class GroupDimension { ENTITY, ENTITY_KIND, CONTAINER, UNIT, FILE, ROLE, STATUS, REASON, ORIGIN, STATEMENT_KIND, VALUE }

/**
 * The value of one dimension of a group: [entity] for `ENTITY` and `CONTAINER`, [unit] and its [path] for `UNIT`,
 * [path] for `FILE`, [valueKind] and [text] for `VALUE`, [text] otherwise. Every field is null for a group without the
 * dimension (an occurrence without an entity, a reason or a statement).
 */
@Serializable
data class GroupValue(
    val text: String? = null,
    val entity: EntitySummary? = null,
    val unit: UnitRef? = null,
    val path: String? = null,
    val valueKind: String? = null
)

@Serializable
data class OccurrenceGroup(val values: List<GroupValue>, val count: Long)

/** The first groups by count, descending, then by their values; [totalGroups] counts them all. */
@Serializable
data class GroupPage(val dimensions: List<String>, val groups: List<OccurrenceGroup>, val totalGroups: Long)

/** A stored code, and whether only a later phase of the indexer produces it (no index holds it today). */
@Serializable
data class Code(val code: String, val reserved: Boolean)

/** Every code the index stores, by enum, in the writer's order. */
@Serializable
data class Codes(
    val roles: List<Code>,
    val statuses: List<Code>,
    val entityKinds: List<Code>,
    val unitKinds: List<Code>,
    val origins: List<Code>,
    val statementKinds: List<Code>,
    val valueKinds: List<Code>,
    val groupDimensions: List<Code>
)

@Serializable
data class SourceLine(val number: Int, val text: String)

@Serializable
data class SourceSnippet(val path: String, val lines: List<SourceLine>, val stale: Boolean)

/**
 * One occurrence of a [FileView], compact: [at] is the span of its name as `[startLine, startCol, endLine, endCol]`
 * (lines from 1, columns from 0, end exclusive); [entity] and [candidates] are indexes into [FileView.entities].
 */
@Serializable
data class FileOccurrence(val at: List<Int>, val roles: List<String>, val status: String, val reason: String?, val entity: Int?, val candidates: List<Int>)

/**
 * A whole indexed file: its [lines] and, unless it is [stale] (changed since it was indexed, so spans would point at
 * the wrong text), its occurrences, with every entity they refer to listed once in [entities].
 */
@Serializable
data class FileView(val path: String, val stale: Boolean, val lines: List<String>, val entities: List<EntitySummary>, val occurrences: List<FileOccurrence>)

/** A request the index cannot answer as asked (an unknown role, a limit out of range, a path outside the index). */
class InvalidQueryException(message: String) : RuntimeException(message)

/** An impact graph of [nodes] nodes, more than [limit]: a smaller depth gives a smaller one. */
class GraphTooLargeException(val nodes: Int, val limit: Int) :
    RuntimeException("The graph has $nodes nodes, more than $limit: lower maxDepth")

/** A query ran longer than the index allows; narrower filters make it faster. */
class QueryTimeoutException(val seconds: Double) :
    RuntimeException("The query took longer than ${"%.0f".format(java.util.Locale.ROOT, seconds)} s; narrow the filters and try again.")

/** The file was written by an indexer with another schema version. */
class SchemaMismatchException(val found: Int, val expected: Int) :
    RuntimeException("The index has schema version $found; this server reads version $expected. Re-run `zpa-atlas index` to rebuild it, or use the matching zpa-atlas version.")
