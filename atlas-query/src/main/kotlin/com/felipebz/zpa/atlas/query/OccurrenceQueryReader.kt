//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import java.sql.Connection
import java.sql.ResultSet

/** The occurrence query (list, summary, groups) over one open connection; [validate] checks a query before it opens. */
internal class OccurrenceQueryReader(private val connection: Connection) {

    /** [AtlasIndex.queryOccurrences] after its checks. */
    fun queryOccurrences(query: OccurrenceQuery, after: Long?, limit: Int, summary: Boolean): OccurrencePage {
        val sql = sql(query)
        val found = connection.query(
            """${sql.with}
               SELECT o.id, ${sql.matched}, ${sql.via}, o.roles, o.status FROM ${sql.from}
               ${sql.where("o.id > ?")} ORDER BY o.id LIMIT ?""",
            sql.parameters + listOf(after ?: 0L, limit + 1)
        ) { Hit(it.getLong(1), (it.getObject(2) as Number?)?.toLong(), it.getInt(3) == 1, false, roles(it.getString(4)), it.getString(5)) }
        val page = found.take(limit)
        val nextAfter = if (found.size > limit) page.last().id else null
        val (total, facets) = if (summary) summary(sql) else -1L to Facets(emptyList(), emptyList())
        return OccurrencePage(rows(connection, page), nextAfter, total, facets)
    }

    fun querySummary(query: OccurrenceQuery): QuerySummary {
        val (total, facets) = summary(sql(query))
        return QuerySummary(total, facets)
    }

    /** [AtlasIndex.groupOccurrences] after its checks. */
    fun groupOccurrences(query: OccurrenceQuery, dimensions: List<GroupDimension>, limit: Int): GroupPage {
        val sql = sql(query)
        val joins = ArrayList<String>()
        val joinParameters = ArrayList<Any?>()
        val valueRows = GroupDimension.VALUE in dimensions
        if (GroupDimension.ENTITY in dimensions || GroupDimension.ENTITY_KIND in dimensions) joins += "LEFT JOIN entity me ON me.id = ${sql.matched}"
        if (GroupDimension.CONTAINER in dimensions) joins += "LEFT JOIN entity ce ON ce.id = o.container_entity_id"
        if (GroupDimension.UNIT in dimensions) joins += "JOIN unit un ON un.id = o.unit_id JOIN source_file uf ON uf.id = un.file_id"
        if (GroupDimension.FILE in dimensions) joins += "JOIN source_file fi ON fi.id = o.file_id"
        if (GroupDimension.STATEMENT_KIND in dimensions) joins += "LEFT JOIN sql_statement st ON st.id = o.statement_id"
        if (valueRows) {
            // The value rows of the occurrence, those of the value filter only when there is one.
            val (condition, valueParameters) =
                if (query.valueKind != null || query.value != null) sql.valueCondition(query.valueKind, query.value) else "1" to emptyList()
            joins += """JOIN (SELECT occurrence_id, value_kind, CASE WHEN value_kind IN ('BIND', 'EXPRESSION') THEN NULL ELSE value END AS shown
                              FROM written_value WHERE $condition
                              UNION ALL
                              SELECT occurrence_id, value_kind, CASE WHEN value_kind IN ('BIND', 'EXPRESSION') THEN NULL ELSE value END
                              FROM call_argument WHERE $condition) va ON va.occurrence_id = o.id"""
            joinParameters.addAll(valueParameters + valueParameters)
        }
        if (GroupDimension.ROLE in dimensions) {
            // Roles are stored as one text: an occurrence joins once each of its roles the roles filter keeps.
            val kept = ROLES.filter { query.roles.isEmpty() || it in query.roles }
            sql.addCte("role_list(role) AS (VALUES ${kept.joinToString(", ") { "(?)" }})", kept)
            joins += "JOIN role_list rl ON instr(',' || o.roles || ',', ',' || rl.role || ',') > 0"
        }
        // Per dimension: the grouped expressions, then the expression that orders by its value.
        val grouped = dimensions.map { dimension ->
            when (dimension) {
                GroupDimension.ENTITY -> listOf(sql.matched) to "me.key"
                GroupDimension.ENTITY_KIND -> listOf("me.kind") to "me.kind"
                GroupDimension.CONTAINER -> listOf("o.container_entity_id") to "ce.key"
                GroupDimension.UNIT -> listOf("o.unit_id") to "uf.path || ' ' || un.name"
                GroupDimension.FILE -> listOf("o.file_id") to "fi.path"
                GroupDimension.ROLE -> listOf("rl.role") to "rl.role"
                GroupDimension.STATUS -> listOf("o.status") to "o.status"
                GroupDimension.REASON -> listOf("o.reason") to "o.reason"
                GroupDimension.ORIGIN -> listOf("o.origin") to "o.origin"
                GroupDimension.STATEMENT_KIND -> listOf("st.kind") to "st.kind"
                GroupDimension.VALUE -> listOf("va.value_kind", "va.shown") to "va.value_kind || ' ' || coalesce(va.shown, '')"
            }
        }
        val columns = grouped.flatMap { it.first }
        val orders = grouped.map { it.second }
        val count = if (valueRows) "count(DISTINCT o.id)" else "count(*)"
        val select = "SELECT ${columns.joinToString()}, ${orders.joinToString()}, $count AS n FROM ${sql.from} ${joins.joinToString(" ")} ${sql.where()} GROUP BY ${columns.joinToString()}"
        val width = columns.size
        fun raw(rs: ResultSet): RawGroup = RawGroup((1..width).map { rs.getObject(it) }, rs.getLong(width + orders.size + 1))
        var totalGroups = 0L
        val rawGroups = connection.query(
            "${sql.with} SELECT *, count(*) OVER () FROM ($select) ORDER BY n DESC, ${orders.indices.joinToString { "${width + it + 1}" }} LIMIT ?",
            sql.withParameters + joinParameters + sql.whereParameters + limit
        ) { rs -> totalGroups = rs.getLong(width + orders.size + 2); raw(rs) }
        return GroupPage(dimensions.map { it.name }, groupValues(dimensions, rawGroups), totalGroups)
    }

