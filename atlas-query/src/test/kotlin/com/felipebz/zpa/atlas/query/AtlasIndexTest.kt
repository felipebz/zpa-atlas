//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.writer.AtlasWriter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AtlasIndexTest {
    private val temp: Path = Files.createTempDirectory("atlas-test")

    private lateinit var db: Path
    private lateinit var index: AtlasIndex

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    @BeforeAll
    fun build() {
        db = Fixture.index(temp.resolve("fixture.db"))
        index = AtlasIndex(db, Fixture.sources)
    }

    private val runJob5 = "db:HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE,P_E IN NUMBER)"
    private val runJob4 = "db:HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE)"

    @Test
    fun `the supported schema version is the one the writer produces`() {
        assertThat(AtlasIndex.SCHEMA_VERSION).isEqualTo(AtlasWriter.SCHEMA_VERSION)
    }

    @Test
    fun `every answer carries the run it was read from`() {
        val answer = index.run()
        assertThat(answer.run.compilationSchema).isEqualTo("HR")
        assertThat(answer.run.metadataOrigin).isEqualTo("fixture")
        assertThat(answer.result.filesByParseStatus).contains(Count("RECOVERED", 2))
    }

    @Test
    fun `search by name prefix puts the exact name first and declared before external`() {
        val hits = index.searchEntities("company").result
        assertThat(hits.first().key).isEqualTo("db:HR.COMPANY")
        assertThat(hits.map { it.key }).contains("db:COMPANY@REMOTE")
        assertThat(hits.indexOfFirst { it.external }).isGreaterThan(hits.indexOfFirst { it.key == "db:HR.COMPANY" })
        assertThat(index.searchEntities("RUN_J", kind = "PROCEDURE").result.map { it.key }).contains(runJob4, runJob5)
        assertThat(index.searchEntities("company", includeExternal = false).result).noneMatch { it.external }
    }

    @Test
    fun `search with a dot matches a substring of the display`() {
        val hits = index.searchEntities("company.st_ac").result
        assertThat(hits.map { it.key }).containsExactly("db:HR.COMPANY.ST_ACTIVE")
        assertThat(hits.single().usages).isGreaterThan(0)
    }

    @Test
    fun `entity carries overloads, relations and a usage summary`() {
        val entity = index.entity(runJob5).result!!
        assertThat(entity.parent!!.key).isEqualTo("db:HR.PACK_PROCESS")
        assertThat(entity.overloads.map { it.entity.key to it.body }).containsExactly(
            runJob4 to false, "$runJob4@BODY" to true, runJob5 to false, "$runJob5@BODY" to true
        )
        assertThat(entity.overloads.single { it.self }.entity.key).isEqualTo(runJob5)
        assertThat(entity.relations).contains(RelationLink("IMPLEMENTS", "IN", EntitySummary("$runJob5@BODY", "PROCEDURE", "HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE,P_E IN NUMBER)@BODY", false)))
        assertThat(entity.children.map { it.key.substringAfterLast('.') }).containsExactly("P_A", "P_B", "P_C", "P_D", "P_E")
        assertThat(entity.definitions.map { it.path to it.roles }).containsExactly("hr/pack_process.pks" to listOf("DECLARATION"))
        // Two resolved calls in pack_calls.pkb; two ambiguous calls list it as a candidate.
        assertThat(entity.usage.byRole).containsExactly(Count("CALL", 2))
        assertThat(entity.usage.byStatus).containsExactly(Count("RESOLVED", 2))
        assertThat(entity.usage.asCandidate).isEqualTo(2)
        assertThat(index.entity("db:NO.SUCH").result).isNull()
    }

    @Test
    fun `role filter keeps occurrences with any of the roles`() {
        val page = index.occurrences("db:HR.COMPANY", roles = setOf("SQL_DELETE")).result!!
        assertThat(page.rows).isNotEmpty.allMatch { "SQL_DELETE" in it.roles }
        assertThat(page.rows.map { it.path }).contains("hr/proc_standalone.prc", "hr/pack_process.pkb")
    }

    @Test
    fun `IMPLEMENTATIONS scope on a body sees the calls resolved to the specification`() {
        val body = "db:HR.PACK_CALLS.F_PUB(P_X IN NUMBER)@BODY"
        assertThat(index.occurrences(body).result!!.rows).isEmpty()
        val rows = index.occurrences(body, scope = setOf(Scope.SELF, Scope.IMPLEMENTATIONS)).result!!.rows
        assertThat(rows.map { it.path to it.statementKind })
            .containsExactly("hr/pack_calls.pkb" to "SELECT", "hr/proc_synonym_objects.prc" to null, "scripts/calls.sql" to "SELECT")
        assertThat(rows).allMatch { it.matched.key == "db:HR.PACK_CALLS.F_PUB(P_X IN NUMBER)" && it.arguments.single().parameter != null }
    }

    @Test
    fun `CHILDREN scope on a table includes its column writes`() {
        val rows = index.occurrences("db:HR.COMPANY", roles = setOf("WRITE"), scope = setOf(Scope.CHILDREN)).result!!.rows
        assertThat(rows.map { it.matched.key }).contains("db:HR.COMPANY.ST_ACTIVE", "db:HR.COMPANY.COMPANY_NAME")
        assertThat(index.occurrences("db:HR.COMPANY", roles = setOf("WRITE")).result!!.rows).allMatch { it.matched.key == "db:HR.COMPANY" }
    }

    @Test
    fun `who writes a column lists the triggers assigning it through NEW`() {
        val rows = index.occurrences("db:HR.COMPANY.DT_CHANGED", roles = setOf("WRITE")).result!!.rows
        assertThat(rows.map { it.container?.key }).contains("db:HR.TRG_COMPANY_BU", "db:HR.TRG_COMPANY_FORMS", "db:HR.TRG_COMPANY_COMPOUND")
    }

    @Test
    fun `trigger events are listed on the trigger, its table and the updated columns`() {
        fun events(key: String) = index.entity(key).result!!.triggerEvents.map { "${it.trigger.key} ${it.timing} ${it.event} ${it.column?.key ?: "-"}" }
        assertThat(events("db:HR.TRG_COMPANY_FORMS")).containsExactly(
            "db:HR.TRG_COMPANY_FORMS BEFORE INSERT -",
            "db:HR.TRG_COMPANY_FORMS BEFORE UPDATE -",
            "db:HR.TRG_COMPANY_FORMS BEFORE UPDATE db:HR.COMPANY.COMPANY_NAME",
            "db:HR.TRG_COMPANY_FORMS BEFORE UPDATE db:HR.COMPANY.ST_ACTIVE"
        )
        assertThat(events("db:HR.COMPANY")).contains("db:HR.TRG_COMPANY_STMT AFTER DELETE -").doesNotContain("db:HR.TRG_V_COMPANY_IOI INSTEAD_OF INSERT -")
        assertThat(events("db:HR.V_COMPANY")).containsExactly("db:HR.TRG_V_COMPANY_IOI INSTEAD_OF INSERT -")
        // UPDATE OF the column, or UPDATE without a column list (also through the synonym); not UPDATE OF another column, not other events.
        assertThat(events("db:HR.COMPANY.ST_ACTIVE")).containsExactly(
            "db:HR.TRG_COMPANY_BU BEFORE UPDATE -",
            "db:HR.TRG_COMPANY_COMPOUND COMPOUND UPDATE -",
            "db:HR.TRG_COMPANY_FORMS BEFORE UPDATE db:HR.COMPANY.ST_ACTIVE",
            "db:HR.TRG_DYNAMIC BEFORE UPDATE -",
            "db:HR.TRG_SYN_COMPANY BEFORE UPDATE -"
        )
        // TRG_COMPANY_FORMS lists columns, one of them unresolved (no column): it does not fire on other columns.
        assertThat(events("db:HR.COMPANY.DT_CHANGED")).noneMatch { it.startsWith("db:HR.TRG_COMPANY_FORMS") }
    }

    @Test
    fun `a view lists what its query reads and a relation lists the views over it`() {
        fun keys(list: List<EntitySummary>) = list.map { it.key }
        // Every level of the query: the WITH subquery's table too.
        assertThat(keys(index.entity("db:HR.V_ACTIVE_COMPANY").result!!.reads)).containsExactly("db:HR.COMPANY")
        assertThat(keys(index.entity("db:HR.V_LOG_UNION").result!!.reads)).containsExactly("db:HR.EVENT_LOG", "db:HR.SEND_QUEUE")
        assertThat(keys(index.entity("db:HR.V_COMPANY_NAMES").result!!.reads)).containsExactly("db:HR.V_COMPANY_LIST")
        assertThat(keys(index.entity("db:HR.V_COMPANY_LIST").result!!.views)).containsExactly("db:HR.MV_COMPANY_LOG", "db:HR.V_COMPANY_NAMES")
        // A procedure reading the table is not a view over it.
        assertThat(keys(index.entity("db:HR.EVENT_LOG").result!!.views)).containsExactly(
            "db:HR.V_INLINE_NESTED", "db:HR.V_INLINE_UNION", "db:HR.V_LIN_JOIN", "db:HR.V_LIN_SCALAR", "db:HR.V_LIN_UNION",
            "db:HR.V_LIN_WITH_STAR", "db:HR.V_LOG_UNION", "db:HR.V_SOURCE_ONLY"
        )
        assertThat(index.entity("db:HR.COMPANY.ID").result!!.views).isEmpty()
    }

    @Test
    fun `a view column lists the columns it comes from and a column the view columns computed from it`() {
        fun keys(list: List<EntitySummary>) = list.map { it.key }
        assertThat(keys(index.entity("db:HR.V_CHAIN2.LABEL").result!!.baseColumns)).containsExactly("db:HR.V_CHAIN1.COMPANY_NAME")
        val middle = index.entity("db:HR.V_CHAIN1.COMPANY_NAME").result!!
        assertThat(keys(middle.baseColumns)).containsExactly("db:HR.COMPANY.COMPANY_NAME")
        assertThat(keys(middle.viewColumns)).containsExactly("db:HR.V_CHAIN2.LABEL")
        // The lineage has its own lists, in neither direction among the relations.
        assertThat(middle.relations).noneMatch { it.kind == "VIEW_BASE" }
        // One hop: the second level of the chain is not listed on the table column.
        assertThat(keys(index.entity("db:HR.COMPANY.COMPANY_NAME").result!!.viewColumns))
            .contains("db:HR.V_CHAIN1.COMPANY_NAME").doesNotContain("db:HR.V_CHAIN2.LABEL")
        assertThat(index.entity("db:HR.COMPANY").result!!.viewColumns).isEmpty()
    }

    @Test
    fun `VIEWS scope on a column reaches the uses of the view columns computed from it, through every level`() {
        val key = "db:HR.COMPANY.COMPANY_NAME"
        val self = index.occurrences(key, limit = 500).result!!
        val viewed = index.occurrences(key, scope = setOf(Scope.SELF, Scope.VIEWS), limit = 500).result!!
        assertThat(self.nextAfter).isNull()
        assertThat(viewed.nextAfter).isNull()
        assertThat(self.rows).noneMatch { it.viaView || it.path == "hr/proc_view_chain.prc" }
        // V_CHAIN2.LABEL comes from V_CHAIN1.COMPANY_NAME, which comes from the table column.
        val chain = viewed.rows.single { it.path == "hr/proc_view_chain.prc" && it.span.startLine == 4 }
        assertThat(chain.written).isEqualTo("label")
        assertThat(chain.viaView).isTrue()
        assertThat(chain.matched.key).isEqualTo("db:HR.V_CHAIN2.LABEL")
        // Rows hit directly are the same, and not marked.
        assertThat(viewed.rows.filter { !it.viaView }).isEqualTo(self.rows)
        assertThat(viewed.total).isGreaterThan(self.total)
    }

    @Test
    fun `VIEWS scope on a table adds the views reading it and their columns from it, not their other columns`() {
        val page = index.occurrences("db:HR.COMPANY", scope = setOf(Scope.CHILDREN, Scope.VIEWS), limit = 500).result!!
        assertThat(page.nextAfter).isNull()
        val rows = page.rows.filter { it.path == "hr/proc_view_chain.prc" }
        // V_LIN_JOIN.MSG (5:9) comes from EVENT_LOG.MSG: CHILDREN does not apply to the views VIEWS adds.
        assertThat(rows.map { "${it.span.startLine}:${it.span.startCol} ${it.matched.key} ${it.viaView}" }).containsExactly(
            "4:9 db:HR.V_CHAIN2.LABEL true",
            "4:27 db:HR.V_CHAIN2 true",
            "4:42 db:HR.V_CHAIN2.ID true",
            "5:25 db:HR.V_LIN_JOIN true",
            "5:42 db:HR.V_LIN_JOIN.ID true",
            "6:9 db:HR.V_LIN_JOIN.COMPANY_NAME true",
            "6:34 db:HR.V_LIN_JOIN true"
        )
        assertThat(page.rows).anyMatch { it.matched.key == "db:HR.COMPANY" && !it.viaView }
        assertThat(index.occurrences("db:HR.COMPANY", scope = setOf(Scope.CHILDREN), limit = 500).result!!.rows).noneMatch { it.viaView }
    }

    @Test
    fun `a materialized view counts as a view reading its base relations, and its readers are reached through it`() {
        assertThat(index.entity("db:HR.COMPANY").result!!.views.map { it.key }).contains("db:HR.MV_COMPANY_LOG")
        assertThat(index.entity("db:HR.MV_COMPANY_LOG").result!!.reads.map { it.key }).containsExactly("db:HR.COMPANY", "db:HR.V_COMPANY_LIST")
        val rows = index.occurrences("db:HR.COMPANY", scope = setOf(Scope.VIEWS), limit = 500).result!!.rows.filter { it.path == "hr/proc_mview.prc" }
        assertThat(rows.map { "${it.span.startLine}:${it.span.startCol} ${it.matched.key} ${it.viaView}" })
            .containsExactly("3:40 db:HR.MV_COMPANY_LOG true")
    }

    @Test
    fun `candidates of the four-parameter overload include the ambiguous calls`() {
        val page = index.occurrences(runJob4).result!!
        val ambiguous = page.rows.filter { it.viaCandidate }
        assertThat(ambiguous.map { it.path to it.span.startLine }).containsExactly("hr/pack_calls.pkb" to 33, "hr/pack_calls.pkb" to 34)
        assertThat(ambiguous).allMatch { it.status == "AMBIGUOUS" && it.entity == null && it.candidates.map { c -> c.key } == listOf(runJob4, runJob5) }
        assertThat(index.occurrences(runJob4, includeCandidates = false).result!!.rows).noneMatch { it.viaCandidate }
    }

    @Test
    fun `paging visits every row once, in source order`() {
        val all = index.occurrences("db:HR.COMPANY", scope = setOf(Scope.CHILDREN), limit = 500).result!!
        val seen = ArrayList<OccurrenceRow>()
        var after: Long? = null
        do {
            val page = index.occurrences("db:HR.COMPANY", scope = setOf(Scope.CHILDREN), after = after, limit = 2).result!!
            assertThat(page.rows.size).isLessThanOrEqualTo(2)
            seen += page.rows
            after = page.nextAfter
        } while (after != null)
        assertThat(seen).isEqualTo(all.rows)
        assertThat(seen.map { it.id }).doesNotHaveDuplicates().isSorted
        // Ids follow file path, then position.
        assertThat(seen.map { it.path to it.span.startLine * 10_000 + it.span.startCol }).isSortedAccordingTo(compareBy({ it.first }, { it.second }))
        assertThat(all.total).isEqualTo(seen.size.toLong())
    }

    @Test
    fun `facets count roles under the status filter and statuses under the role filter`() {
        val page = index.occurrences("db:HR.COMPANY", roles = setOf("SQL_UPDATE"), statuses = setOf("PARTIAL_SOURCE")).result!!
        assertThat(page.rows).allMatch { it.status == "PARTIAL_SOURCE" && "SQL_UPDATE" in it.roles }
        // Roles of the PARTIAL_SOURCE occurrences only; statuses of the SQL_UPDATE occurrences only.
        assertThat(page.facets.byRole).containsExactly(Count("SQL_UPDATE", 2))
        assertThat(page.facets.byStatus.map { it.value }).containsExactly("PARTIAL_SOURCE", "RESOLVED")
        assertThat(page.total).isEqualTo(2)
    }

    @Test
    fun `bad filters are rejected`() {
        assertThatThrownBy { index.occurrences("db:HR.COMPANY", roles = setOf("WRITES")) }.isInstanceOf(InvalidQueryException::class.java)
        assertThatThrownBy { index.occurrences("db:HR.COMPANY", statuses = setOf("OK")) }.isInstanceOf(InvalidQueryException::class.java)
        assertThatThrownBy { index.occurrences("db:HR.COMPANY", limit = 501) }.isInstanceOf(InvalidQueryException::class.java)
        assertThatThrownBy { index.searchEntities("x", limit = 0) }.isInstanceOf(InvalidQueryException::class.java)
        assertThat(index.occurrences("db:NO.SUCH").result).isNull()
    }

    @Test
    fun `source returns the lines around a position and detects stale files`() {
        val snippet = index.source("hr/proc_standalone.prc", 3, 1).result!!
        assertThat(snippet.lines.map { it.number to it.text }).containsExactly(2 to "begin", 3 to "  delete company where id = p_id;", 4 to "end;")
        assertThat(snippet.stale).isFalse()

        val copy = temp.resolve("sources")
        Fixture.sources.toFile().copyRecursively(copy.toFile())
        Files.writeString(copy.resolve("hr/proc_standalone.prc"), "changed\n")
        val edited = AtlasIndex(db, copy).source("hr/proc_standalone.prc", 1, 0).result!!
        assertThat(edited.stale).isTrue()
        assertThat(edited.lines.single().text).isEqualTo("changed")

        assertThat(AtlasIndex(db, null).source("hr/proc_standalone.prc", 1).result).isNull()
        assertThat(AtlasIndex(db, temp.resolve("missing")).source("hr/proc_standalone.prc", 1).result).isNull()
        assertThatThrownBy { index.source("../hr/proc_standalone.prc", 1) }.isInstanceOf(InvalidQueryException::class.java)
        assertThatThrownBy { index.source("hr/nao_indexado.sql", 1) }.isInstanceOf(InvalidQueryException::class.java)
    }

    private fun FileView.at(line: Int, col: Int) =
        occurrences.filter { it.at[0] == line && it.at[1] == col }.map { "${it.at.joinToString(",")} ${it.entity?.let { e -> entities[e].key }} ${it.roles}" }

    @Test
    fun `file gives the name span of a definition, its parameters and a view`() {
        val calls = index.file("hr/pack_calls.pkb").result!!
        assertThat(calls.stale).isFalse()
        assertThat(calls.lines[9]).isEqualTo("  procedure p_mix(p_a number, p_b varchar2, p_c date) is begin null; end;")
        val pMix = "db:HR.PACK_CALLS.P_MIX(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN DATE)@BODY"
        assertThat(calls.at(10, 12)).containsExactly("10,12,10,17 $pMix [DEFINITION]")
        assertThat(calls.at(10, 18)).containsExactly("10,18,10,21 $pMix.P_A [DEFINITION]")
        assertThat(index.file("hr/views_remote.sql").result!!.at(1, 23)).containsExactly("1,23,1,36 db:HR.V_REMOTE_SAME [DEFINITION]")
    }

    @Test
    fun `file gives the names inside the literal of a dynamic statement at their place`() {
        val dynamic = index.file("hr/pack_dynamic.pkb").result!!
        assertThat(dynamic.at(10, 35)).containsExactly("10,35,10,42 db:HR.COMPANY [SQL_DELETE]")
        // The second line of a literal across two lines.
        assertThat(dynamic.at(27, 10)).containsExactly("27,10,27,22 db:HR.COMPANY.COMPANY_NAME [SQL_UPDATE, WRITE]")
        assertThat(dynamic.lines[26].substring(10, 22)).isEqualTo("company_name")
    }

    @Test
    fun `file keeps a qualified read over the column definition inside it`() {
        val mview = index.file("hr/mv_company.sql").result!!
        assertThat(mview.at(2, 9)).containsExactly("2,9,2,13 db:HR.COMPANY.ID [READ]")
        assertThat(mview.at(2, 11)).containsExactly("2,11,2,13 db:HR.MV_COMPANY_LOG.ID [DEFINITION]")
    }

    @Test
    fun `file gives the candidates of an ambiguous call as entity indexes`() {
        val calls = index.file("hr/pack_calls.pkb").result!!
        val ambiguous = calls.occurrences.single { it.at[0] == 33 && it.status == "AMBIGUOUS" }
        assertThat(ambiguous.at).containsExactly(33, 4, 33, 24)
        assertThat(ambiguous.entity).isNull()
        assertThat(ambiguous.candidates.map { calls.entities[it].key }).containsExactly(runJob4, runJob5)
        // Every entity is listed once.
        assertThat(calls.entities.map { it.key }).doesNotHaveDuplicates()
    }

    @Test
    fun `file of a changed file has its lines and no occurrences, and other paths are rejected`() {
        val copy = temp.resolve("file-sources")
        Fixture.sources.toFile().copyRecursively(copy.toFile())
        Files.writeString(copy.resolve("hr/pack_calls.pkb"), "changed\n")
        val edited = AtlasIndex(db, copy).file("hr/pack_calls.pkb").result!!
        assertThat(edited.stale).isTrue()
        assertThat(edited.lines.first()).isEqualTo("changed")
        assertThat(edited.occurrences).isEmpty()
        assertThat(edited.entities).isEmpty()

        assertThat(AtlasIndex(db, null).file("hr/pack_calls.pkb").result).isNull()
        assertThatThrownBy { index.file("../hr/pack_calls.pkb") }.isInstanceOf(InvalidQueryException::class.java)
        assertThatThrownBy { index.file("hr/nao_indexado.sql") }.isInstanceOf(InvalidQueryException::class.java)
    }

    @Test
    fun `a file with another schema version is refused`() {
        val other = temp.resolve("other.db")
        Files.copy(db, other)
        DriverManager.getConnection("jdbc:sqlite:$other").use { it.createStatement().execute("UPDATE run SET schema_version = 2") }
        assertThatThrownBy { AtlasIndex(other, null).run() }
            .isInstanceOf(SchemaMismatchException::class.java)
            .hasMessageContaining("schema version 2").hasMessageContaining("version ${AtlasIndex.SCHEMA_VERSION}")
    }

    @Test
    fun `the name prefix search and the candidate lookup use their indexes`() {
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            fun plan(sql: String) = connection.createStatement().executeQuery("EXPLAIN QUERY PLAN $sql").use { rs ->
                buildList { while (rs.next()) add(rs.getString("detail")) }
            }
            assertThat(plan("SELECT id FROM entity e WHERE e.name LIKE 'exec%' ESCAPE '\\'"))
                .anyMatch { it.contains("entity_name_nocase") }
            assertThat(plan("SELECT occurrence_id FROM occurrence_candidate WHERE entity_id = 1"))
                .anyMatch { it.contains("occurrence_candidate_entity") }
        }
    }
}
