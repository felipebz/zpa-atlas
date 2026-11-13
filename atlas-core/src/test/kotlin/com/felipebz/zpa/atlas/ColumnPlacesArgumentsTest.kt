//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.api.project.ProjectExternalColumn
import com.felipebz.zpa.api.project.ProjectExternalDatatype
import com.felipebz.zpa.api.project.ProjectExternalDatatype.LengthSemantics
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionaryRelation
import com.felipebz.zpa.atlas.dictionary.DictionaryRelationKind
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The places a column's value reaches through the arguments of routine calls: OUT parameters and IN parameters. */
class ColumnPlacesArgumentsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `the arguments callers pass for an OUT parameter are places, through the callers' own OUT parameters`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("calls.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte));
            /
            create or replace package pk_ord as
              procedure get_code(p_id number, o_code out varchar2);
              procedure relay(p_id number, o_code out varchar2);
            end;
            /
            create or replace package body pk_ord as
              procedure get_code(p_id number, o_code out varchar2) is
              begin
                select code into o_code from orders where order_id = p_id;
              end;
              procedure relay(p_id number, o_code out varchar2) is
              begin
                get_code(p_id, o_code);
              end;
            end;
            /
            create or replace procedure show_code is
              l_code varchar2(12);
              l_wide varchar2(30);
              l_any  orders.code%type;
              type r_t is record (c varchar2(12));
              r      r_t;
              type a_t is table of varchar2(12) index by pls_integer;
              a      a_t;
            begin
              pk_ord.get_code(1, l_code);
              pk_ord.get_code(1, l_wide);
              pk_ord.get_code(o_code => l_code, p_id => 1);
              pk_ord.get_code(1, l_any);
              pk_ord.get_code(1, r.c);
              pk_ord.get_code(1, a(1));
              pk_ord.relay(1, l_code);
              pk_ord.get_code(1, l_code || 'x');
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = shopTables("ORDERS" to listOf("ORDER_ID" to number(10), "CODE" to varchar2(20))), threads = 1))
        assertThat(places(db, "calls.sql")).containsExactly(
            // The parameter's own place stays: a caller the index does not see may pass anything.
            "11 ORDERS.CODE SELECT_INTO o_code CALLER_DECIDES varchar2 - - - OUT",
            // relay passes its own OUT parameter: its callers are followed in turn.
            "15 ORDERS.CODE OUT_ARGUMENT o_code CALLER_DECIDES varchar2 - - - OUT",
            "28 ORDERS.CODE OUT_ARGUMENT l_code FIXED_SIZE varchar2(12) 12 BYTE - -",
            "29 ORDERS.CODE OUT_ARGUMENT l_wide FIXED_SIZE varchar2(30) 30 BYTE - -",
            // A named argument.
            "30 ORDERS.CODE OUT_ARGUMENT l_code FIXED_SIZE varchar2(12) 12 BYTE - -",
            "31 ORDERS.CODE OUT_ARGUMENT l_any ANCHORED_TO_COLUMN orders.code%type - ORDERS.CODE -",
            "32 ORDERS.CODE OUT_ARGUMENT r.c FIXED_SIZE varchar2(12) 12 BYTE - -",
            "33 ORDERS.CODE OUT_ARGUMENT a(1) FIXED_SIZE a_t 12 BYTE SHOW_CODE().A_T -",
            // Through relay's o_code.
            "34 ORDERS.CODE OUT_ARGUMENT l_code FIXED_SIZE varchar2(12) 12 BYTE - -"
        )
        // Line 35 passes an expression: counted.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED'").map { it.substringBefore(" ") })
            .containsExactly("OUT_ARGUMENT_NOT_FOLLOWED:")
        assertThat(Fixture.query(db, "SELECT a.key || ' ' || g.key FROM column_place p JOIN entity a ON a.id = p.argument_parameter_id JOIN entity g ON g.id = p.argument_routine_id WHERE p.start_line = 34"))
            .containsExactly("db:SHOP.PK_ORD.RELAY(P_ID IN NUMBER,O_CODE OUT VARCHAR2).O_CODE db:SHOP.PK_ORD.RELAY(P_ID IN NUMBER,O_CODE OUT VARCHAR2)")
    }

    @Test
    fun `the arguments a routine passes to its own nested subprogram's OUT parameter are places`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("nested.sql"), """
            create table ord_x (order_id number(10), code varchar2(20 byte));

            create or replace procedure show_x is
              l_code varchar2(12);
              l_wide varchar2(30);
              procedure inner(o_i out varchar2) is
              begin
                select code into o_i from ord_x where order_id = 1;
              end;
              procedure outer(p_n number, o_o out varchar2) is
              begin
                inner(o_o);
              end;
            begin
              inner(l_code);
              outer(o_o => l_wide, p_n => 1);
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = shopTables("ORD_X" to listOf("ORDER_ID" to number(10), "CODE" to varchar2(20))), threads = 1))
        assertThat(places(db, "nested.sql")).containsExactly(
            "8 ORD_X.CODE SELECT_INTO o_i CALLER_DECIDES varchar2 - - - OUT",
            // outer passes its own OUT parameter: outer's callers are followed in turn.
            "12 ORD_X.CODE OUT_ARGUMENT o_o CALLER_DECIDES varchar2 - - - OUT",
            "15 ORD_X.CODE OUT_ARGUMENT l_code FIXED_SIZE varchar2(12) 12 BYTE - -",
            "16 ORD_X.CODE OUT_ARGUMENT l_wide FIXED_SIZE varchar2(30) 30 BYTE - -"
        )
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED'")).isEmpty()
    }

    @Test
    fun `an object type method's OUT parameter, whose calls the index does not record, is counted`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("box.sql"), """
            create table ord_x (order_id number(10), code varchar2(20 byte));
            create or replace type t_box as object (n number, member procedure get_code(o_c out varchar2));
            /
            create or replace type body t_box as
              member procedure get_code(o_c out varchar2) is
              begin
                select code into o_c from ord_x where order_id = 1;
              end;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = shopTables("ORD_X" to listOf("ORDER_ID" to number(10), "CODE" to varchar2(20))), threads = 1))
        assertThat(places(db, "box.sql")).containsExactly("7 ORD_X.CODE SELECT_INTO o_c CALLER_DECIDES varchar2 - - - OUT")
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED'").map { it.substringBefore(":") })
            .containsExactly("OUT_PARAMETER_NOT_FOLLOWED")
    }

    @Test
    fun `a column passed to a routine's IN parameter is a place of each target the routine writes the parameter into`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("fmt.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte), ref_code varchar2(20 byte));
            create table short_codes (c varchar2(5));

            create or replace package pk_fmt as
              g_short varchar2(12);
              function f_assign(p_code varchar2) return number;
              function f_anchored(p_code orders.code%type) return number;
              function f_upper(p_code varchar2) return number;
              function f_nvl(p_code varchar2) return number;
              function f_relay(p_code varchar2) return number;
              function f_global(p_code varchar2) return number;
              function f_wide(p_code varchar2) return number;
              function f_filter(p_code varchar2) return number;
              function f_return(p_code varchar2) return varchar2;
              procedure p_assign(p_code varchar2);
            end;
            /
            create or replace package body pk_fmt as
              function f_assign(p_code varchar2) return number is l varchar2(12); begin l := p_code; return 1; end;
              function f_anchored(p_code orders.code%type) return number is l varchar2(12); begin l := p_code; return 1; end;
              function f_upper(p_code varchar2) return number is l varchar2(12); begin l := upper(p_code); return 1; end;
              function f_nvl(p_code varchar2) return number is l varchar2(12); begin l := nvl(p_code, '-'); return 1; end;
              function f_relay(p_code varchar2) return number is begin return f_assign(p_code); end;
              function f_global(p_code varchar2) return number is begin g_short := p_code; return 1; end;
              function f_wide(p_code varchar2) return number is l varchar2(30); begin l := p_code; return 1; end;
              function f_filter(p_code varchar2) return number is n number;
                begin select count(*) into n from orders where code = p_code; return n; end;
              function f_return(p_code varchar2) return varchar2 is begin return p_code; end;
              procedure p_assign(p_code varchar2) is l varchar2(12); begin l := p_code; end;
            end;
            /
            create or replace procedure show_code is
              n number;
              l varchar2(12);
              r orders%rowtype;
            begin
              select count(*) into n from orders where pk_fmt.f_assign(code) = 1;
              update orders set order_id = order_id where pk_fmt.f_assign(code) = 1;
              select pk_fmt.f_anchored(code) into n from orders;
              select pk_fmt.f_upper(code) into n from orders;
              select pk_fmt.f_nvl(code) into n from orders;
              select pk_fmt.f_relay(code) into n from orders;
              select pk_fmt.f_global(code) into n from orders;
              select pk_fmt.f_wide(code) into n from orders;
              select pk_fmt.f_filter(code) into n from orders;
              select * into r from orders;
              pk_fmt.p_assign(r.code);
              for c in (select code from orders) loop pk_fmt.p_assign(c.code); end loop;
              select pk_fmt.f_return(code) into l from orders;
              select pk_fmt.f_relay(ref_code) into n from orders;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = shopTables("ORDERS" to listOf("ORDER_ID" to number(10), "CODE" to varchar2(20), "REF_CODE" to varchar2(20)), "SHORT_CODES" to listOf("C" to varchar2(5))), threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.target_written || ' ' || p.class || ' ' || coalesce(p.declared_type, '-') || ' ' || coalesce(p.functions, '-') || ' ' ||
                   replace(a.key, 'db:SHOP.PK_FMT.', '') || ' ' || p.call_count || ' ' || p.call_path || ':' || p.call_line
            FROM column_place p JOIN entity a ON a.id = p.argument_parameter_id JOIN entity c ON c.id = p.column_id
            WHERE p.source = 'IN_ARGUMENT' ORDER BY p.start_line, c.key""")).containsExactly(
            // f_assign's local: from the WHERE of a SELECT and of an UPDATE, one place for both calls; f_relay passes
            // its parameter on to f_assign, which gives the column passed only to f_relay a place there too.
            "19 CODE l FIXED_SIZE varchar2(12) - F_ASSIGN(P_CODE IN VARCHAR2).P_CODE 2 fmt.sql:37",
            "19 REF_CODE l FIXED_SIZE varchar2(12) - F_RELAY(P_CODE IN VARCHAR2).P_CODE 1 fmt.sql:50",
            // A %TYPE parameter does not limit the value: the local does.
            "20 CODE l FIXED_SIZE varchar2(12) - F_ANCHORED(P_CODE IN ORDERS.CODE%TYPE).P_CODE 1 fmt.sql:39",
            "21 CODE l FIXED_SIZE varchar2(12) UPPER F_UPPER(P_CODE IN VARCHAR2).P_CODE 1 fmt.sql:40",
            "22 CODE l FIXED_SIZE varchar2(12) NVL F_NVL(P_CODE IN VARCHAR2).P_CODE 1 fmt.sql:41",
            "24 CODE g_short FIXED_SIZE varchar2(12) - F_GLOBAL(P_CODE IN VARCHAR2).P_CODE 1 fmt.sql:43",
            "25 CODE l FIXED_SIZE varchar2(30) - F_WIDE(P_CODE IN VARCHAR2).P_CODE 1 fmt.sql:44",
            // A record's field passed in PL/SQL, from a %ROWTYPE variable and a cursor loop record: one place.
            "29 CODE l FIXED_SIZE varchar2(12) - P_ASSIGN(P_CODE IN VARCHAR2).P_CODE 2 fmt.sql:47"
            // f_filter only compares its parameter: no place.
        )
        // What each function returns into n or l is counted once, as an expression of the column; f_return returns its
        // parameter, which reaches l as the function's result.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY 1").map { it.substringBefore(" ") })
            .containsExactly("EXPRESSION_OTHER_ROUTINE:")
        assertThat(Fixture.query(db, "SELECT p.start_line || ' ' || p.target_written || ' ' || p.class FROM column_place p WHERE p.source = 'RETURNED'"))
            .containsExactly("49 l FIXED_SIZE")
    }

    @Test
    fun `an IN parameter selected into a target, passed to a nested subprogram, or to a routine with no PL SQL body`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("more.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte));

            create or replace package pk_more as
              procedure p_into(p_code varchar2);
              procedure p_nested(p_code varchar2);
            end;
            /
            create or replace package body pk_more as
              procedure p_into(p_code varchar2) is l varchar2(9);
              begin select p_code into l from dual; end;
              procedure p_nested(p_code varchar2) is
                procedure inner(p_i varchar2) is l varchar2(13); begin l := p_i; end;
              begin inner(p_code); end;
            end;
            /
            create or replace function f_java(p_code varchar2) return number
              as language java name 'Fmt.f(java.lang.String) return int';
            /
            create or replace procedure show_more is
              n number;
              procedure direct(p_d varchar2) is l varchar2(7); begin l := p_d; end;
            begin
              for r in (select code from orders) loop
                pk_more.p_into(r.code);
                pk_more.p_nested(r.code);
                direct(r.code);
              end loop;
              select f_java(code) into n from orders;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = shopTables("ORDERS" to listOf("ORDER_ID" to number(10), "CODE" to varchar2(20))), threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || ' ' || p.class || ' ' || coalesce(p.declared_type, '-') || ' ' ||
                   coalesce(replace(a.key, 'db:SHOP.PK_MORE.', ''), '-') || ' ' || p.call_count || ' ' || p.call_path || ':' || p.call_line
            FROM column_place p LEFT JOIN entity a ON a.id = p.argument_parameter_id WHERE p.source = 'IN_ARGUMENT' ORDER BY p.start_line""")).containsExactly(
            // Selected into a local, as an assignment would write it.
            "10 l FIXED_SIZE varchar2(9) P_INTO(P_CODE IN VARCHAR2).P_CODE 1 more.sql:24",
            // Passed on to a nested subprogram, which has no entity: followed where it is declared.
            "12 l FIXED_SIZE varchar2(13) P_NESTED(P_CODE IN VARCHAR2).P_CODE 1 more.sql:25",
            // Passed straight to a nested subprogram of the calling routine.
            "21 l FIXED_SIZE varchar2(7) - 1 more.sql:26"
        )
        // f_java's body is Java: what it is given is counted, as for a routine outside the index.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' AND message LIKE 'ARGUMENT%'")
            .map { it.substringBefore(" not") }).containsExactly("ARGUMENT_ROUTINE_OUTSIDE: 1 column value(s) passed to a routine whose PL/SQL body is")
    }

    @Test
    fun `an IN parameter selected into a record, and calls of routines outside the index counted at each call`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("rec.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte));
            create table short_codes (c varchar2(5));

            create or replace package pk_rec as
              procedure p_one(p_code varchar2);
              procedure p_two(p_code varchar2);
              procedure p_rowtype(p_code varchar2);
              procedure d_one;
              procedure d_two;
              procedure d_rowtype;
            end;
            /
            create or replace package body pk_rec as
              type r1_t is record (f varchar2(3));
              type r2_t is record (n number, f varchar2(4));
              procedure p_one(p_code varchar2) is r r1_t; begin select p_code into r from dual; end;
              procedure p_two(p_code varchar2) is r r2_t; begin select 1, p_code into r from dual; end;
              procedure p_rowtype(p_code varchar2) is r short_codes%rowtype; begin select p_code into r from dual; end;
              procedure d_one is r r1_t; begin select code into r from orders where rownum = 1; end;
              procedure d_two is r r2_t; begin select 1, code into r from orders where rownum = 1; end;
              procedure d_rowtype is r short_codes%rowtype; begin select code into r from orders where rownum = 1; end;
            end;
            /
        """.trimIndent())
        Files.writeString(sources.resolve("ext.sql"), """
            create or replace function f_ext(p_code varchar2) return number
              as language java name 'Ext.f(java.lang.String) return int';
            /
        """.trimIndent())
        Files.writeString(sources.resolve("show.sql"), """
            create or replace procedure show_rec is
              n number;
            begin
              for r in (select code from orders) loop
                pk_rec.p_one(r.code);
                pk_rec.p_two(r.code);
                pk_rec.p_rowtype(r.code);
              end loop;
              select f_ext(code) into n from orders;
              select f_ext(code) into n from orders where order_id = 1;
              select count(*) into n from orders where pk_missing.f(code) = 1;
              select count(*) into n from orders where pk_missing.f(code) = 2;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("places.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = shopTables("ORDERS" to listOf("ORDER_ID" to number(10), "CODE" to varchar2(20))), threads = 1))
        // The parameter gives the place the column gives in the same position of the same target (the %ROWTYPE's columns
        // are not known without SHORT_CODES in the dictionary, for both).
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || p.source || ' ' || coalesce(p.field, '-') || ' ' || p.class || ' ' ||
                   coalesce(p.length, -1) || ' ' || coalesce(p.reason, '-')
            FROM column_place p WHERE p.start_line BETWEEN 16 AND 21 ORDER BY p.start_line""")).containsExactly(
            "16 IN_ARGUMENT F FIXED_SIZE 3 -",
            "17 IN_ARGUMENT F FIXED_SIZE 4 -",
            "18 IN_ARGUMENT - UNKNOWN -1 ROWTYPE_COLUMNS_UNKNOWN",
            "19 SELECT_INTO F FIXED_SIZE 3 -",
            "20 SELECT_INTO F FIXED_SIZE 4 -",
            "21 SELECT_INTO - UNKNOWN -1 ROWTYPE_COLUMNS_UNKNOWN"
        )
        // One per call, in the caller's file: the call spec's file counts nothing.
        assertThat(Fixture.query(db, """
            SELECT f.path || ' ' || n.message FROM coverage_note n JOIN source_file f ON f.id = n.file_id
            WHERE n.message LIKE 'ARGUMENT%'""").map { it.substringBefore(" column") }).containsExactly("show.sql ARGUMENT_ROUTINE_OUTSIDE: 4")
    }

    @Test
    fun `an IN parameter read in a statement on a table`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("tab.sql"), """
            create or replace package pk_tab as
              procedure p_dual(p_code varchar2);
              procedure p_sel(p_code varchar2);
              procedure p_nvl(p_code varchar2);
              procedure p_ret(p_code varchar2);
            end;
            /
            create or replace package body pk_tab as
              procedure p_dual(p_code varchar2) is l varchar2(7); begin select p_code into l from dual; end;
              procedure p_sel(p_code varchar2) is l varchar2(8); begin select p_code into l from orders where rownum = 1; end;
              procedure p_nvl(p_code varchar2) is l varchar2(9); begin select nvl(alt_code, p_code) into l from orders where rownum = 1; end;
              procedure p_ret(p_code varchar2) is l varchar2(10); begin update orders set order_id = 1 where rownum = 1 returning p_code into l; end;
            end;
            /
            create or replace procedure show_tab is
            begin
              for r in (select code from orders) loop
                pk_tab.p_dual(r.code);
                pk_tab.p_sel(r.code);
                pk_tab.p_nvl(r.code);
                pk_tab.p_ret(r.code);
              end loop;
            end;
            /
        """.trimIndent())
        fun type(length: Int?) = ProjectExternalDatatype.of(if (length == null) "NUMBER" else "VARCHAR2", null, length,
            length?.let { LengthSemantics.BYTE }, if (length == null) 10 else null, if (length == null) 0 else null)
        val orders = ProjectExternalRelation.of("SHOP", "ORDERS", true, listOf(
            ProjectExternalColumn.of("ORDER_ID", 1, type(null)), ProjectExternalColumn.of("CODE", 2, type(20)),
            ProjectExternalColumn.of("ALT_CODE", 3, type(20))))
        val known = AtlasDictionary.of("tab", listOf("SHOP"), listOf(DictionaryRelation(orders, DictionaryRelationKind.TABLE)))
        val places = "SELECT p.start_line || ' ' || p.class || ' ' || coalesce(p.length, -1) || ' ' || coalesce(p.functions, '-') " +
            "FROM column_place p WHERE p.source = 'IN_ARGUMENT' ORDER BY p.start_line"
        val notes = "SELECT message FROM coverage_note WHERE message LIKE 'ARGUMENT%'"
        val withColumns = temp.resolve("known.db")
        Indexer().index(IndexOptions(sources, withColumns, compilationSchema = "SHOP", dictionary = known, threads = 1))
        // The dictionary lists the columns of orders, none named p_code: the name is the parameter.
        assertThat(Fixture.query(withColumns, places)).containsExactly("9 FIXED_SIZE 7 -", "10 FIXED_SIZE 8 -", "11 FIXED_SIZE 9 NVL", "12 FIXED_SIZE 10 -")
        assertThat(Fixture.query(withColumns, notes)).isEmpty()
        // The columns not known (no dictionary; the table only in the sources, or nowhere): p_code may be a column of orders.
        for (table in listOf(true, false)) {
            if (table) Files.writeString(sources.resolve("orders.sql"), "create table orders (order_id number(10), code varchar2(20 byte), alt_code varchar2(20 byte));\n")
            else Files.delete(sources.resolve("orders.sql"))
            val withoutColumns = temp.resolve("unknown-$table.db")
            Indexer().index(IndexOptions(sources, withoutColumns, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
            assertThat(Fixture.query(withoutColumns, places)).containsExactly("9 FIXED_SIZE 7 -")
            assertThat(Fixture.query(withoutColumns, notes).map { it.substringBefore(" parameter") }).containsExactly("ARGUMENT_COLUMN_POSSIBLE: 3")
        }
    }

    @Test
    fun `the expressions a parameter is written within, and the fields whose column is not known, are counted by what they are`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("split.sql"), """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace package pk_split as
              procedure p_concat(p_code varchar2);
              procedure p_substr(p_code varchar2);
              procedure p_to_char(p_code varchar2);
              procedure p_length(p_code varchar2);
              procedure p_case(p_code varchar2);
              procedure p_other(p_code varchar2);
              procedure p_rec(p_code varchar2);
            end;
            /
            create or replace package body pk_split as
              procedure p_concat(p_code varchar2) is l varchar2(30); begin l := p_code || '-'; end;
              procedure p_substr(p_code varchar2) is l varchar2(30); begin l := substr(p_code, 1, 3); end;
              procedure p_to_char(p_code varchar2) is l varchar2(30); begin l := to_char(p_code); end;
              procedure p_length(p_code varchar2) is n number; begin n := length(p_code); end;
              procedure p_case(p_code varchar2) is l varchar2(30); begin l := case when p_code is null then 'x' else p_code end; end;
              procedure p_other(p_code varchar2) is l varchar2(30); begin l := replace(p_code, 'a', 'b'); end;
              procedure p_rec(p_code varchar2) is begin null; end;
            end;
            /
            create or replace procedure show_split is
              cursor c_expr is select code || note as joined, upper(code) as up from orders;
              r_unknown unknown_tab%rowtype;
            begin
              for r in (select code from orders) loop
                pk_split.p_concat(r.code);
                pk_split.p_substr(r.code);
                pk_split.p_to_char(r.code);
                pk_split.p_length(r.code);
                pk_split.p_case(r.code);
                pk_split.p_other(r.code);
              end loop;
              for x in c_expr loop
                pk_split.p_rec(x.joined);
                pk_split.p_rec(x.up);
              end loop;
              for y in (select nvl(note, code) as n from orders) loop
                pk_split.p_rec(y.n);
              end loop;
              select * into r_unknown from unknown_tab where rownum = 1;
              pk_split.p_rec(r_unknown.code);
            end;
            /
            create or replace package pk_cur as
              cursor c_spec return orders%rowtype;
            end;
            /
            create or replace procedure show_more is
              r_anchor nowhere.missing_tab%rowtype;
              r_spec pk_cur.c_spec%rowtype;
            begin
              pk_split.p_rec(r_anchor.code);
              pk_split.p_rec(r_spec.code);
              for s in (select * from missing_tab) loop
                pk_split.p_rec(s.code);
              end loop;
              for z in (select 'a' as lit from dual) loop
                pk_split.p_rec(z.lit);
              end loop;
            end;
            /
        """.trimIndent())
        val db = temp.resolve("split.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = PlacesFixture.dictionary, threads = 1))
        // x.up is read through UPPER, p_substr's p_code through SUBSTR, and x.joined (code || note) and p_concat's
        // p_code || '-' are concatenations, which the index follows; r_unknown's relation is not known but its field is
        // named: the column is told by name, and p_rec writes it nowhere. Neither is counted; nor is r_anchor, whose type
        // the index does not resolve at all.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ", "") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "ARGUMENT_COLUMN_NOT_KNOWN_CURSOR: 1",                      // r_spec, a cursor declared with no query
            "ARGUMENT_COLUMN_NOT_KNOWN_EXPRESSION_NVL_SEVERAL_COLUMNS: 1", // y.n, nvl(note, code)
            "ARGUMENT_COLUMN_NOT_KNOWN_NO_COLUMN: 1",                   // z.lit, a literal
            "ARGUMENT_COLUMN_NOT_KNOWN_STAR: 1",                        // s.code, select * from a table not known
            "ARGUMENT_EXPRESSION_CASE: 1",
            "ARGUMENT_EXPRESSION_LENGTH: 1",
            "ARGUMENT_EXPRESSION_TO_CHAR: 1"
        )
    }
}
