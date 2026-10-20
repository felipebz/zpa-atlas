//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import java.sql.Connection
import java.sql.ResultSet

internal class Hit(val id: Long, val matched: Long?, val viaCandidate: Boolean, val viaView: Boolean, val roles: List<String>, val status: String)

/** The hit an occurrence hit several ways reports: not added by [Scope.VIEWS] first, then direct before candidate. */
internal val hitPreference = compareBy<Hit>({ it.viaView }, { it.viaCandidate }, { it.matched })

internal fun rows(connection: Connection, page: List<Hit>): List<OccurrenceRow> {
    if (page.isEmpty()) return emptyList()
    val marks = page.joinToString(",") { "?" }
    val ids = page.map { it.id }
    val summaries = HashMap<Long, EntitySummary>()
    fun entityOf(id: Long?): EntitySummary? = id?.let { summaries.getOrPut(it) { summary(connection, it) } }
    val candidates = connection.query(
        "SELECT c.occurrence_id, e.key, e.kind, e.display, e.external, e.name FROM occurrence_candidate c JOIN entity e ON e.id = c.entity_id WHERE c.occurrence_id IN ($marks) ORDER BY c.occurrence_id, e.id",
        ids
    ) { it.getLong(1) to EntitySummary(it.getString(2), it.getString(3), it.getString(4), it.getInt(5) == 1, it.getString(6)) }
        .groupBy({ it.first }, { it.second })
    val arguments = connection.query(
        """SELECT a.occurrence_id, a.ordinal, a.label, e.key, e.kind, e.display, e.external, a.start_line, a.start_col, a.end_line, a.end_col,
                  a.value_kind, a.value, e.name
           FROM call_argument a LEFT JOIN entity e ON e.id = a.parameter_entity_id WHERE a.occurrence_id IN ($marks) ORDER BY a.occurrence_id, a.ordinal""",
        ids
    ) {
        it.getLong(1) to CallArgument(it.getInt(2), it.getString(3),
            it.getString(4)?.let { k -> EntitySummary(k, it.getString(5), it.getString(6), it.getInt(7) == 1, it.getString(14)) }, span(it, 8),
            it.getString(12), it.getString(13))
    }.groupBy({ it.first }, { it.second })
    val written = connection.query(
        "SELECT occurrence_id, row_number, value_kind, value FROM written_value WHERE occurrence_id IN ($marks) ORDER BY occurrence_id, row_number", ids
    ) { it.getLong(1) to WrittenValue(it.getInt(2), it.getString(3), it.getString(4)) }.groupBy({ it.first }, { it.second })
    val byId = page.associateBy { it.id }
    return connection.query(
        """SELECT o.id, f.path, o.start_line, o.start_col, o.end_line, o.end_col, o.written, o.roles, o.status, o.reason, o.origin,
                  o.entity_id, o.container_entity_id, u.kind, u.name, s.kind
           FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN unit u ON u.id = o.unit_id
           LEFT JOIN sql_statement s ON s.id = o.statement_id
           WHERE o.id IN ($marks) ORDER BY o.id""",
        ids
    ) { rs ->
        val id = rs.getLong(1)
        val hit = byId.getValue(id)
        val entityId = (rs.getObject(12) as Number?)?.toLong()
        val containerId = (rs.getObject(13) as Number?)?.toLong()
        OccurrenceRow(
            id, rs.getString(2), span(rs, 3), rs.getString(7), roles(rs.getString(8)), rs.getString(9), rs.getString(10), rs.getString(11),
            entityOf(hit.matched), hit.viaCandidate, hit.viaView, null, null, UnitRef(rs.getString(14), rs.getString(15)), rs.getString(16),
            candidates[id].orEmpty(), arguments[id].orEmpty(), written[id].orEmpty()
        ).let { it.copy(entity = entityOf(entityId), container = entityOf(containerId)) }
    }
}

internal fun summary(connection: Connection, id: Long): EntitySummary =
    connection.query("SELECT key, kind, display, external, name FROM entity WHERE id = ?", listOf(id), ::entitySummary).single()

internal fun entitySummary(rs: ResultSet) = EntitySummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1, rs.getString(5))

internal fun span(rs: ResultSet, first: Int) = Span(rs.getInt(first), rs.getInt(first + 1), rs.getInt(first + 2), rs.getInt(first + 3))

internal fun roles(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split(',')

internal fun tally(values: List<String>): List<Count> = values.groupingBy { it }.eachCount().toSortedMap().map { Count(it.key, it.value.toLong()) }
