//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.devtools

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import com.felipebz.zpa.atlas.cli.execute as zpaAtlas

class PhaseTimesTest {
    @TempDir
    lateinit var temp: Path

    /** `index` of the fixture into [out], with [options]: its exit code and the lines it printed to stderr. */
    private fun index(out: Path, vararg options: String): Pair<Int, List<String>> {
        val stderr = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
        try {
            val code = zpaAtlas(arrayOf("index", "--sources", Fixture.sources.toString(), "--out", out.toString(), "--compilation-schema", "HR",
                "--threads", "2", *options)) { null }
            return code to stderr.toString(Charsets.UTF_8).lines().dropLastWhile { it.isEmpty() }
        } finally {
            System.setErr(original)
        }
    }

    private val usual = Regex("""Indexed \d+ files into .+: scan \d+ ms, write \d+ ms, total \d+ ms""")

    @Test
    fun `with phase-times the usual lines come first, then every phase in the order it runs, a total and the slowest files`() {
        val without = temp.resolve("without.db")
        val with = temp.resolve("with.db")
        val (plainCode, plain) = index(without)
        val (timedCode, timed) = index(with, "--phase-times")
        assertThat(listOf(plainCode, timedCode)).containsExactly(0, 0)

        assertThat(plain).singleElement().matches { usual.matches(it) }
        assertThat(timed.first()).matches(usual.pattern)
        assertThat(timed[1]).isEqualTo("Phases:")
        val row = Regex("""  (.+?) +(\d+) +(\d+\.\d)%(?: +(\d+))?""")
        val rows = timed.drop(3).takeWhile { row.matches(it) }.map { row.matchEntire(it)!!.groupValues }
        assertThat(rows.map { it[1] }).containsExactly(
            "reading the sources", "project preparation", "object type members", "file walks", "global facts",
            "writing: tables", "writing: entities and relations", "writing: file rows", "writing: source lines and text index",
            "writing: secondary indexes", "writing: commit and file move", "outside the phases", "total"
        )
        assertThat(rows.dropLast(2)).allSatisfy { assertThat(it[4]).isNotEmpty() }
        assertThat(rows.last()[3]).isEqualTo("100.0")
        val walks = timed.drop(3 + rows.size)
        assertThat(walks.first()).matches("""File walks: \d+ files on 2 threads, wall \d+ ms; the sum of the per-file times is \d+ ms:""")
        val slowest = walks.dropWhile { it != "Slowest files:" }.drop(2).dropLast(1)
        assertThat(walks.last()).matches("""Text index: built on its own thread in \d+ ms, from the end of the reading; the writer waited \d+ ms for it""")
        assertThat(slowest).hasSize(10).allMatch { Regex("""  +\d+ +\d+ +\d+ +\d+  \S+""").matches(it) }

        val result = SameRows.compare(without, with)
        assertThat(result.identical).describedAs(result.lines.joinToString("\n")).isTrue()
    }
}