    private class RawGroup(val values: List<Any?>, val count: Long)

    /** The values of [groups] for their [dimensions]: entities, units and files read once each. */
    private fun groupValues(dimensions: List<GroupDimension>, groups: List<RawGroup>): List<OccurrenceGroup> {
        val entityIds = HashSet<Long>()
        val unitIds = HashSet<Long>()
        val fileIds = HashSet<Long>()
        for (group in groups) {
            var column = 0
            for (dimension in dimensions) {
                val value = (group.values[column] as? Number)?.toLong()
                when (dimension) {
                    GroupDimension.ENTITY, GroupDimension.CONTAINER -> value?.let { entityIds += it }
                    GroupDimension.UNIT -> value?.let { unitIds += it }
                    GroupDimension.FILE -> value?.let { fileIds += it }
                    else -> Unit
                }
                column += if (dimension == GroupDimension.VALUE) 2 else 1
            }
        }
        val entities = byIds(connection, entityIds, "SELECT id, key, kind, display, external, name FROM entity") {
            EntitySummary(it.getString(2), it.getString(3), it.getString(4), it.getInt(5) == 1, it.getString(6))
        }
        val units = byIds(connection, unitIds, "SELECT un.id, un.kind, un.name, f.path FROM unit un JOIN source_file f ON f.id = un.file_id", "un.id") {
            UnitRef(it.getString(2), it.getString(3)) to it.getString(4)
        }
        val files = byIds(connection, fileIds, "SELECT id, path FROM source_file") { it.getString(2) }
        return groups.map { group ->
            var column = 0
            val values = dimensions.map { dimension ->
                val raw = group.values[column]
                val id = (raw as? Number)?.toLong()
                column += if (dimension == GroupDimension.VALUE) 2 else 1
                when (dimension) {
                    GroupDimension.ENTITY, GroupDimension.CONTAINER -> GroupValue(entity = id?.let { entities.getValue(it) })
                    GroupDimension.UNIT -> id?.let { units.getValue(it) }?.let { GroupValue(unit = it.first, path = it.second) } ?: GroupValue()
                    GroupDimension.FILE -> GroupValue(path = id?.let { files.getValue(it) })
                    GroupDimension.VALUE -> GroupValue(text = group.values[column - 1] as String?, valueKind = raw as String?)
                    else -> GroupValue(text = raw as String?)
                }
            }
            OccurrenceGroup(values, group.count)
        }
    }

