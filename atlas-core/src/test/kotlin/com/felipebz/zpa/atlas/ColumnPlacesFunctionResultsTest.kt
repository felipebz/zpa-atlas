//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** A query item that calls a function of the index: followed into the function and out through its RETURN, wherever the item is read. */
class ColumnPlacesFunctionResultsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `a query item that calls a function of the index is followed wherever it is read`() {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(sources.resolve("results.sql"), RESULTS)
        val db = temp.resolve("fr.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        assertThat(Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || ' ' || replace(c.key, 'db:SHOP.', '') || ' ' || p.source || ' ' ||
                   coalesce(p.functions, '-') || (CASE WHEN p.concatenated = 1 THEN ' ||' ELSE '' END)
            FROM column_place p JOIN entity c ON c.id = p.column_id ORDER BY p.start_line, p.target_written, c.key, p.functions""")).containsExactly(
            // In take, reached through take(pk_f.same(q.code)) and take(r.v), which bring the same column by the same chain.
            "37 l1 ORDERS.CODE IN_ARGUMENT -",
            "38 l2 ORDERS.CODE RETURNED -",
            "39 l3 ORDERS.CODE RETURNED -",
            "50 s1 ORDERS.CODE RETURNED -",
            "51 s2 ORDERS.CODE RETURNED - ||",
            // s3: num gives a number.
            "53 s4 ORDERS.CODE RETURNED UPPER",
            "54 s5 ORDERS.CODE RETURNED NVL",
            "54 s5 ORDERS.NOTE RETURNED NVL",
            "55 s6 ORDERS.CODE RETURNED NVL",
            "55 s6 ORDERS.NOTE SELECT_INTO NVL",
            // s7: a routine outside the index.
            "58 k1 ORDERS.CODE RETURNED -",
            "59 k2 ORDERS.NOTE RETURNED UPPER",
            "60 k3 ORDERS.CODE RETURNED - ||",
            "67 k4 ORDERS.CODE RETURNED -",
            "69 b1 ORDERS.CODE RETURNED -"
        )
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "ARGUMENT_EXPRESSION_LENGTH: 1",   // inside num
            "ARGUMENT_ROUTINE_OUTSIDE: 1",     // s7
            "EXPRESSION_OTHER_ROUTINE: 1"      // s7
        )
    }

    @Test
    fun `a function's result is counted where its reader does not follow it`() {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(sources.resolve("not_followed.sql"), NOT_FOLLOWED)
        val db = temp.resolve("nf.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        assertThat(Fixture.query(db, "SELECT count(*) FROM column_place WHERE target_written = 'x'")).containsExactly("0")
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            // q1: a result passed to another function; q2: a FETCH into a %ROWTYPE record; q3: a FETCH from a cursor of
            // another package; q5: the item of an inline view.
            "EXPRESSION_OTHER_ROUTINE: 4",
            // q2: the record's field read later; q4: a loop over a cursor of another package.
            "RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN_EXPRESSION_OTHER_ROUTINE: 2"
        )
    }

    private companion object {
        const val NOT_FOLLOWED = """create table orders (order_id number(10), code varchar2(20 char), note varchar2(40 char));

create or replace package pk_f as
  function same(p varchar2) return varchar2;
  function wrap(p varchar2) return varchar2;
end;
/
create or replace package body pk_f as
  function same(p varchar2) return varchar2 is
  begin
    return p;
  end;
  function wrap(p varchar2) return varchar2 is
  begin
    return '[' || p || ']';
  end;
end;
/
create or replace package pk_cur as
  cursor gc is select pk_f.same(code) v from orders;
end;
/
create or replace procedure q1 is
  x varchar2(5 char);
begin
  select pk_f.same(pk_f.wrap(code)) into x from orders where rownum = 1;
end;
/
create or replace procedure q2 is
  cursor c is select pk_f.same(code) v, note from orders;
  v_r c%rowtype;
  x varchar2(5 char);
begin
  open c;
  fetch c into v_r;
  close c;
  x := v_r.v;
end;
/
create or replace procedure q3 is
  x varchar2(5 char);
begin
  open pk_cur.gc;
  fetch pk_cur.gc into x;
  close pk_cur.gc;
end;
/
create or replace procedure q4 is
  x varchar2(5 char);
begin
  for r in pk_cur.gc loop
    x := r.v;
  end loop;
end;
/
create or replace procedure q5 is
  x varchar2(5 char);
begin
  select v into x from (select pk_f.same(code) v from orders) where rownum = 1;
end;
/
"""

        const val RESULTS = """create table orders (order_id number(10), code varchar2(20 char), note varchar2(40 char), amount number(8,2));

create or replace package pk_f as
  function same(p varchar2) return varchar2;
  function wrap(p varchar2) return varchar2;
  function num(p varchar2) return number;
  function pick(p_a varchar2, p_b varchar2) return varchar2;
end;
/
create or replace package body pk_f as
  function same(p varchar2) return varchar2 is
  begin
    return p;
  end;
  function wrap(p varchar2) return varchar2 is
  begin
    return '[' || p || ']';
  end;
  function num(p varchar2) return number is
  begin
    return length(p);
  end;
  function pick(p_a varchar2, p_b varchar2) return varchar2 is
  begin
    return nvl(p_a, p_b);
  end;
end;
/
create or replace function f_up(p varchar2) return varchar2 is
begin
  return upper(p);
end;
/
create or replace procedure take(p_v varchar2) is
  l1 varchar2(5 char); l2 varchar2(5 char); l3 varchar2(5 char);
begin
  l1 := p_v;
  select pk_f.same(p_v) into l2 from dual;
  l3 := pk_f.same(p_v);
end;
/
create or replace procedure run(p_id number) is
  cursor c is select pk_f.same(code) v from orders;
  type t_codes is table of varchar2(5 char);
  s1 varchar2(5 char); s2 varchar2(5 char); s3 varchar2(5 char); s4 varchar2(5 char); s5 varchar2(5 char);
  s6 varchar2(5 char); s7 varchar2(5 char);
  k1 varchar2(5 char); k2 varchar2(5 char); k3 varchar2(5 char); k4 varchar2(5 char);
  b1 t_codes;
begin
  select pk_f.same(code) into s1 from orders where order_id = p_id;
  select pk_f.wrap(code) into s2 from orders where order_id = p_id;
  select pk_f.num(code) into s3 from orders where order_id = p_id;
  select f_up(code) into s4 from orders where order_id = p_id;
  select pk_f.pick(code, note) into s5 from orders where order_id = p_id;
  select nvl(pk_f.same(code), note) into s6 from orders where order_id = p_id;
  select other_owner.fmt(code) into s7 from orders where order_id = p_id;
  for r in (select pk_f.same(code) v, f_up(note) w, pk_f.wrap(code) z from orders) loop
    k1 := r.v;
    k2 := r.w;
    k3 := r.z;
    take(r.v);
  end loop;
  for q in (select code from orders) loop
    take(pk_f.same(q.code));
  end loop;
  open c;
  fetch c into k4;
  close c;
  select pk_f.same(code) bulk collect into b1 from orders;
end;
/
"""
    }
}
