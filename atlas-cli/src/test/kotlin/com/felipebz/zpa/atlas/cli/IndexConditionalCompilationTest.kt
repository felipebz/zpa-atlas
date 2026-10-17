//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class IndexConditionalCompilationTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var sources: Path
    private lateinit var out: Path

    @BeforeEach
    fun sources() {
        sources = Files.createDirectory(directory.resolve("src"))
        Files.writeString(sources.resolve("unit.sql"), "begin\n  \$if \$\$debug \$then null; \$end\n  null;\nend;\n/\n")
        out = directory.resolve("atlas.db")
    }

    /** The exit code of `index` with [options], and what it printed to stderr. */
    private fun index(vararg options: String): Pair<Int, String> {
        val stderr = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
        try {
            val code = execute(arrayOf("index", "--sources", sources.toString(), "--out", out.toString(), "--threads", "1", *options)) { null }
            return code to stderr.toString(Charsets.UTF_8)
        } finally {
            System.setErr(original)
        }
    }

    /** `database_version`, `database_version_origin` and `ccflags` of the `run` row. */
    private fun run(): List<String?> = DriverManager.getConnection("jdbc:sqlite:$out").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT database_version, database_version_origin, ccflags FROM run").use { rows ->
                assertThat(rows.next()).isTrue()
                listOf(rows.getString(1), rows.getString(2), rows.getString(3))
            }
        }
    }

    @Test
    fun `an invalid database version is a usage error naming the option`() {
        val (code, stderr) = index("--database-version", "abc")

        assertThat(code).isEqualTo(2)
        assertThat(stderr).contains("--database-version").contains("abc")
        assertThat(out).doesNotExist()
    }

    @Test
    fun `invalid flags are a usage error naming the option`() {
        val (code, stderr) = index("--ccflags", "x")

        assertThat(code).isEqualTo(2)
        assertThat(stderr).contains("--ccflags")
        assertThat(out).doesNotExist()
    }

    @Test
    fun `an explicit database version is stored with its origin`() {
        val (code, stderr) = index("--database-version", "11.2")

        assertThat(code).isEqualTo(0)
        assertThat(stderr).contains("Conditional compilation: database version 11.2 (from --database-version), flags none")
        assertThat(run()).containsExactly("11.2", "OPTION", null)
    }

    @Test
    fun `flags are stored upper-cased in their order`() {
        val (code, stderr) = index("--ccflags", "debug:true, Level:2")

        assertThat(code).isEqualTo(0)
        assertThat(stderr).contains("Conditional compilation: database version none, flags DEBUG:TRUE, LEVEL:2")
        assertThat(run()).containsExactly(null, null, "DEBUG:TRUE, LEVEL:2")
    }

    @Test
    fun `without options conditional compilation is off`() {
        val (code, stderr) = index()

        assertThat(code).isEqualTo(0)
        assertThat(stderr).doesNotContain("Conditional compilation")
        assertThat(run()).containsExactly(null, null, null)
    }
}
