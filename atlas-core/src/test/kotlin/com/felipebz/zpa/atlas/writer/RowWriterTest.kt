//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class RowWriterTest {
    @TempDir
    lateinit var temp: Path

    private fun database(): Connection = DriverManager.getConnection("jdbc:sqlite:${temp.resolve("shop.db")}").also { connection ->
        connection.createStatement().use { it.execute("CREATE TABLE crate (id INTEGER PRIMARY KEY, label TEXT, weight INTEGER)") }
        connection.autoCommit = false
    }

    private fun writerThreads() = Thread.getAllStackTraces().keys.filter { it.name == NAME && it.isAlive }

    @Test
    @Timeout(60)
    fun `the rows of many batches are written in order, with the actions between them where they were asked`() {
        database().use { connection ->
            RowWriter(connection, NAME).use { writer ->
                val insert = writer.insert("INSERT INTO crate(id, label, weight) VALUES (?,?,?)")
                for (id in 1..50_000L) {
                    insert.setLong(1, id)
                    insert.setString(2, "crate $id")
                    if (id % 7 == 0L) insert.setNull(3) else insert.setInt(3, (id % 1000).toInt())
                    insert.add()
                    writer.flushIfFull()
                    if (id == 30_000L) writer.execute { it.createStatement().use { s -> s.execute("UPDATE crate SET label = 'counted' WHERE id <= 30000") } }
                }
                insert.close()
                writer.finish()
            }
            assertThat(writerThreads()).isEmpty()
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*), sum(label = 'counted'), sum(weight IS NULL), max(id) FROM crate").use {
                    it.next()
                    assertThat((1..4).map { column -> it.getLong(column) }).containsExactly(50_000L, 30_000L, 7_142L, 50_000L)
                }
            }
        }
    }

    @Test
    @Timeout(60)
    fun `an error while writing stops the caller with it, and the writing thread ends`() {
        database().use { connection ->
            val error = assertThatThrownBy {
                RowWriter(connection, NAME).use { writer ->
                    val insert = writer.insert("INSERT INTO crate(id, label, weight) VALUES (?,?,?)")
                    // Id 5 twice, early: the caller keeps adding rows and must not wait for a batch that never comes back.
                    for (id in listOf(1L, 2L, 3L, 4L, 5L, 5L) + (6L..200_000L)) {
                        insert.setLong(1, id)
                        insert.setString(2, "crate")
                        insert.setInt(3, 1)
                        insert.add()
                        writer.flushIfFull()
                    }
                    writer.finish()
                }
            }
            error.isInstanceOf(SQLException::class.java).hasMessageContaining("UNIQUE")
            assertThat(writerThreads()).isEmpty()
            // The connection is the caller's again.
            connection.createStatement().use { it.executeQuery("SELECT count(*) FROM crate").use { rows -> assertThat(rows.next()).isTrue() } }
        }
    }

    @Test
    @Timeout(60)
    fun `an error of the caller stops it with that error, and the writing thread ends`() {
        database().use { connection ->
            assertThatThrownBy {
                RowWriter(connection, NAME).use { writer ->
                    val insert = writer.insert("INSERT INTO crate(id, label, weight) VALUES (?,?,?)")
                    for (id in 1..100_000L) {
                        insert.setLong(1, id)
                        insert.setString(2, "crate")
                        insert.setInt(3, 1)
                        insert.add()
                        writer.flushIfFull()
                        check(id < 70_000L) { "no weight for crate $id" }
                    }
                }
            }.isInstanceOf(IllegalStateException::class.java).hasMessage("no weight for crate 70000")
            assertThat(writerThreads()).isEmpty()
        }
    }

    /**
     * Every connection the index opens, wrapped: each call to it, its statements and their results is counted while it
     * runs, and the thread of each statement's calls kept.
     */
    private class Recording(private val sqlite: Driver) : Driver by sqlite {
        val overlaps = AtomicInteger()
        val calls = AtomicInteger()
        val statementThreads = ConcurrentHashMap<Any, MutableSet<String>>()
        private val running = ConcurrentHashMap<Any, AtomicInteger>()

        override fun connect(url: String, info: Properties): Connection? =
            sqlite.connect(url, info)?.let { wrap(it, Connection::class.java, it, null) }

        private fun <T> wrap(target: Any, type: Class<T>, connection: Any, statement: Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                val counter = running.computeIfAbsent(connection) { AtomicInteger() }
                if (counter.incrementAndGet() > 1) overlaps.incrementAndGet()
                calls.incrementAndGet()
                statement?.let { statementThreads.computeIfAbsent(it) { ConcurrentHashMap.newKeySet() } += Thread.currentThread().name }
                try {
                    when (val result = method.invoke(target, *args.orEmpty())) {
                        is ResultSet -> wrap(result, ResultSet::class.java, connection, statement ?: result)
                        is java.sql.PreparedStatement -> wrap(result, java.sql.PreparedStatement::class.java, connection, result)
                        is Statement -> wrap(result, Statement::class.java, connection, result)
                        is DatabaseMetaData -> result
                        else -> result
                    }
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                } finally {
                    counter.decrementAndGet()
                }
            })
    }

    @Test
    fun `no two threads use a connection or a statement of the index at once`() {
        val sqlite = DriverManager.getDrivers().toList().first { it.acceptsURL("jdbc:sqlite:") }
        val recording = Recording(sqlite)
        DriverManager.deregisterDriver(sqlite)
        DriverManager.registerDriver(recording)
        try {
            val index = Fixture.index(temp.resolve("fixture.db"))
            assertThat(Files.size(index)).isGreaterThan(0)
        } finally {
            DriverManager.deregisterDriver(recording)
            DriverManager.registerDriver(sqlite)
        }
        assertThat(recording.calls.get()).isGreaterThan(1000)
        assertThat(recording.overlaps.get()).isZero()
        assertThat(recording.statementThreads.values.filter { it.size > 1 }).isEmpty()
        assertThat(recording.statementThreads.values.flatten().toSet()).contains("atlas-index rows")
    }

    private companion object {
        const val NAME = "test rows"
    }
}
