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

/** The run of the index: its stamp, and the counts of files, occurrences and coverage notes. */
internal fun runSummary(connection: Connection): RunSummary {
    val (version, root, started) = connection.query("SELECT schema_version, sources_root, started_at FROM run") {
        Triple(it.getInt(1), it.getString(2), it.getString(3))
    }.single()
    return RunSummary(
        version, root, started,
        counts(connection, "SELECT parse_status, count(*) FROM source_file GROUP BY parse_status ORDER BY parse_status"),
        counts(connection, "SELECT status, count(*) FROM occurrence GROUP BY status ORDER BY status"),
        counts(connection, "SELECT kind, count(*) FROM coverage_note GROUP BY kind ORDER BY kind")
    )
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
