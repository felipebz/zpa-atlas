//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import org.sqlite.ProgressHandler
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration

private const val PROGRESS_STEPS = 1000

/**
 * One read-only connection for one request; the run stamp is read and the schema version checked first. With
 * [timed], the body is interrupted once [queryTimeout] has passed: `Statement.setQueryTimeout` does not stop a
 * statement that is running, a progress handler does (SQLite calls it every [PROGRESS_STEPS] virtual machine steps).
 */
internal fun <T> readIndex(database: Path, queryTimeout: Duration, timed: Boolean, body: (Connection, RunStamp) -> T): Answer<T> {
    val config = SQLiteConfig().apply { setReadOnly(true) }
    config.createConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
        connection.createStatement().use { it.execute("PRAGMA query_only = 1") }
        val (version, stamp) = connection.query(
            "SELECT schema_version, finished_at, atlas_version, zpa_version, compilation_schema, metadata_origin FROM run"
        ) { it.getInt(1) to RunStamp(it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6)) }.single()
        if (version != AtlasIndex.SCHEMA_VERSION) throw SchemaMismatchException(version, AtlasIndex.SCHEMA_VERSION)
        if (!timed) return Answer(stamp, body(connection, stamp))
        val deadline = System.nanoTime() + queryTimeout.toNanos()
        ProgressHandler.setHandler(connection, PROGRESS_STEPS, object : ProgressHandler() {
            override fun progress(): Int = if (System.nanoTime() > deadline) 1 else 0
        })
        try {
            return Answer(stamp, body(connection, stamp))
        } catch (e: SQLiteException) {
            if (e.resultCode == SQLiteErrorCode.SQLITE_INTERRUPT) throw QueryTimeoutException(queryTimeout.toMillis() / 1000.0)
            throw e
        }
    }
}

/**
 * The run of the index: its stamp, the counts of files, occurrences and coverage notes, and the files that did not
 * parse cleanly (the first [shown] by path, and their number).
 */
internal fun runSummary(connection: Connection, shown: Int = RunSummary.PARSE_PROBLEMS_SHOWN): RunSummary {
    val (version, root, started) = connection.query("SELECT schema_version, sources_root, started_at FROM run") {
        Triple(it.getInt(1), it.getString(2), it.getString(3))
    }.single()
    val files = counts(connection, "SELECT parse_status, count(*) FROM source_file GROUP BY parse_status ORDER BY parse_status")
    val problems = connection.query(
        "SELECT path, parse_status FROM source_file WHERE parse_status <> 'OK' ORDER BY path LIMIT ?", listOf(shown)
    ) { FileParse(it.getString(1), it.getString(2)) }
    return RunSummary(
        version, root, started, files,
        counts(connection, "SELECT status, count(*) FROM occurrence GROUP BY status ORDER BY status"),
        counts(connection, "SELECT kind, count(*) FROM coverage_note GROUP BY kind ORDER BY kind"),
        problems, files.filter { it.value != "OK" }.sumOf { it.count }
    )
}

/**
 * The project's own packages most used from outside, at most [limit]: the uses of the package and of every entity it
 * holds (spec and body members) that sit outside the package, in another unit or in a script in no unit; a use inside
 * the package's spec or body (a nested routine included) does not count, nor do definitions and declarations. A package
 * with no use from outside is left out; ties by key. Each with its member of most uses from outside, counted the same way.
 */
internal fun topPackages(connection: Connection, limit: Int): List<PackageUses> {
    // An occurrence is outside the package when its unit is not one of the package's (spec or body) or it has no unit.
    val outside = "o.roles NOT IN ('DEFINITION', 'DECLARATION') AND (u.entity_id IS NULL OR u.entity_id <> %s)"
    val packages = connection.query(
        """WITH member AS (SELECT p.id AS package_id, p.id AS entity_id FROM entity p WHERE p.kind = 'PACKAGE' AND p.external = 0
                           UNION ALL
                           SELECT p.id, m.id FROM entity p JOIN entity m ON m.parent_id = p.id WHERE p.kind = 'PACKAGE' AND p.external = 0)
           SELECT p.key, p.kind, p.display, p.external, p.name, count(*), p.id
           FROM member m JOIN occurrence o ON o.entity_id = m.entity_id LEFT JOIN unit u ON u.id = o.unit_id
           JOIN entity p ON p.id = m.package_id
           WHERE ${outside.format("m.package_id")}
           GROUP BY p.id ORDER BY 6 DESC, p.key LIMIT ?""", listOf(limit)
    ) { Triple(entitySummary(it), it.getLong(6), it.getLong(7)) }
    return packages.map { (entity, uses, id) ->
        val top = connection.query(
            """SELECT m.key, m.kind, m.display, m.external, m.name, count(*)
               FROM entity m JOIN occurrence o ON o.entity_id = m.id LEFT JOIN unit u ON u.id = o.unit_id
               WHERE m.parent_id = ? AND ${outside.format("m.parent_id")}
               GROUP BY m.id ORDER BY 6 DESC, m.key LIMIT 1""", listOf(id)
        ) { entitySummary(it) to it.getLong(6) }.singleOrNull()
        PackageUses(entity, uses, top?.first, top?.second ?: 0)
    }
}

private fun counts(connection: Connection, sql: String) = connection.query(sql) { Count(it.getString(1), it.getLong(2)) }

/** Rows of [select] (whose first column is the id) for [ids], read in chunks, by id. */
internal fun <T> byIds(connection: Connection, ids: Collection<Long>, select: String, idColumn: String = "id", map: (ResultSet) -> T): Map<Long, T> {
    val result = HashMap<Long, T>()
    for (chunk in ids.chunked(500)) {
        connection.query("$select WHERE $idColumn IN (${chunk.joinToString(",") { "?" }})", chunk) { it.getLong(1) to map(it) }
            .forEach { result[it.first] = it.second }
    }
    return result
}

internal fun <T> Connection.query(sql: String, parameters: List<Any?> = emptyList(), map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(parameters)
        statement.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

internal fun PreparedStatement.bind(parameters: List<Any?>) {
    parameters.forEachIndexed { index, value ->
        when (value) {
            null -> setObject(index + 1, null)
            is Boolean -> setInt(index + 1, if (value) 1 else 0)
            is Int -> setInt(index + 1, value)
            is Long -> setLong(index + 1, value)
            is Number -> setLong(index + 1, value.toLong())
            else -> setString(index + 1, value.toString())
        }
    }
}
