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

/** An entity's occurrences: scopes, filters, candidates, facets and paging. */
class OccurrencesTest {
    private val db: Path = SharedFixtureIndex.db
    private val index = AtlasIndex(db, Fixture.sources)

    @Test
    fun `a spec parameter's uses are those of the same parameter of its body, the page's default for a parameter`() {
        // hr/param_uses.sql: zpa binds the uses in the body to the body's declaration.
        val spec = "db:HR.PK_P.RUN(P_ROWS IN T_ROWS,P_REC IN OUT T_ROW).P_REC"
        val body = "db:HR.PK_P.RUN(P_ROWS IN T_ROWS,P_REC IN OUT T_ROW)@BODY.P_REC"
        fun rows(key: String, scope: Set<Scope>) = index.occurrences(key, UsageFilter(scope = scope)).result!!.rows.map { "${it.span!!.startLine} ${it.entity?.key}" }
        assertThat(defaultUsageScopes("PARAMETER")).containsExactly(Scope.IMPLEMENTATIONS)
        assertThat(rows(spec, setOf(Scope.SELF))).isEmpty()
        val bodyUses = listOf("23 $body", "26 $body")
        assertThat(rows(spec, setOf(Scope.SELF, Scope.IMPLEMENTATIONS))).isEqualTo(bodyUses)
        assertThat(rows(body, setOf(Scope.SELF, Scope.IMPLEMENTATIONS))).isEqualTo(bodyUses)
        // A parameter of another routine with the same name is not a partner.
        assertThat(rows("db:HR.PK_P.FILL(P_OUT OUT NUMBER).P_OUT", setOf(Scope.SELF, Scope.IMPLEMENTATIONS))).containsExactly(
            "18 db:HR.PK_P.FILL(P_OUT OUT NUMBER)@BODY.P_OUT"
        )
    }

    @Test
    fun `role filter keeps occurrences with any of the roles`() {
        val page = index.occurrences("db:HR.COMPANY", UsageFilter(roles = setOf("SQL_DELETE"))).result!!
        assertThat(page.rows).isNotEmpty.allMatch { "SQL_DELETE" in it.roles }
        assertThat(page.rows.map { it.path }).contains("hr/proc_standalone.prc", "hr/pack_process.pkb")
    }

    @Test
    fun `IMPLEMENTATIONS scope on a body sees the calls resolved to the specification`() {
        val body = "db:HR.PACK_CALLS.F_PUB(P_X IN NUMBER)@BODY"
        assertThat(index.occurrences(body).result!!.rows).isEmpty()
        val rows = index.occurrences(body, UsageFilter(scope = setOf(Scope.SELF, Scope.IMPLEMENTATIONS))).result!!.rows
        assertThat(rows.map { it.path to it.statementKind })
            .containsExactly("hr/pack_calls.pkb" to "SELECT", "hr/proc_synonym_objects.prc" to null, "scripts/calls.sql" to "SELECT")
        assertThat(rows).allMatch { it.matched!!.key == "db:HR.PACK_CALLS.F_PUB(P_X IN NUMBER)" && it.arguments.single().parameter != null }
    }

    @Test
    fun `CHILDREN scope on a table includes its column writes`() {
        val rows = index.occurrences("db:HR.COMPANY", UsageFilter(roles = setOf("WRITE"), scope = setOf(Scope.CHILDREN))).result!!.rows
        assertThat(rows.map { it.matched!!.key }).contains("db:HR.COMPANY.ST_ACTIVE", "db:HR.COMPANY.COMPANY_NAME")
        assertThat(index.occurrences("db:HR.COMPANY", UsageFilter(roles = setOf("WRITE"))).result!!.rows).allMatch { it.matched!!.key == "db:HR.COMPANY" }
    }

    @Test
    fun `who writes a column lists the triggers assigning it through NEW`() {
        val rows = index.occurrences("db:HR.COMPANY.DT_CHANGED", UsageFilter(roles = setOf("WRITE"))).result!!.rows
        assertThat(rows.map { it.container?.key }).contains("db:HR.TRG_COMPANY_BU", "db:HR.TRG_COMPANY_FORMS", "db:HR.TRG_COMPANY_COMPOUND")
    }

