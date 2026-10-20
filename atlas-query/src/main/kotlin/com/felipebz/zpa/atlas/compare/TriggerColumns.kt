//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.compare

import java.sql.Connection

/**
 * The `ALL_TRIGGER_COLS` rows against the index. Every database row and every index facet of a trigger with an
 * `ALL_OBJECTS` row is classified: [TriggerColumnFact.group] `COMPARED` (a unit of the index), `SOURCE_DIFFERS`
 * (a unit whose stored source differs) or `NO_UNIT` (a database trigger the index does not have).
 */
internal fun triggerColumns(connection: Connection, database: DatabaseDependencies, units: Set<DbObjectId>, differing: Set<DbObjectId>): List<TriggerColumnFact> {
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
