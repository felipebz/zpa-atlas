//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/** The line of code each place's row shows, and the piece it marks. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlaceLinesTest {
    private val temp: Path = Files.createTempDirectory("atlas-place-lines")
    private lateinit var index: AtlasIndex

    @BeforeAll
    fun build() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("orders.sql"), "create table orders (order_id number(10), code varchar2(10 byte), name varchar2(30));\n")
        Files.writeString(sources.resolve("mc.sql"), """
            create or replace package pk_mc as
              function add_macro(p_text varchar2, p_name varchar2, p_value varchar2) return varchar2;
              procedure fill(p_id number);
              procedure two_lines(p_id number);
            end;
            /
            create or replace package body pk_mc as
              type t_macro is record (name varchar2(30), value varchar2(5));
              type t_macros is table of t_macro index by pls_integer;
              g_macros t_macros;
              function add_macro(p_text varchar2, p_name varchar2, p_value varchar2) return varchar2 is
                v_pos pls_integer := g_macros.count + 1;
              begin
                g_macros(v_pos).name := p_name;
                g_macros(v_pos).value := p_value;
                return replace(p_text, p_name, p_value);
              end;

              procedure fill(p_id number) is
                v_code varchar2(5);
              begin
                select code into v_code from orders where order_id = p_id;
              end;

              procedure two_lines(p_id number) is
                v_code2 varchar2(5);
                cursor c is select code from orders;
                v_f varchar2(5);
              begin
                select upper(code)
                  into v_code2
                  from orders
                 where order_id = p_id;
                open c;
                fetch c into v_f;
                close c;
              end;

              procedure out_code(p_id number, p_out out varchar2) is
                l varchar2(10);
              begin
                select code into l from orders where order_id = p_id;
                p_out := replace(l, '-');
              end;

              procedure fill_text(p_id number) is
                v_text varchar2(200);
              begin
                for r in (select code from orders where order_id = p_id) loop
                  v_text := add_macro(v_text, '<code>', r.code);
                end loop;
              end;
            end;
            /
        """.trimIndent() + "\n")
        Files.writeString(sources.resolve("caller.sql"), """
            create or replace procedure use_mc is
              v_t varchar2(200);
            begin
              for r in (select code from orders) loop
                v_t := pk_mc.add_macro(v_t, 'x', r.code);
              end loop;
            end;
            /
        """.trimIndent() + "\n")
        Files.writeString(sources.resolve("shapes.sql"), """
            create or replace procedure shapes(p_id number) is
              v_ret varchar2(5);
              v_del varchar2(6);
              v_rec varchar2(7);
              v_a varchar2(3);
              v_b varchar2(4);
            begin
              update orders
                 set code = upper(code)
               where order_id = p_id
              returning code into v_ret;
              delete from orders
               where order_id = p_id
                 and code is not null
              returning code into v_del;
              for r in (select code from orders) loop
                v_rec := r.code;
              end loop;
              select name, code
                into v_b, v_a
                from orders
               where code = 'x';
            end;
            /
        """.trimIndent() + "\n")
        val db = temp.resolve("lines.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", threads = 1))
        index = AtlasIndex(db, sources)
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun `a row shows the call for a parameter, the line its statement reads the column on, else the target's`() {
        val page = index.columnPlaceGroups("db:SHOP.ORDERS.CODE", PlaceFilter(verdicts = Verdict.entries.toSet(), concatenated = true)).result!!
        val rows = page.groups.flatMap { it.rows }.associateBy { it.targetWritten }
        /** The row's target line, the line it shows (file:line, its text) and the piece marked. */
        fun shown(target: String): String {
            val row = rows.getValue(target)
            val line = row.shown!!
            return "${row.line} ${line.path}:${line.line} ${line.text!!.trim()} [${line.text.substring(line.from, line.to!!)}]"
        }
        assertThat(shown("v_code")).isEqualTo("22 mc.sql:22 select code into v_code from orders where order_id = p_id; [code]")
        assertThat(shown("v_code2")).isEqualTo("31 mc.sql:30 select upper(code) [code]")
        assertThat(shown("v_f")).isEqualTo("35 mc.sql:35 fetch c into v_f; [v_f]")
        assertThat(shown("g_macros(v_pos).value")).isEqualTo("15 caller.sql:5 v_t := pk_mc.add_macro(v_t, 'x', r.code); [r.code]")
        // The read that gives the target its value: the RETURNING item after a SET or a WHERE, the target's own select item.
        assertThat(shown("v_ret")).isEqualTo("11 shapes.sql:11 returning code into v_ret; [code]")
        assertThat(shown("v_del")).isEqualTo("15 shapes.sql:15 returning code into v_del; [code]")
        assertThat(shown("v_a")).isEqualTo("20 shapes.sql:19 select name, code [code]")
        // No read in the statement: the target's line, the target marked as written, not its `:=`.
        assertThat(shown("v_rec")).isEqualTo("17 shapes.sql:17 v_rec := r.code; [v_rec]")
        assertThat(shown("p_out")).isEqualTo("43 mc.sql:43 p_out := replace(l, '-'); [p_out]")
        assertThat(shown("v_text")).isEqualTo("50 mc.sql:50 v_text := add_macro(v_text, '<code>', r.code); [v_text]")
        // The MCP's rows give the line, not its text.
        val listed = index.columnPlaces("db:SHOP.ORDERS.CODE", PlaceFilter(verdicts = Verdict.entries.toSet())).result!!.rows
        assertThat(listed.single { it.targetWritten == "v_code2" }.shown).isEqualTo(PlaceLine("mc.sql", 30, 17, 21))
    }
}
