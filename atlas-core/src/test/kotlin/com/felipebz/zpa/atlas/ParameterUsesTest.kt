//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The uses of the parameters of subprograms and cursors, as zpa binds them (hr/param_uses.sql). */
class ParameterUsesTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy { Fixture.index(temp.resolve("fixture.db")) }

    private val run = "db:HR.PK_P.RUN(P_ROWS IN T_ROWS,P_REC IN OUT T_ROW)@BODY"

    /** Every use of a parameter in the file: position, written text, entity, roles and status. */
    private fun parameterUses(): List<String> = Fixture.query(
        db, """SELECT o.start_line || ':' || o.start_col || ' ' || o.written || ' ' || e.key || ' ' || o.roles || ' ' || o.status
               FROM occurrence o JOIN entity e ON e.id = o.entity_id JOIN source_file f ON f.id = o.file_id
               WHERE f.path = 'hr/param_uses.sql' AND e.kind = 'PARAMETER' AND o.roles NOT IN ('DEFINITION', 'DECLARATION')
               ORDER BY o.start_line, o.start_col"""
    )

    @Test
    fun `reads and writes of a parameter are uses of its entity, with the roles of any value`() {
        assertThat(parameterUses()).containsExactly(
            "7:36 p_id db:HR.PK_P.C_X.P_ID READ RESOLVED",                                   // the cursor's query
            "14:10 h_a db:HR.PK_P.HELPER(H_A IN NUMBER)@BODY.H_A READ RESOLVED",             // inner(h_a)
            "18:4 p_out db:HR.PK_P.FILL(P_OUT OUT NUMBER)@BODY.P_OUT WRITE RESOLVED",        // p_out := 1
            "23:4 p_rec.items.delete $run.P_REC WRITE RESOLVED",                             // a method that changes it
            "24:11 p_rows $run.P_ROWS READ RESOLVED",                                        // p_rows(1).items.count
            "25:53 p_rows $run.P_ROWS READ RESOLVED",                                        // p_rows(1).items(1) in SQL
            "26:9 p_rec.items $run.P_REC WRITE RESOLVED",                                    // an OUT actual
            "34:9 p_v db:HR.F_X(P_V IN NUMBER).P_V READ RESOLVED"                            // return p_v + 1
            // 28: `h_a =>` names the formal, not a use.
        )
    }

    @Test
    fun `a parameter of a nested subprogram has no entity and no use`() {
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE key LIKE '%.I_B'")).isEmpty()
    }

    @Test
    fun `a parameter whose subprogram has an undecided IF may have uses the index did not record`() {
        assertThat(Fixture.query(db, "SELECT e.key || ' ' || g.reasons FROM uses_gap g JOIN entity e ON e.id = g.entity_id WHERE e.kind = 'PARAMETER' ORDER BY 1"))
            .containsExactly(
                "db:HR.PACK_COND.RUN_NEW(P_ID IN NUMBER)@BODY.P_ID CONDITIONAL_COMPILATION",
                "db:HR.PACK_DECLARED.POST_NEW(P_ID IN NUMBER)@BODY.P_ID CONDITIONAL_COMPILATION",
                "db:HR.PK_G.RUN(P_V IN NUMBER)@BODY.P_V CONDITIONAL_COMPILATION"
            )
    }

    /** The uses on [line] of hr/param_fixes.sql of a parameter or body global: written text, entity name and roles. */
    private fun fixes(line: Int): List<String> = Fixture.query(
        db, """SELECT o.written || ' ' || e.name || ' ' || o.roles FROM occurrence o JOIN entity e ON e.id = o.entity_id
               JOIN source_file f ON f.id = o.file_id
               WHERE f.path = 'hr/param_fixes.sql' AND o.start_line = $line AND e.kind IN ('PARAMETER', 'PACKAGE_VARIABLE')
                 AND o.roles NOT IN ('DEFINITION', 'DECLARATION') ORDER BY o.start_col"""
    )

    @Test
    fun `a bind of dynamic SQL is written as its mode says, for a parameter and a body global alike`() {
        assertThat(fixes(25)).containsExactly("p_a P_A WRITE")          // using out p_a
        assertThat(fixes(26)).containsExactly("g_a G_A WRITE")          // using out g_a
        assertThat(fixes(27)).containsExactly("p_c P_C READ,WRITE")     // using in out p_c
        assertThat(fixes(28)).containsExactly("p_b P_B READ")           // using in p_b
        assertThat(fixes(29)).containsExactly("p_d P_D READ")           // using p_d
        assertThat(fixes(30)).containsExactly("p_b P_B READ")           // open … for … using p_b
    }

    @Test
    fun `a cursor attribute reads the cursor variable before it`() {
        assertThat(fixes(37)).containsExactly("p_cursor P_CURSOR READ")  // exit when p_cursor%notfound
        assertThat(fixes(39)).containsExactly("g_cur G_CUR READ")        // if g_cur%isopen
    }
}
