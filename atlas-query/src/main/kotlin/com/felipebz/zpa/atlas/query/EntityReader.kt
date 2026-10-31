//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import java.sql.Connection
import java.sql.ResultSet

/** Entity search and the entity page, over one open connection. */
internal class EntityReader(private val connection: Connection) {

    /** [AtlasIndex.searchEntities] after its checks: [text] trimmed, split into [segments]. */
    fun searchEntities(text: String, segments: List<String>, kind: String?, includeExternal: Boolean, limit: Int): List<SearchHit> {
        fun search(joins: String, match: String, parameters: List<Any?>) = connection.query(
            """SELECT e.key, e.kind, e.display, e.external,
                      (SELECT count(*) FROM occurrence o WHERE o.entity_id = e.id AND o.roles NOT IN ('DEFINITION', 'DECLARATION')), e.name
               FROM entity e $joins
               WHERE $match ${if (kind != null) "AND e.kind = ?" else ""} ${if (includeExternal) "" else "AND e.external = 0"}
               ORDER BY CASE WHEN e.name = ? COLLATE NOCASE THEN 0 ELSE 1 END, e.external, e.name COLLATE NOCASE, e.display COLLATE NOCASE, e.key
               LIMIT ?""",
            parameters + listOfNotNull(kind, text, limit)
        ) { SearchHit(it.getString(1), it.getString(2), it.getString(3), it.getInt(4) == 1, it.getLong(5), it.getString(6)) }

        if (segments.size == 1) return search("", "e.name LIKE ? ESCAPE '\\'", listOf("${escapeLike(text)}%"))
        val structural = if (segments.size > OccurrenceQuerySql.MAX_SEGMENTS || segments.any { it.isEmpty() }) {
            emptyList()
        } else {
            // The last segment is a name prefix, the others name the parents exactly.
            val (joins, match, parameters) = OccurrenceQuerySql.parentChain("e", segments) { column, segment, last ->
                if (last) "$column LIKE ? ESCAPE '\\'" to listOf("${escapeLike(segment)}%") else "$column = ? COLLATE NOCASE" to listOf(segment)
            }
            search(joins, match, parameters)
        }
        return structural.ifEmpty { search("", "e.display LIKE ? ESCAPE '\\'", listOf("%${escapeLike(text)}%")) }
    }

