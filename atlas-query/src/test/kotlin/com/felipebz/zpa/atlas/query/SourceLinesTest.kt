//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/** Source text served from the index: snippets, occurrence lines and file views. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceLinesTest {
    private val temp: Path = Files.createTempDirectory("atlas-test")
    private val db: Path = SharedFixtureIndex.db
    private val index = AtlasIndex(db, Fixture.sources)

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun `source returns the lines around a position and detects stale files`() {
        val snippet = index.source("hr/proc_standalone.prc", 3, 1).result!!
        assertThat(snippet.lines.map { it.number to it.text }).containsExactly(2 to "begin", 3 to "  delete company where id = p_id;", 4 to "end;")
        assertThat(snippet.stale).isFalse()

        val copy = temp.resolve("sources")
        Fixture.sources.toFile().copyRecursively(copy.toFile())
        Files.writeString(copy.resolve("hr/proc_standalone.prc"), "changed\n")
        val edited = AtlasIndex(db, copy).source("hr/proc_standalone.prc", 1, 0).result!!
        assertThat(edited.stale).isTrue()
        assertThat(edited.lines.single().text).isEqualTo("changed")

        assertThat(AtlasIndex(db, null).source("hr/proc_standalone.prc", 1).result).isNull()
        assertThat(AtlasIndex(db, temp.resolve("missing")).source("hr/proc_standalone.prc", 1).result).isNull()
        assertThatThrownBy { index.source("../hr/proc_standalone.prc", 1) }.isInstanceOf(InvalidQueryException::class.java)
        assertThatThrownBy { index.source("hr/nao_indexado.sql", 1) }.isInstanceOf(InvalidQueryException::class.java)
    }

    @Test
    fun `occurrence lines give each use's line as indexed, served or not, and say when its file changed`() {
        val rows = index.queryOccurrences(OccurrenceQuery(path = "hr/proc_standalone.prc"), summary = false).result.rows.filter { it.span!!.startLine == 3 }
        assertThat(rows).hasSizeGreaterThanOrEqualTo(2)
        val ids = rows.map { it.id }
        val lines = index.occurrenceLines(ids + 999_999_999L).result
        // In the order asked, an unknown id left out; each the line its span starts on.
        assertThat(lines.map { it.id }).isEqualTo(ids)
        assertThat(lines.map { it.text to it.stale }).containsOnly("  delete company where id = p_id;" to false)

        // A changed file keeps the indexed line, so the span is still on it, and says it changed.
        val copy = temp.resolve("lines-sources")
        Fixture.sources.toFile().copyRecursively(copy.toFile())
        Files.writeString(copy.resolve("hr/proc_standalone.prc"), "a\nb\nchanged line\n")
        val edited = AtlasIndex(db, copy).occurrenceLines(ids).result
        assertThat(edited.map { it.text to it.stale }).containsOnly("  delete company where id = p_id;" to true)
        // Without sources: the same line, its file's state unknown.
        assertThat(AtlasIndex(db, null).occurrenceLines(ids).result.map { it.text to it.stale }).containsOnly("  delete company where id = p_id;" to null)
        assertThatThrownBy { index.occurrenceLines(List(501) { it.toLong() }) }.isInstanceOf(InvalidQueryException::class.java)
    }

    @Test
    fun `occurrence lines find the indexed line of every file, early or late in the index`() {
        // The last occurrence of each file of the whole index.
        val all = generateSequence(index.queryOccurrences(OccurrenceQuery(), limit = 500, summary = false).result) { page ->
            page.nextAfter?.let { index.queryOccurrences(OccurrenceQuery(), after = it, limit = 500, summary = false).result }
        }.flatMap { it.rows }.toList()
        val page = all.groupBy { it.path }.values.map { it.last() }.take(500)
        assertThat(page.size).isGreaterThan(20)
        val lines = index.occurrenceLines(page.map { it.id }).result.associateBy { it.id }
        for (row in page) {
            val expected = Fixture.sources.resolve(row.path).toFile().readText().lines()[row.span!!.startLine - 1]
            if (expected.length < 3) continue
            assertThat(lines.getValue(row.id).text).describedAs("${row.path}:${row.span!!.startLine}").isEqualTo(expected)
        }
    }

    @Test
    fun `file gives the name span of a definition, its parameters and a view`() {
        val calls = index.file("hr/pack_calls.pkb").result!!
        assertThat(calls.stale).isFalse()
        assertThat(calls.lines[9]).isEqualTo("  procedure p_mix(p_a number, p_b varchar2, p_c date) is begin null; end;")
        val pMix = "db:HR.PACK_CALLS.P_MIX(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN DATE)@BODY"
        assertThat(calls.at(10, 12)).containsExactly("10,12,10,17 $pMix [DEFINITION]")
        assertThat(calls.at(10, 18)).containsExactly("10,18,10,21 $pMix.P_A [DEFINITION]")
        assertThat(index.file("hr/views_remote.sql").result!!.at(1, 23)).containsExactly("1,23,1,36 db:HR.V_REMOTE_SAME [DEFINITION]")
    }

    @Test
    fun `file gives the names inside the literal of a dynamic statement at their place`() {
        val dynamic = index.file("hr/pack_dynamic.pkb").result!!
        assertThat(dynamic.at(10, 35)).containsExactly("10,35,10,42 db:HR.COMPANY [SQL_DELETE]")
        // The second line of a literal across two lines.
        assertThat(dynamic.at(27, 10)).containsExactly("27,10,27,22 db:HR.COMPANY.COMPANY_NAME [SQL_UPDATE, WRITE]")
        assertThat(dynamic.lines[26].substring(10, 22)).isEqualTo("company_name")
    }

    @Test
    fun `file keeps a qualified read over the column definition inside it`() {
        val mview = index.file("hr/mv_company.sql").result!!
        assertThat(mview.at(2, 9)).containsExactly("2,9,2,13 db:HR.COMPANY.ID [READ]")
        assertThat(mview.at(2, 11)).containsExactly("2,11,2,13 db:HR.MV_COMPANY_LOG.ID [DEFINITION]")
    }

    @Test
    fun `file gives the candidates of an ambiguous call as entity indexes`() {
        val calls = index.file("hr/pack_calls.pkb").result!!
        val ambiguous = calls.occurrences.single { it.at[0] == 33 && it.status == "AMBIGUOUS" }
        assertThat(ambiguous.at).containsExactly(33, 4, 33, 24)
        assertThat(ambiguous.entity).isNull()
        assertThat(ambiguous.candidates.map { calls.entities[it].key }).containsExactly(runJob4, runJob5)
        // Every entity is listed once.
        assertThat(calls.entities.map { it.key }).doesNotHaveDuplicates()
    }

    @Test
    fun `file of a changed file has its lines and no occurrences, and other paths are rejected`() {
        val copy = temp.resolve("file-sources")
        Fixture.sources.toFile().copyRecursively(copy.toFile())
        Files.writeString(copy.resolve("hr/pack_calls.pkb"), "changed\n")
        val edited = AtlasIndex(db, copy).file("hr/pack_calls.pkb").result!!
        assertThat(edited.stale).isTrue()
        assertThat(edited.lines.first()).isEqualTo("changed")
        assertThat(edited.occurrences).isEmpty()
        assertThat(edited.entities).isEmpty()

        assertThat(AtlasIndex(db, null).file("hr/pack_calls.pkb").result).isNull()
        assertThatThrownBy { index.file("../hr/pack_calls.pkb") }.isInstanceOf(InvalidQueryException::class.java)
        assertThatThrownBy { index.file("hr/nao_indexado.sql") }.isInstanceOf(InvalidQueryException::class.java)
    }
}
