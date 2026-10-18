//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** `hr/mv_company.sql`: a materialized view over a table and a view, read by `hr/proc_mview.prc`. */
class MaterializedViewTest {
    @TempDir
    lateinit var temp: Path

    private fun kinds(db: Path) = Fixture.query(db,
        "SELECT u.kind || ' ' || e.key || ' ' || e.kind FROM unit u JOIN entity e ON e.id = u.entity_id WHERE e.name = 'MV_COMPANY_LOG'")

    private fun read(db: Path) = Fixture.query(db,
        "SELECT e.key || ' ' || e.kind FROM occurrence o JOIN entity e ON e.id = o.entity_id JOIN source_file f ON f.id = o.file_id " +
            "WHERE f.path = 'hr/proc_mview.prc' AND o.roles = 'SQL_SELECT'")

    @Test
    fun `with the dictionary the unit and the entity are a MATERIALIZED_VIEW, read as one`() {
        val db = Fixture.index(temp.resolve("fixture.db"))
        assertThat(kinds(db)).containsExactly("MATERIALIZED_VIEW db:HR.MV_COMPANY_LOG MATERIALIZED_VIEW")
        assertThat(read(db)).containsExactly("db:HR.MV_COMPANY_LOG MATERIALIZED_VIEW")
        assertThat(Fixture.query(db, "SELECT v.name || ' -> ' || b.key FROM relation r JOIN entity v ON v.id = r.from_id JOIN entity b ON b.id = r.to_id " +
            "WHERE r.kind = 'VIEW_BASE' AND v.key LIKE 'db:HR.MV_COMPANY_LOG.%' ORDER BY 1")).containsExactly(
            "COMPANY_LABEL -> db:HR.V_COMPANY_LIST.COMPANY_LABEL", "COMPANY_NAME -> db:HR.COMPANY.COMPANY_NAME", "ID -> db:HR.COMPANY.ID"
        )
    }

    @Test
    fun `without a dictionary the unit and the entity are a MATERIALIZED_VIEW too`() {
        val db = Fixture.index(temp.resolve("bare.db"), dictionary = AtlasDictionary.NONE)
        assertThat(kinds(db)).containsExactly("MATERIALIZED_VIEW db:HR.MV_COMPANY_LOG MATERIALIZED_VIEW")
        assertThat(read(db)).containsExactly("db:HR.MV_COMPANY_LOG MATERIALIZED_VIEW")
    }
}
