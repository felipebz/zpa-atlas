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

/** Calls of overloads that differ only by the type of a parameter, as zpa's local analysis decides them. */
class CallBindingsTest {
    @TempDir
    lateinit var temp: Path

    private fun index(dictionary: AtlasDictionary = AtlasDictionary.NONE): Path {
        val src = Files.createDirectories(temp.resolve("src"))
        Files.writeString(src.resolve("orders.sql"), ORDERS)
        Files.writeString(src.resolve("pk_ov.sql"), OVERLOADS)
        Files.writeString(src.resolve("pk_use.sql"), USES)
        val db = temp.resolve("atlas.db")
        Indexer().index(IndexOptions(src, db, compilationSchema = "SHOP", dictionary = dictionary, threads = 1))
        return db
    }

    @Test
    fun `a call takes the overload the types of its actuals select`() {
        val db = index()
        val calls = Fixture.query(db, """
            SELECT o.start_line || ' ' || o.written || ' ' || o.status || ' ' || coalesce(o.reason, '-') || ' ' ||
                   coalesce(replace(e.key, 'db:SHOP.', ''), '-') ||
                   coalesce(' [' || (SELECT group_concat(replace(c.key, 'db:SHOP.', ''), ' | ') FROM
                       (SELECT ce.key FROM occurrence_candidate oc JOIN entity ce ON ce.id = oc.entity_id WHERE oc.occurrence_id = o.id ORDER BY ce.key) c) || ']', '')
            FROM occurrence o LEFT JOIN entity e ON e.id = o.entity_id JOIN source_file f ON f.id = o.file_id
            WHERE f.path = 'pk_use.sql' AND o.roles LIKE '%CALL%' ORDER BY o.start_line, o.start_col""")
        assertThat(calls).containsExactly(
            "17 pk_ov.p RESOLVED - PK_OV.P(A IN NUMBER)",
            "18 pk_ov.p RESOLVED - PK_OV.P(A IN DATE)",
            "19 pk_ov.p RESOLVED - PK_OV.P(A IN DATE)",
            "20 pk_ov.f RESOLVED - PK_OV.F(A IN NUMBER)",
            "21 pk_ov.f RESOLVED - PK_OV.F(A IN VARCHAR2)",
            "22 pk_ov.p AMBIGUOUS AMBIGUOUS - [PK_OV.P(A IN DATE) | PK_OV.P(A IN NUMBER)]",
            // zpa does not answer for a call written in SQL: the structural resolution stays.
            "23 pk_ov.f AMBIGUOUS AMBIGUOUS - [PK_OV.F(A IN NUMBER) | PK_OV.F(A IN VARCHAR2)]",
            "24 q RESOLVED - PK_USE.Q(A IN NUMBER)@BODY",
            "25 q RESOLVED - PK_USE.Q(A IN DATE)@BODY",
            // A nested subprogram has no entity.
            "26 z UNRESOLVED LOCAL_SUBPROGRAM -",
            "27 z UNRESOLVED LOCAL_SUBPROGRAM -",
            // Without the dictionary zpa does not know the type of `code` of the cursor's query: both overloads are kept.
            "29 pk_ov.put AMBIGUOUS AMBIGUOUS - [PK_OV.PUT(P_V IN NUMBER) | PK_OV.PUT(P_V IN VARCHAR2)]"
        )
    }

    @Test
    fun `a column passed to the overload its type selects goes on to the parameter`() {
        // The columns of orders, so that zpa reads `code` of the cursor's query as the column.
        val db = index(shopTables("ORDERS" to listOf("ORDER_ID" to number(10), "CODE" to varchar2(20, LengthSemantics.CHAR), "AMOUNT" to number(8, 2),
            "CREATED" to date())))
        assertThat(places(db, "pk_ov.sql")).containsExactly("18 ORDERS.CODE IN_ARGUMENT l1 FIXED_SIZE varchar2(5 char) 5 CHAR - -")
        assertThat(Fixture.query(db, "SELECT substr(message, 1, instr(message, ':') - 1) FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED'"))
            .doesNotContain("ARGUMENT_ROUTINE_UNDECIDED")
    }

    private companion object {
        const val ORDERS = "create table orders (order_id number(10), code varchar2(20 char), amount number(8,2), created date);\n"

        const val OVERLOADS = """create or replace package pk_ov as
  procedure p(a number);
  procedure p(a date);
  function f(a number) return number;
  function f(a varchar2) return varchar2;
  procedure put(p_v varchar2);
  procedure put(p_v number);
end;
/
create or replace package body pk_ov as
  procedure p(a number) is begin null; end;
  procedure p(a date) is begin null; end;
  function f(a number) return number is begin return a; end;
  function f(a varchar2) return varchar2 is begin return a; end;
  procedure put(p_v varchar2) is
    l1 varchar2(5 char);
  begin
    l1 := p_v;
  end;
  procedure put(p_v number) is begin null; end;
end;
/
"""

        const val USES = """create or replace package pk_use as
  procedure run(p_id number);
end;
/
create or replace package body pk_use as
  procedure q(a number) is begin null; end;
  procedure q(a date) is begin null; end;

  procedure run(p_id number) is
    n number := 1;
    d date := sysdate;
    v varchar2(10);
    x number;
    procedure z(a number) is begin null; end;
    procedure z(a date) is begin null; end;
  begin
    pk_ov.p(n);
    pk_ov.p(d);
    pk_ov.p(a => d);
    x := pk_ov.f(1);
    v := pk_ov.f('x');
    pk_ov.p(unknown_pkg.g_value);
    select pk_ov.f(order_id) into x from orders where order_id = p_id;
    q(1);
    q(sysdate);
    z(1);
    z(sysdate);
    for r in (select code from orders) loop
      pk_ov.put(r.code);
    end loop;
  end;
end;
/
"""
    }
}