    @Test
    fun `VIEWS scope on a column reaches the uses of the view columns computed from it, through every level`() {
        val key = "db:HR.COMPANY.COMPANY_NAME"
        val self = index.occurrences(key, limit = 500).result!!
        val viewed = index.occurrences(key, UsageFilter(scope = setOf(Scope.SELF, Scope.VIEWS)), limit = 500).result!!
        assertThat(self.nextAfter).isNull()
        assertThat(viewed.nextAfter).isNull()
        assertThat(self.rows).noneMatch { it.viaView || it.path == "hr/proc_view_chain.prc" }
        // V_CHAIN2.LABEL comes from V_CHAIN1.COMPANY_NAME, which comes from the table column.
        val chain = viewed.rows.single { it.path == "hr/proc_view_chain.prc" && it.span!!.startLine == 4 }
        assertThat(chain.written).isEqualTo("label")
        assertThat(chain.viaView).isTrue()
        assertThat(chain.matched!!.key).isEqualTo("db:HR.V_CHAIN2.LABEL")
        // Rows hit directly are the same, and not marked.
        assertThat(viewed.rows.filter { !it.viaView }).isEqualTo(self.rows)
        assertThat(viewed.total).isGreaterThan(self.total)
    }

    @Test
    fun `VIEWS scope on a table adds the views reading it and their columns from it, not their other columns`() {
        val page = index.occurrences("db:HR.COMPANY", UsageFilter(scope = setOf(Scope.CHILDREN, Scope.VIEWS)), limit = 500).result!!
        assertThat(page.nextAfter).isNull()
        val rows = page.rows.filter { it.path == "hr/proc_view_chain.prc" }
        // V_LIN_JOIN.MSG (5:9) comes from EVENT_LOG.MSG: CHILDREN does not apply to the views VIEWS adds.
        assertThat(rows.map { "${it.span!!.startLine}:${it.span!!.startCol} ${it.matched!!.key} ${it.viaView}" }).containsExactly(
            "4:9 db:HR.V_CHAIN2.LABEL true",
            "4:27 db:HR.V_CHAIN2 true",
            "4:42 db:HR.V_CHAIN2.ID true",
            "5:25 db:HR.V_LIN_JOIN true",
            "5:42 db:HR.V_LIN_JOIN.ID true",
            "6:9 db:HR.V_LIN_JOIN.COMPANY_NAME true",
            "6:34 db:HR.V_LIN_JOIN true"
        )
        assertThat(page.rows).anyMatch { it.matched!!.key == "db:HR.COMPANY" && !it.viaView }
        assertThat(index.occurrences("db:HR.COMPANY", UsageFilter(scope = setOf(Scope.CHILDREN)), limit = 500).result!!.rows).noneMatch { it.viaView }
    }

    @Test
    fun `a materialized view counts as a view reading its base relations, and its readers are reached through it`() {
        assertThat(index.entity("db:HR.COMPANY").result!!.views.map { it.key }).contains("db:HR.MV_COMPANY_LOG")
        assertThat(index.entity("db:HR.MV_COMPANY_LOG").result!!.reads.map { it.key }).containsExactly("db:HR.COMPANY", "db:HR.V_COMPANY_LIST")
        val rows = index.occurrences("db:HR.COMPANY", UsageFilter(scope = setOf(Scope.VIEWS)), limit = 500).result!!.rows.filter { it.path == "hr/proc_mview.prc" }
        assertThat(rows.map { "${it.span!!.startLine}:${it.span!!.startCol} ${it.matched!!.key} ${it.viaView}" })
            .containsExactly("3:40 db:HR.MV_COMPANY_LOG true")
    }

