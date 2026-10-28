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

/** The value that reaches a dynamic statement whose variable is written several times (`dynamic-reach`, with the fixture's dictionary). */
class DynamicVariableReachTest {
    @TempDir
    lateinit var temp: Path

    private val db: Path by lazy {
        val sources = Path.of(javaClass.getResource("/dynamic-reach")!!.toURI())
        temp.resolve("reach.db").also {
            Indexer().index(IndexOptions(sources, it, compilationSchema = "HR", dictionary = Fixture.dictionary, threads = 1))
        }
    }

    @Test
    fun `a straight build gives the whole value and its appends, in source order`() {
        assertThat(reason(18)).isEqualTo("VARIABLE_PARSED")
        assertThat(read(18)).isEqualTo("VARIABLE_TEXT_BUILD")
        assertThat(texts(15, 16, 17)).containsExactly(
            "15 db:HR.COMPANY SQL_SELECT", "16 db:HR.COMPANY.COMPANY_NAME READ", "17 db:HR.COMPANY.ID READ"
        )
    }

    @Test
    fun `a variable reused for unrelated texts gives each statement its own`() {
        assertThat(reason(21)).isEqualTo("VARIABLE_PARSED")
        assertThat(texts(19, 20)).containsExactly("19 db:HR.COMPANY.COMPANY_NAME READ", "19 db:HR.COMPANY SQL_SELECT", "20 db:HR.COMPANY.ID READ")
        assertThat(read(39)).isEqualTo("VARIABLE_TEXT_WHOLE")
        assertThat(read(41)).isEqualTo("VARIABLE_TEXT_WHOLE")
        assertThat(texts(38)).containsExactly("38 db:HR.SEND_QUEUE SQL_DELETE", "38 db:HR.SEND_QUEUE.ID READ")
        assertThat(texts(40)).containsExactly("40 db:HR.EVENT_LOG SQL_DELETE", "40 db:HR.EVENT_LOG.ID READ")
    }

    @Test
    fun `appends in an IF without ELSE are optional fragments, one or several`() {
        assertThat(reason(26)).isEqualTo("VARIABLE_PARSED")
        assertThat(read(26)).isEqualTo("VARIABLE_TEXT_OPTIONAL")
        assertThat(texts(22, 24)).contains("24 db:HR.COMPANY.ID READ")
        assertThat(reason(37)).isEqualTo("VARIABLE_PARSED")
        assertThat(read(37)).isEqualTo("VARIABLE_TEXT_OPTIONAL")
        assertThat(texts(27, 29, 33)).containsExactly(
            "27 db:HR.EVENT_LOG.MSG READ", "27 db:HR.EVENT_LOG SQL_SELECT", "29 db:HR.EVENT_LOG.ID READ", "33 db:HR.EVENT_LOG.MSG READ"
        )
    }

    @Test
    fun `the walk climbs out of a block body, an IF branch and a loop that does not write the variable`() {
        assertThat(mapOf("block" to read(45), "branch" to read(51), "loop" to read(55))).containsExactlyInAnyOrderEntriesOf(mapOf(
            "block" to "VARIABLE_TEXT_BUILD", "branch" to "VARIABLE_TEXT_WHOLE", "loop" to "VARIABLE_TEXT_WHOLE"
        ))
        assertThat(listOf(reason(45), reason(51), reason(55))).containsOnly("VARIABLE_PARSED")
        assertThat(texts(43)).containsExactly("43 db:HR.COMPANY.ID READ")
        assertThat(texts(49)).containsExactly("49 db:HR.COMPANY SQL_DELETE", "49 db:HR.COMPANY.ID READ")
        assertThat(texts(53)).containsExactly("53 db:HR.SEND_QUEUE.ID READ", "53 db:HR.SEND_QUEUE SQL_SELECT", "53 db:HR.SEND_QUEUE.ID READ")
    }

