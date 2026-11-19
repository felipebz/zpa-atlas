//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.api.project.ProjectExternalDatatype.LengthSemantics
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** DECODE and CASE give back one of their results: each column in a result is a place, what they compare is not. */
class ColumnPlacesDecodeCaseTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `every column a DECODE or CASE may give back is a place`() {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(sources.resolve("pk_dc.sql"), DECODE_CASE)
        val db = temp.resolve("dc.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = shopTables(
            "ORDERS" to listOf("ORDER_ID" to number(10), "CODE" to varchar2(20, LengthSemantics.CHAR), "NOTE" to varchar2(40, LengthSemantics.CHAR),
                "STATUS" to varchar2(1, LengthSemantics.CHAR), "AMOUNT" to number(8, 2))), threads = 1))
        val rows = Fixture.query(db, """
            SELECT p.start_line || ' ' || p.target_written || ' ' || replace(c.key, 'db:SHOP.', '') || ' ' || p.source || ' ' ||
                   coalesce(p.functions, '-') || (CASE WHEN p.concatenated = 1 THEN ' ||' ELSE '' END)
            FROM column_place p JOIN entity c ON c.id = p.column_id ORDER BY p.start_line, p.target_written, c.key, p.functions, p.concatenated""")
        assertThat(rows).containsExactly(
            // take(r.v) and take(case when r.status = 'A' then r.code else 'x' end).
            "10 l1 ORDERS.CODE IN_ARGUMENT CASE",
            "10 l1 ORDERS.CODE IN_ARGUMENT DECODE",
            "10 l1 ORDERS.NOTE IN_ARGUMENT DECODE",
            // d1 and d8 only compare their column.
            "21 d2 ORDERS.CODE SELECT_INTO DECODE",
            "21 d2 ORDERS.NOTE SELECT_INTO DECODE",
            "22 d3 ORDERS.CODE SELECT_INTO DECODE",
            "23 d4 ORDERS.CODE SELECT_INTO DECODE",
            // The first code of d5 is only compared.
            "24 d5 ORDERS.CODE SELECT_INTO DECODE",
            "24 d5 ORDERS.NOTE SELECT_INTO DECODE",
            "25 d6 ORDERS.CODE SELECT_INTO DECODE,UPPER",
            "26 d7 ORDERS.CODE SELECT_INTO DECODE",
            "26 d7 ORDERS.CODE SELECT_INTO DECODE ||",
            "26 d7 ORDERS.NOTE SELECT_INTO DECODE",
            "28 d9 ORDERS.CODE SELECT_INTO NVL,DECODE",
            "28 d9 ORDERS.NOTE SELECT_INTO NVL",
            // d10 only compares; d12's first result is a number, so every result is one.
            "30 d11 ORDERS.CODE SELECT_INTO DECODE",
            "30 d11 ORDERS.NOTE SELECT_INTO DECODE",
            "32 c1 ORDERS.CODE SELECT_INTO CASE",
            "32 c1 ORDERS.NOTE SELECT_INTO CASE",
            "33 c2 ORDERS.CODE SELECT_INTO CASE",
            "35 c4 ORDERS.CODE SELECT_INTO CASE,LOWER",
            "37 a1 ORDERS.CODE ASSIGNMENT DECODE",
            "37 a1 ORDERS.NOTE ASSIGNMENT DECODE",
            "38 a2 ORDERS.CODE ASSIGNMENT CASE",
            "38 a2 ORDERS.NOTE ASSIGNMENT CASE"
        )
        assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY message")
            .map { it.substringBefore(" ") + " " + it.substringAfter(": ").substringBefore(" ") }).containsExactly(
            "EXPRESSION_CASE_CONDITION: 1",                       // c3
            "EXPRESSION_CASE_TO_CHAR: 1",                         // c4
            "EXPRESSION_DECODE_CONDITION: 2",                     // d1, d8
            "EXPRESSION_DECODE_TO_CHAR: 1",                       // d6
            "EXPRESSION_SEVERAL_COLUMNS_DECODE_CONDITION: 1",     // d10
            "EXPRESSION_SEVERAL_COLUMNS_OTHER_DECODE: 1"          // d12
        )
    }

    @Test
    fun `a DECODE whose first result is not text is not followed, its type written or anchored`() {
        val tables = listOf("ORDER_ID" to number(10), "CODE" to varchar2(20, LengthSemantics.CHAR), "STATUS" to varchar2(1, LengthSemantics.CHAR),
            "AMOUNT" to number(8, 2), "CREATED" to date())
        // With the dictionary, and with the table created only in the sources.
        for (dictionary in listOf(shopTables("ORDERS" to tables), AtlasDictionary.NONE)) {
            val sources = Files.createDirectories(temp.resolve("src-${dictionary.isAvailable}"))
            Files.writeString(sources.resolve("pr.sql"), NOT_TEXT)
            Files.writeString(sources.resolve("pg.sql"), ANCHORED)
            if (!dictionary.isAvailable) Files.writeString(sources.resolve("orders.sql"),
                "create table orders (order_id number(10), code varchar2(20 char), status varchar2(1 char), amount number(8,2), created date);\n")
            val db = temp.resolve("nt-${dictionary.isAvailable}.db")
            Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = dictionary, threads = 1))
            // g6's first result, v_c, is text: the only place.
            assertThat(Fixture.query(db, """
                SELECT p.target_written || ' ' || replace(c.key, 'db:SHOP.', '') || ' ' || p.functions
                FROM column_place p JOIN entity c ON c.id = p.column_id""")).containsExactly("g6 ORDERS.CODE DECODE")
            assertThat(Fixture.query(db, "SELECT message FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED'")
                .map { it.substringBefore(" ") + " " + it.substringAfter(": ").substringBefore(" ") })
                .containsExactly("EXPRESSION_SEVERAL_COLUMNS_OTHER_DECODE: 5", "EXPRESSION_SEVERAL_COLUMNS_OTHER_DECODE: 5")
        }
    }

    private companion object {
        const val ANCHORED = """create or replace procedure pg(p_id number, p_a orders.amount%type, p_d orders.created%type) is
  g1 varchar2(5 char); g2 varchar2(5 char); g3 varchar2(5 char); g4 varchar2(5 char); g5 varchar2(5 char); g6 varchar2(5 char);
  v_r orders%rowtype;
  v_a orders.amount%type;
  v_b v_a%type;
  v_c orders.code%type;
begin
  select decode(status, 'A', p_a, code) into g1 from orders where order_id = p_id;
  select decode(status, 'A', p_d, code) into g2 from orders where order_id = p_id;
  select decode(status, 'A', v_r.amount, code) into g3 from orders where order_id = p_id;
  select decode(status, 'A', v_b, code) into g4 from orders where order_id = p_id;
  select decode(status, 'A', v_a, code) into g5 from orders where order_id = p_id;
  select decode(status, 'A', v_c, code) into g6 from orders where order_id = p_id;
end;
/
"""

        const val NOT_TEXT = """create or replace procedure pr(p_id number, p_n number) is
  e1 varchar2(5 char); e2 varchar2(5 char); e3 varchar2(5 char); e4 varchar2(5 char); e5 varchar2(5 char);
  n number := 1;
begin
  select decode(status, 'A', p_n, code) into e1 from orders where order_id = p_id;
  select decode(status, 'A', n, code) into e2 from orders where order_id = p_id;
  select decode(status, 'A', amount * 2, code) into e3 from orders where order_id = p_id;
  select decode(status, 'A', created, code) into e4 from orders where order_id = p_id;
  select decode(status, 'A', amount, code) into e5 from orders where order_id = p_id;
end;
/
"""

        const val DECODE_CASE = """create or replace package pk_dc as
  procedure take(p_v varchar2);
  procedure run(p_id number);
end;
/
create or replace package body pk_dc as
  procedure take(p_v varchar2) is
    l1 varchar2(5 char);
  begin
    l1 := p_v;
  end;

  procedure run(p_id number) is
    d1 varchar2(5 char); d2 varchar2(5 char); d3 varchar2(5 char); d4 varchar2(5 char); d5 varchar2(5 char);
    d6 varchar2(5 char); d7 varchar2(5 char); d8 varchar2(5 char); d9 varchar2(5 char); d10 varchar2(5 char);
    d11 varchar2(5 char); d12 varchar2(5 char);
    c1 varchar2(5 char); c2 varchar2(5 char); c3 varchar2(5 char); c4 varchar2(5 char);
    a1 varchar2(5 char); a2 varchar2(5 char);
  begin
    select decode(status, 'A', 'Active', 'B', 'Blocked', 'Other') into d1 from orders where order_id = p_id;
    select decode(status, 'A', code, note) into d2 from orders where order_id = p_id;
    select decode(status, 'A', code, 'x') into d3 from orders where order_id = p_id;
    select decode(status, 'A', code) into d4 from orders where order_id = p_id;
    select decode(code, null, note, code) into d5 from orders where order_id = p_id;
    select decode(status, 'A', upper(code), to_char(amount)) into d6 from orders where order_id = p_id;
    select decode(status, 'A', code, 'B', note, code || '#') into d7 from orders where order_id = p_id;
    select decode(code, 'x', 'y') into d8 from orders where order_id = p_id;
    select nvl(decode(status, 'A', code), note) into d9 from orders where order_id = p_id;
    select decode(status, code, 'y', 'z') into d10 from orders where order_id = p_id;
    select decode(status, 'A', note, 'B', code) into d11 from orders where order_id = p_id;
    select decode(status, 'A', 1, code) into d12 from orders where order_id = p_id;
    select case status when 'A' then code else note end into c1 from orders where order_id = p_id;
    select case when status = 'A' then code when amount > 0 then 'pos' end into c2 from orders where order_id = p_id;
    select case when code is null then 'empty' else 'full' end into c3 from orders where order_id = p_id;
    select case when status = 'A' then lower(code) else to_char(amount) end into c4 from orders where order_id = p_id;
    for r in (select code, note, status, decode(status, 'A', code, note) v from orders) loop
      a1 := r.v;
      a2 := case r.status when 'A' then r.code else r.note end;
      take(r.v);
      take(case when r.status = 'A' then r.code else 'x' end);
    end loop;
  end;
end;
/
"""
    }
}
