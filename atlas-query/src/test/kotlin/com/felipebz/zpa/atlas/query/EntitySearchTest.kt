//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Searching entities by name, and the names hits and summaries carry. */
class EntitySearchTest {
    private val db: Path = SharedFixtureIndex.db
    private val index = AtlasIndex(db, Fixture.sources)

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
    fun `search with a dot matches the parent chain, then a substring of the display`() {
        val hits = index.searchEntities("company.st_ac").result
        assertThat(hits.map { it.key }).containsExactly("db:HR.COMPANY.ST_ACTIVE")
        assertThat(hits.single().usages).isGreaterThan(0)
        // The subprogram's spec and body, not its parameters.
        assertThat(index.searchEntities("pack_process.run_job").result.map { it.key })
            .containsExactlyInAnyOrder(runJob4, "$runJob4@BODY", runJob5, "$runJob5@BODY")
        // The leftmost segment may be the schema of a schema-level entity.
        assertThat(index.searchEntities("hr.company").result.first().key).isEqualTo("db:HR.COMPANY")
        // No name chain matches: the display substring still finds remote names.
        assertThat(index.searchEntities("req@far.example").result).isNotEmpty
    }

    @Test
    fun `summaries and search hits carry the entity's own name`() {
        val entity = index.entity(runJob5).result!!
        assertThat(entity.children.map { it.entity.name }).containsExactly("P_A", "P_B", "P_C", "P_D", "P_E")
        assertThat(entity.relations.single { it.kind == "IMPLEMENTS" }.entity.let { it.key to it.name }).isEqualTo("$runJob5@BODY" to "RUN_JOB")
        assertThat(index.searchEntities("notify_req").result.map { it.display to it.name }).contains("NOTIFY_REQ@FAR" to "NOTIFY_REQ@FAR")
        assertThat(index.entity("db:~.PACK_REQ@FAR.EXAMPLE.COM").result!!.children.map { it.entity.name }).contains("GET_MAX")
        val pack = index.entity("db:HR.PACK_PROCESS").result!!
        assertThat(pack.children.first { it.entity.key == runJob5 }.usages)
            .isEqualTo(index.occurrences(runJob5, UsageFilter(includeCandidates = false)).result!!.total)
    }
}
