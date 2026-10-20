//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.compare

import java.sql.Connection
import java.sql.ResultSet
import java.util.Locale

internal class IndexUnit(val id: Long, val kind: String, val name: String, val path: String, val encoding: String, val span: IntArray, val obj: DbObjectId?)

internal class Evidence(val segments: List<String>, val status: String, val target: Target?, val location: Location, val roles: Set<String>)

internal class IndexEdge(val target: Target) {
    val locations = sortedSetOf<Location>()
    /** `S1.S2` readings of unknown-schema names (`db:~.S1.S2…`): `S1` may be a schema the run does not know. */
    val readings = HashSet<Pair<String, String>>()
    var fromCollapsedOnly = true
    /** Every occurrence behind the edge only names its target (`TYPE_USE`). */
    var typeUseOnly = true
}

internal class EntityRow(val key: String, val kind: String, val schema: String?, val name: String, val parent: Long?)

/** What the index says about the units: their objects, their edges, the evidence behind them, and the synonyms' targets. */
internal class IndexSide(
    val units: List<IndexUnit>,
    val edges: HashMap<DbObjectId, MutableMap<Target, IndexEdge>>,
    val evidence: HashMap<DbObjectId, MutableList<Evidence>>,
    val dynamicCount: HashMap<DbObjectId, Int>,
    val partialCount: HashMap<DbObjectId, Int>,
    val scriptEdges: Int,
    val collapsed: Int,
    /** (owner, name) -> the projection of the final target of its SYNONYM_FOR chain. */
    val synonymTargets: HashMap<Pair<String, String>, Target>
)

/** The index side of the comparison, units owned by their written owner, their file's schema, [runSchema] or [defaultOwner]. */
internal fun readIndexSide(connection: Connection, runSchema: String?, defaultOwner: String?): IndexSide {
    val units = mapUnits(connection, runSchema, defaultOwner)
    val unitsById = units.associateBy { it.id }

    // ---- entity projection
    val entities = connection.query("SELECT id, key, kind, schema_name, name, parent_id FROM entity") { rs ->
        rs.getLong(1) to EntityRow(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), (rs.getObject(6) as Number?)?.toLong())
    }.toMap()
    val projection = Projection(entities)

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
            val (projected, readings) = projection.projectOccurrence(entityId)
            target = projected
            addEdge(obj, projected, location, false, readings, roles)
        } else if (status == "AMBIGUOUS") {
            val roots = candidates[rs.getLong(1)].orEmpty().map { projection.project(it) }.toSet()
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
        addEdge(obj, projection.project(rs.getLong(1)), Location(rs.getString(3), rs.getInt(4)), false)
    }

    // A package body or a type body depends on its specification: the unit's own entity, as Oracle compiles it.
    connection.forEach(
        """SELECT u.id, u.entity_id, f.path, u.start_line FROM unit u JOIN source_file f ON f.id = u.file_id
           WHERE u.kind IN ('PACKAGE_BODY', 'TYPE_BODY') AND u.entity_id IS NOT NULL"""
    ) { rs ->
        val obj = unitsById.getValue(rs.getLong(1)).obj ?: return@forEach
        addEdge(obj, projection.project(rs.getLong(2)), Location(rs.getString(3), rs.getInt(4)), false)
    }

    return IndexSide(units, edges, evidence, dynamicCount, partialCount, scriptEdges, collapsed, synonymTargets(connection, entities, projection))
}

/** The units of the index, each mapped to its database object when it is one; a unit without any owner is an error. */
private fun mapUnits(connection: Connection, runSchema: String?, defaultOwner: String?): List<IndexUnit> {
    val missingOwner = ArrayList<String>()
    val units = connection.query(
        """SELECT u.id, u.kind, u.name, f.path, f.compilation_schema, u.start_line, u.start_col, u.end_line, u.end_col, f.encoding
           FROM unit u JOIN source_file f ON f.id = u.file_id ORDER BY u.id"""
    ) { rs ->
        val kind = rs.getString(2)
        val name = rs.getString(3)
        val type = DependencyComparison.OBJECT_TYPES[kind]
        val obj = if (type == null || name.isEmpty()) null else {
            val segments = name.split('.')
            val owner = if (segments.size >= 2) segments.first() else rs.getString(5) ?: runSchema ?: defaultOwner
            if (owner == null) { missingOwner += "${rs.getString(4)}: $kind $name"; null } else DbObjectId(owner, segments.last(), type)
        }
        IndexUnit(rs.getLong(1), kind, name, rs.getString(4), rs.getString(10), intArrayOf(rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9)), obj)
    }
    if (missingOwner.isNotEmpty()) {
        throw IllegalArgumentException("Units without owner (no written owner, no compilation schema, no --default-owner):\n" +
            missingOwner.sorted().joinToString("\n"))
    }
    return units
}

/** The object an entity projects to, cached per entity. */
private class Projection(private val entities: Map<Long, EntityRow>) {
    private val projections = HashMap<Long, Target>()

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
}

// ---- synonyms of the index: (owner, name) -> the projection of the final target of its SYNONYM_FOR chain
private fun synonymTargets(connection: Connection, entities: Map<Long, EntityRow>, projection: Projection): HashMap<Pair<String, String>, Target> {
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
        if (current !in synonymFor) synonymTargets[row.schema to row.name] = projection.project(current)
    }
    return synonymTargets
}

/** Name segments of a written reference, normalized; a DB link (`@link`) is removed. */
private fun segments(written: String): List<String> =
    written.substringBefore('@').split('.').filter { it.isNotEmpty() }.map {
        if (it.length >= 2 && it.startsWith('"') && it.endsWith('"')) it.substring(1, it.length - 1) else it.uppercase(Locale.ROOT)
    }

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
internal fun declaringOnly(roles: Set<String>) =
    roles.any { it in DECLARING_ROLES } && roles.all { it in DECLARING_ROLES || it == "IMPLICIT" }

internal fun <T> Connection.query(sql: String, map: (ResultSet) -> T): List<T> =
    createStatement().use { s -> s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(map(rs)) } } }

internal fun Connection.forEach(sql: String, action: (ResultSet) -> kotlin.Unit) =
    createStatement().use { s -> s.executeQuery(sql).use { rs -> while (rs.next()) action(rs) } }
