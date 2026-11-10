//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query


import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * What "who uses" covers besides the entity itself: [IMPLEMENTATIONS] its `IMPLEMENTS` partners (for a parameter, the
 * parameter of the same name of its routine's partners), [CHILDREN] its `CONTAINS` descendants, [VIEWS] the view
 * columns computed from them and the views reading them, transitively.
 */
enum class Scope { SELF, IMPLEMENTATIONS, CHILDREN, VIEWS }

/**
 * Which usages to keep: [DIRECT] the rows whose matched entity is the entity, its partners or its contents;
 * [THROUGH_VIEWS] the rows [Scope.VIEWS] added ([OccurrenceRow.viaView]).
 */
enum class UsagePath { DIRECT, THROUGH_VIEWS }

/**
 * Where a usage sits against the entity asked about: [INSIDE] its unit is the entity's own (a package's or type's spec
 * or body: the unit whose entity it is), [OUTSIDE] any other unit, or a script in no entity's unit.
 */
enum class UsageSide { OUTSIDE, INSIDE }

/**
 * Which uses "who uses" keeps ([AtlasIndex.occurrences], [AtlasIndex.groupUsages]): [roles] (null or empty: every usage
 * role) and [statuses] (null or empty: every status), over the entities of [scope]; with [includeCandidates] the
 * `AMBIGUOUS` uses where one of them is a candidate; with [via], only the rows reached that way.
 */
data class UsageFilter(
    val roles: Set<String>? = null,
    val statuses: Set<String>? = null,
    val scope: Set<Scope> = setOf(Scope.SELF),
    val includeCandidates: Boolean = true,
    val via: UsagePath? = null
)

/**
 * How [AtlasIndex.impact] walks: [roles] and [statuses] filter the first hop as in [UsageFilter]; [include] is the
 * scopes of that hop (null: the default by kind, [defaultUsageScopes]); [maxDepth] bounds the walk; with
 * [followTriggers], a DML trigger is followed through the writes that fire it.
 */
data class ImpactOptions(
    val roles: Set<String>? = null,
    val statuses: Set<String>? = null,
    val include: List<Scope>? = null,
    val maxDepth: Int = 10,
    val followTriggers: Boolean = true
)

/**
 * The scopes "who uses" adds by default for an entity of [kind], as the entity page does: a subprogram's or parameter's
 * spec or body, a package's spec or body and members, a type's members.
 */
fun defaultUsageScopes(kind: String): List<Scope> = listOfNotNull(
    Scope.IMPLEMENTATIONS.takeIf { kind in setOf("PROCEDURE", "FUNCTION", "PACKAGE", "PARAMETER") },
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
 * A use Forms makes without code (a data block's query or DML, an item's column) has [origin] `FORMS_IMPLICIT`, the
 * module's file as [path] and no [line]; [origin] is left out for a use in code.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ImpactVia(
    val uses: String,
    val occurrence: Long,
    val path: String,
    val line: Int?,
    val status: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fires: String? = null,
    /** In a Forms module, the code [line] is in and its line there ([CodeAt]); null elsewhere. */
    val code: CodeAt? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val origin: String? = null
)

/** One event of a trigger node: as [TriggerEvent] without the trigger. */
@Serializable
data class ImpactTriggerEvent(val timing: String, val level: String?, val event: String, val column: EntitySummary?)

/** The table a trigger node is on, and the events that fire it. */
@Serializable
data class ImpactTrigger(val table: EntitySummary?, val events: List<ImpactTriggerEvent>)

/**
 * One node of an impact graph: a routine, view, trigger, type or package ([entity]), a Forms block or item whose data
 * Forms queries or writes without code, or for [ImpactStop.NO_CONTAINER] a file ([path], with its [uses] and
 * [firstLine], counted over the uses of every node reached, not only those on its shortest path). [depth] is the
 * shortest path from the entity; [certain] is true when some path uses only `RESOLVED` uses.
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
