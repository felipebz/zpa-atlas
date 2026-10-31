//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.extract.DeclaredTexts
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** What a declaration says, as written: `declared_text` rows (type, value, cut) of the fixture. */
class DeclaredTextTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy { Fixture.index(temp.resolve("fixture.db")) }

    /** The row of [key]: type, value and cut, `-` for none; null without a row. */
    private fun declared(key: String, database: Path = db): String? = Fixture.query(
        database, "SELECT coalesce(d.datatype, '-') || ' | ' || coalesce(d.value, '-') || ' | ' || d.value_cut FROM declared_text d JOIN entity e ON e.id = d.entity_id WHERE e.key = '$key'"
    ).singleOrNull()

    private val runJob5 = "db:HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE,P_E IN NUMBER)"

    @Test
    fun `a function says its return type, a parameter its type and default`() {
        assertThat(declared("db:HR.PACK_PROCESS.F_NEXT()")).isEqualTo("number | - | 0")
        assertThat(declared("db:HR.PACK_PROCESS.F_NEXT()@BODY")).isEqualTo("number | - | 0")
        assertThat(declared("$runJob5.P_E")).isEqualTo("number | 0 | 0")
        assertThat(declared("$runJob5@BODY.P_E")).isEqualTo("number | 0 | 0")
        assertThat(declared("$runJob5.P_A")).isEqualTo("number | - | 0")
        assertThat(declared("db:HR.PACK_ARGS.P(P_CODE IN NUMBER,P_NAME IN VARCHAR2,P_FLAG IN BOOLEAN).P_NAME")).isEqualTo("varchar2 | null | 0")
        // A procedure says nothing beside its name and parameters.
        assertThat(declared(runJob5)).isNull()
    }

    @Test
    fun `a package variable, constant, record field and cursor say their type and initial value as written`() {
        assertThat(declared("db:HR.PACK_PROCESS.C_KIND")).isEqualTo("varchar2(1) | 'J' | 0")
        assertThat(declared("db:HR.PACK_PROCESS.G_COUNTER")).isEqualTo("number | - | 0")
        // Not resolved: the name of a type stays as written.
        assertThat(declared("db:HR.PACK_PROCESS.G_EVENTS")).isEqualTo("t_events | - | 0")
        assertThat(declared("db:HR.PACK_DECLARED.G_AMOUNT")).isEqualTo("send_queue.id%type | - | 0")
        assertThat(declared("db:HR.FN_COMPANY_NAME(P_ID IN NUMBER)")).isEqualTo("company.company_name%type | - | 0")
        // Not evaluated.
        assertThat(declared("db:HR.PACK_DECLARED.G_STAMP")).isEqualTo("date | sysdate + 1 | 0")
        assertThat(declared("db:HR.PACK_DECLARED.R_LINE.QTY")).isEqualTo("number | 1 | 0")
        assertThat(declared("db:HR.PACK_DECLARED.R_LINE.LABEL")).isEqualTo("varchar2(10) not null | 'x' | 0")
        // The cursor is one entity declared in the spec and defined in the body: its return type, no value.
        assertThat(declared("db:HR.PACK_DECLARED.C_LINES")).isEqualTo("r_line | - | 0")
    }

    @Test
    fun `a value over several lines loses its comments and line breaks, its spaces made one`() {
        assertThat(declared("db:HR.PACK_DECLARED.C_LABEL")).isEqualTo("varchar2(40) | 'ledger' || '_' || 'note' | 0")
        val notice = declared("db:HR.PACK_DECLARED.C_NOTICE")!!.split(" | ")
        assertThat(notice[1]).startsWith("'Every ledger entry posted after the monthly close is held for review by the controller, ' || 'and an entry")
            .endsWith("…").hasSize(DeclaredTexts.VALUE_LIMIT + 1)
        assertThat(notice[2]).isEqualTo("1")
    }

    @Test
    fun `spec and body keep their own text`() {
        val post = "db:HR.PACK_DECLARED.POST(P_ID IN NUMBER,P_NOTE IN VARCHAR2,P_WHEN IN DATE)"
        assertThat(declared("$post.P_NOTE")).isEqualTo("varchar2 | 'none' | 0")
        assertThat(declared("$post@BODY.P_NOTE")).isEqualTo("varchar2 | - | 0")
        assertThat(declared("$post.P_WHEN")).isEqualTo("date | sysdate | 0")
        assertThat(declared("$post@BODY.P_WHEN")).isEqualTo("date | trunc(sysdate) | 0")
    }

    @Test
    fun `under conditional compilation the text is the branch kept, a declaration in a branch not kept is not indexed`() {
        // No settings: zpa keeps the first branch of each $IF.
        assertThat(declared("db:HR.PACK_DECLARED.G_MODE")).isEqualTo("varchar2(20) | - | 0")
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE key LIKE 'db:HR.PACK_DECLARED.POST\\_%' ESCAPE '\\' AND kind = 'PROCEDURE' ORDER BY key"))
            .containsExactly("db:HR.PACK_DECLARED.POST_NEW(P_ID IN NUMBER)", "db:HR.PACK_DECLARED.POST_NEW(P_ID IN NUMBER)@BODY")
    }

    @Test
    fun `a value longer than the limit is cut and marked, a character never split`() {
        val sources = Files.createDirectories(temp.resolve("src"))
        val long = "'" + "ab".repeat(150) + "'"
        Files.writeString(sources.resolve("pack_long.pks"), """
            create or replace package pack_long is
              c_text constant varchar2(400) := $long;
              c_emoji constant varchar2(800) := '${"\uD83D\uDE00".repeat(DeclaredTexts.VALUE_LIMIT)}';
            end;
            /
        """.trimIndent() + "\n")
        val index = temp.resolve("long.db")
        Indexer().index(IndexOptions(sources, index, compilationSchema = "HR", threads = 1))
        assertThat(declared("db:HR.PACK_LONG.C_TEXT", index)).isEqualTo("varchar2(400) | ${long.take(DeclaredTexts.VALUE_LIMIT)}… | 1")
        val emoji = declared("db:HR.PACK_LONG.C_EMOJI", index)!!.split(" | ")[1]
        assertThat(emoji.codePointCount(0, emoji.length)).isEqualTo(DeclaredTexts.VALUE_LIMIT + 1)
    }
}
