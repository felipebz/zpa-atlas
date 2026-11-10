//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Values passed to parameters and written to columns, and the sites of each value. */
class ValuesTest {
    private val db: Path = SharedFixtureIndex.db
    private val index = AtlasIndex(db, Fixture.sources)

    private fun throughViews(key: String) = index.values(key).result!!.filter { it.value in setOf("10", "11", "12") || it.value?.startsWith("via ") == true }

    @Test
    fun `the values passed to a parameter are grouped by kind and value, and a body parameter shows its spec's`() {
        val groups = index.values(pCode).result!!
        assertThat(groups.first()).isEqualTo(ValueGroup("NUMBER", "1", 10))
        assertThat(groups).contains(ValueGroup("NUMBER", "1.5", 2), ValueGroup("NAME", "R.CODE", 1), ValueGroup("BIND", null, 1), ValueGroup("EXPRESSION", null, 2))
        assertThat(index.values(pCode.replace(").P_CODE", ")@BODY.P_CODE")).result).isEqualTo(groups)
        assertThat(index.values("db:NO.SUCH").result).isNull()
    }

    @Test
    fun `the calls of one value are call occurrences with their argument values`() {
        val page = index.valueSites(pCode, "NUMBER", "1.5").result!!
        assertThat(page.rows.map { it.span!!.startLine }).containsExactly(14, 15)
        assertThat(page.rows.first().arguments.single().let { it.valueKind to it.value }).isEqualTo("NUMBER" to "1.5")
        assertThat(index.valueSites(pCode, "EXPRESSION", null).result!!.rows.map { it.span!!.startLine }).containsExactly(28, 30)
        assertThatThrownBy { index.valueSites(pCode, "NOPE", null) }.isInstanceOf(InvalidQueryException::class.java)
    }

    @Test
    fun `the values written to a column are grouped like the values passed, and open their write sites`() {
        val groups = index.values("db:HR.COMPANY.ST_ACTIVE").result!!
        assertThat(groups.take(5)).containsExactly(ValueGroup("STRING", "S", 9), ValueGroup("STRING", "N", 7), ValueGroup("NULL", null, 3), ValueGroup("STRING", "X", 3), ValueGroup("DEFAULT", null, 1))
        val sites = index.valueSites("db:HR.COMPANY.ST_ACTIVE", "STRING", "S").result!!
        assertThat(sites.rows.filter { it.path == "hr/proc_writes.prc" }.map { it.span!!.startLine }).containsExactly(7, 9, 10, 16)
        assertThat(sites.rows.first { it.path == "hr/proc_writes.prc" }.values.single().let { it.valueKind to it.value }).isEqualTo("STRING" to "S")
        assertThat(index.values("db:HR.COMPANY").result).isEmpty()
        // A row trigger's `:new.st_active := 'S'` is a write of the column too.
        assertThat(sites.rows.filter { it.path == "hr/trg_company_values.trg" }.map { it.span!!.startLine }).containsExactly(7)
    }

    @Test
    fun `values written through views reach the base column, stopped by a trigger or a materialized view`() {
        assertThat(throughViews("db:HR.COMPANY.ID")).containsExactly(ValueGroup("NUMBER", "10", 1))
        assertThat(throughViews("db:HR.COMPANY.COMPANY_NAME")).containsExactlyInAnyOrder(
            ValueGroup("STRING", "via list", 1), ValueGroup("STRING", "via names", 1), ValueGroup("STRING", "via calc", 1))
        assertThat(throughViews("db:HR.V_COMPANY_LIST.COMPANY_LABEL")).containsExactlyInAnyOrder(ValueGroup("STRING", "via list", 1), ValueGroup("STRING", "via names", 1))
        // A site through a view is the occurrence on the view column.
        val sites = index.valueSites("db:HR.COMPANY.COMPANY_NAME", "STRING", "via names").result!!
        assertThat(sites.rows.map { "${it.path}:${it.span!!.startLine} ${it.entity?.key}" })
            .containsExactly("hr/proc_view_writes.prc:4 db:HR.V_COMPANY_NAMES.COMPANY_LABEL")
        assertThat(index.valueSites("db:HR.COMPANY.ID", "NUMBER", "11").result!!.rows).isEmpty()
        assertThat(index.valueSites("db:HR.COMPANY.ID", "NUMBER", "12").result!!.rows).isEmpty()
    }
}