    fun entity(key: String): EntityDetail? {
        val row = connection.query(
            "SELECT id, key, kind, schema_name, name, display, external, parent_id FROM entity WHERE key = ?", listOf(key)
        ) { Entity(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getInt(7) == 1, it.getObject(8) as Number?) }
            .singleOrNull() ?: return null
        val parent = row.parentId?.let { summary(connection, it.toLong()) }
        val ancestors = connection.query(
            """WITH RECURSIVE up(id, depth) AS (
                   SELECT parent_id, 1 FROM entity WHERE id = ? AND parent_id IS NOT NULL
                   UNION ALL
                   SELECT e.parent_id, up.depth + 1 FROM up JOIN entity e ON e.id = up.id WHERE e.parent_id IS NOT NULL)
               SELECT e.key, e.kind, e.display, e.external, e.name FROM up JOIN entity e ON e.id = up.id ORDER BY up.depth DESC""",
            listOf(row.id), ::entitySummary
        )
        val childRows = connection.query(
            """SELECT c.key, c.kind, c.display, c.external, c.name,
                      (SELECT count(*) FROM occurrence o WHERE o.entity_id = c.id AND o.roles NOT IN ('DEFINITION', 'DECLARATION')), c.id
               FROM relation r JOIN entity c ON c.id = r.to_id
               WHERE r.from_id = ? AND r.kind = 'CONTAINS' ORDER BY c.kind, c.name COLLATE NOCASE, c.key""", listOf(row.id)
        ) { Triple(entitySummary(it), it.getLong(6), it.getLong(7)) }
        val declared = declared(childRows.map { it.third } + row.id)
        val children = childRows.map { (entity, usages, id) -> Member(entity, usages, declared[id]) }
        // VIEW_BASE edges have their own lists (baseColumns, viewColumns).
        val relations = connection.query(
            """SELECT r.kind, 'OUT', t.key, t.kind, t.display, t.external, t.name FROM relation r JOIN entity t ON t.id = r.to_id
               WHERE r.from_id = ? AND r.kind NOT IN ('CONTAINS', 'VIEW_BASE')
               UNION ALL
               SELECT r.kind, 'IN', f.key, f.kind, f.display, f.external, f.name FROM relation r JOIN entity f ON f.id = r.from_id
               WHERE r.to_id = ? AND r.kind NOT IN ('CONTAINS', 'VIEW_BASE')
               ORDER BY 1, 2, 3""", listOf(row.id, row.id)
        ) { RelationLink(it.getString(1), it.getString(2), EntitySummary(it.getString(3), it.getString(4), it.getString(5), it.getInt(6) == 1, it.getString(7))) }
        val definitions = definitions("o.entity_id = ?", row.id)
        val partners = relations.filter { it.kind == "IMPLEMENTS" }.map { Partner(it.entity, it.direction, definitions(it.entity.key)) }
        val overloads = if (row.kind == "PROCEDURE" || row.kind == "FUNCTION") {
            connection.query(
                """SELECT key, kind, display, external, name FROM entity
                   WHERE parent_id IS ? AND name = ? AND kind = ? ORDER BY key""", listOf(row.parentId, row.name, row.kind), ::entitySummary
            ).map { Overload(it, isBody(it.key), it.key == row.key) }
        } else {
            emptyList()
        }
        val usages = connection.query(
            "SELECT roles, status FROM occurrence WHERE entity_id = ? AND roles NOT IN ('DEFINITION', 'DECLARATION')", listOf(row.id)
        ) { it.getString(1) to it.getString(2) }
        val asCandidate = connection.query("SELECT count(*) FROM occurrence_candidate WHERE entity_id = ?", listOf(row.id)) { it.getLong(1) }.single()
        return EntityDetail(
            row.key, row.kind, row.schema, row.name, row.display, row.external, parent, ancestors, children, relations, definitions, overloads, partners,
            UsageSummary(tally(usages.flatMap { roles(it.first) }), tally(usages.map { it.second }), asCandidate),
            triggerEvents(row.id, row.kind, row.parentId?.toLong()),
            if (row.kind in VIEW_KINDS) viewReads(row.id) else emptyList(),
            if (row.kind in VIEWED_KINDS) viewsReading(row.id) else emptyList(),
            if (row.kind == "COLUMN") viewBase(row.id, outgoing = true) else emptyList(),
            if (row.kind == "COLUMN") viewBase(row.id, outgoing = false) else emptyList(),
            declared[row.id]
        )
    }

    /**
     * What the declarations of [ids] say ([Declared.of]), with the spec's text for a body: the spec a routine body
     * `IMPLEMENTS`, or for a body's parameter, the parameter of the same name of that spec.
     */
    private fun declared(ids: List<Long>): Map<Long, Declared> = connection.query(
        """SELECT e.id, d.datatype, d.value, d.value_cut, sd.entity_id, sd.datatype, sd.value, sd.value_cut
           FROM entity e
           LEFT JOIN declared_text d ON d.entity_id = e.id
           LEFT JOIN declared_text sd ON sd.entity_id = CASE WHEN e.kind = 'PARAMETER'
               THEN (SELECT p.id FROM relation r JOIN entity p ON p.parent_id = r.to_id AND p.kind = 'PARAMETER' AND p.name = e.name
                     WHERE r.from_id = e.parent_id AND r.kind = 'IMPLEMENTS' ORDER BY p.id LIMIT 1)
               ELSE (SELECT r.to_id FROM relation r WHERE r.from_id = e.id AND r.kind = 'IMPLEMENTS' ORDER BY r.to_id LIMIT 1) END
           WHERE e.id IN (SELECT value FROM json_each(?)) AND (d.entity_id IS NOT NULL OR sd.entity_id IS NOT NULL)""",
        listOf(ids.joinToString(",", "[", "]"))
    ) { rows ->
        val own = DeclaredText(rows.getString(2), rows.getString(3), rows.getInt(4) == 1)
        val spec = rows.getObject(5)?.let { DeclaredText(rows.getString(6), rows.getString(7), rows.getInt(8) == 1) }
        rows.getLong(1) to Declared.of(own, spec)
    }.mapNotNull { (id, declared) -> declared?.let { id to it } }.toMap()

    /** The definitions and declarations of the entity [key], alone; empty for an unknown key. */
    fun definitions(key: String): List<Location> = definitions("o.entity_id = (SELECT id FROM entity WHERE key = ?)", key)

