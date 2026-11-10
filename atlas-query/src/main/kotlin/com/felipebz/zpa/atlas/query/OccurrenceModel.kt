//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query


import kotlinx.serialization.Serializable

/**
 * The unit an occurrence is in: its kind, its name, and for "who uses" asked with sides its [entity] (the package,
 * routine, view, trigger or type whose unit it is; null for a script, and when not asked).
 */
@Serializable
data class UnitRef(val kind: String, val name: String, val entity: EntitySummary? = null)

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

/**
 * One occurrence of a listing. A use Forms makes without code ([origin] `FORMS_IMPLICIT`: a data block's query, insert,
 * update or delete of its table, an item's read or write of its column) has no line: its [span], [unit],
 * [statementKind] and [code] are null, [path] is the module's file and [container] the block or the item.
 */
@Serializable
data class OccurrenceRow(
    val id: Long,
    val path: String,
    val span: Span?,
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
    val unit: UnitRef?,
    val statementKind: String?,
    val candidates: List<EntitySummary>,
    val arguments: List<CallArgument>,
    /** For a column write: the value written, one per row of its statement. */
    val values: List<WrittenValue>,
    /** In a Forms module, the code the occurrence is in and its line there ([CodeAt]); null elsewhere. */
    val code: CodeAt? = null
)

/** The value a column write writes in [row] of its statement; [value] as for [CallArgument.value]. */
@Serializable
data class WrittenValue(val row: Int, val valueKind: String, val value: String?)

/**
 * Counts over a set of occurrences: roles split (an occurrence counts once under each of its roles). [byOrigin],
 * [byEntityKind] (the kind of the matched entity; occurrences without one are left out), [byUnitKind] (occurrences in
 * no unit, the uses Forms makes without code, are left out) and [byReason] (occurrences without a reason are left out)
 * are filled by the occurrence query only.
 */
@Serializable
data class Facets(
    val byRole: List<Count>,
    val byStatus: List<Count>,
    val byOrigin: List<Count> = emptyList(),
    val byEntityKind: List<Count> = emptyList(),
    val byUnitKind: List<Count> = emptyList(),
    val byReason: List<Count> = emptyList(),
    /** For "who uses" asked with sides: the hits by [UsageSide], under the role and status filters but not the side's. */
    val bySide: List<Count>? = null
)

/**
 * One page of "who uses". [usesIncomplete] lists, for the entity asked for and the children in scope, those whose
 * recorded uses may be incomplete ([EntityDetail.usesIncomplete]); null when none.
 */
@Serializable
data class OccurrencePage(
    val rows: List<OccurrenceRow>, val nextAfter: Long?, val total: Long, val facets: Facets, val usesIncomplete: List<UsesGap>? = null
)

/** An entity whose recorded uses may be incomplete, and zpa's [reasons]. */
@Serializable
data class UsesGap(val key: String, val reasons: List<String>)

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
