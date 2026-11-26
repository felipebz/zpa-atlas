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

/**
 * A program unit switched off by its text: `NULL;` with its old procedure in a comment, that comment alone, or every line
 * commented. It declares nothing, and the code after it in the module is read as if it were not there.
 */
class FormsSwitchedOffUnitTest {
    @TempDir
    lateinit var temp: Path

    /** The module with [off] as the text of the unit before `LOAD_BRANCH`, indexed into `[name].db`. */
    private fun index(name: String, off: String): Path {
        val sources = temp.resolve(name).createDirectories()
        sources.resolve("tables.sql").writeText(
            "create table accounts (id number, branch_code number(6));\ncreate table transfers (id number, branch_code number(6));\n"
        )
        val load = "procedure load_branch(p_id number) is$NL  v_branch accounts.branch_code%type;${NL}begin$NL" +
            "  select branch_code into v_branch from transfers where id = p_id;$NL  :accounts.branch_code := v_branch;${NL}end;"
        sources.resolve("ledger_fmb.xml").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            |<Module version="122010300" xmlns="http://xmlns.oracle.com/Forms"><FormModule Name="LEDGER">
            |<Block Name="ACCOUNTS" QueryDataSourceName="ACCOUNTS"><Item Name="BRANCH_CODE" ColumnName="BRANCH_CODE"/><Item Name="ID"/></Block>
            |<ProgramUnit Name="OLD_REPORT" ProgramUnitType="Procedure" ProgramUnitText="$off"/>
            |<ProgramUnit Name="LOAD_BRANCH" ProgramUnitType="Procedure" ProgramUnitText="$load"/>
            |</FormModule></Module>""".trimMargin()
        )
        return temp.resolve("$name.db").also { Indexer().index(IndexOptions(sources, it, compilationSchema = "SHOP", threads = 1)) }
    }

    @Test
    fun `the code after a switched-off program unit is parsed and bound, the block's names still the module's`() {
        // NULL; and the old text in a comment; the old text in a comment alone; every line commented.
        val shapes = mapOf(
            "statement" to "NULL;$NL$NL/*${NL}procedure old_report is${NL}begin${NL}  null;${NL}end;$NL*/",
            "comment" to "/*${NL}procedure old_report is${NL}begin${NL}  null;${NL}end;$NL*/",
            "lines" to "--procedure old_report is$NL--begin$NL--  null;$NL--end;"
        )
        for ((name, off) in shapes) checkAfter(name, index(name, off))
    }

    private fun checkAfter(name: String, db: Path) {
        assertThat(Fixture.query(db, "SELECT parse_status FROM source_file WHERE path = 'ledger_fmb.xml'")).describedAs(name).containsExactly("OK")
        assertThat(Fixture.query(db, """
            SELECT p.target_written, p.class, s.key FROM column_place p JOIN entity c ON c.id = p.column_id LEFT JOIN entity s ON s.id = p.type_source_id
            WHERE c.key = 'db:SHOP.TRANSFERS.BRANCH_CODE'""")).describedAs(name).containsExactly("v_branch\tANCHORED_ELSEWHERE\tdb:SHOP.ACCOUNTS.BRANCH_CODE")
        assertThat(Fixture.query(db, """
            SELECT o.written, e.key, o.roles FROM occurrence o JOIN entity e ON e.id = o.entity_id
            WHERE lower(o.written) IN ('v_branch', 'p_id', ':accounts.branch_code') AND o.roles NOT LIKE '%DEFINITION%' ORDER BY o.id""")).describedAs(name).containsExactly(
            "v_branch\tforms:LEDGER/LOAD_BRANCH.V_BRANCH\tWRITE",
            "p_id\tforms:LEDGER/LOAD_BRANCH.P_ID\tREAD",
            ":accounts.branch_code\tforms:LEDGER/ACCOUNTS/BRANCH_CODE\tWRITE",
            "v_branch\tforms:LEDGER/LOAD_BRANCH.V_BRANCH\tREAD"
        )
    }

    private companion object {
        const val NL = "&amp;#10;"
    }
}