    @Test
    fun `candidates of the four-parameter overload include the ambiguous calls`() {
        val page = index.occurrences(runJob4).result!!
        val ambiguous = page.rows.filter { it.viaCandidate }
        assertThat(ambiguous.map { it.path to it.span!!.startLine }).containsExactly("hr/pack_calls.pkb" to 33, "hr/pack_calls.pkb" to 34)
        assertThat(ambiguous).allMatch { it.status == "AMBIGUOUS" && it.entity == null && it.candidates.map { c -> c.key } == listOf(runJob4, runJob5) }
        assertThat(index.occurrences(runJob4, UsageFilter(includeCandidates = false)).result!!.rows).noneMatch { it.viaCandidate }
    }

    @Test
    fun `paging visits every row once, in source order`() {
        val all = index.occurrences("db:HR.COMPANY", UsageFilter(scope = setOf(Scope.CHILDREN)), limit = 500).result!!
        val seen = ArrayList<OccurrenceRow>()
        var after: Long? = null
        do {
            val page = index.occurrences("db:HR.COMPANY", UsageFilter(scope = setOf(Scope.CHILDREN)), after = after, limit = 2).result!!
            assertThat(page.rows.size).isLessThanOrEqualTo(2)
            seen += page.rows
            after = page.nextAfter
        } while (after != null)
        assertThat(seen).isEqualTo(all.rows)
        assertThat(seen.map { it.id }).doesNotHaveDuplicates().isSorted
        // Ids follow file path, then position.
        assertThat(seen.map { it.path to it.span!!.startLine * 10_000 + it.span!!.startCol }).isSortedAccordingTo(compareBy({ it.first }, { it.second }))
        assertThat(all.total).isEqualTo(seen.size.toLong())
    }

    @Test
    fun `facets count roles under the status filter and statuses under the role filter`() {
        val page = index.occurrences("db:HR.COMPANY", UsageFilter(roles = setOf("SQL_UPDATE"), statuses = setOf("PARTIAL_SOURCE"))).result!!
        assertThat(page.rows).allMatch { it.status == "PARTIAL_SOURCE" && "SQL_UPDATE" in it.roles }
        // Roles of the PARTIAL_SOURCE occurrences only; statuses of the SQL_UPDATE occurrences only.
        assertThat(page.facets.byRole).containsExactly(Count("SQL_UPDATE", 2))
        assertThat(page.facets.byStatus.map { it.value }).containsExactly("PARTIAL_SOURCE", "RESOLVED")
        assertThat(page.total).isEqualTo(2)
    }

    @Test
    fun `bad filters are rejected`() {
        assertThatThrownBy { index.occurrences("db:HR.COMPANY", UsageFilter(roles = setOf("WRITES"))) }.isInstanceOf(InvalidQueryException::class.java)
        assertThatThrownBy { index.occurrences("db:HR.COMPANY", UsageFilter(statuses = setOf("OK"))) }.isInstanceOf(InvalidQueryException::class.java)
        assertThatThrownBy { index.occurrences("db:HR.COMPANY", limit = 501) }.isInstanceOf(InvalidQueryException::class.java)
        assertThatThrownBy { index.searchEntities("x", limit = 0) }.isInstanceOf(InvalidQueryException::class.java)
        assertThat(index.occurrences("db:NO.SUCH").result).isNull()
    }

    @Test
    fun `a function called through a database link lists its call sites with their argument values`() {
        val page = index.occurrences("db:NEXT_REQ@FAR").result!!
        assertThat(page.rows.filter { it.path == "hr/remote_calls.prc" }.map { "${it.span!!.startLine} ${it.status} ${it.reason} ${it.arguments.map { a -> a.valueKind + " " + a.value }}" })
            .containsExactly("7 EXTERNAL DB_LINK [NUMBER 4]", "8 EXTERNAL DB_LINK [NUMBER 5]")
        val pack = index.entity("db:~.PACK_REQ@FAR.EXAMPLE.COM").result!!
        assertThat(pack.children.map { it.entity.key }).containsExactlyInAnyOrder("db:~.PACK_REQ@FAR.EXAMPLE.COM.CLOSE_REQ", "db:~.PACK_REQ@FAR.EXAMPLE.COM.GET_MAX")
    }
}
