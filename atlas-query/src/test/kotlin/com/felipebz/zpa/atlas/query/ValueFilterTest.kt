//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/** A value filter beside a container, a container name, a path or a name keeps the occurrences both filters keep. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValueFilterTest {
    private val temp: Path = Files.createTempDirectory("atlas-value-filter-test")
    private lateinit var index: AtlasIndex

    @BeforeAll
    fun build() {
        index = AtlasIndex(Fixture.index(temp.resolve("fixture.db")), Fixture.sources)
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    /** Every occurrence [query] keeps, page after page. */
    private fun ids(query: OccurrenceQuery): List<Long> {
        val ids = ArrayList<Long>()
        var after: Long? = null
        do {
            val page = index.queryOccurrences(query, after, 500, summary = false).result
            ids += page.rows.map { it.id }
            after = page.nextAfter
        } while (after != null)
        return ids
    }

    @Test
    fun `a value pattern beside a container, a container name or a path keeps the occurrences both keep`() {
        val narrow = listOf(
            OccurrenceQuery(container = "db:HR.PACK_PROCESS"),
            OccurrenceQuery(container = "db:HR.PACK_PROCESS", containerInside = false),
            OccurrenceQuery(containerName = "pack_process"),
            OccurrenceQuery(path = "hr/pack_process.pkb"),
            OccurrenceQuery(path = "hr/"),
            OccurrenceQuery(name = "run_job")
        )
        // `*` at the start, in the middle, at the end, and exact; with and without a kind; a name upper-cased.
        val values = listOf(null to "*dual", null to "select*", null to "*1 from*", null to "select 1 from dual", "STRING" to "*dual",
            "STRING" to "N", null to "N", "NAME" to "*id*", null to "*")
        var matched = 0
        for (filter in narrow) for ((kind, value) in values) {
            val alone = ids(OccurrenceQuery(valueKind = kind, value = value)).toSet()
            val both = filter.copy(valueKind = kind, value = value)
            val expected = ids(filter).filter { it in alone }
            val page = index.queryOccurrences(both, limit = 500).result
            assertThat(page.rows.map { it.id }).describedAs("$filter $kind $value").isEqualTo(expected)
            assertThat(page.total).describedAs("$filter $kind $value").isEqualTo(expected.size.toLong())
            val grouped = index.groupOccurrences(both, listOf(GroupDimension.STATUS), 100).result.groups.sumOf { it.count }
            assertThat(grouped).describedAs("$filter $kind $value").isEqualTo(expected.size.toLong())
            matched += expected.size
        }
        // The cases are not all empty.
        assertThat(matched).isGreaterThan(20)
    }
}
