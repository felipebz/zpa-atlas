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

/**
 * Which column a name of static SQL reads is zpa's decision: the cases where it reads otherwise than the scope walk the
 * atlas made before, each as Oracle reads it.
 */
class ColumnReadsDecisionTest {
    @TempDir
    lateinit var temp: Path

    /**
     * Line, written name, entity, roles, status and reason of the reads in the statements of `p_reads` (not the
     * relations, the calls or `v`); then the undecided-name note.
     */
    private fun reads(vararg statements: String): List<String> {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(sources.resolve("pk_d.sql"), """
            create or replace package pk_d as
              g_qty number;
              function f_id return number;
            end;
            /
        """.trimIndent() + "\n")
        Files.writeString(sources.resolve("p_reads.prc"), "create or replace procedure p_reads is\n  v number;\nbegin\n" +
            statements.joinToString("") { "  $it\n" } + "end;\n/\n")
        val db = temp.resolve("reads.db")
        val dictionary = shopTables(
            "ORDER_LINE" to listOf("ORDER_ID" to number(10), "QTY" to number(5)),
            "ORDER_HEAD" to listOf("ID" to number(10), "STATUS" to varchar2(5)),
            "ORDER_OBJ" to listOf("ID" to number(10), "OBJECT_ID" to number(10))
        )
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = dictionary, threads = 1))
        return Fixture.query(db, """
            SELECT o.start_line || ' ' || o.written || ' ' || coalesce(replace(e.key, 'db:SHOP.', ''), '-') || ' ' || o.roles || ' ' || o.status ||
                   coalesce(' ' || o.reason, '')
            FROM occurrence o LEFT JOIN entity e ON e.id = o.entity_id JOIN source_file f ON f.id = o.file_id
            WHERE f.path = 'p_reads.prc' AND o.statement_id IS NOT NULL AND o.roles NOT LIKE '%SQL_%' AND o.roles NOT LIKE '%CALL%' AND lower(o.written) <> 'v'
            ORDER BY o.start_line, o.start_col""") +
            Fixture.query(db, "SELECT 'note ' || substr(message, 1, instr(message, ' ') - 1) FROM coverage_note WHERE kind = 'SQL_NAME_UNDECIDED'")
    }

    @Test
    fun `an inline view that lacks the column passes a qualified name outward, then to PL SQL`() {
        // The scope walk read nothing: the inline view captured the name. Oracle reads the package variable.
        assertThat(reads("select pk_d.g_qty into v from (select order_id from order_line) pk_d;"))
            .containsExactly("4 pk_d.g_qty PK_D.G_QTY READ RESOLVED", "4 order_id ORDER_LINE.ORDER_ID READ RESOLVED")
    }

    @Test
    fun `a known match beside a source whose columns are not known reads one of both, unverified`() {
        // zpa does not decide: missing_tab may have a qty too (ORA-00918). Its candidates are both columns.
        assertThat(reads("select qty into v from order_line, missing_tab;")).containsExactly("4 qty - READ UNVERIFIED COLUMN_CANDIDATES")
        assertThat(Fixture.query(temp.resolve("reads.db"), """
            SELECT replace(e.key, 'db:SHOP.', '') FROM occurrence_candidate c JOIN occurrence o ON o.id = c.occurrence_id JOIN entity e ON e.id = c.entity_id
            WHERE o.written = 'qty' ORDER BY e.key""")).containsExactly("MISSING_TAB.QTY", "ORDER_LINE.QTY")
    }

    @Test
    fun `a name of the only source whose columns are not known reads its column, unverified`() {
        // zpa does not decide, as a parameterless schema function may take the name: its one candidate is the column, of a
        // relation the dictionary lacks.
        assertThat(reads("select x_col into v from missing_tab;")).containsExactly("4 x_col MISSING_TAB.X_COL READ UNRESOLVED COLUMN_CANDIDATE")
    }

    @Test
    fun `a name a declaration may take reads no candidate`() {
        // A loop index of that name (no entity of its own): counted. An alias lacking the column, named like an object
        // that may hold a package variable: the missing column is not read, as code that compiles reads the variable.
        assertThat(reads(
            "for x_col in 1 .. 2 loop select count(*) into v from missing_tab where x_col = 1; end loop;",
            "select zz.no_col into v from order_head zz;"
        )).containsExactly("note 2")
    }

    @Test
    fun `an inline view that is not LATERAL does not see the query that holds it`() {
        // The scope walk read ORDER_HEAD.STATUS of the holding query; Oracle rejects it (ORA-00904): nothing is read.
        assertThat(reads("select count(*) into v from order_head h, (select status s from order_line) x;")).isEmpty()
    }

    @Test
    fun `a column named like a pseudocolumn is the column`() {
        // The scope walk read nothing: OBJECT_ID was a pseudocolumn.
        assertThat(reads("select object_id into v from order_obj;")).containsExactly("4 object_id ORDER_OBJ.OBJECT_ID READ RESOLVED")
    }

    @Test
    fun `an alias that lacks the column takes no function of a package of its name`() {
        // A package variable takes `pk_d.g_qty`; its function never does: the missing column, which Oracle rejects.
        assertThat(reads(
            "select pk_d.g_qty into v from order_head pk_d;",
            "select pk_d.f_id into v from order_head pk_d;"
        )).containsExactly("4 pk_d.g_qty PK_D.G_QTY READ RESOLVED", "5 pk_d.f_id ORDER_HEAD.F_ID READ UNRESOLVED NOT_IN_DICTIONARY")
    }

    @Test
    fun `a name marked for an outer join reads its column`() {
        // zpa answers `h.id(+)` and `status(+)` as the names without the marker.
        assertThat(reads("select count(*) into v from order_line l, order_head h where l.order_id = h.id(+) and status(+) = 'X';")).containsExactly(
            "4 l.order_id ORDER_LINE.ORDER_ID READ RESOLVED", "4 h.id ORDER_HEAD.ID READ RESOLVED", "4 status ORDER_HEAD.STATUS READ RESOLVED"
        )
    }
}
