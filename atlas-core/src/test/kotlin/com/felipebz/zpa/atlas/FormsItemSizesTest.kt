//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** The size of each kind of Forms target a column's value is read into, on an invented module. */
class FormsItemSizesTest {
    @TempDir
    lateinit var temp: Path

    private val items = listOf(
        "TEXT_CODE" to "Text Item" to "Char" to 5,
        "LIST_CODE" to "List Item" to "Char" to 3,
        "CHECK_CODE" to "Check Box" to "Char" to 1,
        "RADIO_CODE" to "Radio Group" to "Char" to 2,
        "LIST_NUMBER" to "List Item" to "Number" to 6,
        "PICTURE" to "Image" to "Char" to 30,
        "GO_BUTTON" to "Push Button" to "Char" to 30
    )

    private val db: Path by lazy {
        val sources = temp.resolve("src").createDirectories()
        sources.resolve("bins.sql").writeText("create table bins (bin_id number(6), code varchar2(4));\n")
        val nl = "&amp;#10;"
        val reads = items.joinToString("") { (named, _) -> "  select code into :ctrl.${named.first.first.lowercase()} from bins where bin_id = 1;$nl" } +
            "  select code into :parameter.p_code from bins where bin_id = 1;$nl  select code into :global.last_code from bins where bin_id = 1;$nl"
        val itemXml = items.joinToString("\n") { (named, length) ->
            val (nameKind, type) = named
            """<Item Name="${nameKind.first}" ItemType="${nameKind.second}" DatabaseItem="false" DataType="$type" MaximumLength="$length"/>"""
        }
        sources.resolve("yard_fmb.xml").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            |<Module version="122010300" xmlns="http://xmlns.oracle.com/Forms"><FormModule Name="YARD">
            |<Block Name="CTRL" DatabaseBlock="false">
            |$itemXml
            |</Block>
            |<ModuleParameter Name="P_CODE" ParameterDataType="Char"/>
            |<ProgramUnit Name="READ_CODES" ProgramUnitType="Procedure" ProgramUnitText="PROCEDURE read_codes IS${nl}BEGIN$nl$reads${nl}END;"/>
            |</FormModule></Module>""".trimMargin()
        )
        temp.resolve("atlas.db").also { Indexer().index(IndexOptions(sources, it, compilationSchema = "YARD", threads = 1)) }
    }

    @Test
    fun `a list item, check box and radio group hold their maximum length as a text item does, and the others say why they have no size`() {
        assertThat(Fixture.query(db, """
            SELECT p.target_written, p.class, coalesce(p.declared_type, '-'), coalesce(p.length, '-'), coalesce(p.reason, '-') FROM column_place p
            WHERE p.column_id = (SELECT id FROM entity WHERE key = 'db:YARD.BINS.CODE') ORDER BY p.start_line""")).containsExactly(
            ":ctrl.text_code\tFIXED_SIZE\tCHAR\t5\t-",
            ":ctrl.list_code\tFIXED_SIZE\tCHAR\t3\t-",
            ":ctrl.check_code\tFIXED_SIZE\tCHAR\t1\t-",
            ":ctrl.radio_code\tFIXED_SIZE\tCHAR\t2\t-",
            ":ctrl.list_number\tNO_SIZE\tNUMBER\t-\t-",
            ":ctrl.picture\tUNKNOWN\t-\t-\tITEM_HOLDS_IMAGE",
            ":ctrl.go_button\tUNKNOWN\t-\t-\tITEM_HOLDS_NO_VALUE",
            // frmf2xml writes no maximum length of a parameter; a global's length is Forms', not the module's.
            ":parameter.p_code\tUNKNOWN\t-\t-\tPARAMETER_LENGTH_NOT_GIVEN",
            ":global.last_code\tUNKNOWN\t-\t-\tGLOBAL_LENGTH_NOT_GIVEN"
        )
    }
}
