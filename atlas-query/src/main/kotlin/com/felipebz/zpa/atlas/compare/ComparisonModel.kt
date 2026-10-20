//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.compare

/** A database object as `(owner, name, type)`. */
data class DbObjectId(val owner: String, val name: String, val type: String) : Comparable<DbObjectId> {
    override fun compareTo(other: DbObjectId) = ORDER.compare(this, other)
    override fun toString() = "$owner.$name $type"

    companion object {
        private val ORDER = compareBy<DbObjectId>({ it.owner }, { it.name }, { it.type })
    }
}

/**
 * The object an index entity projects to: its top-level `CONTAINS` ancestor. [owner] is null for an entity of
 * unknown schema (`db:~.X`), matched by name only; [type] is null when the kind does not tell (`UNKNOWN_RELATION`).
 */
data class Target(val owner: String?, val name: String, val type: String?, val link: String?) : Comparable<Target> {
    val byName: Boolean get() = owner == null
    override fun compareTo(other: Target) = ORDER.compare(this, other)
    override fun toString() = (owner ?: "~") + "." + name + (link?.let { "@$it" } ?: "")

    companion object {
        private val ORDER = compareBy<Target>({ it.owner ?: "" }, { it.name }, { it.link ?: "" }, { it.type ?: "" })
    }
}

data class Location(val path: String, val line: Int) : Comparable<Location> {
    override fun compareTo(other: Location) = compareValuesBy(this, other, { it.path }, { it.line })
    override fun toString() = "$path:$line"
}

/**
 * How a unit's file text compares with what the database stores. [REWRITTEN]: Oracle stores a rewritten query of a
 * materialized view, which cannot be compared with the file.
 */
enum class SourceStatus { SAME, DIFFERS, WRAPPED, REWRITTEN, NO_STORED_SOURCE, UNAVAILABLE }

data class UnitRow(
    val unit: DbObjectId,
    val files: List<String>,
    val databaseStatus: String?,
    val sourceStatus: SourceStatus,
    val firstDifferentLine: Int?,
    val databaseEdges: Int,
    val indexEdges: Int,
    val matched: Int,
    val dynamic: Int,
    val partialSource: Int
)

data class MatchedEdge(
    val unit: DbObjectId,
    val reference: DbDependency,
    val byName: Boolean,
    val collapsedOnly: Boolean,
    val locations: List<Location>,
    /** A `SYNONYM` row matched through the index edge to the synonym's final target. */
    val synonym: Boolean = false
) {
    val matchClass: String get() = when {
        synonym -> "SYNONYM"
        byName -> "BY_NAME"
        else -> "EXACT"
    }
}

data class DatabaseOnlyEdge(
    val dbClass: String,
    val unit: DbObjectId,
    val reference: DbDependency,
    val detail: String,
    /** For `TRANSITIVE`: the first hop, a matched dependency of the unit through which the database reaches the target. */
    val via: DbObjectId? = null
)

data class IndexOnlyEdge(val indexClass: String, val unit: DbObjectId, val target: Target, val locations: List<Location>)

/**
 * One (trigger, column, facet) seen by one side only, or by both. Facets: `LIST` (named in `UPDATE OF`), `IN` (read
 * through a correlation name in the body), `OUT` (written through `NEW`), and `USED` for compound triggers, where
 * Oracle records the usage as `NONE`. [group] is `COMPARED`, `SOURCE_DIFFERS`, `INVALID` or `NO_UNIT`; [debug] is true when
 * the trigger was compiled with `PLSQL_DEBUG`, which makes Oracle list every column of the table as read.
 */
data class TriggerColumnFact(
    val group: String,
    val owner: String,
    val trigger: String,
    val column: String,
    val facet: String,
    val side: String,
    val debug: Boolean,
    val locations: List<Location>
)

/**
 * The conditional compilation settings the index was built with (`run.database_version`, `database_version_origin` and
 * `ccflags`) next to the database's. Versions are text such as `23.0`, null when unknown.
 */
data class ConditionalCompilationCheck(
    val indexVersion: String?,
    val indexVersionOrigin: String?,
    val indexFlags: String?,
    val databaseVersion: String?,
    val databaseVersionOrigin: String?,
    /** False when the database's `PLSQL_CCFLAGS` were not read: the counts below are then empty. */
    val flagsChecked: Boolean,
    /** Compared PL/SQL units (no views) whose `PLSQL_CCFLAGS` mean the same flags as the index's. */
    val sameFlags: Int,
    /** Compared PL/SQL units whose `PLSQL_CCFLAGS` (null: none) differ from the index's or do not parse, sorted. */
    val differentFlags: List<Pair<DbObjectId, String?>>,
    /** Compared PL/SQL units without `ALL_PLSQL_OBJECT_SETTINGS` row. */
    val withoutSettings: Int
) {
    /** Whether the index was built for another database version than the database's, or for none. */
    val versionDiffers: Boolean
        get() = databaseVersion != null && (indexVersion == null || CcFlags.version(indexVersion) != CcFlags.version(databaseVersion))
}

data class ComparisonResult(
    val units: List<UnitRow>,
    val unitsWithoutObject: List<DbObjectId>,
    /** Index edges of the units without `ALL_OBJECTS` row, which are not compared. */
    val uncomparedEdges: Int,
    val objectsWithoutUnit: List<DbObjectId>,
    val scriptUnits: Int,
    val scriptEdges: Int,
    val matched: List<MatchedEdge>,
    val databaseOnly: List<DatabaseOnlyEdge>,
    val indexOnly: List<IndexOnlyEdge>,
    val collapsedOccurrences: Int,
    val sourceReadable: Boolean,
    val textAvailable: Boolean,
    val builtins: Set<String>,
    /** `ALL_TRIGGER_COLS` against the index, for the triggers with an `ALL_OBJECTS` row; side `MATCHED`, `DB_ONLY` or `INDEX_ONLY`. */
    val triggerColumns: List<TriggerColumnFact> = emptyList(),
    val conditionalCompilation: ConditionalCompilationCheck
)
