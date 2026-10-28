//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The text of a dynamic statement written as a variable (`dynamic-variables/pack_dyn_vars.pkb`, with the fixture's dictionary). */
class DynamicVariableTextTest {
    @TempDir
    lateinit var temp: Path

    private val db: Path by lazy {
        val sources = Path.of(javaClass.getResource("/dynamic-variables")!!.toURI())
        temp.resolve("dynamic.db").also {
            Indexer().index(IndexOptions(sources, it, compilationSchema = "HR", dictionary = Fixture.dictionary, threads = 1))
        }
    }

    /** The reason of the `DYNAMIC` occurrence of the statement on [line]. */
    private fun reason(line: Int) = Fixture.query(db,
        """SELECT o.reason FROM occurrence o JOIN source_file f ON f.id = o.file_id
           WHERE f.path = 'pack_dyn_vars.pkb' AND o.status = 'DYNAMIC' AND o.start_line = $line""").single()

    @Test
    fun `one assignment gives the text, at its place in the literal, even when a call with an IN formal reads the variable`() {
        assertThat(reason(24)).isEqualTo("VARIABLE_PARSED")
        assertThat(Fixture.query(db,
            """SELECT o.start_line || ':' || o.start_col || ' ' || e.key || ' ' || o.roles
               FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id
               WHERE f.path = 'pack_dyn_vars.pkb' AND o.origin = 'DYNAMIC_LITERAL' AND o.start_line = 22 ORDER BY o.start_col""")).containsExactly(
            "22:26 db:HR.EVENT_LOG SQL_INSERT", "22:37 db:HR.EVENT_LOG.ID SQL_INSERT,WRITE", "22:41 db:HR.EVENT_LOG.MSG SQL_INSERT,WRITE"
        )
    }

    @Test
    fun `a default value and a constant give their text`() {
        assertThat(reason(25)).isEqualTo("VARIABLE_PARSED")
        assertThat(reason(26)).isEqualTo("VARIABLE_PARSED")
        assertThat(texts(10)).contains("db:HR.COMPANY SQL_UPDATE", "db:HR.COMPANY.ST_ACTIVE SQL_UPDATE,WRITE")
        assertThat(texts(11)).containsExactly("db:HR.COMPANY.COMPANY_NAME READ", "db:HR.COMPANY SQL_SELECT", "db:HR.COMPANY.ID READ")
    }

    @Test
    fun `a value run by two statements is walked once, and each statement keeps its own reason`() {
        // v_default: EXECUTE IMMEDIATE, then DBMS_SQL.PARSE, which reads it.
        assertThat(reason(25)).isEqualTo("VARIABLE_PARSED")
        assertThat(reason(46)).isEqualTo("VARIABLE_PARSED")
        assertThat(texts(10)).containsExactly("db:HR.COMPANY SQL_UPDATE", "db:HR.COMPANY.ST_ACTIVE SQL_UPDATE,WRITE", "db:HR.COMPANY.ID READ")
        assertThat(statementsAt(10)).containsExactly("UPDATE")
    }

    @Test
    fun `EXECUTE IMMEDIATE and OPEN FOR of one variable give one statement for the text and two DYNAMIC occurrences`() {
        assertThat(Fixture.query(db,
            """SELECT o.start_line || ' ' || o.written || ' ' || o.reason FROM occurrence o JOIN source_file f ON f.id = o.file_id
               WHERE f.path = 'pack_dyn_vars.pkb' AND o.status = 'DYNAMIC' AND o.start_line IN (57, 58) ORDER BY o.start_line""")).containsExactly(
            "57 EXECUTE IMMEDIATE VARIABLE_PARSED", "58 OPEN FOR VARIABLE_PARSED"
        )
        assertThat(statementsAt(54)).containsExactly("SELECT")
        assertThat(texts(54)).containsExactly("db:HR.COMPANY.COMPANY_NAME READ", "db:HR.COMPANY SQL_SELECT", "db:HR.COMPANY.ID READ")
        // The text's names belong to the statement read from the value, in the procedure that runs it.
        assertThat(Fixture.query(db,
            """SELECT DISTINCT s.kind || ' ' || coalesce(c.key, '-') FROM occurrence o JOIN source_file f ON f.id = o.file_id
               JOIN sql_statement s ON s.id = o.statement_id LEFT JOIN entity c ON c.id = o.container_entity_id
               WHERE f.path = 'pack_dyn_vars.pkb' AND o.origin = 'DYNAMIC_LITERAL' AND o.start_line = 54""")).containsExactly(
            "SELECT db:HR.PACK_DYN_VARS.RUN_SHARED()@BODY"
        )
    }

