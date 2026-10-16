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

enum class SourceStatus { SAME, DIFFERS, WRAPPED, NO_STORED_SOURCE, UNAVAILABLE }

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

data class MatchedEdge(val unit: DbObjectId, val reference: DbDependency, val byName: Boolean, val collapsedOnly: Boolean, val locations: List<Location>)

data class DatabaseOnlyEdge(val dbClass: String, val unit: DbObjectId, val reference: DbDependency, val detail: String)

data class IndexOnlyEdge(val indexClass: String, val unit: DbObjectId, val target: Target, val locations: List<Location>)

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
    val builtins: Set<String>
)

/**
 * Compares the object-level edges of an index with the database's `ALL_DEPENDENCIES` (F0.4 §2). It reads the index
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
        val (runSchema, recordedRoot) = connection.query("SELECT compilation_schema, sources_root FROM run") { it.getString(1) to it.getString(2) }.single()
        val root = sourcesRoot ?: Path.of(recordedRoot).takeIf { Files.isDirectory(it) }

        // ---- units to objects (§2.1)
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

        // ---- entity projection (§2.2)
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
            val segments = row.key.removePrefix("db:~.").split('.')
            val (first, link) = segments[0].split('@', limit = 2).let { it[0] to it.getOrNull(1) }
            val target = if (root.name == first && root.byName) root else Target(null, first, null, link)
            val readings = if (segments.size >= 2) setOf(first to segments[1].substringBefore('@')) else emptySet()
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
        fun addEdge(unit: DbObjectId, target: Target, location: Location, collapsedSource: Boolean, readings: Set<Pair<String, String>> = emptySet()) {
            if (isSelf(unit, target)) return
            val edge = edges.getOrPut(unit) { HashMap() }.getOrPut(target) { IndexEdge(target) }
            edge.locations += location
            edge.readings += readings
            if (!collapsedSource) edge.fromCollapsedOnly = false
        }

        val candidates = connection.query("SELECT occurrence_id, entity_id FROM occurrence_candidate") { it.getLong(1) to it.getLong(2) }
            .groupBy({ it.first }, { it.second })
        connection.forEach(
            """SELECT o.id, o.unit_id, o.entity_id, o.status, o.written, f.path, o.start_line, o.reason, o.roles
               FROM occurrence o JOIN source_file f ON f.id = o.file_id ORDER BY o.id"""
        ) { rs ->
            val unit = unitsById.getValue(rs.getLong(2))
            val entityId = (rs.getObject(3) as Number?)?.toLong()
            val status = rs.getString(4)
            val location = Location(rs.getString(6), rs.getInt(7))
            val roles = rs.getString(9).split(',').filter { it.isNotEmpty() }.toSet()
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
                addEdge(obj, projected, location, false, readings)
            } else if (status == "AMBIGUOUS") {
                val roots = candidates[rs.getLong(1)].orEmpty().map { project(it) }.toSet()
                if (roots.size == 1) {
                    collapsed++
                    target = roots.single()
                    effectiveStatus = "COLLAPSED"
                    addEdge(obj, target, location, true)
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

        // ---- sources (§2.4)
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
                PlSqlText.isWrapped(db) -> SourceStatus.WRAPPED to null
                else -> PlSqlText.firstDifference(text, db)?.let { SourceStatus.DIFFERS to it } ?: (SourceStatus.SAME to null)
            }
        }
        val names = unitTexts.mapValues { PlSqlText.names(it.value) }

        // ---- matching and classification (§2.3)
        val matched = ArrayList<MatchedEdge>()
        val databaseOnly = ArrayList<DatabaseOnlyEdge>()
        val matchedIndex = HashSet<Pair<DbObjectId, Target>>()
        for (dep in dbEdges) {
            val obj = DbObjectId(dep.owner, dep.name, dep.type)
            val unitEdges = edges[obj].orEmpty()
            val exact = unitEdges.values.firstOrNull { it.target.owner == dep.referencedOwner && it.target.name == dep.referencedName && it.target.link == dep.referencedLink }
            val byName = exact ?: unitEdges.values.filter {
                it.target.byName && it.target.link == dep.referencedLink &&
                    (it.target.name == dep.referencedName || (dep.referencedOwner to dep.referencedName) in it.readings)
            }.minByOrNull { it.target }
            // A dependency on an object that does not exist is never an index edge (Oracle records it to invalidate the
            // unit when such an object appears, typically next to a synonym of the same name).
            val hit = if (dep.referencedType == "NON-EXISTENT") null else exact ?: byName
            if (hit != null) {
                matchedIndex += obj to hit.target
                matched += MatchedEdge(obj, dep, exact == null, hit.fromCollapsedOnly, hit.locations.toList())
                continue
            }
            val unitEvidence = evidence[obj].orEmpty().filter { dep.referencedName in it.segments && fits(dep.referencedType, it.roles) }
            val unresolved = unitEvidence.filter { it.status in UNRESOLVED_STATUSES }
            val elsewhere = unitEvidence.filter { it.target != null && !(it.target.owner == dep.referencedOwner && it.target.name == dep.referencedName) &&
                !(it.target.byName && it.target.name == dep.referencedName) }
            // Only RESOLVED evidence is a likely resolution bug; UNVERIFIED, EXTERNAL and collapsed guesses are guesses.
            val (resolvedElsewhere, guessedElsewhere) = elsewhere.partition { it.status == "RESOLVED" }
            fun describe(list: List<Evidence>) = list.map { "${it.location}->${it.target}(${it.status})" }.distinct().joinToString(" ")
            val (dbClass, detail) = when {
                dep.referencedOwner in BUILTIN_OWNERS && dep.referencedName in builtins -> "BUILTIN" to ""
                dep.referencedLink != null -> "REMOTE" to ""
                dep.referencedType == "NON-EXISTENT" -> "NON_EXISTENT" to ""
                sourceStatus[obj]?.first == SourceStatus.DIFFERS -> "SOURCE_DIFFERS" to ""
                names[obj] != null && dep.referencedName !in names.getValue(obj) -> "NAME_NOT_IN_TEXT" to ""
                unresolved.isNotEmpty() -> "UNRESOLVED_IN_INDEX" to unresolved.joinToString(" ") { "${it.location}(${it.status})" }
                resolvedElsewhere.isNotEmpty() -> "RESOLVED_ELSEWHERE" to describe(resolvedElsewhere)
                guessedElsewhere.isNotEmpty() -> "GUESSED_ELSEWHERE" to describe(guessedElsewhere)
                else -> "NOT_EXTRACTED" to ""
            }
            databaseOnly += DatabaseOnlyEdge(dbClass, obj, dep, detail)
        }
        val indexOnly = ArrayList<IndexOnlyEdge>()
        for ((obj, unitEdges) in edges.toSortedMap()) {
            if (obj !in compared) continue
            for (edge in unitEdges.values.sortedBy { it.target }) {
                if (obj to edge.target in matchedIndex) continue
                val indexClass = when {
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
            collapsed, database.sourceReadable, root != null, builtins
        )
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
        /** Built-in packages that every unit depends on (from `USER_DEPENDENCIES` on Oracle 26ai, see the F0.4 report). */
        val DEFAULT_BUILTINS = setOf("STANDARD", "DBMS_STANDARD", "PLITBLM", "SYS_STUB_FOR_PURITY_ANALYSIS", "DUAL")

        val BUILTIN_OWNERS = setOf("SYS", "PUBLIC")

        /** Unit kinds that are database objects, with their `ALL_OBJECTS.OBJECT_TYPE`. */
        val OBJECT_TYPES = mapOf(
            "PACKAGE" to "PACKAGE", "PACKAGE_BODY" to "PACKAGE BODY", "PROCEDURE" to "PROCEDURE", "FUNCTION" to "FUNCTION",
            "TRIGGER" to "TRIGGER", "TYPE" to "TYPE", "TYPE_BODY" to "TYPE BODY", "VIEW" to "VIEW"
        )

        private val ENTITY_TYPES = mapOf(
            "TABLE" to "TABLE", "VIEW" to "VIEW", "OBJECT_TABLE" to "TABLE", "PACKAGE" to "PACKAGE", "PROCEDURE" to "PROCEDURE",
            "FUNCTION" to "FUNCTION", "TYPE" to "TYPE", "SEQUENCE" to "SEQUENCE", "TRIGGER" to "TRIGGER"
        )

        private val EDGE_STATUSES = setOf("RESOLVED", "UNVERIFIED", "EXTERNAL")
        private val DECLARING_ROLES = setOf("DEFINITION", "DECLARATION")

        /** A definition or declaration only (an empty role set, dynamic SQL, is not). */
        private fun declaringOnly(roles: Set<String>) = roles.isNotEmpty() && roles.all { it in DECLARING_ROLES }
        private val RELATION_TYPES = setOf("TABLE", "VIEW", "MATERIALIZED VIEW")
        private val CALLABLE_TYPES = setOf("PACKAGE", "PROCEDURE", "FUNCTION", "TYPE", "OPERATOR")

        /** Whether evidence with [roles] can be about an object of [referencedType]: SQL roles for relations, CALL for callables. */
        private fun fits(referencedType: String, roles: Set<String>): Boolean = when (referencedType) {
            in RELATION_TYPES -> roles.any { it.startsWith("SQL_") }
            in CALLABLE_TYPES -> "CALL" in roles
            else -> true
        }
        private val UNRESOLVED_STATUSES = setOf("UNRESOLVED", "AMBIGUOUS", "PARTIAL_SOURCE")

        /** Reads a built-in list: one name per line, `#` starts a comment. */
        fun readBuiltins(text: String): Set<String> =
            text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.map { it.uppercase(Locale.ROOT) }.toSet()
    }
}
