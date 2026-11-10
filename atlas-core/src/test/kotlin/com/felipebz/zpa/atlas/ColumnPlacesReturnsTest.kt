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

/** The places of a column's value a routine returns or gives back out through its calls and OUT parameters. */
class ColumnPlacesReturnsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `a column's value a function returns reaches the targets its callers write the result into`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("pk_ret.sql"), RETURNS_PACKAGE)
        Files.writeString(sources.resolve("show_ret.sql"), RETURNS_CALLER)
        Files.writeString(sources.resolve("show_more.sql"), """
            create or replace package pk_ret2 as
              type t_rec is record (code varchar2(20));
              function f_rec(p_id number) return t_rec;
              function f_self(p_code varchar2, n number) return varchar2;
              function f_ping(p_code varchar2, n number) return varchar2;
              function f_pong(p_code varchar2, n number) return varchar2;
            end;
            /
            create or replace package body pk_ret2 as
              function f_rec(p_id number) return t_rec is r t_rec; begin select code into r from orders where order_id = p_id; return r; end;
              function f_self(p_code varchar2, n number) return varchar2 is
              begin
                if n > 0 then return f_self(p_code, n - 1); end if;
                return p_code;
              end;
              function f_ping(p_code varchar2, n number) return varchar2 is
              begin
                if n > 0 then return f_pong(p_code, n - 1); end if;
                return p_code;
              end;
              function f_pong(p_code varchar2, n number) return varchar2 is begin return f_ping(p_code, n); end;
            end;
            /
            create or replace procedure show_more is
              l1 varchar2(5); l2 varchar2(5); l3 varchar2(5); l4 varchar2(5); l5 varchar2(5);
              r pk_ret2.t_rec;
            begin
              for x in (select code from orders) loop
                l1 := pk_ret.f_cut(x.code);
                l2 := pk_ret2.f_self(x.code, 3);
                l3 := pk_ret2.f_ping(x.code, 3);
              end loop;
              l4 := pk_ret.f_short(1);
              l5 := pk_ret.f_anchored(1);
              r := pk_ret2.f_rec(1);
            end;
            /
        """.trimIndent())
        val db = temp.resolve("ret.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        // Column, target, class, length, functions, concatenated, the function (or the routine of the parameter), calls.
        assertThat(Fixture.query(db, RETURNED_PLACES)).containsExactly(
            "pk_ret.sql:67 l CODE IN_ARGUMENT FIXED_SIZE 5 - 0 PK_RET.TAKE(P_CODE IN VARCHAR2) 1 show_ret.sql:23",     // c15
            "show_more.sql:29 l1 CODE RETURNED FIXED_SIZE 5 SUBSTR 0 PK_RET.F_CUT(P_CODE IN VARCHAR2) 1 show_more.sql:29",  // f_cut's substr(p_code, 1, 3)
            "show_more.sql:30 l2 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET2.F_SELF(P_CODE IN VARCHAR2,N IN NUMBER) 1 show_more.sql:30",
            "show_more.sql:31 l3 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET2.F_PING(P_CODE IN VARCHAR2,N IN NUMBER) 1 show_more.sql:31",
            "show_more.sql:33 l4 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_SHORT(P_ID IN NUMBER) 1 show_more.sql:33",       // r10
            "show_more.sql:34 l5 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_ANCHORED(P_ID IN NUMBER) 1 show_more.sql:34",    // r11
            "show_ret.sql:8 l1 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_PARAM(P_CODE IN VARCHAR2) 1 show_ret.sql:8",       // c1
            "show_ret.sql:9 l2 CODE RETURNED FIXED_SIZE 5 UPPER 0 PK_RET.F_UPPER(P_CODE IN VARCHAR2) 1 show_ret.sql:9",   // c2
            "show_ret.sql:10 l3 CODE RETURNED FIXED_SIZE 5 - 1 PK_RET.F_TAGGED(P_CODE IN VARCHAR2) 1 show_ret.sql:10",    // c3
            "show_ret.sql:12 l13 CODE RETURNED FIXED_SIZE 5 - 0 - 1 show_ret.sql:12",                                     // c13, nested
            "show_ret.sql:14 l4 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_GET(P_ID IN NUMBER) 1 show_ret.sql:14",           // c4
            "show_ret.sql:15 l5 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_GET_ROW(P_ID IN NUMBER) 1 show_ret.sql:15",       // c5
            "show_ret.sql:16 l6 NOTE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_GET_CUR(P_ID IN NUMBER) 1 show_ret.sql:16",       // c6
            "show_ret.sql:17 l7 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_TWO(P_ID IN NUMBER) 1 show_ret.sql:17",           // c7
            "show_ret.sql:17 l7 NOTE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_TWO(P_ID IN NUMBER) 1 show_ret.sql:17",
            "show_ret.sql:18 l8 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_CHAIN(P_ID IN NUMBER) 1 show_ret.sql:18",         // c8
            "show_ret.sql:19 l9 CODE RETURNED FIXED_SIZE 5 NVL 0 PK_RET.F_GET(P_ID IN NUMBER) 1 show_ret.sql:19",         // c9
            "show_ret.sql:20 l10 CODE RETURNED FIXED_SIZE 5 - 1 PK_RET.F_GET(P_ID IN NUMBER) 1 show_ret.sql:20",          // c10
            "show_ret.sql:21 l11 CODE RETURNED FIXED_SIZE 5 SUBSTR 0 PK_RET.F_GET(P_ID IN NUMBER) 1 show_ret.sql:21",     // c11
            "show_ret.sql:22 l14 CODE RETURNED FIXED_SIZE 5 - 0 PK_RET.F_GET(P_ID IN NUMBER) 1 show_ret.sql:22",          // c14, in SQL
            "show_ret.sql:24 p_out CODE RETURNED CALLER_DECIDES - - 0 PK_RET.F_GET(P_ID IN NUMBER) 1 show_ret.sql:24"     // c16
        )
        assertThat(Fixture.query(db, "SELECT f.path || ' ' || n.message FROM coverage_note n JOIN source_file f ON f.id = n.file_id WHERE n.kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY 1")
            .map { it.substringBefore(": ") + ": " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "show_more.sql RETURNED_NOT_SCALAR: 1",         // r := f_rec(1), a record
            "show_ret.sql ARGUMENT_ROUTINE_UNDECIDED: 1",   // c12: zpa leaves the overloaded f_over AMBIGUOUS
            "show_ret.sql EXPRESSION_OTHER_ROUTINE: 1"      // c14's item: order_id passed to f_get, as today
        )
    }

    @Test
    fun `a value passed to a routine comes back out at the call that passed it, a column the routine reads at every call`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("contexts.sql"), """
            create or replace package pk_ctx as
              function f_direct(p varchar2) return varchar2;
              function f_held(p varchar2) return varchar2;
              procedure p_out(p_in varchar2, p_res out varchar2);
              procedure p_out_f(p_in varchar2, p_res out varchar2);
              function f_get(p_id number) return varchar2;
              function f_mixed(p varchar2, p_id number) return varchar2;
            end;
            /
            create or replace package body pk_ctx as
              function f_direct(p varchar2) return varchar2 is begin return p; end;
              function f_held(p varchar2) return varchar2 is l varchar2(100); begin l := p; return l; end;
              procedure p_out(p_in varchar2, p_res out varchar2) is begin p_res := p_in; end;
              procedure p_out_f(p_in varchar2, p_res out varchar2) is begin p_res := f_held(p_in); end;
              function f_get(p_id number) return varchar2 is l varchar2(20);
              begin select code into l from orders where order_id = p_id; return l; end;
              function f_mixed(p varchar2, p_id number) return varchar2 is l varchar2(40);
              begin
                if p_id > 0 then select note into l from orders where order_id = p_id; else l := p; end if;
                return l;
              end;
            end;
            /
            create or replace procedure show_ctx is
              a1 varchar2(5); a2 varchar2(5); b1 varchar2(5); b2 varchar2(5);
              o1 varchar2(5); o2 varchar2(5); q1 varchar2(5); q2 varchar2(5);
              g1 varchar2(5); g2 varchar2(5); m1 varchar2(5); m2 varchar2(5);
            begin
              for r in (select code, note from orders) loop
                a1 := pk_ctx.f_direct(r.code);
                a2 := pk_ctx.f_direct(r.note);
                b1 := pk_ctx.f_held(r.code);
                b2 := pk_ctx.f_held(r.note);
                pk_ctx.p_out(r.code, o1);
                pk_ctx.p_out(r.note, o2);
                pk_ctx.p_out_f(r.code, q1);
                pk_ctx.p_out_f(r.note, q2);
                m1 := pk_ctx.f_mixed(r.code, 0);
                m2 := pk_ctx.f_mixed(r.note, 0);
              end loop;
              g1 := pk_ctx.f_get(1);
              g2 := pk_ctx.f_get(2);
            end;
            /
        """.trimIndent())
        val db = temp.resolve("contexts.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || coalesce(' calls ' || p.call_count, '')
            FROM column_place p JOIN entity c ON c.id = p.column_id
            WHERE p.source IN ('RETURNED', 'IN_ARGUMENT', 'OUT_ARGUMENT') ORDER BY p.target_written, c.key""")).containsExactly(
            // Each call's target takes the column that call passed in, and the columns the routine reads itself.
            "a1 CODE RETURNED calls 1", "a2 NOTE RETURNED calls 1",
            "b1 CODE RETURNED calls 1", "b2 NOTE RETURNED calls 1",
            "g1 CODE RETURNED calls 1", "g2 CODE RETURNED calls 1",
            // Inside the routines (f_held's and f_mixed's l, p_out's and p_out_f's p_res), every column passed to them, as before.
            "l CODE IN_ARGUMENT calls 1", "l CODE IN_ARGUMENT calls 1", "l NOTE IN_ARGUMENT calls 1", "l NOTE IN_ARGUMENT calls 1",
            // m1: the column it passed, and the one f_mixed reads; m2: one place, whether passed or read inside.
            "m1 CODE RETURNED calls 1", "m1 NOTE RETURNED calls 1", "m2 NOTE RETURNED calls 1",
            "o1 CODE OUT_ARGUMENT", "o2 NOTE OUT_ARGUMENT",
            "p_res CODE IN_ARGUMENT calls 1", "p_res CODE RETURNED calls 1", "p_res NOTE IN_ARGUMENT calls 1", "p_res NOTE RETURNED calls 1",
            // Through two levels: p_out_f passes its parameter into f_held and gives the result back through its own OUT parameter.
            "q1 CODE OUT_ARGUMENT", "q2 NOTE OUT_ARGUMENT"
        )
    }

    @Test
    fun `a value kept beyond the call, the same function nested, and a cycle still come back out`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("kept.sql"), """
            create or replace package pk_keep as
              procedure keep(p varchar2);
              function last_kept return varchar2;
              function f_id(p varchar2) return varchar2;
              function f_outer(p varchar2) return varchar2;
            end;
            /
            create or replace package body pk_keep as
              g_last varchar2(100);
              procedure keep(p varchar2) is begin g_last := p; end;
              function last_kept return varchar2 is begin return g_last; end;
              function f_id(p varchar2) return varchar2 is begin return p; end;
              function f_outer(p varchar2) return varchar2 is
                l_held varchar2(100);
                procedure inner_set(x varchar2) is begin l_held := x; end;
              begin
                inner_set(p);
                return l_held;
              end;
            end;
            /
            create or replace package cy_a as function f(p varchar2, n number) return varchar2; end;
            /
            create or replace package cy_b as function g(p varchar2, n number) return varchar2; end;
            /
            create or replace package body cy_a as
              function f(p varchar2, n number) return varchar2 is l varchar2(30);
              begin
                if n = 0 then return p; end if;
                l := cy_b.g(p || 'a', n - 1);
                return 'x' || l;
              end;
            end;
            /
            create or replace package body cy_b as
              function g(p varchar2, n number) return varchar2 is
              begin return trim(cy_a.f(upper(p), n)); end;
            end;
            /
            create or replace procedure show_keep is
              k1 varchar2(5); k2 varchar2(5); k3 varchar2(5); k5 varchar2(5); k6 varchar2(5);
            begin
              for r in (select code, note from orders) loop
                pk_keep.keep(r.note);
                k2 := pk_keep.f_outer(r.code);
                k3 := pk_keep.f_id(pk_keep.f_id(r.note));
                k5 := cy_a.f(r.code, 3);
              end loop;
              k1 := pk_keep.last_kept;
              k6 := pk_keep.f_id(pk_keep.f_id('x'));
            end;
            /
        """.trimIndent())
        val db = temp.resolve("kept.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || coalesce(p.functions, '-')
            FROM column_place p JOIN entity c ON c.id = p.column_id
            WHERE p.target_written IN ('k1', 'k2', 'k3', 'k5', 'k6', 'l') ORDER BY p.target_written, c.key, p.functions""")).containsExactly(
            // A package variable written by keep, returned by last_kept: for every caller of last_kept.
            "k1 NOTE RETURNED -",
            // A variable of f_outer written by its nested inner_set: for every caller of f_outer.
            "k2 CODE RETURNED -",
            // f_id's result passed to f_id again: the column the inner call passed.
            "k3 NOTE RETURNED -",
            "k5 CODE RETURNED -",
            // Inside the cycle: what f returns at the bottom, through g's trim, without going round again.
            "l CODE RETURNED TRIM"
        )
    }

    @Test
    fun `a value comes back out through OUT parameters and the locals that hold it, to the call that passed it`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("leftovers.sql"), """
            create or replace package pk_fl as
              procedure keep(p varchar2);
              procedure out_kept(p_res out varchar2);
              procedure out_local(p_in varchar2, p_res out varchar2);
              procedure out_read(p_id number, p_res out varchar2);
              function f_via_out(p varchar2) return varchar2;
              function f_via_out_read(p_id number) return varchar2;
              procedure out_via_out(p_in varchar2, p_res out varchar2);
            end;
            /
            create or replace package body pk_fl as
              g_kept varchar2(100);
              procedure keep(p varchar2) is begin g_kept := p; end;
              procedure out_kept(p_res out varchar2) is begin p_res := g_kept; end;
              procedure out_local(p_in varchar2, p_res out varchar2) is l varchar2(100); begin l := p_in; p_res := l; end;
              procedure out_read(p_id number, p_res out varchar2) is begin select code into p_res from orders where order_id = p_id; end;
              function f_via_out(p varchar2) return varchar2 is l varchar2(100); begin out_local(p, l); return l; end;
              function f_via_out_read(p_id number) return varchar2 is l varchar2(100); begin out_read(p_id, l); return l; end;
              procedure out_via_out(p_in varchar2, p_res out varchar2) is begin out_local(p_in, p_res); end;
            end;
            /
            create or replace procedure show_fl is
              e1 varchar2(5); e2 varchar2(5); e3 varchar2(5); e4 varchar2(5); e5 varchar2(5);
              e6 varchar2(5); e7 varchar2(5); e8 varchar2(5); e9 varchar2(5); e10 varchar2(5);
              e11 varchar2(5); e12 varchar2(5);
              function inner_l(x varchar2) return varchar2 is l varchar2(100); begin l := x; return l; end;
              procedure inner_o(x varchar2, o out varchar2) is l varchar2(100); begin l := x; o := l; end;
              procedure inner_d(x varchar2, o out varchar2) is begin o := x; end;
            begin
              for r in (select code, note from orders) loop
                pk_fl.keep(r.note);
                e2 := inner_l(r.code);
                e3 := inner_l(r.note);
                inner_o(r.code, e4);
                inner_o(r.note, e5);
                inner_d(r.code, e6);
                inner_d(r.note, e7);
                pk_fl.out_local(r.code, e8);
                e9 := pk_fl.f_via_out(r.code);
                e10 := pk_fl.f_via_out(r.note);
                pk_fl.out_via_out(r.note, e12);
              end loop;
              pk_fl.out_kept(e1);
              e11 := pk_fl.f_via_out_read(1);
            end;
            /
        """.trimIndent())
        val db = temp.resolve("leftovers.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || p.class ||
                   coalesce(' ' || p.length, '')
            FROM column_place p JOIN entity c ON c.id = p.column_id
            WHERE p.source IN ('RETURNED', 'IN_ARGUMENT', 'OUT_ARGUMENT') ORDER BY p.start_line, p.target_written, c.key""")).containsExactly(
            // Inside the routines, every column that reaches a target across the calls.
            "13 g_kept NOTE IN_ARGUMENT FIXED_SIZE 100",
            "14 p_res NOTE IN_ARGUMENT CALLER_DECIDES",                                         // out_kept's, from the package variable
            "15 l CODE IN_ARGUMENT FIXED_SIZE 100", "15 l NOTE IN_ARGUMENT FIXED_SIZE 100",
            "15 p_res CODE IN_ARGUMENT CALLER_DECIDES", "15 p_res NOTE IN_ARGUMENT CALLER_DECIDES", // out_local's, from its local
            "17 l CODE OUT_ARGUMENT FIXED_SIZE 100", "17 l NOTE OUT_ARGUMENT FIXED_SIZE 100",     // f_via_out's, from out_local
            "18 l CODE OUT_ARGUMENT FIXED_SIZE 100",                                            // f_via_out_read's, read in out_read
            "19 p_res NOTE OUT_ARGUMENT CALLER_DECIDES",                                        // out_via_out's, given out_local's
            "26 l CODE IN_ARGUMENT FIXED_SIZE 100", "26 l NOTE IN_ARGUMENT FIXED_SIZE 100",
            "27 l CODE IN_ARGUMENT FIXED_SIZE 100", "27 l NOTE IN_ARGUMENT FIXED_SIZE 100",
            "27 o CODE IN_ARGUMENT CALLER_DECIDES", "27 o NOTE IN_ARGUMENT CALLER_DECIDES",
            "28 o CODE IN_ARGUMENT CALLER_DECIDES", "28 o NOTE IN_ARGUMENT CALLER_DECIDES",
            // Each call's target: the column that call passed in, never another call's.
            "32 e2 CODE RETURNED FIXED_SIZE 5",          // a nested function returning its own local
            "33 e3 NOTE RETURNED FIXED_SIZE 5",
            "34 e4 CODE OUT_ARGUMENT FIXED_SIZE 5",      // a nested procedure's OUT parameter given its local
            "35 e5 NOTE OUT_ARGUMENT FIXED_SIZE 5",
            "36 e6 CODE OUT_ARGUMENT FIXED_SIZE 5",      // given the parameter itself
            "37 e7 NOTE OUT_ARGUMENT FIXED_SIZE 5",
            "38 e8 CODE OUT_ARGUMENT FIXED_SIZE 5",      // a package procedure's OUT parameter given its local
            "39 e9 CODE RETURNED FIXED_SIZE 5",          // back through an OUT argument, then out by RETURN
            "40 e10 NOTE RETURNED FIXED_SIZE 5",
            "41 e12 NOTE OUT_ARGUMENT FIXED_SIZE 5",     // an OUT parameter passed as another routine's OUT argument
            // A column kept in a package variable, or read inside: for every caller.
            "43 e1 NOTE OUT_ARGUMENT FIXED_SIZE 5",
            "44 e11 CODE RETURNED FIXED_SIZE 5"
        )
    }

    @Test
    fun `a local given out through a value function or read inside reaches the callers, one within another expression is counted`() {
        val sources = temp.resolve("src")
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("held.sql"), """
            create or replace package pk_h as
              function f_upper(p varchar2) return varchar2;
              function f_nvl(p varchar2) return varchar2;
              function f_trim(p varchar2) return varchar2;
              function f_upper_p(p varchar2) return varchar2;
              procedure out_upper(p_in varchar2, p_res out varchar2);
              procedure out_read(p_id number, p_res out varchar2);
              function f_read(p_id number) return varchar2;
              procedure out_lpad(p_in varchar2, p_res out varchar2);
              function f_lpad(p varchar2) return varchar2;
              function f_lower(p varchar2) return varchar2;
              function f_rtrim(p varchar2) return varchar2;
            end;
            /
            create or replace package body pk_h as
              function f_upper(p varchar2) return varchar2 is l varchar2(100); begin l := p; return upper(l); end;
              function f_nvl(p varchar2) return varchar2 is l varchar2(100); begin l := p; return nvl(l, 'x'); end;
              function f_trim(p varchar2) return varchar2 is l varchar2(100); begin l := p; return trim(l); end;
              function f_upper_p(p varchar2) return varchar2 is begin return upper(p); end;
              procedure out_upper(p_in varchar2, p_res out varchar2) is l varchar2(100); begin l := p_in; p_res := upper(l); end;
              procedure out_read(p_id number, p_res out varchar2) is l varchar2(100);
              begin select code into l from orders where order_id = p_id; p_res := l; end;
              function f_read(p_id number) return varchar2 is l varchar2(100);
              begin select code into l from orders where order_id = p_id; return l; end;
              procedure out_lpad(p_in varchar2, p_res out varchar2) is l varchar2(100); begin l := p_in; p_res := lpad(l, 3); end;
              function f_lpad(p varchar2) return varchar2 is l varchar2(100); begin l := p; return lpad(l, 3); end;
              function f_lower(p varchar2) return varchar2 is l varchar2(100); begin l := p; return lower(l); end;
              function f_rtrim(p varchar2) return varchar2 is l varchar2(100); begin l := p; return rtrim(l); end;
            end;
            /
            create or replace procedure show_h is
              h1 varchar2(5); h2 varchar2(5); h3 varchar2(5); h4 varchar2(5); h5 varchar2(5);
              h6 varchar2(5); h7 varchar2(5); h8 varchar2(5); h9 varchar2(5); h10 varchar2(5);
              h11 varchar2(5); h12 varchar2(5); h13 varchar2(5);
            begin
              for r in (select code, note from orders) loop
                h1 := pk_h.f_upper(r.code);
                h2 := pk_h.f_upper(r.note);
                h3 := pk_h.f_nvl(r.note);
                h4 := pk_h.f_trim(r.code);
                h5 := pk_h.f_upper_p(r.code);
                pk_h.out_upper(r.code, h6);
                pk_h.out_upper(r.note, h7);
                pk_h.out_lpad(r.code, h10);
                h11 := pk_h.f_lpad(r.code);
                h12 := pk_h.f_lower(r.note);
                h13 := pk_h.f_rtrim(r.code);
              end loop;
              pk_h.out_read(1, h8);
              h9 := pk_h.f_read(1);
            end;
            /
        """.trimIndent())
        val db = temp.resolve("held.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = ORDERS_ITEMS, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.target_written || ' ' || replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || p.class || coalesce(' ' || p.length, '') ||
                   coalesce(' via ' || p.functions, '')
            FROM column_place p JOIN entity c ON c.id = p.column_id
            WHERE p.target_written <> 'l' AND p.source IN ('RETURNED', 'IN_ARGUMENT', 'OUT_ARGUMENT', 'ASSIGNMENT') ORDER BY p.target_written, c.key""")).containsExactly(
            // Each call's target: the column that call passed in, through the function its routine applies.
            "h1 CODE RETURNED FIXED_SIZE 5 via UPPER", "h12 NOTE RETURNED FIXED_SIZE 5 via LOWER", "h13 CODE RETURNED FIXED_SIZE 5 via RTRIM",
            "h2 NOTE RETURNED FIXED_SIZE 5 via UPPER", "h3 NOTE RETURNED FIXED_SIZE 5 via NVL", "h4 CODE RETURNED FIXED_SIZE 5 via TRIM",
            "h5 CODE RETURNED FIXED_SIZE 5 via UPPER", "h6 CODE OUT_ARGUMENT FIXED_SIZE 5 via UPPER", "h7 NOTE OUT_ARGUMENT FIXED_SIZE 5 via UPPER",
            // A column read in the routine itself: for every caller, through RETURN or an OUT parameter.
            "h8 CODE OUT_ARGUMENT FIXED_SIZE 5", "h9 CODE RETURNED FIXED_SIZE 5",
            // Inside the routines: what their OUT parameters are given, from a call, or assigned what the routine read itself.
            "p_res CODE IN_ARGUMENT CALLER_DECIDES via UPPER", "p_res CODE ASSIGNMENT CALLER_DECIDES", "p_res NOTE IN_ARGUMENT CALLER_DECIDES via UPPER"
        )
        // Within lpad, given to an OUT parameter (h10) or returned (h11): not followed, counted where the routine writes it.
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ", "") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly("RETURNED_EXPRESSION_OTHER_LPAD: 2")
    }

    companion object {
        private const val RETURNED_PLACES = """
            SELECT f.path || ':' || p.start_line || ' ' || p.target_written || coalesce('.' || p.field, '') || ' ' ||
                   replace(c.key, 'db:SHOP.ORDERS.', '') || ' ' || p.source || ' ' || p.class || ' ' || coalesce(p.length, '-') || ' ' ||
                   coalesce(p.functions, '-') || ' ' || p.concatenated || ' ' || coalesce(replace(g.key, 'db:SHOP.', ''), '-') || ' ' ||
                   coalesce(p.call_count, '-') || ' ' || coalesce(p.call_path || ':' || p.call_line, '-')
            FROM column_place p JOIN entity c ON c.id = p.column_id JOIN source_file f ON f.id = p.file_id
            LEFT JOIN entity g ON g.id = p.argument_routine_id
            WHERE p.source IN ('RETURNED', 'IN_ARGUMENT') ORDER BY f.path, p.start_line, p.start_col, c.key"""

        private val RETURNS_PACKAGE = """
            create table orders (order_id number(10), code varchar2(20 byte), note varchar2(40 byte));

            create or replace package pk_ret as
              subtype t_short is varchar2(5);
              function f_param(p_code varchar2) return varchar2;
              function f_upper(p_code varchar2) return varchar2;
              function f_tagged(p_code varchar2) return varchar2;
              function f_get(p_id number) return varchar2;
              function f_get_row(p_id number) return varchar2;
              function f_get_cur(p_id number) return varchar2;
              function f_two(p_id number) return varchar2;
              function f_chain(p_id number) return varchar2;
              function f_cut(p_code varchar2) return varchar2;
              function f_short(p_id number) return t_short;
              function f_anchored(p_id number) return orders.code%type;
              function f_over(p_code varchar2) return varchar2;
              function f_over(p_code number) return varchar2;
              procedure take(p_code varchar2);
            end;
            /
            create or replace package body pk_ret as
              function f_param(p_code varchar2) return varchar2 is begin return p_code; end;
              function f_upper(p_code varchar2) return varchar2 is begin return upper(p_code); end;
              function f_tagged(p_code varchar2) return varchar2 is begin return 'C:' || p_code; end;
              function f_get(p_id number) return varchar2 is
                l orders.code%type;
              begin
                select code into l from orders where order_id = p_id;
                return l;
              end;
              function f_get_row(p_id number) return varchar2 is
                r orders%rowtype;
              begin
                select * into r from orders where order_id = p_id;
                return r.code;
              end;
              function f_get_cur(p_id number) return varchar2 is
                cursor c is select note from orders where order_id = p_id;
                l varchar2(40);
              begin
                open c; fetch c into l; close c;
                return l;
              end;
              function f_two(p_id number) return varchar2 is
                l1 varchar2(20); l2 varchar2(40);
              begin
                select code, note into l1, l2 from orders where order_id = p_id;
                if p_id > 0 then return l1; end if;
                return l2;
              end;
              function f_chain(p_id number) return varchar2 is begin return f_get(p_id); end;
              function f_cut(p_code varchar2) return varchar2 is begin return substr(p_code, 1, 3); end;
              function f_short(p_id number) return t_short is
                l varchar2(20);
              begin
                select code into l from orders where order_id = p_id;
                return l;
              end;
              function f_anchored(p_id number) return orders.code%type is
                l varchar2(20);
              begin
                select code into l from orders where order_id = p_id;
                return l;
              end;
              function f_over(p_code varchar2) return varchar2 is begin return p_code; end;
              function f_over(p_code number) return varchar2 is begin return to_char(p_code); end;
              procedure take(p_code varchar2) is l varchar2(5); begin l := p_code; end;
            end;
            /
        """.trimIndent()

        private val RETURNS_CALLER = """
            create or replace procedure show_ret(p_out out varchar2) is
              l1 varchar2(5); l2 varchar2(5); l3 varchar2(5); l4 varchar2(5); l5 varchar2(5);
              l6 varchar2(5); l7 varchar2(5); l8 varchar2(5); l9 varchar2(5); l10 varchar2(5);
              l11 varchar2(5); l12 varchar2(5); l13 varchar2(5); l14 varchar2(5); l15 varchar2(5);
              function f_local(p varchar2) return varchar2 is begin return p; end;
            begin
              for r in (select code from orders) loop
                l1 := pk_ret.f_param(r.code);
                l2 := pk_ret.f_upper(r.code);
                l3 := pk_ret.f_tagged(r.code);
                l12 := pk_ret.f_over(r.code);
                l13 := f_local(r.code);
              end loop;
              l4 := pk_ret.f_get(1);
              l5 := pk_ret.f_get_row(1);
              l6 := pk_ret.f_get_cur(1);
              l7 := pk_ret.f_two(1);
              l8 := pk_ret.f_chain(1);
              l9 := nvl(pk_ret.f_get(1), '-');
              l10 := 'X' || pk_ret.f_get(1);
              l11 := substr(pk_ret.f_get(1), 1, 3);
              select pk_ret.f_get(order_id) into l14 from orders where rownum = 1;
              pk_ret.take(pk_ret.f_get(1));
              p_out := pk_ret.f_get(1);
              l15 := pk_ret.f_cut('abc');
              if pk_ret.f_get(1) = 'A' then null; end if;
            end;
            /
        """.trimIndent()
    }
}
