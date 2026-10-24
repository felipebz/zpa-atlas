//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.devtools

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeText
import com.felipebz.zpa.atlas.cli.execute as zpaAtlas

class IncrementalIndexTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var sources: Path
    private val incremental get() = temp.resolve("incremental.db")
    private val full get() = temp.resolve("full.db")

    @OptIn(ExperimentalPathApi::class)
    @BeforeEach
    fun copy() {
        sources = temp.resolve("src")
        Fixture.sources.copyToRecursively(sources, followLinks = false, overwrite = false)
    }

    /** `index` of the copied sources into [out]: the lines it printed to stderr. */
    private fun index(out: Path, vararg options: String): List<String> {
        val stderr = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
        try {
            val code = zpaAtlas(arrayOf("index", "--sources", sources.toString(), "--out", out.toString(), "--compilation-schema", "HR",
                "--threads", "2", *options)) { null }
            assertThat(code).describedAs(stderr.toString(Charsets.UTF_8)).isEqualTo(0)
            return stderr.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        } finally {
            System.setErr(original)
        }
    }

    /** An incremental run and a full one on the same sources: the incremental run's line, once the indexes are checked the same. */
    private fun bothRuns(step: String, vararg options: String): String {
        val line = index(incremental, "--incremental", *options).single { it.startsWith("Incremental run") || it.startsWith("Full run") }
        index(full, *options)
        val result = SameRows.compare(incremental, full)
        assertThat(result.identical).describedAs("$step: $line\n" + result.lines.filterNot { it.contains(": same (") }.joinToString("\n")).isTrue()
        return line
    }

    private val walked = Regex("""Incremental run: walked (\d+) of \d+ files""")

    private fun walkedIn(line: String): Int = walked.find(line)?.groupValues?.get(1)?.toInt() ?: error("Not an incremental run: $line")

    private fun edit(path: String, from: String, to: String) {
        val file = sources.resolve(path)
        val text = file.readText()
        assertThat(text).describedAs(path).contains(from)
        file.writeText(text.replaceFirst(from, to))
    }

    @Test
    fun `after each edit an incremental run writes the index a full run writes, and a body-only change walks one file`() {
        assertThat(bothRuns("first run")).startsWith("Full run: no facts cache yet")
        assertThat(walkedIn(bothRuns("no change"))).isEqualTo(0)

        // A statement added inside a subprogram of a package body, which also moves the lines after it.
        edit("hr/pack_process.pkb", "  procedure write_log(p_msg varchar2) is\n  begin\n", "  procedure write_log(p_msg varchar2) is\n  begin\n    null;\n\n")
        assertThat(walkedIn(bothRuns("package body changed inside a subprogram"))).isEqualTo(1)

        val steps = listOf<Pair<String, () -> Unit>>(
            "package spec gains a procedure" to { edit("hr/pack_process.pks", "end pack_process;", "  procedure added_later;\nend pack_process;") },
            "a signature changes" to { edit("hr/pack_process.pks", "function f_next return number;", "function f_next(p_step number default 1) return number;") },
            "a procedure is removed" to { edit("hr/pack_process.pks", " procedure get_event(p_id in number, p_out out number, p_io in out number);", "") },
            "a new file" to {
                sources.resolve("hr/added_caller.prc").writeText("create or replace procedure added_caller is\nbegin\n  pack_process.run_job(1, 'a', 2, sysdate);\nend;\n/\n")
            },
            "a removed file" to { Files.delete(sources.resolve("hr/imp_echo_b.prc")) },
            "a renamed file" to { Files.move(sources.resolve("hr/imp_echo_a.prc"), sources.resolve("hr/echo_renamed.prc")) },
            "a table gains a column" to { edit("hr/it_tables.sql", "note varchar2(100));", "note varchar2(100), priority number);") },
            "a view changes" to { edit("hr/it_io_views.sql", "select id, status from it_v_orders_io", "select id, status, id as id_again from it_v_orders_io") },
            "an object type spec changes" to { edit("hr/ty_bag.typ", "  member function g(n number) return number,", "  member function size_of return number,\n  member function g(n number) return number,") },
            "a trigger changes" to { edit("hr/it_triggers.trg", "it_audit('insert');", "it_audit('insert');\n  it_audit('again');") },
            // The package's own function now wins over the schema-level imp_fn its body calls.
            "a declaration that shadows a schema-level name" to { edit("hr/pack_imp_b.pks", "  procedure top;", "  function imp_fn(p_n number) return number;\n  procedure top;") },
            "the shadowing declaration removed" to { edit("hr/pack_imp_b.pks", "  function imp_fn(p_n number) return number;\n", "") }
        )
        for ((step, change) in steps) {
            change()
            assertThat(walkedIn(bothRuns(step))).describedAs(step).isGreaterThan(0)
        }
    }

    @Test
    fun `another option is a full run that names it`() {
        index(incremental, "--incremental")
        val line = index(incremental, "--incremental", "--ccflags", "debug:TRUE").single { it.startsWith("Full run") }
        assertThat(line).isEqualTo(
            "Full run: the facts cache was written with another conditional compilation (--database-version, --ccflags); the facts cache is written for the next run"
        )
        assertThat(index(incremental, "--incremental", "--ccflags", "debug:TRUE")).anyMatch { it.startsWith("Incremental run: walked 0 of") }
    }

    @Test
    fun `a damaged or cut cache is a full run, and the index is still the full one`() {
        index(incremental, "--incremental")
        val cache = temp.resolve("incremental.db.facts")
        val bytes = Files.readAllBytes(cache)
        Files.write(cache, bytes.copyOf(bytes.size / 2))
        assertThat(bothRuns("cut cache")).startsWith("Full run: the facts cache cannot be used:")
        val whole = Files.readAllBytes(cache)
        // A byte of the gzip trailer's CRC: a byte inside the compressed stream may change nothing the reader sees.
        whole[whole.size - 8] = (whole[whole.size - 8] + 1).toByte()
        Files.write(cache, whole)
        assertThat(bothRuns("damaged cache")).startsWith("Full run: the facts cache cannot be used:")
        assertThat(walkedIn(bothRuns("whole cache again"))).isEqualTo(0)
    }

    @Test
    fun `without the option no cache is written`() {
        index(full)
        assertThat(temp.resolve("full.db.facts")).doesNotExist()
    }

    @Test
    fun `with a dictionary, the relations and synonyms the files resolved come back from the cache`() {
        fun run(out: Path, incremental: Boolean) = Indexer().index(
            IndexOptions(sources, out, compilationSchema = "HR", dictionary = Fixture.dictionary, threads = 2), incremental = incremental
        ).incremental
        assertThat(run(incremental, true)!!.fullReason).startsWith("no facts cache yet")
        edit("hr/pack_process.pkb", "  procedure write_log(p_msg varchar2) is\n  begin\n", "  procedure write_log(p_msg varchar2) is\n  begin\n    null;\n")
        val report = run(incremental, true)!!
        assertThat(report.fullReason).isNull()
        assertThat(report.walked).isEqualTo(1)
        run(full, false)
        val result = SameRows.compare(incremental, full)
        assertThat(result.identical).describedAs(result.lines.filterNot { it.contains(": same (") }.joinToString("\n")).isTrue()
        assertThat(Fixture.query(full, "SELECT count(*) FROM relation WHERE kind = 'SYNONYM_FOR'").single().toInt()).isGreaterThan(0)
    }

    private val upperCaseLonger = """
        create or replace function f_straße(p number) return number is
        begin
          return p;
        end;
        /
    """.trimIndent() + "\n"

    private val callerOfIt = """
        create or replace procedure uses_it is
          v number;
        begin
          v := f_straße(1);
        end;
        /
    """.trimIndent() + "\n"

    /** Sources of their own instead of the fixture's. */
    private fun ownSources(vararg files: Pair<String, String>) {
        sources = Files.createDirectory(temp.resolve("own"))
        files.forEach { (name, text) -> sources.resolve(name).writeText(text) }
    }

    @Test
    fun `a caller of a name whose upper case is longer is walked when its signature changes`() {
        ownSources("f.fun" to upperCaseLonger, "u.prc" to callerOfIt)
        bothRuns("first run")
        edit("f.fun", "f_straße(p number)", "f_straße(p number, q number default 0)")
        assertThat(walkedIn(bothRuns("signature changed"))).isEqualTo(2)
    }

    @Test
    fun `a caller of a name whose upper case is longer is walked when the name appears`() {
        ownSources("u.prc" to callerOfIt)
        bothRuns("first run")
        sources.resolve("f.fun").writeText(upperCaseLonger)
        assertThat(walkedIn(bothRuns("declaration added"))).isEqualTo(2)
    }
}
