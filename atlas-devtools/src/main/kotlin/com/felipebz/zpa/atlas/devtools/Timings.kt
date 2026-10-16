package com.felipebz.zpa.atlas.devtools

import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.query.Scope
import org.sqlite.SQLiteConfig
import java.util.Locale

/** `timings`: the query API on the 5 entities with the most non-definition occurrences. */
object Timings {
    fun report(index: AtlasIndex): String = buildString {
        append("Timings (first page, ms)\n")
        val keys = topKeys(index)
        if (keys.isEmpty()) append("  (none)\n")
        for ((key, count) in keys) {
            val self = time { index.occurrences(key, scope = setOf(Scope.SELF)) }
            val wide = time { index.occurrences(key, scope = setOf(Scope.SELF, Scope.IMPLEMENTATIONS, Scope.CHILDREN)) }
            val entity = time { index.entity(key) }
            append(String.format(Locale.ROOT, "  %s (%d occurrences): SELF %.1f, IMPLEMENTATIONS,CHILDREN %.1f, entity %.1f%n", key, count, self, wide, entity))
        }
        append('\n')
    }

    private fun topKeys(index: AtlasIndex): List<Pair<String, Long>> =
        SQLiteConfig().apply { setReadOnly(true) }.createConnection("jdbc:sqlite:${index.database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """SELECT e.key, count(*) AS n FROM occurrence o JOIN entity e ON e.id = o.entity_id
                       WHERE o.roles NOT IN ('DEFINITION', 'DECLARATION') GROUP BY e.key ORDER BY n DESC, e.key LIMIT 5"""
                ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getLong(2)) } }
            }
        }

    private fun time(block: () -> Any?): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000.0
    }
}
