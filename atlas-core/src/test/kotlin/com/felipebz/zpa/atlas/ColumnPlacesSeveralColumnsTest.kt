//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.api.project.ProjectExternalDatatype.LengthSemantics
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** An expression that reads several columns: each column an argument may give back, or a part of a concatenation, is a place. */
class ColumnPlacesSeveralColumnsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `every column an expression of several may give back is a place`() {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(sources.resolve("pk_sc.sql"), SEVERAL)
        val db = temp.resolve("sc.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = shopTables(
            "ORDERS" to listOf("ORDER_ID" to number(10), "CODE" to varchar2(20, LengthSemantics.CHAR), "NOTE" to varchar2(40, LengthSemantics.CHAR),
                "AMOUNT" to number(8, 2)),
            "ORDER_NOTES" to listOf("ORDER_ID" to number(10), "CODE" to varchar2(20, LengthSemantics.CHAR))), threads = 1))
        val rows = Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || ' ' || replace(c.key, 'db:SHOP.', '') || ' ' || p.source || ' ' ||
                   coalesce(p.functions, '-') || (CASE WHEN p.concatenated = 1 THEN ' ||' ELSE '' END)
            FROM column_place p JOIN entity c ON c.id = p.column_id ORDER BY p.start_line, p.target_written, c.key, p.functions, p.concatenated""")
        assertThat(rows).containsExactly(
            "11 l ORDERS.CODE SELECT_INTO -",
            "11 n ORDERS.NOTE SELECT_INTO -",
            // take(r.code); take(r.code || r.note); take(nvl(r.code, r.note)) and take(r.v), the same row.
            "18 l1 ORDERS.CODE IN_ARGUMENT -",
            "18 l1 ORDERS.CODE IN_ARGUMENT - ||",
            "18 l1 ORDERS.CODE IN_ARGUMENT NVL",
            "18 l1 ORDERS.NOTE IN_ARGUMENT - ||",
            "18 l1 ORDERS.NOTE IN_ARGUMENT NVL",
            "32 w1 ORDERS.CODE SELECT_INTO NVL",
            "32 w1 ORDERS.NOTE SELECT_INTO NVL",
            "33 w2 ORDERS.CODE SELECT_INTO NVL",
            "33 w2 ORDER_NOTES.CODE SELECT_INTO NVL",
            "34 w3 ORDERS.CODE SELECT_INTO COALESCE",
            "34 w3 ORDERS.NOTE SELECT_INTO COALESCE",
            // NVL2 gives back its second or third argument; the first is only tested.
            "35 w4 ORDERS.CODE SELECT_INTO NVL2",
            "35 w4 ORDERS.NOTE SELECT_INTO NVL2",
            "36 w5 ORDERS.NOTE SELECT_INTO NVL2",
            "37 w6 ORDERS.CODE SELECT_INTO NVL",
            "37 w6 ORDERS.NOTE SELECT_INTO NVL,LOWER",
            // to_char(amount) is not followed: the item is counted as well.
            "38 w7 ORDERS.CODE SELECT_INTO NVL",
            "39 w8 ORDERS.CODE SELECT_INTO - ||",
            "40 w9 ORDERS.CODE SELECT_INTO - ||",
            "40 w9 ORDERS.NOTE SELECT_INTO - ||",
            "41 w10 ORDERS.CODE SELECT_INTO NVL ||",
            "41 w10 ORDERS.NOTE SELECT_INTO NVL ||",
            "42 w11 ORDERS.CODE SELECT_INTO UPPER,NVL",
            "42 w11 ORDERS.NOTE SELECT_INTO UPPER,NVL",
            "43 w12 ORDERS.CODE SELECT_INTO MAX,NVL",
            "43 w12 ORDERS.NOTE SELECT_INTO MAX,NVL",
            // w13, a DECODE: its result note, the code it compares gives nothing. w14, the same column through two
            // arguments: a row per chain.
            "44 w13 ORDERS.NOTE SELECT_INTO DECODE",
            "45 w14 ORDERS.CODE SELECT_INTO NVL",
            "45 w14 ORDERS.CODE SELECT_INTO NVL,LOWER",
            "46 tv ORDERS.CODE SELECT_INTO NVL",
            "46 tv ORDERS.NOTE SELECT_INTO NVL",
            "48 k1 ORDERS.CODE FETCH NVL",
            "48 k1 ORDERS.NOTE FETCH NVL",
            "51 a1 ORDERS.CODE ASSIGNMENT NVL",
            "51 a1 ORDERS.NOTE ASSIGNMENT NVL",
            "52 a2 ORDERS.CODE ASSIGNMENT NVL",
            "52 a2 ORDERS.NOTE ASSIGNMENT NVL",
            "53 a3 ORDERS.CODE ASSIGNMENT - ||",
            "61 b1 ORDERS.CODE RETURNED NVL",
            "61 b1 ORDERS.NOTE RETURNED NVL"
        )
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "ARGUMENT_ROUTINE_OUTSIDE: 2",                       // put_line(r.code || r.note) once, as put_line(r.code)
            "EXPRESSION_CONCATENATION: 1",                       // w8, as code || to_char(amount) alone
            "EXPRESSION_NVL_TO_CHAR: 1",                         // w7
            "RECORD_ASSIGNMENT_EXPRESSION_CONCATENATION: 1"      // a3
        )
    }

    private companion object {
        const val SEVERAL = """create or replace package pk_sc as
  function both(p_id number) return varchar2;
  procedure take(p_v varchar2);
  procedure show_sc(p_id number);