    /**
     * The total and the facets of the occurrences [sql] matches, counted while the rows stream by: on a large set a SQL
     * `GROUP BY` of these text columns spends most of its time sorting, a scan with a hash count does not. Each row is
     * read as one text, its six values joined by tabs, which the driver hands over in one call.
     */
    private fun summary(sql: OccurrenceQuerySql): Pair<Long, Facets> {
        val counts = HashMap<String, Long>()
        connection.prepareStatement(
            """${sql.with}
               SELECT o.roles || char(9) || o.status || char(9) || o.origin || char(9) || coalesce(me.kind, '') || char(9) || un.kind
                      || char(9) || coalesce(o.reason, '')
               FROM ${sql.from} LEFT JOIN entity me ON me.id = ${sql.matched} JOIN unit un ON un.id = o.unit_id
               ${sql.where()}"""
        ).use { statement ->
            statement.bind(sql.parameters)
            statement.executeQuery().use { rs ->
                while (rs.next()) counts.merge(rs.getString(1), 1L, Long::plus)
            }
        }
        val combinations = counts.map { (text, n) -> text.split('\t') to n }
        fun facet(values: Sequence<Pair<String, Long>>) =
            values.filter { it.first.isNotEmpty() }.groupingBy { it.first }.fold(0L) { sum, item -> sum + item.second }
                .toSortedMap().map { Count(it.key, it.value) }
        fun column(index: Int) = facet(combinations.asSequence().map { (values, n) -> values[index] to n })
        return counts.values.sum() to Facets(
            facet(combinations.asSequence().flatMap { (values, n) -> roles(values[0]).map { it to n } }),
            column(1), column(2), column(3), column(4), column(5)
        )
    }

    /** The SQL of [query], with its entity and container keys resolved; an unknown key is an error. */
    private fun sql(query: OccurrenceQuery): OccurrenceQuerySql {
        fun id(key: String?, what: String) = key?.let {
            connection.query("SELECT id FROM entity WHERE key = ?", listOf(it)) { rs -> rs.getLong(1) }.singleOrNull()
                ?: throw InvalidQueryException("Unknown $what: $it")
        }
        return OccurrenceQuerySql(query, id(query.entity, "entity"), id(query.container, "container"))
    }
}

internal fun validate(query: OccurrenceQuery) {
    fun check(values: Collection<String>, known: Collection<String>, what: String) =
        values.firstOrNull { it !in known }?.let { throw InvalidQueryException("Unknown $what: $it") }
    check(query.roles, ROLES, "role")
    check(query.statuses, STATUSES, "status")
    check(query.entityKinds, ENTITY_KINDS, "entity kind")
    check(query.unitKinds, UNIT_KINDS, "unit kind")
    check(query.origins, ORIGINS, "origin")
    check(query.statementKinds, STATEMENT_KINDS, "statement kind")
    check(listOfNotNull(query.valueKind), VALUE_KINDS, "value kind")
    if (query.value != null) {
        if (query.valueKind != null && query.valueKind !in OccurrenceQuerySql.TEXT_VALUE_KINDS) throw InvalidQueryException("A ${query.valueKind} value has no text to match")
    }
    for ((name, text) in listOf(
        "name" to query.name, "containerName" to query.containerName, "schema" to query.schema, "path" to query.path, "value" to query.value
    )) {
        if (text != null && text.isEmpty()) throw InvalidQueryException("$name must not be empty")
    }
    for ((name, text) in listOf("name" to query.name, "containerName" to query.containerName)) {
        val segments = text?.split('.') ?: continue
        if (segments.size > OccurrenceQuerySql.MAX_SEGMENTS) throw InvalidQueryException("$name takes at most ${OccurrenceQuerySql.MAX_SEGMENTS} dotted segments")
        if (segments.any { it.isEmpty() }) throw InvalidQueryException("$name has an empty segment")
    }
}