    private fun definitions(entity: String, parameter: Any): List<Location> = connection.query(
        """SELECT f.path, o.start_line, o.start_col, o.end_line, o.end_col, o.roles, o.status, o.written, u.kind
           FROM occurrence o JOIN source_file f ON f.id = o.file_id LEFT JOIN unit u ON u.id = o.unit_id
           WHERE $entity AND o.roles IN ('DEFINITION', 'DECLARATION') ORDER BY o.id""", listOf(parameter)
    ) { Location(it.getString(1), span(it, 2), roles(it.getString(6)), it.getString(7), it.getString(8), it.getString(9)) }

    private class Entity(val id: Long, val key: String, val kind: String, val schema: String?, val name: String, val display: String,
                         val external: Boolean, val parentId: Number?)

    /** One hop of `VIEW_BASE` edges of a column: the columns it comes from ([outgoing]), or the view columns coming from it. */
    private fun viewBase(columnId: Long, outgoing: Boolean): List<EntitySummary> {
        val (near, far) = if (outgoing) "from_id" to "to_id" else "to_id" to "from_id"
        return connection.query(
            """SELECT e.key, e.kind, e.display, e.external, e.name FROM relation r JOIN entity e ON e.id = r.$far
               WHERE r.$near = ? AND r.kind = 'VIEW_BASE' ORDER BY e.key""", listOf(columnId), ::entitySummary
        )
    }

    // Both derive from the SQL_SELECT occurrences: no relation rows are stored for them.
    private fun viewReads(viewId: Long): List<EntitySummary> = connection.query(
        """SELECT DISTINCT e.key, e.kind, e.display, e.external, e.name FROM occurrence o JOIN entity e ON e.id = o.entity_id
           WHERE o.container_entity_id = ? AND (',' || o.roles || ',') LIKE '%,SQL_SELECT,%' ORDER BY e.key""", listOf(viewId), ::entitySummary
    )

    private fun viewsReading(relationId: Long): List<EntitySummary> = connection.query(
        """SELECT DISTINCT v.key, v.kind, v.display, v.external, v.name FROM occurrence o JOIN entity v ON v.id = o.container_entity_id
           WHERE o.entity_id = ? AND v.kind IN ($VIEW_KINDS_SQL) AND (',' || o.roles || ',') LIKE '%,SQL_SELECT,%' ORDER BY v.key""", listOf(relationId), ::entitySummary
    )

    /** The trigger events [EntityDetail.triggerEvents] lists for an entity of [kind]. */
    private fun triggerEvents(id: Long, kind: String, parentId: Long?): List<TriggerEvent> {
        val select = """SELECT t.key, t.kind, t.display, t.external, e.timing, e.level, e.event, c.key, c.kind, c.display, c.external, t.name, c.name
               FROM trigger_event e JOIN entity t ON t.id = e.trigger_id LEFT JOIN entity c ON c.id = e.column_id"""
        val order = " ORDER BY t.key, e.event, e.timing, c.key"
        val map = { rs: ResultSet ->
            TriggerEvent(EntitySummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1, rs.getString(12)), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8)?.let { EntitySummary(it, rs.getString(9), rs.getString(10), rs.getInt(11) == 1, rs.getString(13)) })
        }
        return when (kind) {
            "TRIGGER" -> connection.query("$select WHERE e.trigger_id = ?$order", listOf(id), map)
            "TABLE", "VIEW", "MATERIALIZED_VIEW", "OBJECT_TABLE", "UNKNOWN_RELATION" -> connection.query(
                "$select WHERE e.trigger_id IN (SELECT from_id FROM relation WHERE kind = 'TRIGGER_ON' AND to_id = ?)$order", listOf(id), map)
            // UPDATE OF this column, or an UPDATE without column list of a trigger on the column's relation. A NULL column
            // is also an unresolved UPDATE OF column: a trigger with a resolved one has a column list.
            "COLUMN" -> if (parentId == null) emptyList() else connection.query(
                """$select WHERE e.event = 'UPDATE' AND (e.column_id = ? OR (e.column_id IS NULL
                     AND e.trigger_id IN (SELECT from_id FROM relation WHERE kind = 'TRIGGER_ON' AND to_id = ?)
                     AND NOT EXISTS (SELECT 1 FROM trigger_event o WHERE o.trigger_id = e.trigger_id AND o.event = 'UPDATE' AND o.column_id IS NOT NULL)))$order""",
                listOf(id, parentId), map)
            else -> emptyList()
        }
    }

    private fun escapeLike(text: String) = OccurrenceQuerySql.escapeLike(text)
}