end;
/
create or replace package body pk_sc as
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

  procedure show_sc(p_id number) is
    w1 varchar2(5 char); w2 varchar2(5 char); w3 varchar2(5 char); w4 varchar2(5 char); w5 varchar2(5 char);
    w6 varchar2(5 char); w7 varchar2(5 char); w8 varchar2(5 char); w9 varchar2(5 char); w10 varchar2(5 char);
    w11 varchar2(5 char); w12 varchar2(5 char); w13 varchar2(5 char); w14 varchar2(5 char);
    k1 varchar2(5 char);
    a1 varchar2(5 char); a2 varchar2(5 char); a3 varchar2(5 char);
    b1 varchar2(5 char);
    type t_v is table of varchar2(5 char);
    tv t_v;
    cursor c_sc is select nvl(code, note) v from orders;
  begin
    select nvl(code, note) into w1 from orders where order_id = p_id;
    select nvl(o.code, n.code) into w2 from orders o join order_notes n on n.order_id = o.order_id where o.order_id = p_id;
    select coalesce(code, note, 'x') into w3 from orders where order_id = p_id;
    select nvl2(amount, code, note) into w4 from orders where order_id = p_id;
    select nvl2(code, note, 'x') into w5 from orders where order_id = p_id;
    select nvl(code, lower(note)) into w6 from orders where order_id = p_id;
    select nvl(code, to_char(amount)) into w7 from orders where order_id = p_id;
    select code || to_char(amount) into w8 from orders where order_id = p_id;
    select code || note into w9 from orders where order_id = p_id;
    select nvl(code, note) || '#' into w10 from orders where order_id = p_id;
    select upper(nvl(code, note)) into w11 from orders where order_id = p_id;
    select max(nvl(code, note)) into w12 from orders;
    select decode(code, 'a', note, 'x') into w13 from orders where order_id = p_id;
    select nvl(code, lower(code)) into w14 from orders where order_id = p_id;
    select nvl(code, note) bulk collect into tv from orders;
    open c_sc;
    fetch c_sc into k1;
    close c_sc;
    for r in (select nvl(code, note) v, code, note, amount from orders) loop
      a1 := r.v;
      a2 := nvl(r.code, r.note);
      a3 := r.code || to_char(r.amount);
      take(nvl(r.code, r.note));
      take(r.code || r.note);
      take(r.v);
      take(r.code);
      dbms_output.put_line(r.code || r.note);
      dbms_output.put_line(r.code);
    end loop;
    b1 := both(p_id);
  end;
end;
/
"""
    }
}
