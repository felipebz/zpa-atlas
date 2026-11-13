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
import java.nio.file.Files
import java.nio.file.Path

/** Which column a name of a dynamic SQL text reads is zpa's decision, as for static SQL. */
class DynamicTextColumnReadsTest {
    @TempDir
    lateinit var temp: Path

    /** Line, written name, entity, status and reason of the column reads of `p_dyn`'s dynamic texts; then the undecided-name notes. */
    private fun reads(vararg statements: String): List<String> {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(sources.resolve("p_dyn.prc"), "create or replace procedure p_dyn is\n  v number;\n  qty number;\n  w varchar2(100);\nbegin\n" +
            statements.joinToString("") { "  $it\n" } + "end;\n/\n")
        val db = temp.resolve("dyn.db")
        val dictionary = shopTables(
            "ORDER_LINE" to listOf("ORDER_ID" to number(10), "QTY" to number(5)),
            "ORDER_HEAD" to listOf("ID" to number(10), "STATUS" to varchar2(5))
        )
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = dictionary, threads = 1))
        return Fixture.query(db, """
            SELECT o.start_line || ' ' || o.written || ' ' || coalesce(replace(e.key, 'db:SHOP.', ''), '-') || ' ' || o.status || coalesce(' ' || o.reason, '')
            FROM occurrence o LEFT JOIN entity e ON e.id = o.entity_id
            WHERE o.origin = 'DYNAMIC_LITERAL' AND o.roles LIKE '%READ%' AND o.roles NOT LIKE '%CALL%' AND o.roles NOT LIKE '%SQL_%'
            ORDER BY o.start_line, o.start_col""") +
            Fixture.query(db, "SELECT 'note ' || kind || ' ' || substr(message, 1, instr(message, ' ') - 1) FROM coverage_note WHERE kind IN ('SQL_NAME_UNDECIDED', 'DYNAMIC_NAME_UNDECIDED') ORDER BY kind")
    }

    @Test
    fun `a name the procedure declares is a column of a SQL statement text`() {
        // The text is compiled on its own: the procedure's qty is not visible there.
        assertThat(reads("execute immediate 'select qty from order_line' into v;")).containsExactly("6 qty ORDER_LINE.QTY RESOLVED")
    }

    @Test
    fun `a block text reads the field of its loop record when the alias lacks the column`() {
        // ORDER_HEAD has no QTY: Oracle reads the loop record's field, as in static SQL; it has STATUS.
        assertThat(reads(
            "execute immediate 'begin for l in (select 7 qty from dual) loop select l.qty into :x from order_head l; end loop; end;' using out v;",
            "execute immediate 'begin for l in (select 7 qty from dual) loop select l.status into :x from order_head l; end loop; end;' using out v;"
        )).containsExactly("7 l.status ORDER_HEAD.STATUS RESOLVED")
    }

    @Test
    fun `a hole in a value leaves the other names decided`() {
        // A number written as an operand: only the placeholder is undecided.
        assertThat(reads("execute immediate 'select status from order_head where id = ' || v into w;"))
            .containsExactly("6 status ORDER_HEAD.STATUS RESOLVED", "6 id ORDER_HEAD.ID RESOLVED", "note DYNAMIC_NAME_UNDECIDED 1")
    }

    @Test
    fun `a hole that may write a source leaves every name undecided`() {
        assertThat(reads("execute immediate 'select status from order_head where ' || w into w;"))
            .containsExactly("note DYNAMIC_NAME_UNDECIDED 2")
    }
}
