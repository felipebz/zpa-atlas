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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The places of a column's value read by a FETCH from a cursor variable, through its OPEN. */
class ColumnPlacesCursorsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `a FETCH from a cursor variable reads the query of the OPEN that runs last before it`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("opened.sql"), """
            create or replace package pk_of as
              type t_codes is table of varchar2(5);
              type t_strong is ref cursor return orders%rowtype;
              procedure show_of(p_id number, p_flag boolean, p_col varchar2);
              procedure from_param(p_cv sys_refcursor);
            end;
            /
            create or replace package body pk_of as
              procedure show_of(p_id number, p_flag boolean, p_col varchar2) is
                cv sys_refcursor;
                sc t_strong;
                l_sql varchar2(400);
                a1 varchar2(5); a2 varchar2(5);
                b1 varchar2(5); b2 varchar2(5); b3 varchar2(5); b4 varchar2(5);
                c1 varchar2(5); c2 varchar2(5); c3 varchar2(5); d1 varchar2(5);
                r1 orders%rowtype;
                r2 orders%rowtype;
                codes t_codes;
              begin
                open cv for select code, note from orders;
                fetch cv into a1, a2;

                open cv for 'select code from orders';
                fetch cv into b1;

                l_sql := 'select note from orders where order_id = :1';
                open cv for l_sql using p_id;
                fetch cv into b2;

                l_sql := 'select code, note from orders';
                if p_flag then
                  l_sql := l_sql || ' where order_id > 0';
                end if;
                open cv for l_sql;
                fetch cv into b3, b4;

                open cv for 'select order_id, code, note from orders';
                fetch cv into r1;

                open cv for 'select code from orders';
                fetch cv bulk collect into codes;

                open cv for select code from orders;
                fetch cv into c1;
                open cv for 'select note from orders';
                fetch cv into c2;

                if p_flag then
                  open cv for 'select code from orders';
                else
                  open cv for select note from orders;
                end if;
                fetch cv into c3;

                open cv for 'select ' || p_col || ' from orders';
                fetch cv into d1;

                open sc for select order_id, code, note from orders;
                fetch sc into r2;
              end;
              procedure from_param(p_cv sys_refcursor) is
                e1 varchar2(5);
              begin
                fetch p_cv into e1;
              end;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("opened.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.target_written || coalesce('.' || p.field, '') || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || p.class ||
                   coalesce(' ' || p.length, '') || (CASE WHEN p.bulk = 1 THEN ' BULK' ELSE '' END)
            FROM column_place p JOIN entity c ON c.id = p.column_id ORDER BY p.start_line, p.target_written, p.field, c.key""")).containsExactly(
            "a1 CODE FETCH FIXED_SIZE 5", "a2 NOTE FETCH FIXED_SIZE 5",     // a static OPEN
            "b1 CODE FETCH FIXED_SIZE 5",                                   // a literal text
            "b2 NOTE FETCH FIXED_SIZE 5",                                   // a text in a local, with USING
            "b3 CODE FETCH FIXED_SIZE 5", "b4 NOTE FETCH FIXED_SIZE 5",     // a text built with an append in a branch
            // A record takes the items field by field, as from an explicit cursor with that query.
            "r1.CODE CODE FETCH ANCHORED_TO_COLUMN", "r1.NOTE NOTE FETCH ANCHORED_TO_COLUMN", "r1.ORDER_ID ORDER_ID FETCH ANCHORED_TO_COLUMN",
            "codes CODE FETCH FIXED_SIZE 5 BULK",                           // the element's size
            "c1 CODE FETCH FIXED_SIZE 5",                                   // the static OPEN just before it only
            "c2 NOTE FETCH FIXED_SIZE 5",                                   // the string OPEN just before it only
            "c3 CODE FETCH FIXED_SIZE 5", "c3 NOTE FETCH FIXED_SIZE 5",     // one OPEN in each branch
            "r2.CODE CODE FETCH ANCHORED_TO_COLUMN", "r2.NOTE NOTE FETCH ANCHORED_TO_COLUMN", "r2.ORDER_ID ORDER_ID FETCH ANCHORED_TO_COLUMN"
        )
        // d1's item is a hole; e1's cursor comes in as a parameter.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ", "") + " " + it.substringAfter(": ").substringBefore(" ") })
            .containsExactly("FETCH_CURSOR_NOT_KNOWN_OPENED_DYNAMIC_PARSED_WITH_HOLES: 1", "FETCH_CURSOR_NOT_KNOWN_PARAMETER: 1")
    }

    @Test
    fun `what a FETCH from a cursor variable cannot read is counted by why`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("unread.sql"), """
            create or replace package pk_ur as
              g_sql varchar2(100) := 'select code from orders';
              procedure show_ur(p_flag boolean);
            end;
            /
            create or replace package body pk_ur as
              procedure show_ur(p_flag boolean) is
                cv sys_refcursor;
                l_sql varchar2(400);
                f1 varchar2(5); f2 varchar2(5); f3 varchar2(5); f4 varchar2(5); f5 varchar2(5); f6 varchar2(5);
              begin
                open cv for 'select code from';
                fetch cv into f1;
                open cv for g_sql;
                fetch cv into f2;
                if p_flag then
                  l_sql := 'select code from orders';
                else
                  l_sql := 'select note from orders';
                end if;
                open cv for l_sql;
                fetch cv into f3;
                open cv for select code, note from orders;
                fetch cv into f4, f5, f6;
              end;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("unread.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        assertThat(Fixture.query(db, "SELECT count(*) FROM column_place")).containsExactly("0")
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ", "") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "FETCH_CURSOR_ITEMS_NOT_MATCHED: 3",                            // three targets for two items
            "FETCH_CURSOR_NOT_KNOWN_OPENED_DYNAMIC_NOT_PARSED: 1",          // not one statement zpa parses
            "FETCH_CURSOR_NOT_KNOWN_OPENED_DYNAMIC_VARIABLE_NOT_LOCAL: 1",  // a package variable
            "FETCH_CURSOR_NOT_KNOWN_OPENED_DYNAMIC_VARIABLE_SEVERAL_WRITES: 1" // written in two branches
        )
    }

    @Test
    fun `a FETCH after a block that opens, of more items than a scalar, through a hole or a star not known`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("fixes.sql"), """
            create or replace package pk_fx as
              type t_nums is table of number;
              procedure show_fx(p_flag boolean, p_tab varchar2);
            end;
            /
            create or replace package body pk_fx as
              procedure show_fx(p_flag boolean, p_tab varchar2) is
                cv sys_refcursor;
                l_sql varchar2(400);
                v_nums t_nums := t_nums(1, 2);
                x4 varchar2(5); x7 varchar2(5); x8a varchar2(5); x8b varchar2(5); x12 varchar2(5); n1 number;
                x5 varchar2(5);
                r1 orders%rowtype;
                r2 orders%rowtype;
                cursor c_any is select * from unknown_t;
              begin
                open cv for select note from orders;
                begin
                  open cv for 'select code from orders';
                end;
                fetch cv into x4;

                open cv for select note from orders;
                begin
                  open cv for 'select code from orders';
                exception
                  when others then null;
                end;
                fetch cv into x5;

                l_sql := 'select code';
                if p_flag then
                  l_sql := l_sql || ', note';
                end if;
                l_sql := l_sql || ' from orders';
                open cv for l_sql;
                fetch cv into x12;

                open cv for 'select code from ' || p_tab;
                fetch cv into x7;

                open cv for 'select * from unknown_t';
                fetch cv into x8a, x8b;

                open cv for 'select * from unknown_t';
                fetch cv into r1;

                open cv for select * from table(v_nums);
                fetch cv into n1;

                open c_any;
                fetch c_any into r2;
              end;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("fixes.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || p.class || coalesce(' ' || p.length, '')
            FROM column_place p JOIN entity c ON c.id = p.column_id ORDER BY p.start_line, c.key""")).containsExactly(
            "x4 CODE FETCH FIXED_SIZE 5",                                   // a block with no handler is straight code
            "x5 CODE FETCH FIXED_SIZE 5", "x5 NOTE FETCH FIXED_SIZE 5"      // with a handler, its OPEN may not run
        )
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ", "") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "FETCH_CURSOR_ITEMS_NOT_MATCHED: 1",    // x12, one scalar for the two items of the text with its branch's append
            "FETCH_CURSOR_NOT_KNOWN_TEXT_HOLE: 1",  // x7, the table is a hole
            "FETCH_CURSOR_STAR_NOT_KNOWN: 5"        // x8a and x8b, r1, n1, and r2 from an explicit cursor
        )
    }
}
