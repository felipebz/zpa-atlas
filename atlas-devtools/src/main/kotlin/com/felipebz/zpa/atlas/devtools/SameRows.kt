//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.devtools

import org.sqlite.SQLiteConfig
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet

/**
 * `same-rows`: whether two indexes have the same rows in every table, every column compared, rows ordered by primary
 * key. The run timestamps (`run.started_at`, `run.finished_at`) are skipped and the acquisition time at the end of
 * `run.metadata_origin` (`… at <instant>`) is ignored: they differ between any two runs.
 */
object SameRows {
    private val SKIPPED = mapOf("run" to setOf("started_at", "finished_at"))
    private val ACQUIRED_AT = Regex(" at \\d{4}-\\d{2}-\\d{2}T[^ ]*$")

    /** One line per table (`same` or the first difference); [identical] tells whether every table matched. */
    class Result(val lines: List<String>, val identical: Boolean)

    fun compare(first: Path, second: Path): Result = open(first).use { a ->
        open(second).use { b ->
            val tablesA = tables(a)
            val tablesB = tables(b)
            val lines = ArrayList<String>()
            var identical = tablesA == tablesB
            if (!identical) lines += "tables differ: $tablesA vs $tablesB"
            for (table in tablesA.intersect(tablesB.toSet())) {
                val line = compareTable(a, b, table)
                if (!line.startsWith("same")) identical = false
                lines += "$table: $line"
            }
            Result(lines, identical)
        }
    }

    private fun compareTable(a: Connection, b: Connection, table: String): String {
        val columnsA = columns(a, table)
        val columnsB = columns(b, table)
        if (columnsA != columnsB) return "columns differ: ${columnsA.map { it.first }} vs ${columnsB.map { it.first }}"
        val selected = columnsA.map { it.first }.filter { it !in SKIPPED[table].orEmpty() }
        val order = columnsA.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }.ifEmpty { listOf("rowid") }
        val sql = "SELECT ${selected.joinToString(", ") { "\"$it\"" }} FROM \"$table\" ORDER BY ${order.joinToString(", ") { "\"$it\"" }}"
        val normalized = if (table == "run") setOf("metadata_origin") else emptySet()
        return a.createStatement().use { sa ->
            b.createStatement().use { sb ->
                sa.executeQuery(sql).use { ra -> sb.executeQuery(sql).use { rb -> compareRows(ra, rb, selected, normalized) } }
            }
        }
    }

    private fun compareRows(a: ResultSet, b: ResultSet, columns: List<String>, normalized: Set<String>): String {
        var rows = 0L
        while (true) {
            val hasA = a.next()
            val hasB = b.next()
            if (!hasA || !hasB) {
                return if (hasA == hasB) "same ($rows rows)" else "row count differs after $rows rows"
            }
            rows++
            for ((index, column) in columns.withIndex()) {
                val va = a.getObject(index + 1).let { if (column in normalized && it is String) it.replace(ACQUIRED_AT, "") else it }
                val vb = b.getObject(index + 1).let { if (column in normalized && it is String) it.replace(ACQUIRED_AT, "") else it }
                val equal = if (va is ByteArray && vb is ByteArray) va.contentEquals(vb) else va == vb
                if (!equal) return "row $rows differs in $column: $va vs $vb"
            }
        }
    }

    private fun tables(connection: Connection): List<String> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }

    /** Column name and position in the primary key (0 when not part of it), in declaration order. */
    private fun columns(connection: Connection, table: String): List<Pair<String, Int>> = connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info(\"$table\")").use { rs ->
            buildList { while (rs.next()) add(rs.getString("name") to rs.getInt("pk")) }
        }
    }

    private fun open(path: Path): Connection =
        SQLiteConfig().apply { setReadOnly(true) }.createConnection("jdbc:sqlite:${path.toAbsolutePath()}")
}
