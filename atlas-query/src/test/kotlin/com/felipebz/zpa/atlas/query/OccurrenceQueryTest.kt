//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** The occurrence query, checked against plain SQL over the same fixture index. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OccurrenceQueryTest {
    private val temp: Path = Files.createTempDirectory("atlas-query-test")
    private lateinit var db: Path
    private lateinit var index: AtlasIndex

    private val packProcess = "db:HR.PACK_PROCESS"
    private val runJob5Body = "db:HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE,P_E IN NUMBER)@BODY"

    @BeforeAll
    fun build() {
        db = Fixture.index(temp.resolve("fixture.db"))
        index = AtlasIndex(db, Fixture.sources)
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private fun sql(text: String): List<String> = Fixture.query(db, text)
    private fun count(where: String): Long = sql("SELECT count(*) FROM occurrence o WHERE $where").single().toLong()
    private fun total(query: OccurrenceQuery): Long = index.queryOccurrences(query, limit = 1).result.total
    private fun id(key: String) = sql("SELECT id FROM entity WHERE key = '$key'").single()
    private fun subtree(key: String) =
        "(WITH RECURSIVE t(id) AS (SELECT ${id(key)} UNION SELECT r.to_id FROM relation r JOIN t ON r.from_id = t.id WHERE r.kind = 'CONTAINS') SELECT id FROM t)"

    @Test
    fun `no filter matches every occurrence, definitions included`() {
        val all = count("1")
        assertThat(total(OccurrenceQuery())).isEqualTo(all)
        assertThat(index.queryOccurrences(OccurrenceQuery(), limit = 500).result.rows.map { it.roles }).anyMatch { "DEFINITION" in it }
    }

    @Test
    fun `each filter alone matches what SQL selects`() {
        val company = id("db:HR.COMPANY")
        val cases = listOf(
            OccurrenceQuery(entity = "db:HR.COMPANY", candidates = false) to "o.entity_id = $company",
            OccurrenceQuery(entity = "db:HR.COMPANY", inside = true, candidates = false) to "o.entity_id IN ${subtree("db:HR.COMPANY")}",
            OccurrenceQuery(entityKinds = setOf("COLUMN"), candidates = false) to "o.entity_id IN (SELECT id FROM entity WHERE kind = 'COLUMN')",
            OccurrenceQuery(name = "*active", candidates = false) to "o.entity_id IN (SELECT id FROM entity WHERE upper(name) LIKE '%ACTIVE')",
            OccurrenceQuery(schema = "SYS", candidates = false) to "o.entity_id IN (SELECT id FROM entity WHERE schema_name = 'SYS')",
            OccurrenceQuery(external = true, candidates = false) to "o.entity_id IN (SELECT id FROM entity WHERE external = 1)",
            OccurrenceQuery(container = packProcess) to "o.container_entity_id IN ${subtree(packProcess)}",
            OccurrenceQuery(container = packProcess, containerInside = false) to "o.container_entity_id = ${id(packProcess)}",
            OccurrenceQuery(unitKinds = setOf("TRIGGER")) to "o.unit_id IN (SELECT id FROM unit WHERE kind = 'TRIGGER')",
            OccurrenceQuery(path = "hr/pack_") to "o.file_id IN (SELECT id FROM source_file WHERE substr(path, 1, 8) = 'hr/pack_')",
            OccurrenceQuery(path = "*calls*") to "o.file_id IN (SELECT id FROM source_file WHERE instr(path, 'calls') > 0)",
            OccurrenceQuery(roles = setOf("SQL_UPDATE")) to "instr(',' || o.roles || ',', ',SQL_UPDATE,') > 0",
            OccurrenceQuery(statuses = setOf("AMBIGUOUS")) to "o.status = 'AMBIGUOUS'",
            OccurrenceQuery(reasons = setOf("NOT_IN_PROJECT")) to "o.reason = 'NOT_IN_PROJECT'",
            OccurrenceQuery(origins = setOf("DYNAMIC_LITERAL")) to "o.origin = 'DYNAMIC_LITERAL'",
            OccurrenceQuery(statementKinds = setOf("MERGE")) to "o.statement_id IN (SELECT id FROM sql_statement WHERE kind = 'MERGE')",
            OccurrenceQuery(valueKind = "STRING", value = "S") to
                "o.id IN (SELECT occurrence_id FROM written_value WHERE value_kind = 'STRING' AND value = 'S' UNION SELECT occurrence_id FROM call_argument WHERE value_kind = 'STRING' AND value = 'S')",
            OccurrenceQuery(valueKind = "EXPRESSION") to
                "o.id IN (SELECT occurrence_id FROM written_value WHERE value_kind = 'EXPRESSION' UNION SELECT occurrence_id FROM call_argument WHERE value_kind = 'EXPRESSION')"
        )
        for ((query, where) in cases) {
            val expected = count(where)
            assertThat(expected).describedAs(where).isGreaterThan(0)
            assertThat(total(query)).describedAs(query.toString()).isEqualTo(expected)
        }
    }

    @Test
    fun `filters combine with AND`() {
        val query = OccurrenceQuery(entityKinds = setOf("COLUMN"), roles = setOf("SQL_UPDATE"), container = packProcess, candidates = false)
        val expected = count(
            "o.entity_id IN (SELECT id FROM entity WHERE kind = 'COLUMN') AND instr(',' || o.roles || ',', ',SQL_UPDATE,') > 0 " +
                "AND o.container_entity_id IN ${subtree(packProcess)}"
        )
        assertThat(expected).isGreaterThan(0)
        assertThat(total(query)).isEqualTo(expected)
    }

    @Test
    fun `an ambiguous occurrence matches through a candidate only when candidates are on`() {
        val column = "db:HR.COMPANY.ID"
        val on = index.queryOccurrences(OccurrenceQuery(entity = column), limit = 500).result
        val off = index.queryOccurrences(OccurrenceQuery(entity = column, candidates = false), limit = 500).result
        val viaCandidate = on.rows.filter { it.viaCandidate }
        assertThat(viaCandidate).isNotEmpty.allMatch { it.status == "AMBIGUOUS" && it.matched!!.key == column && it.entity == null }
        assertThat(off.rows).noneMatch { it.viaCandidate }
        assertThat(on.total).isEqualTo(off.total + viaCandidate.size)
    }

    @Test
    fun `patterns take star as a wildcard and every other character literally`() {
        // `P_B` would also match `PUB` if the underscore were a LIKE wildcard; `P%B` would match both.
        val keys = index.queryOccurrences(OccurrenceQuery(name = "p_b"), limit = 500).result.rows.mapNotNull { it.matched?.key }
        assertThat(keys).isNotEmpty.allMatch { it.substringAfterLast('.') == "P_B" }
        assertThat(total(OccurrenceQuery(name = "PUB"))).isGreaterThan(0)
        assertThat(total(OccurrenceQuery(name = "P%B"))).isZero()
        assertThat(total(OccurrenceQuery(name = "P*B", candidates = false)))
            .isEqualTo(count("o.entity_id IN (SELECT id FROM entity WHERE upper(name) LIKE 'P%B')"))
            .isGreaterThan(total(OccurrenceQuery(name = "P_B", candidates = false)) + total(OccurrenceQuery(name = "PUB", candidates = false)))
        // A path pattern with an underscore matches it literally too.
        assertThat(total(OccurrenceQuery(path = "hr/pack%"))).isZero()
    }

    @Test
    fun `a dotted name matches the entity and its parents, the first segment perhaps the schema`() {
        fun keys(name: String) = index.groupOccurrences(OccurrenceQuery(name = name, candidates = false), listOf(GroupDimension.ENTITY), limit = 1000)
            .result.groups.map { it.values.single().entity!!.key }.sorted()
        fun uses(vararg keys: String) = count("o.entity_id IN (SELECT id FROM entity WHERE key IN (${keys.joinToString { "'$it'" }}))")
        val every = listOf("db:COMPANY@REMOTE.ST_ACTIVE", "db:HR.COMPANY.ST_ACTIVE", "db:HR.V_LIN_AGG.ST_ACTIVE")
        assertThat(keys("st_active")).isEqualTo(every)
        assertThat(total(OccurrenceQuery(name = "ST_Active", candidates = false))).isEqualTo(uses(*every.toTypedArray()))
        // Two segments: the parent's name; `COMPANY@REMOTE` is another name.
        assertThat(keys("company.st_active")).containsExactly("db:HR.COMPANY.ST_ACTIVE")
        assertThat(keys("v_lin_agg.st_active")).containsExactly("db:HR.V_LIN_AGG.ST_ACTIVE")
        // Three segments, the first the schema of a table without a parent entity.
        assertThat(keys("hr.company.st_active")).containsExactly("db:HR.COMPANY.ST_ACTIVE")
        assertThat(keys("other.company.st_active")).isEmpty()
        // A schema as the first of two segments.
        assertThat(keys("hr.company")).containsExactly("db:HR.COMPANY")
        // A wildcard in a middle segment.
        assertThat(keys("hr.*.st_active")).containsExactly("db:HR.COMPANY.ST_ACTIVE", "db:HR.V_LIN_AGG.ST_ACTIVE")
        assertThat(keys("*.st_act*")).isEqualTo(every)
        // Without `*` a segment is exact, never a prefix.
        assertThat(total(OccurrenceQuery(name = "st_act"))).isZero()
        assertThat(total(OccurrenceQuery(name = "compan.st_active"))).isZero()
        assertThatThrownBy { index.queryOccurrences(OccurrenceQuery(name = "a.b.c.d.e")) }.isInstanceOf(InvalidQueryException::class.java)
            .hasMessageContaining("at most 4")
        assertThatThrownBy { index.queryOccurrences(OccurrenceQuery(name = "company..st_active")) }.isInstanceOf(InvalidQueryException::class.java)
    }

    @Test
    fun `a container name makes every matching entity a container`() {
        val both = "o.container_entity_id IN ${subtree("db:HR.PACK_CALLS")} OR o.container_entity_id IN ${subtree("db:HR.PACK_COLLECTION")}"
        assertThat(count("o.container_entity_id IN ${subtree("db:HR.PACK_CALLS")}")).isGreaterThan(0)
        assertThat(count("o.container_entity_id IN ${subtree("db:HR.PACK_COLLECTION")}")).isGreaterThan(0)
        assertThat(total(OccurrenceQuery(containerName = "pack_c*ll*"))).isEqualTo(count(both))
        assertThat(total(OccurrenceQuery(containerName = "hr.pack_c*ll*", containerInside = false)))
            .isEqualTo(count("o.container_entity_id IN (${id("db:HR.PACK_CALLS")}, ${id("db:HR.PACK_COLLECTION")})"))
        assertThat(total(OccurrenceQuery(containerName = "pack_process"))).isEqualTo(total(OccurrenceQuery(container = packProcess)))
        assertThat(total(OccurrenceQuery(containerName = "no_such_package"))).isZero()
        // With a name: the uses of `COMPANY.ST_ACTIVE` inside `PACK_PROCESS`.
        assertThat(total(OccurrenceQuery(name = "company.st_active", containerName = "pack_process", candidates = false)))
            .isEqualTo(count("o.entity_id = ${id("db:HR.COMPANY.ST_ACTIVE")} AND o.container_entity_id IN ${subtree(packProcess)}"))
            .isGreaterThan(0)
    }

    @Test
    fun `keyset pages cover the whole set once, with the same total`() {
        val query = OccurrenceQuery(roles = setOf("SQL_UPDATE", "SQL_INSERT"))
        val first = index.queryOccurrences(query, limit = 7).result
        val second = index.queryOccurrences(query, after = first.nextAfter, limit = 7).result
        val whole = index.queryOccurrences(query, limit = 14).result
        assertThat(first.rows).hasSize(7)
        assertThat(first.total).isEqualTo(second.total).isEqualTo(whole.total).isGreaterThan(14)
        assertThat((first.rows + second.rows).map { it.id }).isEqualTo(whole.rows.map { it.id }).isSorted
        assertThat(index.queryOccurrences(query, summary = false, limit = 7).result.let { it.total to it.rows.map { r -> r.id } })
            .isEqualTo(-1L to first.rows.map { it.id })
    }

    @Test
    fun `the facets count the filtered set with every filter, roles split`() {
        val query = OccurrenceQuery(container = packProcess, roles = setOf("WRITE"))
        val summary = index.querySummary(query).result
        val where = "o.container_entity_id IN ${subtree(packProcess)} AND instr(',' || o.roles || ',', ',WRITE,') > 0"
        assertThat(summary.total).isEqualTo(count(where))
        assertThat(summary.facets.byRole.single { it.value == "WRITE" }.count).isEqualTo(summary.total)
        assertThat(summary.facets.byRole.single { it.value == "SQL_UPDATE" }.count).isEqualTo(count("$where AND instr(',' || o.roles || ',', ',SQL_UPDATE,') > 0"))
        assertThat(summary.facets.byStatus.sumOf { it.count }).isEqualTo(summary.total)
        assertThat(summary.facets.byOrigin.sumOf { it.count }).isEqualTo(summary.total)
        assertThat(summary.facets.byUnitKind.map { it.value }).containsExactly("PACKAGE_BODY")
        assertThat(summary.facets.byEntityKind.map { it.value }).contains("COLUMN")
        assertThat(index.queryOccurrences(query).result.facets).isEqualTo(summary.facets)
    }

    @Test
    fun `the reason facet counts the occurrences with a reason, with a reason filter and without one`() {
        fun expected(where: String) = sql("SELECT o.reason, count(*) FROM occurrence o WHERE o.reason IS NOT NULL AND $where GROUP BY o.reason ORDER BY o.reason")
            .map { line -> line.split('\t').let { Count(it[0], it[1].toLong()) } }
        val all = index.querySummary(OccurrenceQuery()).result
        assertThat(all.facets.byReason).isEqualTo(expected("1")).hasSizeGreaterThan(1)
        assertThat(all.facets.byReason.sumOf { it.count }).isLessThan(all.total)
        val unresolved = index.querySummary(OccurrenceQuery(statuses = setOf("UNRESOLVED"))).result.facets.byReason
        assertThat(unresolved).isEqualTo(expected("o.status = 'UNRESOLVED'"))
        val filtered = index.querySummary(OccurrenceQuery(reasons = setOf("NOT_IN_PROJECT", "LOCAL_SUBPROGRAM"))).result
        assertThat(filtered.facets.byReason).isEqualTo(expected("o.reason IN ('NOT_IN_PROJECT', 'LOCAL_SUBPROGRAM')")).hasSize(2)
        assertThat(filtered.facets.byReason.sumOf { it.count }).isEqualTo(filtered.total)
    }

    @Test
    fun `every group dimension groups the matching occurrences`() {
        val query = OccurrenceQuery(container = packProcess)
        val total = total(query)
        fun groups(vararg dimensions: GroupDimension) = index.groupOccurrences(query, dimensions.toList(), limit = 1000).result
        for (dimension in GroupDimension.entries - GroupDimension.ROLE - GroupDimension.VALUE) {
            val page = groups(dimension)
            assertThat(page.groups.sumOf { it.count }).describedAs(dimension.name).isEqualTo(total)
            assertThat(page.totalGroups).isEqualTo(page.groups.size.toLong())
            assertThat(page.groups.map { it.count }).isSortedAccordingTo(Comparator.reverseOrder())
        }
        val byStatus = groups(GroupDimension.STATUS).groups.associate { it.values.single().text to it.count }
        assertThat(byStatus).isEqualTo(sql("SELECT status, count(*) FROM occurrence o WHERE o.container_entity_id IN ${subtree(packProcess)} GROUP BY status")
            .associate { line -> line.split('\t').let { it[0] to it[1].toLong() } })
        val byEntity = groups(GroupDimension.ENTITY).groups
        assertThat(byEntity.first { it.values.single().entity?.key == "db:HR.COMPANY" }.count)
            .isEqualTo(count("o.entity_id = ${id("db:HR.COMPANY")} AND o.container_entity_id IN ${subtree(packProcess)}"))
        assertThat(groups(GroupDimension.UNIT).groups.map { it.values.single().let { v -> "${v.path} ${v.unit!!.kind} ${v.unit.name}" } })
            .containsExactlyInAnyOrder("hr/pack_process.pkb PACKAGE_BODY PACK_PROCESS", "hr/pack_process.pks PACKAGE PACK_PROCESS")
        assertThat(groups(GroupDimension.FILE).groups.map { it.values.single().path }).containsExactlyInAnyOrder("hr/pack_process.pkb", "hr/pack_process.pks")
        assertThat(groups(GroupDimension.CONTAINER).groups.map { it.values.single().entity?.key }).contains(runJob5Body)
        assertThat(groups(GroupDimension.STATEMENT_KIND).groups.map { it.values.single().text }).contains("UPDATE", "MERGE", null)
        // Three dimensions, with the count of each combination.
        val three = groups(GroupDimension.STATUS, GroupDimension.ORIGIN, GroupDimension.ENTITY_KIND)
        assertThat(three.dimensions).containsExactly("STATUS", "ORIGIN", "ENTITY_KIND")
        assertThat(three.groups.sumOf { it.count }).isEqualTo(total)
        assertThat(three.groups).allMatch { it.values.size == 3 }
    }

    @Test
    fun `an occurrence without entity groups under a null entity, or under the candidate it matched`() {
        val nulls = index.groupOccurrences(OccurrenceQuery(statuses = setOf("AMBIGUOUS")), listOf(GroupDimension.ENTITY)).result.groups
        assertThat(nulls.single().values.single().entity).isNull()
        assertThat(nulls.single().count).isEqualTo(count("o.status = 'AMBIGUOUS'"))
        val viaCandidate = index.groupOccurrences(OccurrenceQuery(entity = "db:HR.COMPANY.ID", statuses = setOf("AMBIGUOUS")), listOf(GroupDimension.ENTITY))
            .result.groups
        assertThat(viaCandidate.single().values.single().entity!!.key).isEqualTo("db:HR.COMPANY.ID")
    }

    @Test
    fun `ROLE counts an occurrence under each role the roles filter keeps`() {
        val query = OccurrenceQuery(container = packProcess, roles = setOf("SQL_UPDATE", "WRITE"))
        val groups = index.groupOccurrences(query, listOf(GroupDimension.ROLE)).result.groups.associate { it.values.single().text to it.count }
        val inside = "o.container_entity_id IN ${subtree(packProcess)}"
        assertThat(groups.keys).containsExactlyInAnyOrder("SQL_UPDATE", "WRITE")
        assertThat(groups["SQL_UPDATE"]).isEqualTo(count("$inside AND instr(',' || o.roles || ',', ',SQL_UPDATE,') > 0"))
        assertThat(groups["WRITE"]).isEqualTo(count("$inside AND instr(',' || o.roles || ',', ',WRITE,') > 0"))
        // An occurrence with both roles counts twice.
        assertThat(groups.values.sum()).isGreaterThan(total(query))
        // Without a roles filter every role of every occurrence is a group, definitions included.
        val unfiltered = index.groupOccurrences(OccurrenceQuery(container = packProcess), listOf(GroupDimension.ROLE), limit = 1000).result
        assertThat(unfiltered.groups.map { it.values.single().text }).contains("SQL_UPDATE", "WRITE", "IMPLICIT", "CALL")
        assertThat(unfiltered.totalGroups).isEqualTo(unfiltered.groups.size.toLong())
    }

    @Test
    fun `VALUE groups the matching value rows, one occurrence once per value`() {
        val all = index.groupOccurrences(OccurrenceQuery(), listOf(GroupDimension.VALUE), limit = 1000).result.groups
        fun group(kind: String, text: String?) = all.single { it.values.single().valueKind == kind && it.values.single().text == text }.count
        assertThat(group("STRING", "S")).isEqualTo(count(
            "o.id IN (SELECT occurrence_id FROM written_value WHERE value_kind = 'STRING' AND value = 'S' UNION SELECT occurrence_id FROM call_argument WHERE value_kind = 'STRING' AND value = 'S')"))
        // EXPRESSION is one group whatever the expression.
        assertThat(all.filter { it.values.single().valueKind == "EXPRESSION" }).hasSize(1)
        assertThat(group("EXPRESSION", null)).isEqualTo(count(
            "o.id IN (SELECT occurrence_id FROM written_value WHERE value_kind = 'EXPRESSION' UNION SELECT occurrence_id FROM call_argument WHERE value_kind = 'EXPRESSION')"))
        // With a value filter, only the matching value rows are grouped: a call passing `1` and `'a'` counts under `1` only.
        val ones = index.groupOccurrences(OccurrenceQuery(valueKind = "NUMBER", value = "1"), listOf(GroupDimension.VALUE)).result.groups
        assertThat(ones.map { it.values.single().valueKind to it.values.single().text }).containsExactly("NUMBER" to "1")
        val calls = index.groupOccurrences(OccurrenceQuery(roles = setOf("CALL"), valueKind = "NUMBER", value = "1"), listOf(GroupDimension.VALUE, GroupDimension.ROLE))
            .result.groups.single()
        assertThat(calls.count).isEqualTo(count("instr(',' || o.roles || ',', ',CALL,') > 0 AND o.id IN (SELECT occurrence_id FROM call_argument WHERE value_kind = 'NUMBER' AND value = '1')"))
        // A call with several arguments counts in several groups.
        assertThat(all.sumOf { it.count }).isGreaterThan(count("o.id IN (SELECT occurrence_id FROM written_value UNION SELECT occurrence_id FROM call_argument)"))
    }

    @Test
    fun `the CRUD matrix of a package is a group query`() {
        val crud = setOf("SQL_SELECT", "SQL_INSERT", "SQL_UPDATE", "SQL_DELETE")
        val matrix = index.groupOccurrences(
            OccurrenceQuery(container = packProcess, roles = crud),
            listOf(GroupDimension.CONTAINER, GroupDimension.ENTITY, GroupDimension.ROLE), limit = 1000
        ).result
        val cells = matrix.groups.associate { group -> group.values.let { Triple(it[0].entity!!.key, it[1].entity?.key, it[2].text) } to group.count }
        assertThat(cells.keys.map { it.third }.toSet()).isSubsetOf(crud)
        // The UPDATEs of COMPANY in RUN_JOB with five parameters, counted in plain SQL.
        val expected = count("o.container_entity_id = ${id(runJob5Body)} AND o.entity_id = ${id("db:HR.COMPANY")} AND instr(',' || o.roles || ',', ',SQL_UPDATE,') > 0")
        val runJob4Body = "db:HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE)@BODY"
        val updates = count("o.container_entity_id = ${id(runJob4Body)} AND o.entity_id = ${id("db:HR.COMPANY")} AND instr(',' || o.roles || ',', ',SQL_UPDATE,') > 0")
        assertThat(updates).isGreaterThan(0)
        assertThat(cells[Triple(runJob4Body, "db:HR.COMPANY", "SQL_UPDATE")]).isEqualTo(updates)
        if (expected > 0) assertThat(cells[Triple(runJob5Body, "db:HR.COMPANY", "SQL_UPDATE")]).isEqualTo(expected)
        assertThat(cells.keys.map { it.second }).contains("db:HR.SEND_QUEUE", "db:HR.EVENT_LOG")
    }

    @Test
    fun `the value filter follows the case of each value kind`() {
        fun values(kind: String, value: String) =
            count("o.id IN (SELECT occurrence_id FROM written_value WHERE value_kind = '$kind' AND value = '$value' " +
                "UNION SELECT occurrence_id FROM call_argument WHERE value_kind = '$kind' AND value = '$value')")
        val sysdate = values("NAME", "SYSDATE")
        assertThat(sysdate).isGreaterThan(0)
        assertThat(total(OccurrenceQuery(valueKind = "NAME", value = "sysdate"))).isEqualTo(sysdate)
        assertThat(total(OccurrenceQuery(valueKind = "NAME", value = "sysd*"))).isGreaterThanOrEqualTo(sysdate)
        val trues = values("BOOLEAN", "TRUE")
        assertThat(trues).isGreaterThan(0)
        assertThat(total(OccurrenceQuery(valueKind = "BOOLEAN", value = "true"))).isEqualTo(trues)
        // `'S'` is stored; a lower-case pattern does not match it.
        assertThat(total(OccurrenceQuery(valueKind = "STRING", value = "S*"))).isGreaterThan(0)
        assertThat(total(OccurrenceQuery(valueKind = "STRING", value = "s*"))).isEqualTo(
            count("o.id IN (SELECT occurrence_id FROM written_value WHERE value_kind = 'STRING' AND value GLOB 's*' " +
                "UNION SELECT occurrence_id FROM call_argument WHERE value_kind = 'STRING' AND value GLOB 's*')"))
        assertThat(total(OccurrenceQuery(valueKind = "STRING", value = "it's*"))).isGreaterThan(0)
        assertThat(total(OccurrenceQuery(valueKind = "STRING", value = "IT'S*"))).isZero()
        // `?` and `[` are themselves: as GLOB wildcards these would match `'S'` and `'it''s'`; no stored string has them.
        assertThat(total(OccurrenceQuery(valueKind = "STRING", value = "?*"))).isZero()
        assertThat(total(OccurrenceQuery(valueKind = "STRING", value = "[Si]*"))).isZero()
        assertThat(OccurrenceQuerySql.globPattern("a?b[c]*")).isEqualTo("a[?]b[[]c]*")
    }

    @Test
    fun `a value without a kind matches any kind with text, each by its own case rule`() {
        fun rows(where: String) = "o.id IN (SELECT occurrence_id FROM written_value WHERE $where UNION SELECT occurrence_id FROM call_argument WHERE $where)"
        // `1` is a NUMBER; `S` a STRING; `sysdate` a NAME, matched upper case; `true` a BOOLEAN, the same.
        assertThat(total(OccurrenceQuery(value = "1"))).isEqualTo(count(rows("value_kind = 'NUMBER' AND value = '1'"))).isGreaterThan(0)
        assertThat(total(OccurrenceQuery(value = "S"))).isEqualTo(count(rows("value_kind IN ('STRING', 'NUMBER', 'BOOLEAN', 'NAME') AND value = 'S'"))).isGreaterThan(0)
        assertThat(total(OccurrenceQuery(value = "sysdate"))).isEqualTo(count(rows("value_kind = 'NAME' AND value = 'SYSDATE'"))).isGreaterThan(0)
        assertThat(total(OccurrenceQuery(value = "true"))).isEqualTo(count(rows("value_kind = 'BOOLEAN' AND value = 'TRUE'"))).isGreaterThan(0)
        // A STRING stays case-sensitive: `s*` does not match the stored `'S'`, while it matches NAMEs starting with S.
        assertThat(total(OccurrenceQuery(value = "s*"))).isEqualTo(count(rows(
            "(value_kind = 'STRING' AND value GLOB 's*') OR (value_kind IN ('NAME', 'BOOLEAN') AND value LIKE 'S%') OR (value_kind = 'NUMBER' AND value LIKE 's%')"
        )))
        // Grouped by value, only the matching value rows count.
        val groups = index.groupOccurrences(OccurrenceQuery(value = "1"), listOf(GroupDimension.VALUE)).result.groups
        assertThat(groups.map { it.values.single().valueKind to it.values.single().text }).containsExactly("NUMBER" to "1")
    }

    @Test
    fun `every invalid query is refused`() {
        fun refused(message: String, call: () -> Unit) = assertThatThrownBy(call).isInstanceOf(InvalidQueryException::class.java).hasMessageContaining(message)
        refused("Unknown role: NOPE") { index.queryOccurrences(OccurrenceQuery(roles = setOf("NOPE"))) }
        refused("Unknown status: NOPE") { index.queryOccurrences(OccurrenceQuery(statuses = setOf("NOPE"))) }
        refused("Unknown entity kind: NOPE") { index.queryOccurrences(OccurrenceQuery(entityKinds = setOf("NOPE"))) }
        refused("Unknown unit kind: NOPE") { index.queryOccurrences(OccurrenceQuery(unitKinds = setOf("NOPE"))) }
        refused("Unknown origin: NOPE") { index.queryOccurrences(OccurrenceQuery(origins = setOf("NOPE"))) }
        refused("Unknown statement kind: NOPE") { index.queryOccurrences(OccurrenceQuery(statementKinds = setOf("NOPE"))) }
        refused("Unknown value kind: NOPE") { index.queryOccurrences(OccurrenceQuery(valueKind = "NOPE")) }
        refused("Unknown entity: db:NO.SUCH") { index.queryOccurrences(OccurrenceQuery(entity = "db:NO.SUCH")) }
        refused("Unknown container: db:NO.SUCH") { index.groupOccurrences(OccurrenceQuery(container = "db:NO.SUCH"), listOf(GroupDimension.STATUS)) }
        refused("A NULL value has no text") { index.queryOccurrences(OccurrenceQuery(valueKind = "NULL", value = "x")) }
        refused("name must not be empty") { index.queryOccurrences(OccurrenceQuery(name = "")) }
        refused("limit must be between 1 and 500") { index.queryOccurrences(OccurrenceQuery(), limit = 501) }
        refused("limit must be between 1 and 1000") { index.groupOccurrences(OccurrenceQuery(), listOf(GroupDimension.STATUS), limit = 0) }
        refused("one to three dimensions") { index.groupOccurrences(OccurrenceQuery(), emptyList()) }
        refused("one to three dimensions") { index.groupOccurrences(OccurrenceQuery(), GroupDimension.entries.take(4)) }
        refused("repeated") { index.groupOccurrences(OccurrenceQuery(), listOf(GroupDimension.STATUS, GroupDimension.STATUS)) }
    }

    @Test
    fun `a query past its time limit is interrupted`() {
        val hurried = AtlasIndex(db, null, queryTimeout = Duration.ZERO)
        assertThatThrownBy { hurried.groupOccurrences(OccurrenceQuery(), listOf(GroupDimension.ENTITY, GroupDimension.ROLE)) }
            .isInstanceOf(QueryTimeoutException::class.java).hasMessageContaining("narrow the filters")
        assertThatThrownBy { hurried.querySummary(OccurrenceQuery()) }.isInstanceOf(QueryTimeoutException::class.java)
        // The other answers are not timed.
        assertThat(hurried.run().result.schemaVersion).isEqualTo(AtlasIndex.SCHEMA_VERSION)
    }
}
