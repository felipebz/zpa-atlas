//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.ColumnPlacesFixture.ORDERS_ITEMS
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The places of a value that goes round a cycle of calls, followed once. */
class ColumnPlacesCyclesTest {
    @TempDir
    lateinit var temp: Path

    @Test
    @Timeout(60)
    fun `a value that goes round through OUT parameters, concatenated on each turn, is followed once`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        // p_a gives its OUT parameter a column and passes it to p_b, which writes into it what p_a gives it, concatenated.
        Files.writeString(sources.resolve("msg.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace package pk_msg as
              procedure p_a(o_msg out varchar2);
              procedure p_b(o_msg in out varchar2);
            end;
            /
            create or replace package body pk_msg as
              procedure p_a(o_msg out varchar2) is
              begin
                select code into o_msg from orders where rownum = 1;
                p_b(o_msg);
              end;
              procedure p_b(o_msg in out varchar2) is
                l varchar2(100);
              begin
                p_a(l);
                o_msg := upper(o_msg) || l;
              end;
            end;
            /
            create or replace procedure show_msg is
              l varchar2(5);
            begin
              pk_msg.p_a(l);
            end;
            /
        """.trimIndent())
        val db = temp.resolve("msg.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        // The caller's l takes the column as is and concatenated, each once: going round again adds nothing.
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || ' ' || p.source || ' ' || p.class || ' ' || coalesce(p.functions, '-') || ' ' || p.concatenated
            FROM column_place p WHERE p.start_line = 25 ORDER BY 1""")).containsExactly(
            "25 l OUT_ARGUMENT FIXED_SIZE - 0",
            "25 l OUT_ARGUMENT FIXED_SIZE - 1",
            "25 l OUT_ARGUMENT FIXED_SIZE UPPER 1"
        )
    }

    @Test
    @Timeout(60)
    fun `a cycle that mixes value functions and concatenations writes each place once`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("mix.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace package pk_c as
              procedure p1(o out varchar2);
              procedure p2(o in out varchar2);
              procedure p3(o in out varchar2);
            end;
            /
            create or replace package body pk_c as
              procedure p1(o out varchar2) is
                l varchar2(100);
              begin
                select note into o from orders where rownum = 1;
                p2(o);
                p1(l);
                o := o || l;
              end;
              procedure p2(o in out varchar2) is
              begin
                o := upper(o);
                p3(o);
              end;
              procedure p3(o in out varchar2) is
                l varchar2(100);
              begin
                o := trim(o);
                p1(l);
                o := lower(l) || o;
              end;
            end;
            /
            create or replace procedure show_c is
              y varchar2(5);
            begin
              pk_c.p1(y);
            end;
            /
        """.trimIndent())
        val db = temp.resolve("mix.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        // TRIM,LOWER is reached with the concatenation at two points of the chain: one row.
        assertThat(Fixture.query(db, """
            SELECT p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || p.class || ' ' ||
                   coalesce(p.functions, '-') || ' ' || p.concatenated
            FROM column_place p JOIN entity c ON c.id = p.column_id WHERE p.target_written = 'y' ORDER BY 1""")).containsExactly(
            "y NOTE OUT_ARGUMENT FIXED_SIZE - 0",
            "y NOTE OUT_ARGUMENT FIXED_SIZE - 1",
            "y NOTE OUT_ARGUMENT FIXED_SIZE LOWER 1",
            "y NOTE OUT_ARGUMENT FIXED_SIZE LOWER,TRIM 1",
            "y NOTE OUT_ARGUMENT FIXED_SIZE TRIM,LOWER 1"
        )
        // No place row, the two l locals' included, is written twice.
        assertThat(Fixture.query(db, """
            SELECT count(*) FROM column_place GROUP BY file_id, start_line, start_col, end_line, end_col, column_id, source, target_entity_id,
                field, class, functions, concatenated, argument_parameter_id, call_path, call_line HAVING count(*) > 1""")).isEmpty()
    }

    @Test
    @Timeout(60)
    fun `a cycle that wraps the value in a function on each turn is followed once`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("cycles.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace function f_rec(n number) return varchar2 is
              l varchar2(20);
            begin
              if n = 0 then
                select code into l from orders where rownum = 1;
                return l;
              end if;
              return nvl(f_rec(n - 1), 'x');
            end;
            /
            create or replace function f_var(n number) return varchar2 is
              l varchar2(20);
            begin
              if n = 0 then
                select note into l from orders where rownum = 1;
              else
                l := 'x' || f_var(n - 1);
              end if;
              return l;
            end;
            /
            create or replace package pk_cycle as
              function f_ping(p_code varchar2, n number) return varchar2;
              function f_pong(p_code varchar2, n number) return varchar2;
            end;
            /
            create or replace package body pk_cycle as
              function f_ping(p_code varchar2, n number) return varchar2 is
              begin
                if n > 0 then return lower(f_pong(p_code, n - 1)); end if;
                return p_code;
              end;
              function f_pong(p_code varchar2, n number) return varchar2 is
              begin
                return upper(f_ping(trim(p_code), n));
              end;
            end;
            /
            create or replace procedure p_rec(p_code varchar2, n number) is
              l varchar2(6);
            begin
              l := p_code;
              if n > 0 then p_rec(upper(p_code), n - 1); end if;
            end;
            /
            create or replace procedure show_cycles is
              l1 varchar2(5); l2 varchar2(5); l3 varchar2(5);
            begin
              l1 := f_rec(3);
              l2 := f_var(3);
              for r in (select code from orders) loop
                l3 := pk_cycle.f_ping(r.code, 3);
                p_rec(r.code, 3);
              end loop;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("cycles.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        // Each target once per column, through the path that does not go round the cycle.
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' ||
                   p.class || ' ' || coalesce(p.functions, '-') || ' ' || p.concatenated
            FROM column_place p JOIN entity c ON c.id = p.column_id WHERE p.source IN ('RETURNED', 'IN_ARGUMENT') ORDER BY p.start_line, c.key""")).containsExactly(
            // f_var's l := 'x' || f_var(…): its own result written back into its local, once.
            "19 l NOTE RETURNED FIXED_SIZE - 1",
            "44 l CODE IN_ARGUMENT FIXED_SIZE - 0",       // p_rec's l := p_code; p_rec(upper(p_code)) only goes round
            "51 l1 CODE RETURNED FIXED_SIZE - 0",         // f_rec returns l; nvl(f_rec(…)) only goes round
            "52 l2 NOTE RETURNED FIXED_SIZE - 0",         // f_var returns l
            "54 l3 CODE RETURNED FIXED_SIZE - 0"          // f_ping returns p_code; through f_pong only goes round
        )
    }
}
