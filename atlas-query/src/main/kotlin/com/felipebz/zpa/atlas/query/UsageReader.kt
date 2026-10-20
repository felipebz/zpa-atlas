//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import java.sql.Connection

/** "Who uses" an entity and the values it receives, over one open connection. */
internal class UsageReader(private val connection: Connection) {

    /** [AtlasIndex.occurrences] after its checks: [roleFilter] is the roles asked for, else every usage role. */
    fun occurrences(
        key: String,
        roleFilter: Set<String>,
        statuses: Set<String>?,
        scope: Set<Scope>,
        includeCandidates: Boolean,
        after: Long?,
        limit: Int
    ): OccurrencePage? {
        if (connection.query("SELECT 1 FROM entity WHERE key = ?", listOf(key)) { 1 }.isEmpty()) return null
        // UNION, not UNION ALL, in the recursive CTEs: an entity already in the set is not followed again (no cycles).
        val hits = connection.query(
            """WITH RECURSIVE
                 self(id) AS (SELECT id FROM entity WHERE key = ?),
                 linked(id) AS (
                   SELECT id FROM self
                   UNION SELECT r.to_id FROM relation r JOIN self s ON r.from_id = s.id WHERE ? AND r.kind = 'IMPLEMENTS'
                   UNION SELECT r.from_id FROM relation r JOIN self s ON r.to_id = s.id WHERE ? AND r.kind = 'IMPLEMENTS'),
                 scope(id) AS (
                   SELECT id FROM linked
                   UNION SELECT r.to_id FROM relation r JOIN scope s ON r.from_id = s.id WHERE ? AND r.kind = 'CONTAINS'),
                 viewed(id) AS (
                   SELECT id FROM scope
                   UNION SELECT r.from_id FROM viewed v JOIN relation r ON r.to_id = v.id WHERE ? AND r.kind = 'VIEW_BASE'
                   UNION SELECT o.container_entity_id FROM viewed v JOIN entity t ON t.id = v.id
                     JOIN occurrence o ON o.entity_id = v.id JOIN entity w ON w.id = o.container_entity_id
                     WHERE ? AND t.kind IN ($VIEWED_KINDS_SQL) AND w.kind IN ($VIEW_KINDS_SQL) AND (',' || o.roles || ',') LIKE '%,SQL_SELECT,%'),
                 hits(oid, matched, via_candidate) AS (
                   SELECT o.id, o.entity_id, 0 FROM occurrence o WHERE o.entity_id IN (SELECT id FROM viewed)
                   UNION ALL
                   SELECT c.occurrence_id, c.entity_id, 1 FROM occurrence_candidate c WHERE ? AND c.entity_id IN (SELECT id FROM viewed))
               SELECT h.oid, h.matched, h.via_candidate, h.matched NOT IN (SELECT id FROM scope), o.roles, o.status
               FROM hits h JOIN occurrence o ON o.id = h.oid
               ORDER BY h.oid""",
            listOf(key, Scope.IMPLEMENTATIONS in scope, Scope.IMPLEMENTATIONS in scope, Scope.CHILDREN in scope,
                Scope.VIEWS in scope, Scope.VIEWS in scope, includeCandidates)
        ) { Hit(it.getLong(1), it.getLong(2), it.getInt(3) == 1, it.getInt(4) == 1, roles(it.getString(5)), it.getString(6)) }
            .groupBy { it.id }.values.map { it.minWith(hitPreference) }

        fun roleMatch(hit: Hit) = hit.roles.any { it in roleFilter }
        fun statusMatch(hit: Hit) = statuses.isNullOrEmpty() || hit.status in statuses
        val filtered = hits.filter { roleMatch(it) && statusMatch(it) }
        val facets = Facets(
            tally(hits.filter { statusMatch(it) }.flatMap { hit -> hit.roles.filter { it in USAGE_ROLES } }),
            tally(hits.filter { roleMatch(it) }.map { it.status })
        )
        val remaining = filtered.filter { after == null || it.id > after }
        val page = remaining.take(limit)
        val nextAfter = if (remaining.size > limit) page.last().id else null
        return OccurrencePage(rows(connection, page), nextAfter, filtered.size.toLong(), facets)
    }

