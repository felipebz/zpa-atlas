//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.devtools

import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.query.Scope
import org.sqlite.SQLiteConfig
import java.util.Locale

/**
 * `timings`: the query API on the 5 entities with the most non-definition occurrences, and on the column with the most
 * view columns over it.
 */
object Timings {
    fun report(index: AtlasIndex): String = buildString {
        append("Timings (first page, ms)\n")
        val keys = topKeys(index)
        if (keys.isEmpty()) append("  (none)\n")
        for ((key, count) in keys) {
            val self = time { index.occurrences(key, scope = setOf(Scope.SELF)) }
            val wide = time { index.occurrences(key, scope = setOf(Scope.SELF, Scope.IMPLEMENTATIONS, Scope.CHILDREN, Scope.VIEWS)) }
            val entity = time { index.entity(key) }
            append(String.format(Locale.ROOT, "  %s (%d occurrences): SELF %.1f, IMPLEMENTATIONS,CHILDREN,VIEWS %.1f, entity %.1f%n", key, count, self, wide, entity))
        }
        append('\n')
        append("Timings of the column with the most view columns over it (first page, ms)\n")
        val viewed = mostViewedColumn(index)
        if (viewed == null) {
            append("  (none)\n")
        } else {
            val (key, count) = viewed
            val self = time { index.occurrences(key, scope = setOf(Scope.SELF)) }
            val views = time { index.occurrences(key, scope = setOf(Scope.SELF, Scope.VIEWS)) }
            append(String.format(Locale.ROOT, "  %s (%d view columns): SELF %.1f, SELF,VIEWS %.1f%n", key, count, self, views))
        }
        append('\n')
    }

    private fun topKeys(index: AtlasIndex): List<Pair<String, Long>> =
        query(index, """SELECT e.key, count(*) AS n FROM occurrence o JOIN entity e ON e.id = o.entity_id
                       WHERE o.roles NOT IN ('DEFINITION', 'DECLARATION') GROUP BY e.key ORDER BY n DESC, e.key LIMIT 5""")

    /**
     * The column reached, through `VIEW_BASE` relations followed transitively, by the most distinct view columns; ties
     * go to the smallest key. Null when the index has no `VIEW_BASE` rows.
     */
    private fun mostViewedColumn(index: AtlasIndex): Pair<String, Long>? =
        query(index, """WITH RECURSIVE reach(origin, col) AS (
                         SELECT to_id, from_id FROM relation WHERE kind = 'VIEW_BASE'
                         UNION
                         SELECT reach.origin, r.from_id FROM reach JOIN relation r ON r.to_id = reach.col AND r.kind = 'VIEW_BASE')
                       SELECT e.key, count(DISTINCT reach.col) AS n FROM reach JOIN entity e ON e.id = reach.origin
                       GROUP BY e.key ORDER BY n DESC, e.key LIMIT 1""").singleOrNull()

    private fun query(index: AtlasIndex, sql: String): List<Pair<String, Long>> =
        SQLiteConfig().apply { setReadOnly(true) }.createConnection("jdbc:sqlite:${index.database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getLong(2)) } }
            }
        }

    private fun time(block: () -> Any?): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000.0
    }
}
