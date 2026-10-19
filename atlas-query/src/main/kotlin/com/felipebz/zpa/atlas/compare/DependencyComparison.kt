//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.compare

import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.query.SchemaMismatchException
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.util.Locale

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

/**
 * Compares the object-level edges of an index with the database's `ALL_DEPENDENCIES`. It reads the index
 * file and, for the text classes, the sources; it needs neither zpa-core nor a database connection.
 */
class DependencyComparison(
    private val index: Path,
    private val database: DatabaseDependencies,
    private val sourcesRoot: Path? = null,
    private val defaultOwner: String? = null,
    private val builtins: Set<String> = DEFAULT_BUILTINS
) {
    private class Unit(val id: Long, val kind: String, val name: String, val path: String, val encoding: String, val span: IntArray, val obj: DbObjectId?)

    private class Evidence(val segments: List<String>, val status: String, val target: Target?, val location: Location, val roles: Set<String>)

    private class IndexEdge(val target: Target) {
        val locations = sortedSetOf<Location>()
        /** `S1.S2` readings of unknown-schema names (`db:~.S1.S2…`): `S1` may be a schema the run does not know. */
        val readings = HashSet<Pair<String, String>>()
        var fromCollapsedOnly = true
        var fromByName = false
        /** Every occurrence behind the edge only names its target (`TYPE_USE`). */
        var typeUseOnly = true
    }

    private class Projected(val owner: String?, val name: String, val type: String?, val link: String?)

    fun compare(): ComparisonResult {
        val config = SQLiteConfig().apply { setReadOnly(true) }
        config.createConnection("jdbc:sqlite:${index.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA query_only = 1") }
            return compare(connection)
        }
    }

    private fun compare(connection: Connection): ComparisonResult {
        val version = connection.query("SELECT schema_version FROM run") { it.getInt(1) }.single()
        if (version != AtlasIndex.SCHEMA_VERSION) throw SchemaMismatchException(version, AtlasIndex.SCHEMA_VERSION)
        val run = connection.query("SELECT compilation_schema, sources_root, database_version, database_version_origin, ccflags FROM run") { rs ->
            (1..5).map { rs.getString(it) }
        }.single()
        val (runSchema, recordedRoot) = run
        val root = sourcesRoot ?: Path.of(recordedRoot).takeIf { Files.isDirectory(it) }

        // ---- units to objects
        val missingOwner = ArrayList<String>()
        val units = connection.query(
            """SELECT u.id, u.kind, u.name, f.path, f.compilation_schema, u.start_line, u.start_col, u.end_line, u.end_col, f.encoding
               FROM unit u JOIN source_file f ON f.id = u.file_id ORDER BY u.id"""
        ) { rs ->
            val kind = rs.getString(2)
            val name = rs.getString(3)
            val type = OBJECT_TYPES[kind]
            val obj = if (type == null || name.isEmpty()) null else {
                val segments = name.split('.')
                val owner = if (segments.size >= 2) segments.first() else rs.getString(5) ?: runSchema ?: defaultOwner
                if (owner == null) { missingOwner += "${rs.getString(4)}: $kind $name"; null } else DbObjectId(owner, segments.last(), type)
            }
            Unit(rs.getLong(1), kind, name, rs.getString(4), rs.getString(10), intArrayOf(rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9)), obj)
        }
        if (missingOwner.isNotEmpty()) {
            throw IllegalArgumentException("Units without owner (no written owner, no compilation schema, no --default-owner):\n" +
                missingOwner.sorted().joinToString("\n"))
        }
        val unitsById = units.associateBy { it.id }

        // ---- entity projection
        val entities = connection.query("SELECT id, key, kind, schema_name, name, parent_id FROM entity") { rs ->
            rs.getLong(1) to EntityRow(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), (rs.getObject(6) as Number?)?.toLong())
        }.toMap()
        val projections = HashMap<Long, Target>()
        fun project(id: Long): Target = projections.getOrPut(id) {
            var row = entities.getValue(id)
            while (row.parent != null) row = entities.getValue(row.parent)
            val byName = row.key.startsWith("db:~.")
            val (name, link) = row.name.split('@', limit = 2).let { it[0] to it.getOrNull(1) }
            Target(if (byName || link != null) null else row.schema, name, ENTITY_TYPES[row.kind], link)
        }

        /**
         * The target of an occurrence entity, and its schema readings. An unknown-schema key `db:~.S1.S2…` projects to
         * `S1` by name (never its last segment: `db:~.OTHER_S.EXT.RUN` has no parent), and may also be `S1.S2` with `S1` a
         * schema the run did not load.
         */
        fun projectOccurrence(id: Long): Pair<Target, Set<Pair<String, String>>> {
            val row = entities.getValue(id)
            val root = project(id)
            if (!row.key.startsWith("db:~.") || row.kind == "COLUMN") return root to emptySet()
            var rootRow = row
            while (rootRow.parent != null) rootRow = entities.getValue(rootRow.parent)
            if (rootRow !== row) {
                // The first segment and the link come from the root entity's name (`PACK_REQ@FAR.EXAMPLE.COM`), the
                // member from the key after the root's: a link may hold dots, so the key is never split to find it.
                val (first, link) = rootRow.name.split('@', limit = 2).let { it[0] to it.getOrNull(1) }
                val member = row.key.removePrefix(rootRow.key + ".").substringBefore('.')
                val target = if (root.name == first && root.byName) root else Target(null, first, null, link)
                return target to setOf(first to member)
            }
            // A key without parent (`db:~.A.B.X`): its segments, none of them linked.
            val segments = row.key.removePrefix("db:~.").split('.')
            val (first, link) = segments[0].split('@', limit = 2).let { it[0] to it.getOrNull(1) }
            val target = if (root.name == first && root.byName) root else Target(null, first, null, link)
            val readings = if (segments.size >= 2 && link == null) setOf(first to segments[1]) else emptySet()
            return target to readings
        }

        // ---- index edges
        val edges = HashMap<DbObjectId, MutableMap<Target, IndexEdge>>()
        val evidence = HashMap<DbObjectId, MutableList<Evidence>>()
        val dynamicCount = HashMap<DbObjectId, Int>()
        val partialCount = HashMap<DbObjectId, Int>()
        var scriptEdges = 0
        var collapsed = 0

        fun isSelf(unit: DbObjectId, target: Target) = target.owner == unit.owner && target.name == unit.name && target.type == unit.type
        fun addEdge(
            unit: DbObjectId, target: Target, location: Location, collapsedSource: Boolean, readings: Set<Pair<String, String>> = emptySet(),
            roles: Set<String> = emptySet()
        ) {
            if (isSelf(unit, target)) return
            val edge = edges.getOrPut(unit) { HashMap() }.getOrPut(target) { IndexEdge(target) }
            edge.locations += location
            edge.readings += readings
            if (!collapsedSource) edge.fromCollapsedOnly = false
            if (roles != setOf("TYPE_USE")) edge.typeUseOnly = false
        }

        val candidates = connection.query("SELECT occurrence_id, entity_id FROM occurrence_candidate") { it.getLong(1) to it.getLong(2) }
            .groupBy({ it.first }, { it.second })
        // Static code only: Oracle records no dependency for the SQL text of a dynamic statement.
        connection.forEach(
            """SELECT o.id, o.unit_id, o.entity_id, o.status, o.written, f.path, o.start_line, o.reason, o.roles
               FROM occurrence o JOIN source_file f ON f.id = o.file_id WHERE o.origin = 'STATIC' ORDER BY o.id"""
        ) { rs ->
            val unit = unitsById.getValue(rs.getLong(2))
            val entityId = (rs.getObject(3) as Number?)?.toLong()
            val status = rs.getString(4)
            val location = Location(rs.getString(6), rs.getInt(7))
            val roles = rs.getString(9).split(',').filter { it.isNotEmpty() }.toSet()
            // Oracle records no dependency for DDL: the object a DDL statement acts on is neither an edge nor evidence.
            if (roles.isNotEmpty() && roles.all { it.startsWith("DDL_") }) return@forEach
            val obj = unit.obj
            // A call the project does not know still names an (external) entity: `db:~.DBMS_OUTPUT.PUT_LINE`, `db:SYS.X`.
            val edgeStatus = status in EDGE_STATUSES || (status == "UNRESOLVED" && rs.getString(8) == "NOT_IN_PROJECT")
            if (obj == null) {
                if (edgeStatus && entityId != null && !declaringOnly(roles)) scriptEdges++
                return@forEach
            }
            when (status) {
                "DYNAMIC" -> dynamicCount.merge(obj, 1, Int::plus)
                "PARTIAL_SOURCE" -> partialCount.merge(obj, 1, Int::plus)
            }
            // A definition or declaration names the unit's own entity (a parameter may be named like a table): it is
            // neither an edge (the body -> spec edge comes from IMPLEMENTS) nor evidence about another object.
            if (declaringOnly(roles)) return@forEach
            var target: Target? = null
            var effectiveStatus = status
            if (edgeStatus && entityId != null) {
                val (projected, readings) = projectOccurrence(entityId)
                target = projected
                addEdge(obj, projected, location, false, readings, roles)
            } else if (status == "AMBIGUOUS") {
                val roots = candidates[rs.getLong(1)].orEmpty().map { project(it) }.toSet()
                if (roots.size == 1) {
                    collapsed++
                    target = roots.single()
                    effectiveStatus = "COLLAPSED"
                    addEdge(obj, target, location, true, roles = roles)
                }
            }
            evidence.getOrPut(obj) { ArrayList() } += Evidence(segments(rs.getString(5)), effectiveStatus, target, location, roles)
        }

        // Relations: IMPLEMENTS (body -> spec) and TRIGGER_ON (trigger -> table), from the unit defining the source entity.
        connection.forEach(
            """SELECT DISTINCT r.to_id, u.id, f.path, u.start_line FROM relation r
               JOIN (SELECT o.entity_id AS entity_id, o.unit_id AS unit_id FROM occurrence o WHERE o.roles = 'DEFINITION'
                     UNION SELECT u2.entity_id, u2.id FROM unit u2 WHERE u2.entity_id IS NOT NULL) d ON d.entity_id = r.from_id
               JOIN unit u ON u.id = d.unit_id JOIN source_file f ON f.id = u.file_id
               WHERE r.kind IN ('IMPLEMENTS', 'TRIGGER_ON')"""
        ) { rs ->
            val obj = unitsById.getValue(rs.getLong(2)).obj ?: return@forEach
            addEdge(obj, project(rs.getLong(1)), Location(rs.getString(3), rs.getInt(4)), false)
        }

        // A package body or a type body depends on its specification: the unit's own entity, as Oracle compiles it.
        connection.forEach(
            """SELECT u.id, u.entity_id, f.path, u.start_line FROM unit u JOIN source_file f ON f.id = u.file_id
               WHERE u.kind IN ('PACKAGE_BODY', 'TYPE_BODY') AND u.entity_id IS NOT NULL"""
        ) { rs ->
            val obj = unitsById.getValue(rs.getLong(1)).obj ?: return@forEach
            addEdge(obj, project(rs.getLong(2)), Location(rs.getString(3), rs.getInt(4)), false)
        }

        // ---- database side
        val mapped = units.mapNotNull { it.obj }.toSortedSet()
        val dbObjects = database.objects.associateBy { DbObjectId(it.owner, it.name, it.type) }
        val owners = database.objects.map { it.owner }.toSet()
        val objectsWithoutUnit = dbObjects.keys.filter { it.type in OBJECT_TYPES.values && it.owner in owners && it !in mapped }.sorted()
        val unitsWithoutObject = mapped.filter { it !in dbObjects }
        // Only units the database has are compared: the others have no dependency rows at all.
        val compared = mapped.filter { it in dbObjects }.toSortedSet()
        val uncomparedEdges = unitsWithoutObject.sumOf { edges[it]?.size ?: 0 }
        val dbEdges = database.dependencies
            .filter { DbObjectId(it.owner, it.name, it.type) in compared }
            .distinctBy { listOf(it.owner, it.name, it.type, it.referencedOwner, it.referencedName, it.referencedLink) }
            .sortedWith(DatabaseDependencies.DEPENDENCY_ORDER)

        // ---- sources
        val stored = database.sources.groupBy { DbObjectId(it.owner, it.name, it.type) }
            .mapValues { (_, lines) -> lines.sortedBy { it.line }.joinToString("") { it.text } }
        val unitTexts = HashMap<DbObjectId, String>()
        for (unit in units) {
            val obj = unit.obj ?: continue
            val file = root?.resolve(unit.path)?.takeIf { Files.isRegularFile(it) } ?: continue
            // The charset the indexer used for this file, so that the spans cut at the same characters.
            val text = AtlasIndex.decode(Files.readAllBytes(file), unit.encoding)
            unitTexts.merge(obj, slice(text, unit.span)) { a, b -> a + "\n" + b }
        }
        val sourceStatus = HashMap<DbObjectId, Pair<SourceStatus, Int?>>()
        for (obj in mapped) {
            val text = unitTexts[obj]
            val db = stored[obj]
            sourceStatus[obj] = when {
                !database.sourceReadable || text == null -> SourceStatus.UNAVAILABLE to null
                db == null -> SourceStatus.NO_STORED_SOURCE to null
                // ALL_VIEWS.TEXT and ALL_MVIEWS.QUERY: the query only, with the stars of a view expanded.
                obj.type == "VIEW" -> PlSqlText.viewDifference(text, db)?.let { SourceStatus.DIFFERS to it } ?: (SourceStatus.SAME to null)
                obj.type == "MATERIALIZED VIEW" -> PlSqlText.viewDifference(text, db)?.let {
                    if (PlSqlText.isRewrittenQuery(db)) SourceStatus.REWRITTEN to null else SourceStatus.DIFFERS to it
                } ?: (SourceStatus.SAME to null)
                PlSqlText.isWrapped(db) -> SourceStatus.WRAPPED to null
                else -> PlSqlText.firstDifference(text, db)?.let { SourceStatus.DIFFERS to it } ?: (SourceStatus.SAME to null)
            }
        }
        val names = unitTexts.mapValues { PlSqlText.names(it.value) }

        // ---- synonyms of the index: (owner, name) -> the projection of the final target of its SYNONYM_FOR chain
        val synonymFor = connection.query("SELECT from_id, to_id FROM relation WHERE kind = 'SYNONYM_FOR'") { it.getLong(1) to it.getLong(2) }.toMap()
        val synonymTargets = HashMap<Pair<String, String>, Target>()
        for ((id, row) in entities) {
            if (row.kind != "SYNONYM" || row.schema == null) continue
            var current = id
            var hops = 0
            while (current in synonymFor && hops <= synonymFor.size) {
                current = synonymFor.getValue(current)
                hops++
            }
            if (current !in synonymFor) synonymTargets[row.schema to row.name] = project(current)
        }

        // ---- matching
        val matched = ArrayList<MatchedEdge>()
        val databaseOnly = ArrayList<DatabaseOnlyEdge>()
        val matchedIndex = HashSet<Pair<DbObjectId, Target>>()
        val unmatched = ArrayList<DbDependency>()
        for (dep in dbEdges) {
            val obj = DbObjectId(dep.owner, dep.name, dep.type)
            val unitEdges = edges[obj].orEmpty()
            val exact = unitEdges.values.firstOrNull { it.target.owner == dep.referencedOwner && it.target.name == dep.referencedName && it.target.link == dep.referencedLink }
            val byNameEdges = if (exact != null) emptyList() else unitEdges.values.filter {
                it.target.byName && it.target.link == dep.referencedLink &&
                    (it.target.name == dep.referencedName || (dep.referencedOwner to dep.referencedName) in it.readings)
            }
            val byName = byNameEdges.minByOrNull { it.target }
            // A dependency on an object that does not exist is never an index edge (Oracle records it to invalidate the
            // unit when such an object appears, typically next to a synonym of the same name).
            val hit = if (dep.referencedType == "NON-EXISTENT") null else exact ?: byName
            if (hit != null) {
                matchedIndex += obj to hit.target
                if (exact != null) {
                    matched += MatchedEdge(obj, dep, false, hit.fromCollapsedOnly, hit.locations.toList())
                } else {
                    // By-name targets that differ only in type (`~.X` typeless and `~.X` PACKAGE) are one edge: every one
                    // with this name and link is matched, and their locations are merged.
                    val same = byNameEdges.filter { it.target.name == hit.target.name && it.target.link == hit.target.link }
                    same.forEach { matchedIndex += obj to it.target }
                    matched += MatchedEdge(obj, dep, true, same.all { it.fromCollapsedOnly },
                        same.flatMap { it.locations }.toSortedSet().toList())
                }
                continue
            }
            // Oracle records the synonym, not its target: the unit's edge to the target is the evidence.
            val synonymEdge = if (dep.referencedType == "SYNONYM" && dep.referencedLink == null) {
                synonymTargets[dep.referencedOwner to dep.referencedName]?.let { unitEdges[it] }
            } else {
                null
            }
            if (synonymEdge != null) {
                matchedIndex += obj to synonymEdge.target
                matched += MatchedEdge(obj, dep, false, synonymEdge.fromCollapsedOnly, synonymEdge.locations.toList(), synonym = true)
                continue
            }
            unmatched += dep
        }

        // ---- classification of the database rows the index does not match
        val paths = TransitivePaths(database.dependencies)
        // The first hops of each unit: the objects of its matched rows, in dependency order.
        val firstHops = matched.sortedWith { a, b -> DatabaseDependencies.DEPENDENCY_ORDER.compare(a.reference, b.reference) }
            .groupBy({ it.unit }, { DbObjectId(it.reference.referencedOwner ?: "", it.reference.referencedName, it.reference.referencedType) })
        for (dep in unmatched) {
            val obj = DbObjectId(dep.owner, dep.name, dep.type)
            val unitEvidence = evidence[obj].orEmpty().filter { dep.referencedName in it.segments && fits(dep.referencedType, it.roles) }
            val unresolved = unitEvidence.filter { it.status in UNRESOLVED_STATUSES }
            val elsewhere = unitEvidence.filter { it.target != null && !(it.target.owner == dep.referencedOwner && it.target.name == dep.referencedName) &&
                !(it.target.byName && it.target.name == dep.referencedName) }
            // Only RESOLVED evidence is a likely resolution bug; UNVERIFIED, EXTERNAL and collapsed guesses are guesses.
            val (resolvedElsewhere, guessedElsewhere) = elsewhere.partition { it.status == "RESOLVED" }
            fun describe(list: List<Evidence>) = list.map { "${it.location}->${it.target}(${it.status})" }.distinct().joinToString(" ")
            val nameNotInText = names[obj] != null && dep.referencedName !in names.getValue(obj)
            val path = if (nameNotInText && dep.referencedOwner != null && dep.referencedLink == null) {
                paths.shortest(firstHops[obj].orEmpty(), DbObjectId(dep.referencedOwner, dep.referencedName, dep.referencedType))
            } else {
                null
            }
            val (dbClass, detail) = when {
                dep.referencedOwner in BUILTIN_OWNERS && dep.referencedName in builtins -> "BUILTIN" to ""
                dep.referencedLink != null -> "REMOTE" to ""
                dep.referencedType == "NON-EXISTENT" -> "NON_EXISTENT" to ""
                // A materialized view depends on its own container table, a `TABLE` of the same owner and name.
                dep.type == "MATERIALIZED VIEW" && dep.referencedOwner == dep.owner && dep.referencedName == dep.name &&
                    dep.referencedType == "TABLE" -> "MVIEW_CONTAINER" to ""
                sourceStatus[obj]?.first == SourceStatus.DIFFERS -> "SOURCE_DIFFERS" to ""
                path != null -> "TRANSITIVE" to "via ${path.first} (${path.second})"
                nameNotInText -> "NAME_NOT_IN_TEXT" to ""
                unresolved.isNotEmpty() -> "UNRESOLVED_IN_INDEX" to unresolved.joinToString(" ") { "${it.location}(${it.status})" }
                resolvedElsewhere.isNotEmpty() -> "RESOLVED_ELSEWHERE" to describe(resolvedElsewhere)
                guessedElsewhere.isNotEmpty() -> "GUESSED_ELSEWHERE" to describe(guessedElsewhere)
                else -> "NOT_EXTRACTED" to ""
            }
            databaseOnly += DatabaseOnlyEdge(dbClass, obj, dep, detail, path?.first)
        }
        val indexOnly = ArrayList<IndexOnlyEdge>()
        for ((obj, unitEdges) in edges.toSortedMap()) {
            if (obj !in compared) continue
            for (edge in unitEdges.values.sortedBy { it.target }) {
                if (obj to edge.target in matchedIndex) continue
                val indexClass = when {
                    // Oracle records no dependency of a view on a remote object, whatever the view's text.
                    obj.type == "VIEW" && edge.target.link != null -> "VIEW_REMOTE"
                    // A view's MAKE_REF depends on the row type, not on the relation (verified on Oracle 26ai).
                    obj.type == "VIEW" && edge.typeUseOnly && edge.target.type in RELATION_TYPES -> "VIEW_MAKE_REF"
                    sourceStatus[obj]?.first == SourceStatus.DIFFERS -> "SOURCE_DIFFERS"
                    dbObjects[obj]?.status == "INVALID" -> "UNIT_INVALID"
                    edge.target.byName -> "BY_NAME_ONLY"
                    else -> "INDEX_ONLY"
                }
                indexOnly += IndexOnlyEdge(indexClass, obj, edge.target, edge.locations.toList())
            }
        }

        val filesByObject = units.filter { it.obj != null }.groupBy({ it.obj!! }, { it.path })
        val dbEdgesByObject = dbEdges.groupingBy { DbObjectId(it.owner, it.name, it.type) }.eachCount()
        val matchedByObject = matched.groupingBy { it.unit }.eachCount()
        val unitRows = mapped.map { obj ->
            UnitRow(
                obj, filesByObject[obj].orEmpty().distinct().sorted(), dbObjects[obj]?.status,
                sourceStatus.getValue(obj).first, sourceStatus.getValue(obj).second,
                dbEdgesByObject[obj] ?: 0, edges[obj]?.size ?: 0, matchedByObject[obj] ?: 0, dynamicCount[obj] ?: 0, partialCount[obj] ?: 0
            )
        }
        return ComparisonResult(
            unitRows, unitsWithoutObject, uncomparedEdges, objectsWithoutUnit, units.count { it.obj == null }, scriptEdges,
            matched.sortedWith(compareBy<MatchedEdge>({ it.unit }).thenComparator { a, b -> DatabaseDependencies.DEPENDENCY_ORDER.compare(a.reference, b.reference) }),
            databaseOnly.sortedWith(compareBy<DatabaseOnlyEdge>({ it.dbClass }, { it.unit }).thenComparator { a, b -> DatabaseDependencies.DEPENDENCY_ORDER.compare(a.reference, b.reference) }),
            indexOnly.sortedWith(compareBy({ it.indexClass }, { it.unit }, { it.target })),
            collapsed, database.sourceReadable, root != null, builtins, triggerColumns(connection, mapped.filter { it.type == "TRIGGER" && it in dbObjects }.toSet(),
                sourceStatus.filterValues { it.first == SourceStatus.DIFFERS }.keys),
            conditionalCompilation(run[2], run[3], run[4], compared.filter { it.type != "VIEW" && it.type != "MATERIALIZED VIEW" })
        )
    }

    /**
     * The index's settings against the database's for [units] (sorted): flags compare as parsed by [CcFlags.parse], so
     * order, spacing and case do not count, and no flags (null or blank) equals no flags.
     */
    private fun conditionalCompilation(indexVersion: String?, indexOrigin: String?, indexFlags: String?, units: List<DbObjectId>): ConditionalCompilationCheck {
        val settings = database.ccflags.associateBy { DbObjectId(it.owner, it.name, it.type) }
        val expected = CcFlags.parse(indexFlags)
        var same = 0
        var without = 0
        val different = ArrayList<Pair<DbObjectId, String?>>()
        if (database.ccflagsReadable) {
            for (unit in units) {
                val row = settings[unit]
                when {
                    row == null -> without++
                    expected != null && CcFlags.parse(row.ccflags) == expected -> same++
                    else -> different += unit to row.ccflags
                }
            }
        }
        return ConditionalCompilationCheck(indexVersion, indexOrigin, indexFlags, database.databaseVersion, database.databaseVersionOrigin,
            database.ccflagsReadable, same, different, without)
    }

    /**
     * The `ALL_TRIGGER_COLS` rows against the index. Every database row and every index facet of a trigger with an
     * `ALL_OBJECTS` row is classified: [TriggerColumnFact.group] `COMPARED` (a unit of the index), `SOURCE_DIFFERS`
     * (a unit whose stored source differs) or `NO_UNIT` (a database trigger the index does not have).
     */
    private fun triggerColumns(connection: Connection, units: Set<DbObjectId>, differing: Set<DbObjectId>): List<TriggerColumnFact> {
        val triggers = database.objects.filter { it.type == "TRIGGER" }.map { it.owner to it.name }.toSet()
        val invalid = database.objects.filter { it.type == "TRIGGER" && it.status == "INVALID" }.map { DbObjectId(it.owner, it.name, it.type) }.toSet()
        val debug = database.triggerSettings.filter { it.debug }.map { it.owner to it.name }.toSet()
        val compound = connection.query(
            """SELECT DISTINCT t.schema_name, t.name FROM trigger_event e JOIN entity t ON t.id = e.trigger_id WHERE e.timing = 'COMPOUND'"""
        ) { it.getString(1) to it.getString(2) }.toSet()
        fun usage(trigger: Pair<String, String>, facet: String) = if (trigger in compound) "USED" else facet
        val index = HashMap<Triple<String, String, String>, MutableMap<String, MutableSet<Location>>>()
        fun add(owner: String?, trigger: String, column: String, facet: String, location: Location?) {
            if (owner == null || (owner to trigger) !in triggers) return
            val locations = index.getOrPut(Triple(owner, trigger, column)) { HashMap() }.getOrPut(facet) { sortedSetOf() }
            location?.let { locations += it }
        }
        connection.forEach(
            """SELECT t.schema_name, t.name, c.name FROM trigger_event e JOIN entity t ON t.id = e.trigger_id JOIN entity c ON c.id = e.column_id"""
        ) { add(it.getString(1), it.getString(2), it.getString(3), "LIST", null) }
        // Correlation columns in the body carry the colon; WHEN-clause names do not, and Oracle does not list them.
        connection.forEach(
            """SELECT t.schema_name, t.name, c.name, o.roles, f.path, o.start_line FROM occurrence o JOIN entity t ON t.id = o.container_entity_id
               JOIN entity c ON c.id = o.entity_id JOIN source_file f ON f.id = o.file_id
               JOIN relation r ON r.from_id = t.id AND r.kind = 'TRIGGER_ON' AND r.to_id = c.parent_id
               WHERE t.kind = 'TRIGGER' AND c.kind = 'COLUMN' AND o.written LIKE ':%' AND o.origin = 'STATIC'"""
        ) { rs ->
            val trigger = rs.getString(1) to rs.getString(2)
            val location = Location(rs.getString(5), rs.getInt(6))
            val roles = rs.getString(4).split(',')
            if ("READ" in roles) add(trigger.first, trigger.second, rs.getString(3), usage(trigger, "IN"), location)
            if ("WRITE" in roles) add(trigger.first, trigger.second, rs.getString(3), usage(trigger, "OUT"), location)
        }
        val db = HashMap<Triple<String, String, String>, MutableSet<String>>()
        for (c in database.triggerColumns) {
            val trigger = c.triggerOwner to c.triggerName
            val facets = db.getOrPut(Triple(c.triggerOwner, c.triggerName, c.column)) { HashSet() }
            if (c.columnList) facets += "LIST"
            val tokens = c.usage.split(' ')
            if ("IN" in tokens) facets += usage(trigger, "IN")
            if ("OUT" in tokens) facets += usage(trigger, "OUT")
            if (trigger in compound && !c.columnList && "NONE" in tokens) facets += "USED"
        }
        val result = ArrayList<TriggerColumnFact>()
        for (key in (index.keys + db.keys)) {
            val obj = DbObjectId(key.first, key.second, "TRIGGER")
            // INVALID: Oracle keeps the rows of the last successful compile when a dependency invalidates the trigger
            // (stale), and lists the columns with usage NONE when the trigger itself failed to compile (empty).
            val group = when {
                obj !in units -> "NO_UNIT"
                obj in differing -> "SOURCE_DIFFERS"
                obj in invalid -> "INVALID"
                else -> "COMPARED"
            }
            val indexFacets = index[key].orEmpty()
            val dbFacets = db[key].orEmpty()
            for (facet in (indexFacets.keys + dbFacets)) {
                val side = when {
                    facet in dbFacets && facet in indexFacets -> "MATCHED"
                    facet in dbFacets -> "DB_ONLY"
                    else -> "INDEX_ONLY"
                }
                result += TriggerColumnFact(group, key.first, key.second, key.third, facet, side, (key.first to key.second) in debug,
                    indexFacets[facet].orEmpty().toList())
            }
        }
        return result.sortedWith(compareBy({ it.owner }, { it.trigger }, { it.column }, { it.facet }))
    }

    private class EntityRow(val key: String, val kind: String, val schema: String?, val name: String, val parent: Long?)

    /** The text of a unit: from its start position to its end position (lines 1-based, columns 0-based). */
    private fun slice(text: String, span: IntArray): String {
        val lines = text.replace("\r\n", "\n").split('\n')
        val first = span[0] - 1
        val last = (span[2] - 1).coerceAtMost(lines.size - 1)
        if (first > last) return ""
        if (first == last) return lines[first].substring(span[1].coerceAtMost(lines[first].length), span[3].coerceAtMost(lines[first].length))
        val parts = ArrayList<String>()
        parts += lines[first].substring(span[1].coerceAtMost(lines[first].length))
        for (i in first + 1 until last) parts += lines[i]
        parts += lines[last].substring(0, span[3].coerceAtMost(lines[last].length))
        return parts.joinToString("\n")
    }

    /** Name segments of a written reference, normalized; a DB link (`@link`) is removed. */
    private fun segments(written: String): List<String> =
        written.substringBefore('@').split('.').filter { it.isNotEmpty() }.map {
            if (it.length >= 2 && it.startsWith('"') && it.endsWith('"')) it.substring(1, it.length - 1) else it.uppercase(Locale.ROOT)
        }

    private fun <T> Connection.query(sql: String, map: (ResultSet) -> T): List<T> =
        createStatement().use { s -> s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(map(rs)) } } }

    private fun Connection.forEach(sql: String, action: (ResultSet) -> kotlin.Unit) =
        createStatement().use { s -> s.executeQuery(sql).use { rs -> while (rs.next()) action(rs) } }

    companion object {
        /** Built-in packages that every unit depends on (from `USER_DEPENDENCIES` on Oracle 26ai). */
        val DEFAULT_BUILTINS = setOf("STANDARD", "DBMS_STANDARD", "PLITBLM", "SYS_STUB_FOR_PURITY_ANALYSIS", "DUAL")

        val BUILTIN_OWNERS = setOf("SYS", "PUBLIC")

        /** Unit kinds that are database objects, with their `ALL_OBJECTS.OBJECT_TYPE`. */
        val OBJECT_TYPES = mapOf(
            "PACKAGE" to "PACKAGE", "PACKAGE_BODY" to "PACKAGE BODY", "PROCEDURE" to "PROCEDURE", "FUNCTION" to "FUNCTION",
            "TRIGGER" to "TRIGGER", "TYPE" to "TYPE", "TYPE_BODY" to "TYPE BODY", "VIEW" to "VIEW",
            "MATERIALIZED_VIEW" to "MATERIALIZED VIEW"
        )

        private val ENTITY_TYPES = mapOf(
            "TABLE" to "TABLE", "VIEW" to "VIEW",
            // Oracle records a reference to a materialized view as one to its container table.
            "MATERIALIZED_VIEW" to "TABLE", "OBJECT_TABLE" to "TABLE", "PACKAGE" to "PACKAGE", "PROCEDURE" to "PROCEDURE",
            "FUNCTION" to "FUNCTION", "TYPE" to "TYPE", "SEQUENCE" to "SEQUENCE", "TRIGGER" to "TRIGGER"
        )

        private val EDGE_STATUSES = setOf("RESOLVED", "UNVERIFIED", "EXTERNAL")
        private val DECLARING_ROLES = setOf("DEFINITION", "DECLARATION")

        /**
         * A definition or declaration only, possibly `IMPLICIT` (a view column defined by a select-list star); an empty
         * role set (dynamic SQL) is not, nor is `IMPLICIT` with a use such as `READ`.
         */
        private fun declaringOnly(roles: Set<String>) =
            roles.any { it in DECLARING_ROLES } && roles.all { it in DECLARING_ROLES || it == "IMPLICIT" }
        private val RELATION_TYPES = setOf("TABLE", "VIEW", "MATERIALIZED VIEW")
        private val PACKAGE_ROLES = setOf("CALL", "TYPE_USE", "READ", "WRITE", "RAISE", "HANDLE")

        /**
         * Whether evidence with [roles] can be about an object of [referencedType]: SQL roles or a type use for relations,
         * a call, a type use, a member read or write, a raise or a handler for packages, a call or a type use for types, a call for other
         * callables, anything but a declaration otherwise (a sequence: a read).
         */
        private fun fits(referencedType: String, roles: Set<String>): Boolean = when (referencedType) {
            in RELATION_TYPES -> roles.any { it.startsWith("SQL_") } || "TYPE_USE" in roles
            "PACKAGE" -> roles.any { it in PACKAGE_ROLES }
            "TYPE" -> "CALL" in roles || "TYPE_USE" in roles
            "PROCEDURE", "FUNCTION", "OPERATOR" -> "CALL" in roles
            else -> !declaringOnly(roles)
        }
        private val UNRESOLVED_STATUSES = setOf("UNRESOLVED", "AMBIGUOUS", "PARTIAL_SOURCE")

        /** Reads a built-in list: one name per line, `#` starts a comment. */
        fun readBuiltins(text: String): Set<String> =
            text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.map { it.uppercase(Locale.ROOT) }.toSet()
    }
}
