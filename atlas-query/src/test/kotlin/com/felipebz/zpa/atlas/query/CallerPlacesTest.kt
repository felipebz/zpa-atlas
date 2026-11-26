//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/** The OUT parameters a column's value is read into: their callers' arguments, or why they are not listed. */
class CallerPlacesTest {
    @TempDir
    lateinit var temp: Path

    private val sql = """
        create table crate (crate_id number(10), label varchar2(20));
        create or replace package depot as
          procedure find_label(p_id number, p_label out varchar2);
        end;
        /
        create or replace package body depot as
          procedure find_label(p_id number, p_label out varchar2) is
          begin
            select label into p_label from crate where crate_id = p_id;
          end;
        end;
        /
        create or replace procedure show_label is
          l_label varchar2(10);
        begin
          depot.find_label(1, l_label);
        end;
        /
    """.trimIndent() + "\n"

    /** A module whose package and standalone procedure each read the column into an OUT parameter, both called. */
    private val module = """<?xml version="1.0" encoding="UTF-8"?>
        |<Module version="122010300" xmlns="http://xmlns.oracle.com/Forms"><FormModule Name="YARD">
        |<ProgramUnit Name="YPK" ProgramUnitType="Package Spec" ProgramUnitText="PACKAGE ypk IS@  PROCEDURE get_label(p_id number, p_label OUT varchar2);@END;"/>
        |<ProgramUnit Name="YPK" ProgramUnitType="Package Body" ProgramUnitText="PACKAGE BODY ypk IS@  PROCEDURE get_label(p_id number, p_label OUT varchar2) IS@  BEGIN@    SELECT label INTO p_label FROM crate WHERE crate_id = p_id;@  END;@END;"/>
        |<ProgramUnit Name="GET_TAG" ProgramUnitType="Procedure" ProgramUnitText="PROCEDURE get_tag(p_id number, p_tag OUT varchar2) IS@BEGIN@  SELECT label INTO p_tag FROM crate WHERE crate_id = p_id;@END;"/>
        |<ProgramUnit Name="USE_ALL" ProgramUnitType="Procedure" ProgramUnitText="PROCEDURE use_all IS@  l_a varchar2(8);@  l_b varchar2(9);@BEGIN@  ypk.get_label(1, l_a);@  get_tag(1, l_b);@END;"/>
        |</FormModule></Module>""".trimMargin().replace("@", "&amp;#10;")

    @Test
    fun `a caller-decided place lists its callers, or says that Forms module code is not followed to them`() {
        val sources = temp.resolve("src").also { it.toFile().mkdirs() }
        sources.resolve("shop.sql").writeText(sql)
        sources.resolve("yard_fmb.xml").writeText(module)
        val db = temp.resolve("shop.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", threads = 1))
        val rows = AtlasIndex(db, sources).columnPlaces("db:SHOP.CRATE.LABEL").result!!.rows
        assertThat(rows.map { "${it.targetWritten} ${it.verdict} ${it.callersNotFollowed}" }).containsExactlyInAnyOrder(
            "p_label CALLER_DECIDES null", "l_label SMALLER null",
            "p_label CALLER_DECIDES FORMS_CODE", "p_tag CALLER_DECIDES FORMS_CODE"
        )
    }
}