    /** The kinds of the statements read from a text on [line] of the file. */
    private fun statementsAt(line: Int) = Fixture.query(db,
        """SELECT s.kind FROM sql_statement s JOIN source_file f ON f.id = s.file_id
           WHERE f.path = 'pack_dyn_vars.pkb' AND s.kind <> 'DYNAMIC' AND s.start_line = $line ORDER BY s.id""")

    @Test
    fun `an inner block's variable gives its own text, not the hidden one's`() {
        assertThat(reason(30)).isEqualTo("VARIABLE_PARSED")
        assertThat(texts(28)).containsExactly("db:HR.EVENT_LOG SQL_DELETE", "db:HR.EVENT_LOG.ID READ")
        assertThat(texts(12)).isEmpty()
    }

    @Test
    fun `an assignment that concatenates a variable gives a text with a hole`() {
        assertThat(reason(44)).isEqualTo("VARIABLE_PARSED_WITH_HOLES")
        assertThat(texts(43)).containsExactly("db:HR.COMPANY SQL_DELETE", "db:HR.COMPANY.ID READ")
    }

    @Test
    fun `every other shape gives no text and says why`() {
        assertThat(mapOf(
            "two assignments" to 34, "an assignment that appends to the variable" to 36, "SELECT INTO" to 38, "an OUT actual" to 40,
            "an actual of a call the atlas does not resolve" to 42, "a package body variable" to 47, "a package spec variable" to 48,
            "a parameter" to 49, "a number" to 50
        ).mapValues { reason(it.value) }).containsExactlyInAnyOrderEntriesOf(mapOf(
            // Written several times: the value that reaches the statement (see DynamicVariableReachTest).
            "two assignments" to "VARIABLE_PARSED", "an assignment that appends to the variable" to "VARIABLE_PARSED",
            "SELECT INTO" to "VARIABLE_VALUE_NOT_IN_CODE", "an OUT actual" to "VARIABLE_VALUE_NOT_IN_CODE",
            "an actual of a call the atlas does not resolve" to "VARIABLE_UNSEEN_WRITE", "a package body variable" to "VARIABLE_NOT_LOCAL",
            "a package spec variable" to "VARIABLE_UNRESOLVED", "a parameter" to "VARIABLE_NOT_LOCAL", "a number" to "VARIABLE_NOT_CHARACTER"
        ))
        for (line in listOf(2, 17, 32)) assertThat(texts(line)).describedAs("line $line").isEmpty()
        // The default value of v_grows and the last value of v_twice are read now; the first value of v_twice never reaches.
        assertThat(texts(35)).containsExactly("db:HR.SEND_QUEUE.ID READ")
        assertThat(texts(33)).containsExactly("db:HR.SEND_QUEUE SQL_DELETE", "db:HR.SEND_QUEUE.ID READ")
    }

    /** The entity key and roles of every name read from the text on [line] of the file. */
    private fun texts(line: Int) = Fixture.query(db,
        """SELECT e.key || ' ' || o.roles FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id
           WHERE f.path = 'pack_dyn_vars.pkb' AND o.origin = 'DYNAMIC_LITERAL' AND o.start_line = $line ORDER BY o.start_col, o.id""")
}