    @Test
    fun `one reaching value run by two statements is walked once`() {
        assertThat(reason(59)).isEqualTo("VARIABLE_PARSED")
        assertThat(reason(60)).isEqualTo("VARIABLE_PARSED")
        assertThat(texts(57, 58)).containsExactly("57 db:HR.SEND_QUEUE.PAYLOAD READ", "57 db:HR.SEND_QUEUE SQL_SELECT", "58 db:HR.SEND_QUEUE.ID READ")
        assertThat(Fixture.query(db, "SELECT count(*) FROM sql_statement s JOIN source_file f ON f.id = s.file_id WHERE f.path = 'pack_dyn_reach.pkb' AND s.kind = 'SELECT' AND s.start_line = 57"))
            .containsExactly("1")
    }

    @Test
    fun `every other shape gives no text and says why`() {
        val lines = mapOf(
            "appends in the IF and the ELSIF" to 81, "an append in a nested block" to 86, "a whole value in a branch" to 91, "an INTO" to 94,
            "a call of unknown mode" to 97, "a loop that writes the variable" to 101, "an exception handler" to 109,
            "whole values in IF and ELSE only" to 116, "the variable read in the middle" to 119, "an append to the initial NULL" to 122, "a GOTO" to 132
        )
        assertThat(lines.mapValues { reason(it.value) }).containsExactlyInAnyOrderEntriesOf(lines.mapValues {
            if (it.key == "a call of unknown mode") "VARIABLE_UNSEEN_WRITE" else "VARIABLE_SEVERAL_WRITES"
        })
        assertThat(lines.values.mapNotNull { read(it) }).isEmpty()
        assertThat(Fixture.query(db, "SELECT count(*) FROM occurrence o JOIN source_file f ON f.id = o.file_id WHERE f.path = 'pack_dyn_reach.pkb' AND o.origin = 'DYNAMIC_LITERAL' AND o.start_line BETWEEN 63 AND 140"))
            .containsExactly("0")
    }

    @Test
    fun `a write in the condition, selector or declarations of what the statement is inside gives no text`() {
        val lines = mapOf("an IF condition" to 149, "an ELSIF condition" to 155, "a block's declarations" to 161, "a CASE selector" to 165, "a WHILE condition" to 170)
        assertThat(lines.mapValues { reason(it.value) }).containsExactlyInAnyOrderEntriesOf(lines.mapValues { "VARIABLE_SEVERAL_WRITES" })
        assertThat(lines.values.mapNotNull { read(it) }).isEmpty()
        assertThat(Fixture.query(db, "SELECT count(*) FROM occurrence o JOIN source_file f ON f.id = o.file_id WHERE f.path = 'pack_dyn_reach.pkb' AND o.origin = 'DYNAMIC_LITERAL' AND o.start_line BETWEEN 141 AND 171"))
            .containsExactly("0")
    }

    @Test
    fun `a write in an exception handler does not run before the statement of its block`() {
        assertThat(reason(174)).isEqualTo("VARIABLE_PARSED")
        assertThat(read(174)).isEqualTo("VARIABLE_TEXT_WHOLE")
        assertThat(texts(172)).containsExactly("172 db:HR.COMPANY SQL_DELETE", "172 db:HR.COMPANY.ID READ")
    }

    /** The reason of the `DYNAMIC` occurrence of the statement on [line]. */
    private fun reason(line: Int) = Fixture.query(db,
        """SELECT o.reason FROM occurrence o JOIN source_file f ON f.id = o.file_id
           WHERE f.path = 'pack_dyn_reach.pkb' AND o.status = 'DYNAMIC' AND o.start_line = $line""").single()

    /** The kind of the note that says how the text of the statement on [line] was read, or null. */
    private fun read(line: Int) = Fixture.query(db,
        """SELECT n.kind FROM coverage_note n JOIN source_file f ON f.id = n.file_id
           WHERE f.path = 'pack_dyn_reach.pkb' AND n.kind LIKE 'VARIABLE_TEXT_%' AND n.message LIKE 'Dynamic statement at line $line,%'""").singleOrNull()

    /** The line, entity key and roles of every name read from a text on [lines]. */
    private fun texts(vararg lines: Int) = Fixture.query(db,
        """SELECT o.start_line || ' ' || e.key || ' ' || o.roles FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id
           WHERE f.path = 'pack_dyn_reach.pkb' AND o.origin = 'DYNAMIC_LITERAL' AND o.start_line IN (${lines.joinToString()})
           ORDER BY o.start_line, o.start_col, o.id""")
}
