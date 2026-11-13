//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.api.project.ProjectExternalDatatype
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import java.nio.file.Files
import java.nio.file.Path

/**
 * A table whose `CREATE TABLE` is among the sources, with no dictionary: columns sized in bytes, in characters, without a
 * unit, and a number; and a package whose routine reads them into targets of every size against them.
 */
object SizesFixture {
    const val TABLE = """create table orders (
  order_id number(10),
  code varchar2(10 byte),
  note varchar2(10 char),
  amount number(8,2),
  label varchar2(10)
);
"""

    const val PACKAGE = """create or replace package pk_lv as
  type t_rec is record (code varchar2(8));
  procedure show_lv(p_id number, p_out out varchar2);
end;
/
create or replace package body pk_lv as
  procedure show_lv(p_id number, p_out out varchar2) is
    c1 varchar2(5); c2 varchar2(10); c3 varchar2(10 char); c4 varchar2(30);
    c5 orders.code%type; c6 orders.label%type; c7 clob;
    r t_rec;
    n1 varchar2(10 byte); n2 varchar2(4); n3 varchar2(40 byte);
    a1 number(6,2); a2 number(10,2); a3 number;
  begin
    select code, code, code, 'x' || code, code, code, code into c1, c2, c3, c4, c5, c6, c7 from orders where order_id = p_id;
    select code into r.code from orders where order_id = p_id;
    select note, note, note into n1, n2, n3 from orders where order_id = p_id;
    select amount, amount, amount into a1, a2, a3 from orders where order_id = p_id;
    select code into p_out from orders where order_id = p_id;
  end;
end;
/
"""

    /** Writes the table, the package and the [more] files (name to text) into [sources]. */
    fun write(sources: Path, more: Map<String, String> = emptyMap()) {
        Files.createDirectories(sources)
        Files.writeString(sources.resolve("orders.sql"), TABLE)
        Files.writeString(sources.resolve("pk_lv.sql"), PACKAGE)
        for ((name, text) in more) Files.writeString(sources.resolve(name), text)
    }

    /**
     * The columns of [TABLE] for lookups only, as an acquired dictionary gives them, so that zpa reads the names of the
     * queries as its columns; the table's entity stays the one the sources create.
     */
    val COLUMNS: AtlasDictionary by lazy { lookupTables("SHOP", ORDERS) }

    /** [TABLE] as a dictionary lists it. */
    val ORDERS: TableColumns = "ORDERS" to listOf("ORDER_ID" to number(10), "CODE" to varchar2(10),
        "NOTE" to varchar2(10, ProjectExternalDatatype.LengthSemantics.CHAR), "AMOUNT" to number(8, 2), "LABEL" to varchar2(10, null))

    /** Indexes the sources written into [folder]/src as `SHOP`, a length without a unit in [lengthSemantics], to [folder]/sizes.db. */
    fun index(
        folder: Path, lengthSemantics: String = "BYTE", more: Map<String, String> = emptyMap(), dictionary: AtlasDictionary = COLUMNS
    ): Path {
        val sources = folder.resolve("src")
        write(sources, more)
        val output = folder.resolve("sizes.db")
        Indexer().index(IndexOptions(sources, output, compilationSchema = "SHOP", dictionary = dictionary, threads = 1, nlsLengthSemantics = lengthSemantics))
        return output
    }
}
