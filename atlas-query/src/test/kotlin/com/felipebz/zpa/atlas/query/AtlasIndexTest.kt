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
        assertThat(rows.map { it.path to it.statementKind }).containsExactly("hr/pack_calls.pkb" to "SELECT", "scripts/calls.sql" to "SELECT")
        assertThat(rows).allMatch { it.matched.key == "db:HR.PACK_CALLS.F_PUB(P_X IN NUMBER)" && it.arguments.single().parameter != null }
    }

    @Test
    fun `CHILDREN scope on a table includes its column writes`() {
        val rows = index.occurrences("db:HR.COMPANY", roles = setOf("WRITE"), scope = setOf(Scope.CHILDREN)).result!!.rows
        assertThat(rows.map { it.matched.key }).contains("db:HR.COMPANY.ST_ACTIVE", "db:HR.COMPANY.COMPANY_NAME")
        assertThat(index.occurrences("db:HR.COMPANY", roles = setOf("WRITE")).result!!.rows).allMatch { it.matched.key == "db:HR.COMPANY" }
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
