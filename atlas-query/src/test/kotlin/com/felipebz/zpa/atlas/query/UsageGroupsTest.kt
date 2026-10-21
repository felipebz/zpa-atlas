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

/** An entity's usages grouped, and kept by the way they were reached, checked against the rows "who uses" lists. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsageGroupsTest {
    private val temp: Path = Files.createTempDirectory("atlas-usage-groups-test")
    private lateinit var index: AtlasIndex

    @BeforeAll
    fun build() {
        index = AtlasIndex(Fixture.index(temp.resolve("fixture.db")), Fixture.sources)
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private val company = "db:HR.COMPANY"
    private val companyList = "db:HR.V_COMPANY_LIST"
    private val packProcess = "db:HR.PACK_PROCESS"
    private val packageScope = setOf(Scope.SELF, Scope.IMPLEMENTATIONS, Scope.CHILDREN)

    /** Every row [AtlasIndex.occurrences] lists, read at once. */
    private fun rows(key: String, scope: Set<Scope>, via: UsagePath? = null) =
        index.occurrences(key, scope = scope, via = via, limit = 500).result!!.also { assertThat(it.nextAfter).isNull() }.rows

    /** [rows] counted by ENTITY and CONTAINER in the test, as group keys. */
    private fun byEntityAndContainer(rows: List<OccurrenceRow>) =
        rows.groupingBy { listOf(it.matched?.key, it.container?.key) }.eachCount().mapValues { it.value.toLong() }

    private fun groups(page: GroupPage) = page.groups.associate { group -> group.values.map { it.entity?.key ?: it.text } to group.count }

    @Test
    fun `the groups count the rows occurrences lists, by entity and container`() {
        val wide = setOf(Scope.SELF, Scope.CHILDREN, Scope.VIEWS)
        val dimensions = listOf(GroupDimension.ENTITY, GroupDimension.CONTAINER)
        val list = index.groupUsages(companyList, dimensions, scope = wide, limit = 1000).result!!
        assertThat(groups(list)).isEqualTo(byEntityAndContainer(rows(companyList, wide)))
        assertThat(list.totalGroups).isEqualTo(list.groups.size.toLong())

        val views = setOf(Scope.SELF, Scope.VIEWS)
        val throughViews = index.groupUsages(company, dimensions, scope = views, via = UsagePath.THROUGH_VIEWS, limit = 1000).result!!
        val viewRows = rows(company, views, UsagePath.THROUGH_VIEWS)
        assertThat(viewRows).isNotEmpty().allMatch { it.viaView }
        assertThat(groups(throughViews)).isEqualTo(byEntityAndContainer(viewRows))
        // ENTITY names the view or view column a row came through, never the table.
        assertThat(throughViews.groups.map { it.values[0].entity!!.kind }).doesNotContain("TABLE")
    }

    @Test
    fun `ROLE counts a row under each of its usage roles`() {
        val byRole = index.groupUsages(packProcess, listOf(GroupDimension.ROLE), scope = packageScope, limit = 1000).result!!
        val expected = rows(packProcess, packageScope).flatMap { row -> row.roles.filter { it != "DEFINITION" && it != "DECLARATION" } }
            .groupingBy { it }.eachCount().mapValues { it.value.toLong() }
        assertThat(groups(byRole)).isEqualTo(expected.mapKeys { listOf(it.key) })
        val calls = index.groupUsages(packProcess, listOf(GroupDimension.ROLE), roles = setOf("CALL"), scope = packageScope).result!!
        assertThat(groups(calls).keys).containsExactly(listOf("CALL"))
    }

    @Test
    fun `the direct rows and the rows through views make up the whole`() {
        for ((key, scope) in listOf(
            companyList to setOf(Scope.SELF, Scope.CHILDREN, Scope.VIEWS),
            company to setOf(Scope.SELF, Scope.VIEWS),
            packProcess to packageScope
        )) {
            fun total(via: UsagePath?) = index.occurrences(key, scope = scope, via = via, limit = 1).result!!.total
            assertThat(total(UsagePath.DIRECT) + total(UsagePath.THROUGH_VIEWS)).describedAs(key).isEqualTo(total(null))
            assertThat(rows(key, scope, UsagePath.DIRECT)).describedAs(key).noneMatch { it.viaView }
            val statuses = index.occurrences(key, scope = scope, via = UsagePath.DIRECT, limit = 1).result!!.facets.byStatus.sumOf { it.count }
            assertThat(statuses).describedAs(key).isEqualTo(total(UsagePath.DIRECT))
        }
        assertThat(index.occurrences(company, scope = setOf(Scope.SELF, Scope.VIEWS), via = UsagePath.THROUGH_VIEWS, limit = 1).result!!.total)
            .isEqualTo(17)
    }
}
