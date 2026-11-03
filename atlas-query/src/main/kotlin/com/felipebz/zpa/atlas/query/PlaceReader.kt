//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import java.sql.Connection

/**
 * The places a column's value is read into in PL/SQL (`column_place`): those of the column itself and, marked, those of
 * the view columns computed from it, through every level of views, the way "who uses" follows views.
 */
internal class PlaceReader(private val connection: Connection) {
    /** The class of each place, in the order of [CLASS_ORDER]. */
    fun counts(columnId: Long): List<Count> {
        val counts = connection.query("$SCOPE SELECT p.class, count(*) FROM column_place p WHERE p.column_id IN (SELECT id FROM viewed) GROUP BY p.class",
            listOf(columnId)) { it.getString(1) to it.getLong(2) }.toMap()
        return CLASS_ORDER.mapNotNull { name -> counts[name]?.let { Count(name, it) } }
    }

    /** A page of the places of the column [key]: [limit] from [offset]; null when there is no such entity. */
    fun page(key: String, offset: Int, limit: Int): ColumnPlacePage? {
        val id = connection.query("SELECT id FROM entity WHERE key = ?", listOf(key)) { it.getLong(1) }.singleOrNull() ?: return null
        val counts = counts(id)
        val rows = connection.query(
            """$SCOPE SELECT r.key, r.kind, r.display, r.external, r.name, p.column_id <> ?, p.source, p.bulk, f.path, p.start_line, p.start_col,
                      c.key, c.kind, c.display, c.external, c.name, p.target_written,
                      t.key, t.kind, t.display, t.external, t.name, p.field, p.class, p.declared_type, p.length, p.length_unit,
                      s.key, s.kind, s.display, s.external, s.name, p.reason, p.scale, p.parameter_mode, p.functions,
                      a.key, a.kind, a.display, a.external, a.name, g.key, g.kind, g.display, g.external, g.name,
                      p.call_count, p.call_path, p.call_line, p.concatenated
               FROM column_place p JOIN entity r ON r.id = p.column_id JOIN source_file f ON f.id = p.file_id
               LEFT JOIN entity c ON c.id = p.container_entity_id LEFT JOIN entity t ON t.id = p.target_entity_id
               LEFT JOIN entity s ON s.id = p.type_source_id LEFT JOIN entity a ON a.id = p.argument_parameter_id
               LEFT JOIN entity g ON g.id = p.argument_routine_id
               WHERE p.column_id IN (SELECT id FROM viewed)
               ORDER BY $CLASS_RANK, f.path, p.start_line, p.start_col, p.id LIMIT ? OFFSET ?""",
            listOf(id, id, limit, offset)
        ) { rs ->
            fun summary(first: Int) = rs.getString(first)?.let { EntitySummary(it, rs.getString(first + 1), rs.getString(first + 2), rs.getInt(first + 3) == 1, rs.getString(first + 4)) }
            ColumnPlace(
                summary(1)!!, rs.getInt(6) == 1, rs.getString(7), rs.getInt(8) == 1, rs.getString(9), rs.getInt(10), rs.getInt(11),
                summary(12), rs.getString(17), summary(18), rs.getString(23), rs.getString(24), rs.getString(25),
                (rs.getObject(26) as Number?)?.toInt(), rs.getString(27), summary(28), rs.getString(33),
                (rs.getObject(34) as Number?)?.toInt(), rs.getString(35),
                rs.getString(36)?.split(',').orEmpty(), summary(37), summary(42),
                (rs.getObject(47) as Number?)?.toInt(), rs.getString(48), (rs.getObject(49) as Number?)?.toInt(), rs.getInt(50) == 1
            )
        }
        val total = counts.sumOf { it.count }.toInt()
        return ColumnPlacePage(total, counts, offset, rows, (offset + rows.size).takeIf { it < total })
    }

    /** The columns whose value is read into the entity [targetId], or into one of its fields. */
    fun receives(targetId: Long): List<EntitySummary> = connection.query(
        """SELECT DISTINCT e.key, e.kind, e.display, e.external, e.name FROM column_place p JOIN entity e ON e.id = p.column_id
           WHERE p.target_entity_id = ? ORDER BY e.key""", listOf(targetId), ::entitySummary
    )

    companion object {
        /** The classes in the order a page lists them. */
        val CLASS_ORDER = listOf("FIXED_SIZE", "UNKNOWN", "CALLER_DECIDES", "ANCHORED_ELSEWHERE", "NO_SIZE", "ANCHORED_TO_COLUMN")

        private val CLASS_RANK = "CASE p.class " + CLASS_ORDER.withIndex().joinToString(" ") { (rank, name) -> "WHEN '$name' THEN $rank" } + " END"

        /** The column `?` and the view columns computed from it, transitively (`VIEW_BASE` edges, one hop per view). */
        private const val SCOPE = """WITH RECURSIVE viewed(id) AS (
            SELECT ? UNION SELECT r.from_id FROM relation r JOIN viewed v ON r.to_id = v.id AND r.kind = 'VIEW_BASE')"""
    }
}
