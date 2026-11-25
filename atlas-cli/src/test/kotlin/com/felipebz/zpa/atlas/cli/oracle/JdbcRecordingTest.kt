//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli.oracle

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.PrintWriter
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.logging.Logger
import java.util.zip.InflaterInputStream
import javax.sql.DataSource

class JdbcRecordingTest {
    @TempDir
    lateinit var temp: Path

    private val database: DataSource by lazy {
        val file = temp.resolve("shop.db")
        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TABLE crates (owner TEXT, name TEXT, size NUMERIC)")
                it.execute("INSERT INTO crates VALUES ('YARD', 'BOX_A', 12.5), ('YARD', 'BOX_B', NULL), ('DOCK', 'BOX_C', 3)")
            }
        }
        object : DataSource {
            override fun getConnection(): Connection = DriverManager.getConnection("jdbc:sqlite:$file")
            override fun getConnection(username: String, password: String): Connection = connection
            override fun getLogWriter(): PrintWriter? = null
            override fun setLogWriter(out: PrintWriter?) = Unit
            override fun setLoginTimeout(seconds: Int) = Unit
            override fun getLoginTimeout(): Int = 0
            override fun getParentLogger(): Logger = Logger.getGlobal()
            override fun <T : Any?> unwrap(iface: Class<T>): T = throw SQLException()
            override fun isWrapperFor(iface: Class<*>): Boolean = false
        }
    }

    /** A reader as the dictionary loaders read: parameters, strings, numbers, a statement that fails. */
    private fun read(dataSource: DataSource, owner: String): List<String> = dataSource.connection.use { connection ->
        val result = ArrayList<String>()
        result += "major ${connection.metaData.databaseMajorVersion}"
        connection.prepareStatement("SELECT name, size, length(name) FROM crates WHERE owner = ? ORDER BY name").use { statement ->
            statement.setString(1, owner)
            statement.executeQuery().use { rows ->
                while (rows.next()) result += "${rows.getString(1)} ${rows.getBigDecimal(2)} ${rows.getInt(3)}"
            }
        }
        try {
            connection.createStatement().use { it.executeQuery("SELECT * FROM missing_table") }
        } catch (e: SQLException) {
            result += "failed ${e.errorCode}"
        }
        result
    }

    @Test
    fun `a replay gives the same answers to the same calls, and stops at another call`() {
        val recorder = JdbcRecorder()
        val recorded = read(RecordingDataSource(database, recorder), "YARD")
        val bytes = recorder.finish()
        assertThat(recorder.unusable).isNull()
        assertThat(recorder.strings).contains("YARD", "BOX_A")
        assertThat(recorded).containsExactly("major 3", "BOX_A 12.5 5", "BOX_B null 5", "failed 1")

        val replay = ReplayingDataSource(InflaterInputStream(ByteArrayInputStream(bytes)), recorder.calls)
        assertThat(read(replay, "YARD")).isEqualTo(recorded)
        assertThat(replay.complete).isTrue()

        val other = ReplayingDataSource(InflaterInputStream(ByteArrayInputStream(bytes)), recorder.calls)
        assertThatThrownBy { read(other, "DOCK") }.isInstanceOf(ReplayMismatch::class.java).hasMessageContaining("setString")
        assertThat(other.complete).isFalse()
    }
}
