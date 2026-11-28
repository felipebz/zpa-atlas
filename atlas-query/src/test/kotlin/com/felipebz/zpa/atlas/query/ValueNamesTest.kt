//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import kotlin.io.path.writeText

/** The values written or passed that are names, grouped and found by what they name. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValueNamesTest {
    private val sql = """
        create table bin (bin_id number(6), code varchar2(10));
        create table crate (crate_id number(6), code varchar2(10));
        create or replace package stock as
          g_code varchar2(10);
          procedure put_one(p_code varchar2);
        end;
        /
        create or replace package body stock as
          procedure put_one(p_code varchar2) is
          begin
            insert into bin (bin_id, code) values (1, p_code);
          end;
        end;
        /
        create or replace procedure fill_a is
          v varchar2(10);
        begin
          v := 'A';
          insert into bin (bin_id, code) values (1, v);
        end;
        /
        create or replace procedure fill_b(p_code varchar2) is
          v varchar2(10);
          r crate%rowtype;
        begin
          v := 'B';
          update bin set code = v;
          insert into bin (bin_id, code) values (2, stock.g_code);
          stock.put_one(p_code);
          select * into r from crate where crate_id = 1;
          insert into bin (bin_id, code) values (3, r.code);
          update bin set code = r.code where bin_id = 3;
          for c in (select crate_id, code from crate) loop
            insert into bin (bin_id, code) values (c.crate_id, c.code);
            update bin set code = c.code where bin_id = c.crate_id;
          end loop;
          for e in (select decode(code, 'a', 'b') tag from crate) loop
            insert into bin (code) values (e.tag);
          end loop;
          insert into bin (bin_id, code) values (4, missing_name);
        end;
        /
        create or replace procedure fill_c is
          w varchar2(10);
        begin
          stock.put_one(w);
        end;
        /
        declare
          w varchar2(10);
        begin
          stock.put_one(w);
        end;
        /
    """.trimIndent() + "\n"

    private lateinit var index: AtlasIndex

    @BeforeAll
    fun index() {
        val temp = Files.createTempDirectory("atlas-value-names")
        val sources = temp.resolve("src").also { it.toFile().mkdirs() }
        sources.resolve("stock.sql").writeText(sql)
        val db = temp.resolve("stock.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", threads = 1))
        index = AtlasIndex(db, sources)
    }

    private fun groups(key: String) = index.values(key).result!!.filter { it.valueKind == "NAME" }
        .map { "${it.value} ${it.entity?.key} ${it.field} ${it.column?.key} ${it.columnReason} ${it.count}" }

    @Test
    fun `a name is grouped by the entity it names, a row record's field also by its column`() {
        assertThat(groups("db:SHOP.BIN.CODE")).containsExactlyInAnyOrder(
            "C.CODE null CODE db:SHOP.CRATE.CODE null 2",
            "R.CODE db:SHOP.FILL_B(P_CODE IN VARCHAR2).R CODE db:SHOP.CRATE.CODE null 2",
            "V db:SHOP.FILL_A().V null null null 1",
            "V db:SHOP.FILL_B(P_CODE IN VARCHAR2).V null null null 1",
            "STOCK.G_CODE db:SHOP.STOCK.G_CODE null null null 1",
            "P_CODE db:SHOP.STOCK.PUT_ONE(P_CODE IN VARCHAR2)@BODY.P_CODE null null null 1",
            "E.TAG null TAG null EXPRESSION_DECODE_CONDITION 1",
            "MISSING_NAME null null null null 1"
        )
        assertThat(groups("db:SHOP.BIN.BIN_ID")).containsExactly("C.CRATE_ID null CRATE_ID db:SHOP.CRATE.CRATE_ID null 1")
    }

    @Test
    fun `an argument that is a name is grouped by what it names`() {
        assertThat(groups("db:SHOP.STOCK.PUT_ONE(P_CODE IN VARCHAR2).P_CODE"))
            .containsExactlyInAnyOrder(
                "P_CODE db:SHOP.FILL_B(P_CODE IN VARCHAR2).P_CODE null null null 1",
                "W db:SHOP.FILL_C().W null null null 1",
                "W null null null null 1"
            )
    }

    @Test
    fun `the sites of a name group are those of that group alone, as many as its count`() {
        for (key in listOf("db:SHOP.BIN.CODE", "db:SHOP.BIN.BIN_ID", "db:SHOP.STOCK.PUT_ONE(P_CODE IN VARCHAR2).P_CODE")) {
            for (group in index.values(key).result!!) {
                val name = NameGroup(group.entity?.key, group.field, group.column?.key, group.columnReason)
                val sites = index.valueSites(key, group.valueKind, group.value, name = name).result!!
                assertThat(sites.rows.size.toLong()).describedAs("$key $group").isEqualTo(group.count)
            }
        }
        fun lines(value: String, name: NameGroup) = index.valueSites("db:SHOP.BIN.CODE", "NAME", value, name = name).result!!.rows.map { it.span!!.startLine }
        assertThat(lines("V", NameGroup("db:SHOP.FILL_A().V"))).containsExactly(19)
        assertThat(lines("V", NameGroup())).isEmpty()
        assertThat(lines("C.CODE", NameGroup(field = "CODE", column = "db:SHOP.CRATE.CODE"))).containsExactly(34, 35)
        val row = index.valueSites("db:SHOP.BIN.CODE", "NAME", "R.CODE", name = NameGroup("db:SHOP.FILL_B(P_CODE IN VARCHAR2).R", "CODE", "db:SHOP.CRATE.CODE"))
            .result!!.rows.first()
        assertThat(row.values.single().let { "${it.entity?.key} ${it.field} ${it.column?.key}" })
            .isEqualTo("db:SHOP.FILL_B(P_CODE IN VARCHAR2).R CODE db:SHOP.CRATE.CODE")
    }
}
