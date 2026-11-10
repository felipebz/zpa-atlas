//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.ColumnPlacesFixture.ORDERS_ITEMS
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The places of a column's value read within an expression: value functions, concatenation, NVL, inline views, REPLACE, and how the rest is counted. */
class ColumnPlacesValueTextTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `a column read through value functions is a place, other expressions stay counted`() {
        val sources = temp.resolve("src")
        PlacesFixture.write(sources)
        Files.writeString(sources.resolve("funcs.sql"), """
            declare
              l_code  varchar2(12);
              l_amt   number(5);
              l_flag  varchar2(1);
              cursor c_max is select max(code) from orders;
            begin
              select nvl(code, '-') into l_code from orders where order_id = 1;
              select max(code) into l_code from orders;
              select nvl(amount, 0) into l_amt from orders where order_id = 1;
              for r in (select code from orders) loop
                l_code := upper(trim(r.code));
              end loop;
              select nvl2(code, 'Y', 'N') into l_flag from orders where order_id = 1;
              select nvl(code, note) into l_code from orders where order_id = 1;
              select nvl(substr(code, 1, 3), '-') into l_code from orders where order_id = 1;
              open c_max;
              fetch c_max into l_code;
              close c_max;
              for r in (select upper(code) code from orders) loop
                l_code := r.code;
              end loop;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = PlacesFixture.dictionary, threads = 1))
        assertThat(places(db, "funcs.sql")).containsExactly(
            "7 ORDERS.CODE SELECT_INTO l_code FIXED_SIZE varchar2(12) 12 BYTE - - via NVL",
            "8 ORDERS.CODE SELECT_INTO l_code FIXED_SIZE varchar2(12) 12 BYTE - - via MAX",
            "9 ORDERS.AMOUNT SELECT_INTO l_amt FIXED_SIZE number(5) 5 DIGITS scale 0 - - via NVL",
            "11 ORDERS.CODE ASSIGNMENT l_code FIXED_SIZE varchar2(12) 12 BYTE - - via UPPER,TRIM",
            // Lines 13 (NVL2's first argument) and 14 (two columns) are not places.
            "15 ORDERS.CODE SELECT_INTO l_code FIXED_SIZE varchar2(12) 12 BYTE - - via NVL,SUBSTR",
            "17 ORDERS.CODE FETCH l_code FIXED_SIZE varchar2(12) 12 BYTE - - via MAX",
            "20 ORDERS.CODE ASSIGNMENT l_code FIXED_SIZE varchar2(12) 12 BYTE - - via UPPER"
        )
        assertThat(Fixture.query(db, """SELECT c.message FROM coverage_note c JOIN source_file f ON f.id = c.file_id
            WHERE c.kind = 'COLUMN_VALUE_NOT_FOLLOWED' AND f.path = 'funcs.sql' ORDER BY 1""").map { it.substringBefore(" ") })
            // NVL2 gives back its second or third argument, not the code it tests; nvl(code, note) reads two columns.
            .containsExactly("EXPRESSION_NVL2_CONDITION:", "EXPRESSION_SEVERAL_COLUMNS_NVL:")
    }

    @Test
    fun `a column's value concatenated into a target is a place of the column, which says so`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("cat.sql"), CONCATENATIONS)
        val db = temp.resolve("cat.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || p.class || ' ' ||
                   coalesce(p.length, -1) || ' ' || coalesce(p.functions, '-') || ' ' || p.concatenated
            FROM column_place p JOIN entity c ON c.id = p.column_id ORDER BY p.start_line, c.key""")).containsExactly(
            "12 l CODE IN_ARGUMENT FIXED_SIZE 11 - 1",          // k7  'C:' || p_code
            "13 l CODE IN_ARGUMENT FIXED_SIZE 12 UPPER 1",      // k8  upper(p_code) || '-' || nvl(p_code, '?'): one place, by its first part
            "14 l CODE IN_ARGUMENT FIXED_SIZE 13 SUBSTR 1",     // k9  substr(p_code || 'x', 1, 5)
            "15 l CODE IN_ARGUMENT FIXED_SIZE 14 - 1",          // k10 concat(p_code, 'x')
            "16 l CODE IN_ARGUMENT FIXED_SIZE 15 - 1",          // k11 select p_code || 'x' into l
            "29 l1 CODE SELECT_INTO FIXED_SIZE 16 - 1",         // k1
            "30 l2 CODE SELECT_INTO FIXED_SIZE 17 - 1",         // k2, a place for each column
            "30 l2 NOTE SELECT_INTO FIXED_SIZE 17 - 1",
            "31 l3 CODE SELECT_INTO FIXED_SIZE 18 UPPER 1",     // k3 upper(code || 'x')
            "33 l4 CODE ASSIGNMENT FIXED_SIZE 19 - 1",          // k4 r.code || '#'
            "34 l5 CODE FETCH FIXED_SIZE 20 - 1",               // k5 the cursor's item code || '/'
            "35 l6 CODE RETURNING FIXED_SIZE 10 - 1",           // k6
            "47 o_text CODE SELECT_INTO CALLER_DECIDES -1 - 1", // an OUT parameter ...
            "55 l7 CODE OUT_ARGUMENT FIXED_SIZE 21 - 1",        // ... and its caller's argument
            "57 l8 CODE ASSIGNMENT FIXED_SIZE 22 - 1",          // r.code || r.note, a place for each field
            "57 l8 NOTE ASSIGNMENT FIXED_SIZE 22 - 1"
        )
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED'")).isEmpty()
    }

    @Test
    fun `an NVL count says where following its column stops, and an expression of several columns is counted by its shape`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("nv.sql"), NVL_STOPS)
        val db = temp.resolve("nv.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "ARGUMENT_EXPRESSION_SEVERAL_COLUMNS_NVL: 1",            // take(nvl(r.code, r.note))
            "EXPRESSION_NVL_OTHER_ARITHMETIC: 1",                    // v17: the + around max(order_id), not MAX
            "EXPRESSION_NVL_OTHER_DECODE: 1",                        // v4
            "EXPRESSION_NVL_OTHER_LPAD: 1",                          // v13
            "EXPRESSION_NVL_OTHER_ROUTINE: 1",                       // v5: fmt's result is a place, the item is counted as well
            "EXPRESSION_NVL_TO_CHAR: 4",                             // v2, v8, v15 (through UPPER), v16 (through an inner NVL)
            "EXPRESSION_SEVERAL_COLUMNS_NVL2: 2",                    // v12, m7
            "EXPRESSION_SEVERAL_COLUMNS_NVL: 4",                     // v3, v9, v6 (amount in the CASE), v11 (the subquery's column)
            "EXPRESSION_SEVERAL_COLUMNS_OTHER_CASE: 1",              // m2
            "EXPRESSION_SEVERAL_COLUMNS_OTHER_COALESCE: 1",          // m5
            "EXPRESSION_SEVERAL_COLUMNS_OTHER_DECODE: 1",            // m1
            "EXPRESSION_SEVERAL_COLUMNS_OTHER_GREATEST: 1",          // m3
            "RECORD_ASSIGNMENT_EXPRESSION_SEVERAL_COLUMNS_NVL: 1",   // a2
            "RETURNED_EXPRESSION_NVL_OTHER_COMPARISON: 1"            // ok: code_of's result compared
        )
        // The places are those an NVL already gave: m4's concatenation, b1 through both's NVL, the routines' own INTOs.
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || replace(c.key, 'db:SHOP.', '') || ' ' || p.source || ' ' || p.target_written || ' ' || coalesce(p.functions, '-')
            FROM column_place p JOIN entity c ON c.id = p.column_id ORDER BY p.id""")).containsExactly(
            "18 ORDERS.CODE SELECT_INTO l -",
            "25 ORDERS.CODE SELECT_INTO l -",
            "25 ORDERS.NOTE SELECT_INTO n -",
            "32 ORDERS.CODE IN_ARGUMENT l1 NVL,LOWER",
            "43 ORDERS.CODE SELECT_INTO v1 NVL",
            "47 ORDERS.CODE RETURNED v5 NVL",
            "49 ORDERS.CODE SELECT_INTO v7 NVL,LOWER",
            "52 ORDERS.AMOUNT SELECT_INTO v10 NVL",
            "56 ORDERS.CODE SELECT_INTO v14 COALESCE,LOWER",
            "62 ORDERS.CODE SELECT_INTO m4 -",
            "62 ORDERS.NOTE SELECT_INTO m4 -",
            "66 ORDERS.CODE ASSIGNMENT a1 NVL,LOWER",
            "71 ORDERS.CODE RETURNED f1 NVL,LOWER",
            "72 ORDERS.CODE RETURNED b1 NVL",
            "72 ORDERS.NOTE RETURNED b1 NVL"
        )
    }

    @Test
    fun `the largest not followed counts say what they are made of`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("m.sql"), MADE_OF)
        val db = temp.resolve("m.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ", "") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "ARGUMENT_COLUMN_NOT_KNOWN_NO_COLUMN: 1",                       // pk_m.take(x.lit), a literal
            "ARGUMENT_EXPRESSION_OTHER_ARITHMETIC: 1",                      // to_number(p_code) + 1
            "ARGUMENT_EXPRESSION_OTHER_DECODE: 1",                          // decode(p_code, …)
            "EXPRESSION_OTHER_ARITHMETIC: 1",                               // order_id * 2
            "EXPRESSION_OTHER_DECODE: 1",                                   // decode(code, 'a', 'b')
            "EXPRESSION_OTHER_ROUTINE: 1",                                  // pk_m.fmt(code)
            // c and d, opened for a query and for a string just before their FETCH, are followed: no count.
            "FETCH_CURSOR_NOT_KNOWN_PARAMETER: 1",                          // from_param's p
            "FETCH_CURSOR_NOT_KNOWN_STRONG: 1",                             // from_strong's p, of order_cur
            "RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN_EXPRESSION_OTHER_DECODE: 1", // w.dc
            "RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN_NO_COLUMN: 2",              // x.v (extractvalue over table(…)), x.rn (rownum)
            "RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN_REF_CURSOR: 1"              // r.code, r p%rowtype
        )
        // Places already: lower(…) is a value function the index follows (take's l := lower(p_code), the select's
        // lower(code)); unknown_tab's code is taken by name as a column of it, so y.code is followed; z.code is the
        // code of orders the inline view reads; pk_m.fmt(code) returns its parameter into l.
        assertThat(Fixture.query(db, "SELECT p.start_line || ' ' || p.source FROM column_place p ORDER BY p.start_line"))
            .containsExactly("18 IN_ARGUMENT", "48 ASSIGNMENT", "52 ASSIGNMENT", "57 RETURNED", "58 SELECT_INTO", "60 FETCH", "62 FETCH")
    }

    @Test
    fun `a column read through an inline view or a WITH query gives the place a direct read gives`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("iv.sql"), INLINE_VIEWS)
        val db = temp.resolve("iv.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        fun places(from: Int, to: Int) = Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || coalesce('.' || p.field, '') || ' ' || replace(replace(c.key, 'db:SHOP.', ''), 'ORDERS.', '') || ' ' ||
                   p.source || ' ' || p.class || ' ' || coalesce(p.length, '-') || ' ' || coalesce(p.functions, '-') || ' ' || p.concatenated
            FROM column_place p JOIN entity c ON c.id = p.column_id WHERE p.start_line BETWEEN $from AND $to ORDER BY p.start_line, p.start_col, c.key""")
        assertThat(places(1, 46)).containsExactly(
            "9 l CODE IN_ARGUMENT FIXED_SIZE 5 - 0",                // take's l := p_code, from pk_iv.take(z.code)
            "20 l1 CODE SELECT_INTO FIXED_SIZE 5 - 0",              // v1 v.code
            "21 l2 CODE SELECT_INTO FIXED_SIZE 5 - 0",              // v2 bare name
            "22 l3 CODE SELECT_INTO FIXED_SIZE 5 - 0",              // v3 renamed
            "23 l4 CODE SELECT_INTO FIXED_SIZE 5 - 0",              // v4 WITH
            "24 l5 CODE SELECT_INTO FIXED_SIZE 5 - 0",              //    WITH read by another
            "25 l6 CODE SELECT_INTO FIXED_SIZE 5 - 0",              // v5 nested
            "26 l7 CODE SELECT_INTO FIXED_SIZE 5 - 0",              // v6 union: both columns
            "26 l7 NOTE SELECT_INTO FIXED_SIZE 5 - 0",
            "27 l8 CODE SELECT_INTO FIXED_SIZE 5 UPPER 0",          // v7
            "28 l9 CODE SELECT_INTO FIXED_SIZE 5 NVL 0",
            "29 l10 CODE SELECT_INTO FIXED_SIZE 5 - 1",
            "30 l11 CODE SELECT_INTO FIXED_SIZE 5 REPLACE 0",           // replace(v.code, …), through the view
            "31 r_all.CODE CODE SELECT_INTO ANCHORED_TO_COLUMN - - 0", // v9 * of the view into orders%rowtype
            "31 r_all.NOTE NOTE SELECT_INTO ANCHORED_TO_COLUMN - - 0",
            "31 r_all.ORDER_ID ORDER_ID SELECT_INTO ANCHORED_TO_COLUMN - - 0",
            "32 l12 CODE SELECT_INTO FIXED_SIZE 5 - 0",             //    a view joined with a table
            "32 l13 ITEMS.SKU SELECT_INTO FIXED_SIZE 5 - 0",
            "34 l1 CODE ASSIGNMENT FIXED_SIZE 5 - 0",               // v10 cursor loop
            "37 r_iv.CODE CODE FETCH ANCHORED_TO_COLUMN - - 0",     //     FETCH into the cursor's %ROWTYPE
            "38 l2 CODE ASSIGNMENT FIXED_SIZE 5 - 0",
            "40 l3 CODE ASSIGNMENT FIXED_SIZE 5 - 0",               //     a loop over a WITH query
            "45 l5 CODE SELECT_INTO FIXED_SIZE 5 MAX 0"             // v12 MAX is a value function; COUNT gives nothing
        )
        // The same reads made directly give the same places.
        assertThat(places(52, 70).map { it.substringAfter(' ') }).containsExactly(
            "l8 CODE SELECT_INTO FIXED_SIZE 5 UPPER 0",
            "l9 CODE SELECT_INTO FIXED_SIZE 5 NVL 0",
            "l10 CODE SELECT_INTO FIXED_SIZE 5 - 1",
            "l11 CODE SELECT_INTO FIXED_SIZE 5 REPLACE 0",
            "r_all.CODE CODE SELECT_INTO ANCHORED_TO_COLUMN - - 0",
            "r_all.NOTE NOTE SELECT_INTO ANCHORED_TO_COLUMN - - 0",
            "r_all.ORDER_ID ORDER_ID SELECT_INTO ANCHORED_TO_COLUMN - - 0",
            "r_iv.CODE CODE FETCH ANCHORED_TO_COLUMN - - 0"
        )
        // v8, replace(code, …), is a place as a direct read is; v11 is counted under _NO_COLUMN.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ", "") + " " + it.substringAfter(": ").substringBefore(" ") })
            .containsExactly("RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN_NO_COLUMN: 1")
    }

    @Test
    fun `an expression is named by an Oracle built-in only, a collection element and any other call apart`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("names.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace package pk_names as
              procedure take(p_code varchar2);
            end;
            /
            create or replace package body pk_names as
              procedure take(p_code varchar2) is
                type t_map is table of varchar2(30) index by varchar2(20);
                m t_map;
                l varchar2(30);
              begin
                l := fmt_code(p_code);
                l := m(p_code);
                l := lpad(p_code, 30);
              end;
            end;
            /
            create or replace procedure show_names is
              l varchar2(30);
            begin
              select fmt_code(code), lpad(code, 30) into l, l from orders where rownum = 1;
              for r in (select code from orders) loop
                pk_names.take(r.code);
              end loop;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("names.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = PlacesFixture.dictionary, threads = 1))
        // fmt_code is not in the index: a routine, not named; m(p_code) is an element of the local collection m.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ", "") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "ARGUMENT_EXPRESSION_OTHER_ELEMENT: 1",
            "ARGUMENT_EXPRESSION_OTHER_LPAD: 1",
            "ARGUMENT_EXPRESSION_OTHER_ROUTINE: 1",
            "EXPRESSION_OTHER_LPAD: 1",
            "EXPRESSION_OTHER_ROUTINE: 1"
        )
    }

    @Test
    fun `a column's value read through REPLACE or REGEXP_REPLACE is the value, or text put into another when it is the replacement`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("replace.sql"), """
            create or replace package pk_rp as
              function code_of(p_id number) return varchar2;
              function clean(p_id number) return varchar2;
              function with_code(p_id number) return varchar2;
              function strip(p_v varchar2) return varchar2;
              procedure out_code(p_id number, p_out out varchar2);
              procedure show_rp(p_id number);
            end;
            /
            create or replace package body pk_rp as
              function code_of(p_id number) return varchar2 is
                l varchar2(20);
              begin
                select code into l from orders where order_id = p_id;
                return l;
              end;

              function clean(p_id number) return varchar2 is
                l varchar2(20);
              begin
                select code into l from orders where order_id = p_id;
                return replace(l, ' ', '_');
              end;

              function with_code(p_id number) return varchar2 is
                l varchar2(20);
              begin
                select code into l from orders where order_id = p_id;
                return replace('Code: #', '#', l);
              end;

              function strip(p_v varchar2) return varchar2 is
                l1 varchar2(5);
              begin
                l1 := replace(p_v, ' ');
                return l1;
              end;

              procedure out_code(p_id number, p_out out varchar2) is
                l varchar2(20);
              begin
                select code into l from orders where order_id = p_id;
                p_out := replace(l, '''', '''''');
              end;

              procedure show_rp(p_id number) is
                s1 varchar2(5); s2 varchar2(5); s3 varchar2(5); s4 varchar2(5); s5 varchar2(5);
                s6 varchar2(5); s7 varchar2(5); s8 varchar2(5); s9 varchar2(5);
                a1 varchar2(5); a2 varchar2(5);
                f1 varchar2(5); f2 varchar2(5); f3 varchar2(5); f4 varchar2(5);
                o1 varchar2(5);
                k1 varchar2(5);
                g1 varchar2(5); g2 varchar2(5);
                type t_codes is table of varchar2(5);
                codes t_codes;
                cursor c_rp is select replace(code, '-') v from orders;
              begin
                select replace(code, ' ', '_') into s1 from orders where order_id = p_id;
                select replace(code, ' ') into s2 from orders where order_id = p_id;
                select replace('Code: #', '#', code) into s3 from orders where order_id = p_id;
                select replace(note, code) into s4 from orders where order_id = p_id;
                select replace(code, 'x', note) into s5 from orders where order_id = p_id;
                select upper(replace(trim(code), ' ')) into s6 from orders where order_id = p_id;
                select 'x' || replace(code, ' ') into s7 from orders where order_id = p_id;
                select nvl(replace(code, ' '), 'none') into s8 from orders where order_id = p_id;
                select replace(code || note, ' ') into s9 from orders where order_id = p_id;
                select regexp_replace(code, '[0-9]') into g1 from orders where order_id = p_id;
                select regexp_replace(code, '(.)', '\1\1') into g2 from orders where order_id = p_id;
                select replace(code, ' ') bulk collect into codes from orders;
                open c_rp;
                fetch c_rp into k1;
                close c_rp;
                for r in (select code, note from orders) loop
                  a1 := replace(r.code, ' ');
                  a2 := replace('[#]', '#', r.note);
                end loop;
                f1 := replace(code_of(p_id), ' ');
                f2 := clean(p_id);
                f3 := with_code(p_id);
                f4 := strip(code_of(p_id));
                out_code(p_id, o1);
              end;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("replace.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || (CASE WHEN p.bulk = 1 THEN ' BULK' ELSE '' END) || ' ' ||
                   p.class || coalesce(' ' || p.length, '') || ' ' || coalesce(p.functions, '-') || (CASE WHEN p.concatenated = 1 THEN ' concatenated' ELSE '' END)
            FROM column_place p JOIN entity c ON c.id = p.column_id ORDER BY p.start_line, p.target_written, c.key""")).containsExactly(
            "l CODE SELECT_INTO FIXED_SIZE 20 -", "l CODE SELECT_INTO FIXED_SIZE 20 -", "l CODE SELECT_INTO FIXED_SIZE 20 -",
            "l1 CODE IN_ARGUMENT FIXED_SIZE 5 REPLACE",                     // strip's, from f4's code_of result
            "l CODE SELECT_INTO FIXED_SIZE 20 -",
            "p_out CODE ASSIGNMENT CALLER_DECIDES REPLACE",                 // held by l, read in out_code itself: no call
            "s1 CODE SELECT_INTO FIXED_SIZE 5 REPLACE", "s2 CODE SELECT_INTO FIXED_SIZE 5 REPLACE",
            "s3 CODE SELECT_INTO FIXED_SIZE 5 REPLACE concatenated",        // the column is the replacement
            "s4 NOTE SELECT_INTO FIXED_SIZE 5 REPLACE",                     // code is the search text: no place
            "s5 CODE SELECT_INTO FIXED_SIZE 5 REPLACE", "s5 NOTE SELECT_INTO FIXED_SIZE 5 REPLACE concatenated",
            "s6 CODE SELECT_INTO FIXED_SIZE 5 UPPER,REPLACE,TRIM",
            "s7 CODE SELECT_INTO FIXED_SIZE 5 REPLACE concatenated",
            "s8 CODE SELECT_INTO FIXED_SIZE 5 NVL,REPLACE",
            "s9 CODE SELECT_INTO FIXED_SIZE 5 REPLACE concatenated", "s9 NOTE SELECT_INTO FIXED_SIZE 5 REPLACE concatenated",
            "g1 CODE SELECT_INTO FIXED_SIZE 5 REGEXP_REPLACE", "g2 CODE SELECT_INTO FIXED_SIZE 5 REGEXP_REPLACE",
            "codes CODE SELECT_INTO BULK FIXED_SIZE 5 REPLACE",
            "k1 CODE FETCH FIXED_SIZE 5 REPLACE",
            "a1 CODE ASSIGNMENT FIXED_SIZE 5 REPLACE", "a2 NOTE ASSIGNMENT FIXED_SIZE 5 REPLACE concatenated",
            "f1 CODE RETURNED FIXED_SIZE 5 REPLACE", "f2 CODE RETURNED FIXED_SIZE 5 REPLACE", "f3 CODE RETURNED FIXED_SIZE 5 REPLACE concatenated",
            "f4 CODE RETURNED FIXED_SIZE 5 REPLACE",
            "o1 CODE OUT_ARGUMENT FIXED_SIZE 5 REPLACE"
        )
        // Nothing of the forms above is counted any more.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED'")).isEmpty()
    }

    @Test
    fun `in PL SQL too, a field or a local in the search text of a REPLACE is not read, and the others are as in SQL`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("replace_plsql.sql"), """
            create or replace package pk_rq as
              procedure put_in(p_v varchar2);
              function f_both(p_id number) return varchar2;
              function f_lit(p_id number) return varchar2;
              procedure show_rq;
            end;
            /
            create or replace package body pk_rq as
              procedure put_in(p_v varchar2) is l2 varchar2(5); begin l2 := p_v; end;
              function f_both(p_id number) return varchar2 is l_note varchar2(40); l_code varchar2(20);
              begin select note, code into l_note, l_code from orders where order_id = p_id; return replace(l_note, l_code); end;
              function f_lit(p_id number) return varchar2 is l_code varchar2(20);
              begin select code into l_code from orders where order_id = p_id; return replace('abc', l_code); end;
              procedure show_rq is
                a3 varchar2(5); a4 varchar2(5); a5 varchar2(5); a6 varchar2(5); a7 varchar2(5); a8 varchar2(5);
                b1 varchar2(5); b2 varchar2(5);
              begin
                for r in (select code, note from orders) loop
                  a3 := replace(r.note, r.code);
                  a4 := replace(r.code, 'x', r.note);
                  a5 := regexp_replace(r.code, '[a-z]', r.note);
                  a6 := regexp_replace(r.code, r.note);
                  a7 := replace('abc', r.code);
                  a8 := replace(r.note, 'x', r.code) || r.code;
                  put_in(replace(r.note, r.code));
                end loop;
                b1 := f_both(1);
                b2 := f_lit(1);
              end;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("replace_plsql.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || p.class || coalesce(' ' || p.length, '') ||
                   ' ' || coalesce(p.functions, '-') || (CASE WHEN p.concatenated = 1 THEN ' concatenated' ELSE '' END)
            FROM column_place p JOIN entity c ON c.id = p.column_id ORDER BY p.start_line, p.target_written, c.key, p.functions """)).containsExactly(
            "l2 NOTE IN_ARGUMENT FIXED_SIZE 5 REPLACE",                     // put_in(replace(r.note, r.code)): the field searched is not passed
            "l_code CODE SELECT_INTO FIXED_SIZE 20 -", "l_note NOTE SELECT_INTO FIXED_SIZE 40 -", "l_code CODE SELECT_INTO FIXED_SIZE 20 -",
            "a3 NOTE ASSIGNMENT FIXED_SIZE 5 REPLACE",                      // r.code is the search text
            "a4 CODE ASSIGNMENT FIXED_SIZE 5 REPLACE", "a4 NOTE ASSIGNMENT FIXED_SIZE 5 REPLACE concatenated",
            "a5 CODE ASSIGNMENT FIXED_SIZE 5 REGEXP_REPLACE", "a5 NOTE ASSIGNMENT FIXED_SIZE 5 REGEXP_REPLACE concatenated",
            "a6 CODE ASSIGNMENT FIXED_SIZE 5 REGEXP_REPLACE",               // r.note is the pattern
            // a7, replace('abc', r.code): nothing, the field is only searched for.
            "a8 CODE ASSIGNMENT FIXED_SIZE 5 - concatenated", "a8 CODE ASSIGNMENT FIXED_SIZE 5 REPLACE concatenated",
            "a8 NOTE ASSIGNMENT FIXED_SIZE 5 REPLACE concatenated",
            "b1 NOTE RETURNED FIXED_SIZE 5 REPLACE"                         // return replace(l_note, l_code); f_lit's l_code gives nothing
        )
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED'")).isEmpty()
    }

    companion object {
        private val INLINE_VIEWS = """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));
            create table items (order_id number(10), sku varchar2(15 byte));

            create or replace package pk_iv as
              procedure take(p_code varchar2);
            end;
            /
            create or replace package body pk_iv as
              procedure take(p_code varchar2) is l varchar2(5); begin l := p_code; end;
            end;
            /
            create or replace procedure show_iv is
              l1 varchar2(5); l2 varchar2(5); l3 varchar2(5); l4 varchar2(5); l5 varchar2(5);
              l6 varchar2(5); l7 varchar2(5); l8 varchar2(5); l9 varchar2(5); l10 varchar2(5);
              l11 varchar2(5); l12 varchar2(5); l13 varchar2(5); n number;
              r_all orders%rowtype;
              cursor c_iv is select v.code from (select code from orders) v;
              r_iv c_iv%rowtype;
            begin
              select v.code into l1 from (select code from orders) v where rownum = 1;
              select code into l2 from (select code from orders) where rownum = 1;
              select x.c2 into l3 from (select code as c2 from orders) x where rownum = 1;
              with w as (select code from orders) select w.code into l4 from w where rownum = 1;
              with a as (select code from orders), b as (select code from a) select code into l5 from b where rownum = 1;
              select code into l6 from (select code from (select code from orders)) where rownum = 1;
              select u.v into l7 from (select code as v from orders union all select note from orders) u where rownum = 1;
              select up into l8 from (select upper(code) as up from orders) where rownum = 1;
              select nvl(v.code, 'x') into l9 from (select code from orders) v where rownum = 1;
              select tag into l10 from (select 'C:' || code as tag from orders) where rownum = 1;
              select r into l11 from (select replace(code, 'a', 'b') as r from orders) where rownum = 1;
              select * into r_all from (select * from orders) where rownum = 1;
              select v.code, i.sku into l12, l13 from (select order_id, code from orders) v join items i on i.order_id = v.order_id where rownum = 1;
              for z in (select v.code from (select code from orders) v) loop
                l1 := z.code;
                pk_iv.take(z.code);
              end loop;
              open c_iv; fetch c_iv into r_iv; close c_iv;
              l2 := r_iv.code;
              for q in (with w as (select code from orders) select code from w) loop
                l3 := q.code;
              end loop;
              for k in (select lit from (select 'k' as lit from dual)) loop
                l4 := k.lit;
              end loop;
              select m into l5 from (select max(code) as m from orders) where rownum = 1;
              select cnt into n from (select count(*) as cnt from orders);
            end;
            /
            create or replace procedure show_direct is
              l8 varchar2(5); l9 varchar2(5); l10 varchar2(5); l11 varchar2(5);
              r_all orders%rowtype;
              cursor c_d is select code from orders;
              r_iv c_d%rowtype;
            begin
              select upper(code) into l8 from orders where rownum = 1;
              select nvl(code, 'x') into l9 from orders where rownum = 1;
              select 'C:' || code into l10 from orders where rownum = 1;
              select replace(code, 'a', 'b') into l11 from orders where rownum = 1;
              select * into r_all from orders where rownum = 1;
              open c_d; fetch c_d into r_iv; close c_d;
            end;
            /
        """.trimIndent()

        private val MADE_OF = """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace package pk_m as
              type order_cur is ref cursor return orders%rowtype;
              procedure take(p_code varchar2);
              procedure from_strong(p order_cur);
              procedure from_param(p sys_refcursor);
              function fmt(p varchar2) return varchar2;
            end;
            /
            create or replace package body pk_m as
              procedure take(p_code varchar2) is
                l varchar2(30);
                n number;
              begin
                l := decode(p_code, 'a', 'b', 'c');
                n := to_number(p_code) + 1;
                l := lower(p_code);
              end;
              procedure from_strong(p order_cur) is
                r p%rowtype;
                l varchar2(30);
              begin
                fetch p into r;
                l := r.code;
              end;
              procedure from_param(p sys_refcursor) is
                l varchar2(30);
              begin
                fetch p into l;
              end;
              function fmt(p varchar2) return varchar2 is begin return p; end;
            end;
            /
            create or replace procedure show_m is
              l varchar2(100);
              n number;
              c sys_refcursor;
              d sys_refcursor;
            begin
              for x in (select extractvalue(value(t), '/a') as v, rownum as rn, 'k' as lit
                          from table(xmlsequence(xmltype('<a>1</a>').extract('/a'))) t) loop
                l := x.v;
                n := x.rn;
                pk_m.take(x.lit);
              end loop;
              for y in (select code from unknown_tab) loop
                l := y.code;
                pk_m.take(y.code);
              end loop;
              for z in (select v.code from (select code from orders) v) loop
                l := z.code;
              end loop;
              for w in (select decode(code, 'a', 'b') as dc from orders) loop
                l := w.dc;
              end loop;
              select decode(code, 'a', 'b'), order_id * 2, pk_m.fmt(code), lower(code)
                into l, n, l, l from orders where rownum = 1;
              open c for select code from orders;
              fetch c into l;
              open d for 'select code from orders';
              fetch d into l;
            end;
            /
        """.trimIndent()

        /** `NVL`s whose column goes through what the index does not follow, and expressions of several columns. */
        private val NVL_STOPS = """
            create table orders (order_id number(10), code varchar2(20 char), note varchar2(40 char), amount number(8,2), created date);
            create table order_notes (order_id number(10), code varchar2(20 char));

            create or replace package pk_nv as
              function fmt(p varchar2) return varchar2;
              function code_of(p_id number) return varchar2;
              function both(p_id number) return varchar2;
              procedure take(p_v varchar2);
              procedure show_nv(p_id number);
            end;
            /
            create or replace package body pk_nv as
              function fmt(p varchar2) return varchar2 is begin return p; end;

              function code_of(p_id number) return varchar2 is
                l varchar2(20 char);
              begin
                select code into l from orders where order_id = p_id;
                return l;
              end;

              function both(p_id number) return varchar2 is
                l varchar2(20 char); n varchar2(40 char);
              begin
                select code, note into l, n from orders where order_id = p_id;
                return nvl(l, n);
              end;

              procedure take(p_v varchar2) is
                l1 varchar2(5 char);
              begin
                l1 := p_v;
              end;

              procedure show_nv(p_id number) is
                v1 varchar2(30); v2 varchar2(30); v3 varchar2(30); v4 varchar2(30); v5 varchar2(30); v6 varchar2(30);
                v7 varchar2(30); v8 varchar2(30); v9 varchar2(30); v10 varchar2(30); v11 varchar2(30); v12 varchar2(30);
                v13 varchar2(30); v14 varchar2(30); v15 varchar2(30); v16 varchar2(30); v17 number; ok boolean;
                m1 varchar2(30); m2 varchar2(30); m3 varchar2(30); m4 varchar2(30); m5 varchar2(30); m7 varchar2(30);
                a1 varchar2(30); a2 varchar2(30);
                f1 varchar2(30); b1 varchar2(30);
              begin
                select nvl(code, 'x') into v1 from orders where order_id = p_id;
                select nvl(to_char(order_id), 'x') into v2 from orders where order_id = p_id;
                select nvl(code, note) into v3 from orders where order_id = p_id;
                select nvl(decode(code, 'a', 'b'), 'x') into v4 from orders where order_id = p_id;
                select nvl(fmt(code), 'x') into v5 from orders where order_id = p_id;
                select nvl(case when amount > 0 then code end, 'x') into v6 from orders where order_id = p_id;
                select nvl(lower(code), 'x') into v7 from orders where order_id = p_id;
                select nvl(to_char(created, 'yyyy'), '-') into v8 from orders where order_id = p_id;
                select nvl(o.code, n.code) into v9 from orders o left join order_notes n on n.order_id = o.order_id where o.order_id = p_id;
                select nvl(amount, 0) into v10 from orders where order_id = p_id;
                select nvl(trim(code), (select max(code) from order_notes)) into v11 from orders where order_id = p_id;
                select nvl2(code, note, 'x') into v12 from orders where order_id = p_id;
                select nvl(lpad(code, 10), 'x') into v13 from orders where order_id = p_id;
                select coalesce(lower(code), 'x') into v14 from orders where order_id = p_id;
                select nvl(upper(to_char(order_id)), 'x') into v15 from orders where order_id = p_id;
                select nvl(nvl(to_char(order_id), 'a'), 'b') into v16 from orders where order_id = p_id;
                select decode(code, 'a', note, 'x') into m1 from orders where order_id = p_id;
                select case when amount > 0 then code else note end into m2 from orders where order_id = p_id;
                select greatest(code, note) into m3 from orders where order_id = p_id;
                select code || note into m4 from orders where order_id = p_id;
                select coalesce(code, note, 'x') into m5 from orders where order_id = p_id;
                select nvl2(amount, code, 'x') into m7 from orders where order_id = p_id;
                for r in (select code, note from orders) loop
                  a1 := nvl(lower(r.code), 'x');
                  a2 := nvl(r.code, r.note);
                  take(nvl(lower(r.code), 'x'));
                  take(nvl(r.code, r.note));
                end loop;
                f1 := nvl(lower(code_of(p_id)), 'x');
                b1 := both(p_id);
                select nvl((max(order_id) + 1), 1) into v17 from orders;
                ok := nvl(code_of(p_id), 'x') = 'x';
              end;
            end;
            /
        """.trimIndent()

        private val CONCATENATIONS = """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace package pk_cat as
              procedure p_param(p_code varchar2);
              procedure p_param_upper(p_code varchar2);
              procedure p_param_inner(p_code varchar2);
              procedure p_param_concat_fn(p_code varchar2);
              procedure p_param_into(p_code varchar2);
            end;
            /
            create or replace package body pk_cat as
              procedure p_param(p_code varchar2) is l varchar2(11); begin l := 'C:' || p_code; end;
              procedure p_param_upper(p_code varchar2) is l varchar2(12); begin l := upper(p_code) || '-' || nvl(p_code, '?'); end;
              procedure p_param_inner(p_code varchar2) is l varchar2(13); begin l := substr(p_code || 'x', 1, 5); end;
              procedure p_param_concat_fn(p_code varchar2) is l varchar2(14); begin l := concat(p_code, 'x'); end;
              procedure p_param_into(p_code varchar2) is l varchar2(15); begin select p_code || 'x' into l from dual; end;
            end;
            /
            create or replace procedure show_cat is
              l1 varchar2(16);
              l2 varchar2(17);
              l3 varchar2(18);
              l4 varchar2(19);
              l5 varchar2(20);
              l6 varchar2(10);
              r orders%rowtype;
              cursor c is select code || '/' as joined from orders;
            begin
              select code || '-' into l1 from orders where rownum = 1;
              select code || note into l2 from orders where rownum = 1;
              select upper(code || 'x') into l3 from orders where rownum = 1;
              select * into r from orders where rownum = 1;
              l4 := r.code || '#';
              open c; fetch c into l5; close c;
              update orders set note = note where rownum = 1 returning code || '!' into l6;
              for x in (select code from orders) loop
                pk_cat.p_param(x.code);
                pk_cat.p_param_upper(x.code);
                pk_cat.p_param_inner(x.code);
                pk_cat.p_param_concat_fn(x.code);
                pk_cat.p_param_into(x.code);
              end loop;
            end;
            /
            create or replace procedure fill_cat(o_text out varchar2) is
            begin
              select code || '+' into o_text from orders where rownum = 1;
            end;
            /
            create or replace procedure show_cat_more is
              l7 varchar2(21);
              l8 varchar2(22);
              r orders%rowtype;
            begin
              fill_cat(l7);
              select * into r from orders where rownum = 1;
              l8 := r.code || r.note;
            end;
            /
        """.trimIndent()
    }
}
