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

/** What `index` prints of where a run is, and the database failures it gives before reading the sources. */
class IndexProgressTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var sources: Path
    private lateinit var out: Path

    @BeforeEach
    fun sources() {
        sources = Files.createDirectory(directory.resolve("src"))
        Files.writeString(sources.resolve("depot.sql"), "create or replace procedure depot_job is\nbegin\n  null;\nend;\n/\n")
        Files.writeString(sources.resolve("yard.sql"), "begin\n  depot_job;\nend;\n/\n")
        out = directory.resolve("atlas.db")
    }

    /** The exit code of `index` with [options], and what it printed to stderr, by line. */
    private fun index(vararg options: String): Pair<Int, List<String>> {
        val stderr = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
        try {
            val code = execute(arrayOf("index", "--sources", sources.toString(), "--out", out.toString(), "--threads", "1", *options)) { "secret" }
            return code to stderr.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        } finally {
            System.setErr(original)
        }
    }

    @Test
    fun `each step says when it starts and how long it took, and the final line stays last`() {
        val (code, lines) = index()
        assertThat(code).isZero()
        assertThat(lines).contains("[reading] 2 files to read on 1 threads", "[walks] walking 2 files on 1 threads")
        assertThat(lines.filter { it.startsWith("[walks] ") }.last()).matches("\\[walks] 2 files walked in \\d+\\.\\d s")
        assertThat(lines.map { it.substringBefore(']') + "]" }.filter { it.startsWith("[") }.distinct())
            .containsSubsequence("[reading]", "[preparation]", "[walks]", "[global facts]", "[write: tables]", "[write: commit]")
        assertThat(lines.last()).startsWith("Indexed 2 files into ")
    }

    @Test
    fun `an incremental run says how many files it walks and how many it takes from the facts cache`() {
        assertThat(index("--incremental").first).isZero()
        val (code, lines) = index("--incremental")
        assertThat(code).isZero()
        assertThat(lines).contains("[walks] walking 0 of 2 files on 1 threads; 2 from the facts cache")
    }

    @Test
    fun `a missing JDBC driver fails before the sources are read`() {
        val (code, lines) = index("--oracle-url", "jdbc:oracle:thin:@//localhost:1521/depot", "--oracle-user", "yard", "--oracle-schemas", "yard")
        assertThat(code).isNotZero()
        assertThat(lines).anyMatch { it.startsWith("No JDBC driver accepts the configured --oracle-url.") }
        assertThat(lines).noneMatch { it.startsWith("[reading]") }
        assertThat(Files.exists(out)).isFalse()
    }
}
