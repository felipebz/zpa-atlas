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
import java.nio.file.Files
import java.nio.file.Path

/**
 * A small project whose code reads the columns of `SHOP.ORDERS` into PL/SQL targets of every kind of declaration
 * (fixed size, anchored to the column or to another one, without size, not known), with a view over the table and
 * a hand-built dictionary.
 */
object PlacesFixture {
    private fun type(name: String, length: Int? = null, unit: LengthSemantics? = null, precision: Int? = null, scale: Int? = null) =
        ProjectExternalDatatype.of(name, null, length, unit, precision, scale)

    private fun relation(name: String, kind: DictionaryRelationKind, vararg columns: Pair<String, ProjectExternalDatatype>): DictionaryRelation {
        val fields = columns.mapIndexed { i, (column, type) -> ProjectExternalColumn.of(column, i + 1, type) }
        val relation = if (kind == DictionaryRelationKind.VIEW) ProjectExternalRelation.view("SHOP", name, true, fields)
            else ProjectExternalRelation.of("SHOP", name, true, fields)
        return DictionaryRelation(relation, kind)
    }

    val dictionary: AtlasDictionary = AtlasDictionary.of("places", listOf("SHOP"), listOf(
        relation("ORDERS", DictionaryRelationKind.TABLE, "ORDER_ID" to type("NUMBER", precision = 10, scale = 0),
            "CODE" to type("VARCHAR2", 12, LengthSemantics.BYTE), "AMOUNT" to type("NUMBER", precision = 12, scale = 2),
            "NOTE" to type("VARCHAR2", 200, LengthSemantics.CHAR)),
        relation("V_ORDERS", DictionaryRelationKind.VIEW, "ORDER_ID" to type("NUMBER", precision = 10, scale = 0),
            "CODE" to type("VARCHAR2", 12, LengthSemantics.BYTE))
    ))

    /** The package spec, whose subtype [codeLength] long the body reads a column into. */
    fun spec(codeLength: Int = 12) = """
        create or replace package pk_ord is
          subtype t_code is varchar2($codeLength char);
          type t_codes is table of varchar2(12);
          type t_pair is record (code orders.code%type, amount number);
          g_code orders.code%type;
          cursor c_ord is select order_id, code, upper(code) code_up from orders;
          procedure run(p_code out varchar2);
          procedure log_it(p_text varchar2);
        end;
        /
    """.trimIndent()

    const val BODY = """create or replace package body pk_ord is
  procedure log_it(p_text varchar2) is
  begin
    null;
  end;
  procedure run(p_code out varchar2) is
    l_code    varchar2(12);
    l_code2   orders.code%type;
    l_any     varchar2(4000);
    l_sub     t_code;
    l_via     l_code2%type;
    l_num     number;
    l_note    orders.note%type;
    l_pair    t_pair;
    r_ord     orders%rowtype;
    l_codes   t_codes;
    l_ret     varchar2(20 byte);
  begin
    select code into l_code from orders where order_id = 1;
    select o.code into l_code2 from orders o where o.order_id = 1;
    select shop.orders.code into l_any from shop.orders where order_id = 1;
    select code into l_sub from orders where order_id = 1;
    select code into l_via from orders where order_id = 1;
    select code into p_code from orders where order_id = 1;
    select code into l_note from orders where order_id = 1;
    select code into g_code from orders where order_id = 1;
    select code, amount into l_pair from orders where order_id = 1;
    select code into l_pair.code from orders where order_id = 1;
    select * into r_ord from orders where order_id = 1;
    select code bulk collect into l_codes from orders;
    select upper(code) into l_code from orders where order_id = 1;
    select v.code into l_code from v_orders v where v.order_id = 1;
    update orders set amount = 0 where order_id = 1 returning code into l_ret;
    open c_ord;
    fetch c_ord into l_num, l_code, l_any;
    close c_ord;
    select code into ext_pkg.g_code from orders where order_id = 1;
    execute immediate 'select code from orders where order_id = 1' into l_code;
    for r in (select code from orders) loop
      l_code := r.code;
    end loop;
    log_it(r_ord.code);
  end;
end;
/
"""

    const val SCRIPT = """declare
  l_short varchar2(5);
begin
  select code into l_short from orders where order_id = 1;
end;
/
"""

    /** Writes the sources into [sources]. */
    fun write(sources: Path) {
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("v_orders.sql"), "create or replace view v_orders as select order_id, code from orders;\n")
        Files.writeString(sources.resolve("pk_ord.pks"), spec())
        Files.writeString(sources.resolve("pk_ord.pkb"), BODY)
        Files.writeString(sources.resolve("script.sql"), SCRIPT)
    }

    /** Indexes the sources written into [folder]/src as `SHOP`, to [folder]/places.db. */
    fun index(folder: Path, dictionary: AtlasDictionary = this.dictionary): Path {
        val sources = folder.resolve("src")
        write(sources)
        val output = folder.resolve("places.db")
        Indexer().index(IndexOptions(sources, output, compilationSchema = "SHOP", dictionary = dictionary, threads = 1))
        return output
    }
}