    fun values(key: String): List<ValueGroup>? {
        val source = valueSource(key) ?: return null
        return connection.query(
            """SELECT value_kind, CASE WHEN value_kind IN ('BIND', 'EXPRESSION') THEN NULL ELSE value END AS shown, count(*) AS n
               FROM (${source.sql}) GROUP BY value_kind, shown ORDER BY n DESC, value_kind, shown""", source.parameters
        ) { ValueGroup(it.getString(1), it.getString(2), it.getLong(3)) }
    }

    /** [AtlasIndex.valueSites] after its checks: with [anyValue], every value of [valueKind] matches. */
    fun valueSites(key: String, valueKind: String, value: String?, anyValue: Boolean, after: Long?, limit: Int): OccurrencePage? {
        val source = valueSource(key) ?: return null
        val hits = connection.query(
            """SELECT DISTINCT o.id, o.entity_id, o.roles, o.status FROM (${source.sql}) v JOIN occurrence o ON o.id = v.occurrence_id
               WHERE v.value_kind = ? AND (? OR v.value = ?) ORDER BY o.id""", source.parameters + listOf(valueKind, anyValue, value)
        ) { Hit(it.getLong(1), it.getLong(2), false, false, roles(it.getString(3)), it.getString(4)) }
        val remaining = hits.filter { after == null || it.id > after }
        val page = remaining.take(limit)
        val nextAfter = if (remaining.size > limit) page.last().id else null
        return OccurrencePage(rows(connection, page), nextAfter, hits.size.toLong(),
            Facets(tally(hits.flatMap { it.roles }), tally(hits.map { it.status })))
    }

    /** The rows `(occurrence_id, value_kind, value)` of the values [values] reads for an entity. */
    private class ValueSource(val sql: String, val parameters: List<Any?>)

    /** The values of the entity [key] by its kind; null when there is no such entity. */
    private fun valueSource(key: String): ValueSource? {
        val (id, kind) = connection.query("SELECT id, kind FROM entity WHERE key = ?", listOf(key)) { it.getLong(1) to it.getString(2) }.singleOrNull() ?: return null
        return when (kind) {
            "PARAMETER" -> {
                val twins = connection.query(
                    """SELECT s.id FROM entity p JOIN relation r ON r.from_id = p.parent_id AND r.kind = 'IMPLEMENTS'
                       JOIN entity s ON s.parent_id = r.to_id AND s.name = p.name AND s.kind = 'PARAMETER'
                       WHERE p.id = ?""", listOf(id)
                ) { it.getLong(1) }
                val ids = listOf(id) + twins
                ValueSource("SELECT occurrence_id, value_kind, value FROM call_argument WHERE parameter_entity_id IN (${ids.joinToString(",") { "?" }})", ids)
            }
            // The column's own writes, and the writes to every view column that reaches it: a chain of VIEW_BASE
            // relations whose columns each belong to a VIEW (a materialized view keeps its own rows), have exactly one
            // base (a computed or UNION column is not written in valid code), and whose view has no INSTEAD OF trigger
            // (Oracle then runs the trigger, whose own writes are indexed in it).
            "COLUMN" -> ValueSource(
                """WITH RECURSIVE reach(id) AS (
                     SELECT ?
                     UNION
                     SELECT r.from_id FROM reach JOIN relation r ON r.to_id = reach.id AND r.kind = 'VIEW_BASE'
                     JOIN entity c ON c.id = r.from_id JOIN entity v ON v.id = c.parent_id
                     WHERE v.kind = 'VIEW'
                       AND (SELECT count(*) FROM relation b WHERE b.from_id = c.id AND b.kind = 'VIEW_BASE') = 1
                       AND NOT EXISTS (SELECT 1 FROM relation t JOIN trigger_event e ON e.trigger_id = t.from_id
                                       WHERE t.to_id = v.id AND t.kind = 'TRIGGER_ON' AND e.timing = 'INSTEAD_OF'))
                   SELECT w.occurrence_id, w.value_kind, w.value FROM written_value w JOIN occurrence o ON o.id = w.occurrence_id
                   WHERE o.entity_id IN (SELECT id FROM reach)""", listOf(id)
            )
            else -> ValueSource("SELECT occurrence_id, value_kind, value FROM call_argument WHERE 0", emptyList())
        }
    }
}
