//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import java.sql.Connection

/**
 * A `CONTAINS` edge `r` that does not lead into a routine's own declarations: its local variables and constants, and the
 * types it declares (a type whose parent is not a package), with their fields. A package's or type's members are its
 * spec's and body's, never what its routines declare inside them: those stay out of "who uses" and "Impact".
 */
private const val NOT_ROUTINE_OWN = """AND NOT EXISTS (SELECT 1 FROM entity c JOIN entity p ON p.id = r.from_id WHERE c.id = r.to_id
        AND (c.kind IN ('LOCAL_VARIABLE', 'LOCAL_CONSTANT') OR c.kind = 'TYPE' AND p.kind <> 'PACKAGE'))"""

/** "Who uses" an entity and the values it receives, over one open connection. */
internal class UsageReader(private val connection: Connection) {

    /**
     * [AtlasIndex.occurrences] after its checks: [roleFilter] is [filter]'s roles, else every usage role; [side],
     * when set, keeps the rows on that side of [key] (the facets other than `bySide` count those rows only); with
     * [sides], the facets count both sides and each row's unit names its entity.
     */
    fun occurrences(
        key: String, roleFilter: Set<String>, filter: UsageFilter, side: UsageSide?, sides: Boolean, after: Long?, limit: Int
    ): OccurrencePage? {
        val (all, allFiltered) = usageHits(key, roleFilter, filter) ?: return null
        val statuses = filter.statuses
        fun sideMatch(hit: Hit) = side == null || hit.inside == (side == UsageSide.INSIDE)
        val hits = all.filter { sideMatch(it) }
        val filtered = allFiltered.filter { sideMatch(it) }
        fun roleMatch(hit: Hit) = hit.roles.any { it in roleFilter }
        fun statusMatch(hit: Hit) = statuses.isNullOrEmpty() || hit.status in statuses
        val facets = Facets(
            tally(hits.filter { statusMatch(it) }.flatMap { hit -> hit.roles.filter { it in USAGE_ROLES } }),
            tally(hits.filter { roleMatch(it) }.map { it.status }),
            bySide = if (sides) tally(allFiltered.map { if (it.inside) UsageSide.INSIDE.name else UsageSide.OUTSIDE.name }) else null
        )
        val remaining = filtered.filter { after == null || it.id > after }
        val page = remaining.take(limit)
        val nextAfter = if (remaining.size > limit) page.last().id else null
        return OccurrencePage(rows(connection, page, unitEntities = sides), nextAfter, filtered.size.toLong(), facets,
            usesGaps(key, Scope.IMPLEMENTATIONS in filter.scope, Scope.CHILDREN in filter.scope))
    }

    /**
     * The entities whose recorded uses may be incomplete: [key] itself, with [implementations] a parameter's partner
     * (whose uses the rows include), and with [children] its `CONTAINS` descendants.
     */
    private fun usesGaps(key: String, implementations: Boolean, children: Boolean): List<UsesGap>? = connection.query(
        """WITH RECURSIVE scope(id) AS (
               SELECT id FROM entity WHERE key = ?
               UNION SELECT p.id FROM entity e $PARAMETER_PARTNERS WHERE e.key = ? AND e.kind = 'PARAMETER' AND ?
               UNION SELECT r.to_id FROM relation r JOIN scope s ON r.from_id = s.id WHERE ? AND r.kind = 'CONTAINS' $NOT_ROUTINE_OWN)
           SELECT e.key, g.reasons FROM uses_gap g JOIN scope s ON s.id = g.entity_id JOIN entity e ON e.id = g.entity_id ORDER BY e.key""",
        listOf(key, key, implementations, children)
    ) { UsesGap(it.getString(1), it.getString(2).split(',')) }.ifEmpty { null }

    /** [AtlasIndex.groupUsages] after its checks: the rows [occurrences] lists, grouped. */
    fun groupUsages(key: String, roleFilter: Set<String>, filter: UsageFilter, dimensions: List<GroupDimension>, limit: Int): GroupPage? {
        val (_, filtered) = usageHits(key, roleFilter, filter) ?: return null
        return OccurrenceQueryReader(connection).groupHits(filtered, roleFilter, dimensions, limit)
    }

    /**
     * The hits of the scope of [filter] around [key], one per occurrence and kept by its path, then those that also pass
     * [roleFilter] (its roles, checked) and its statuses; null when there is no such entity.
     */
    internal fun usageHits(key: String, roleFilter: Set<String>, filter: UsageFilter): Pair<List<Hit>, List<Hit>>? {
        val (_, statuses, scope, includeCandidates, via) = filter
        if (connection.query("SELECT 1 FROM entity WHERE key = ?", listOf(key)) { 1 }.isEmpty()) return null
        // UNION, not UNION ALL, in the recursive CTEs: an entity already in the set is not followed again (no cycles).
        val hits = connection.query(
            """WITH RECURSIVE
                 self(id) AS (SELECT id FROM entity WHERE key = ?),
                 linked(id) AS (
                   SELECT id FROM self
                   UNION SELECT r.to_id FROM relation r JOIN self s ON r.from_id = s.id WHERE ? AND r.kind = 'IMPLEMENTS'
                   UNION SELECT r.from_id FROM relation r JOIN self s ON r.to_id = s.id WHERE ? AND r.kind = 'IMPLEMENTS'
                   -- A parameter's partner: the parameter of the same name of its routine's spec or body.
                   UNION SELECT p.id FROM self s JOIN entity e ON e.id = s.id AND e.kind = 'PARAMETER' $PARAMETER_PARTNERS WHERE ?),
                 scope(id) AS (
                   SELECT id FROM linked
                   UNION SELECT r.to_id FROM relation r JOIN scope s ON r.from_id = s.id WHERE ? AND r.kind = 'CONTAINS' $NOT_ROUTINE_OWN),
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
               SELECT h.oid, h.matched, h.via_candidate, h.matched NOT IN (SELECT id FROM scope), o.roles, o.status,
                      u.entity_id IS NOT NULL AND u.entity_id IN (SELECT id FROM self)
               FROM hits h JOIN occurrence o ON o.id = h.oid LEFT JOIN unit u ON u.id = o.unit_id
               ORDER BY h.oid""",
            listOf(key, Scope.IMPLEMENTATIONS in scope, Scope.IMPLEMENTATIONS in scope, Scope.IMPLEMENTATIONS in scope, Scope.CHILDREN in scope,
                Scope.VIEWS in scope, Scope.VIEWS in scope, includeCandidates)
        ) { Hit(it.getLong(1), it.getLong(2), it.getInt(3) == 1, it.getInt(4) == 1, roles(it.getString(5)), it.getString(6), it.getInt(7) == 1) }
            .groupBy { it.id }.values.map { it.minWith(hitPreference) }
            .filter { via == null || it.viaView == (via == UsagePath.THROUGH_VIEWS) }
        val filtered = hits.filter { hit -> hit.roles.any { it in roleFilter } && (statuses.isNullOrEmpty() || hit.status in statuses) }
        return hits to filtered
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

    companion object {
        /**
         * The kinds of write that go through views differently, by the INSTEAD OF triggers that stop them:
         * - [INSERT], [UPDATE], [DELETE]: a view with an INSTEAD OF trigger for that event takes the write (Oracle runs
         *   the trigger instead, and its own writes are indexed in it); a write of another event goes on to the base, as
         *   through a view without trigger. Each view of the chain is checked, from the written one down, so the first
         *   one with such a trigger takes the write.
         * - [MERGE]: a MERGE that Oracle does not refuse ([refusedMerge]) goes on to the base through every view of the
         *   chain, whatever INSTEAD OF triggers the views below its target have; it never runs one.
         * - [OTHER]: a write that is no INSERT or UPDATE (an assignment) stops at a view with any INSTEAD OF trigger.
         */
        internal enum class ViewWrite(val event: String?) { INSERT("INSERT"), UPDATE("UPDATE"), DELETE("DELETE"), MERGE(null), OTHER(null) }

        /**
         * The recursive step of a CTE [cte]`(id, base)` from base columns to the view columns a write of the kind [write]
         * to which writes them: a chain of VIEW_BASE relations whose columns each belong to a VIEW (a materialized view
         * keeps its own rows), have exactly one base (a computed or UNION column is not written in valid code), and whose
         * view has no INSTEAD OF trigger that stops [write] ([ViewWrite]). `base` is carried along.
         */
        internal fun writtenThroughViews(cte: String, write: ViewWrite): String {
            val insteadOf = when (write) {
                ViewWrite.MERGE -> ""
                else -> """
              AND NOT EXISTS (SELECT 1 FROM relation t JOIN trigger_event e ON e.trigger_id = t.from_id
                              WHERE t.to_id = v.id AND t.kind = 'TRIGGER_ON' AND e.timing = 'INSTEAD_OF'${write.event?.let { " AND e.event = '$it'" } ?: ""})"""
            }
            return """
            SELECT r.from_id, $cte.base FROM $cte JOIN relation r ON r.to_id = $cte.id AND r.kind = 'VIEW_BASE'
            JOIN entity c ON c.id = r.from_id JOIN entity v ON v.id = c.parent_id
            WHERE v.kind = 'VIEW'
              AND (SELECT count(*) FROM relation b WHERE b.from_id = c.id AND b.kind = 'VIEW_BASE') = 1$insteadOf"""
        }

        /**
         * A condition on the occurrence [alias]: its statement is a MERGE that Oracle refuses, one into a relation with
         * an INSTEAD OF trigger for the event of one of its clauses (`ORA-38106`). Such a MERGE writes nothing.
         */
        internal fun refusedMerge(alias: String) = """EXISTS (
            SELECT 1 FROM sql_statement s JOIN occurrence t ON t.statement_id = s.id
            JOIN relation r ON r.to_id = t.entity_id AND r.kind = 'TRIGGER_ON'
            JOIN trigger_event e ON e.trigger_id = r.from_id AND e.timing = 'INSTEAD_OF'
            WHERE s.id = $alias.statement_id AND s.kind = 'MERGE' AND instr(',' || t.roles || ',', ',SQL_MERGE,') > 0
              AND instr(',' || t.roles || ',', ',SQL_' || e.event || ',') > 0)"""
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
            // The column's own writes, and the writes to every view column that reaches it by the kind of write
            // ([ViewWrite]): a MERGE column through every view, an INSERT column through the views that let an INSERT
            // through, and so on. A MERGE Oracle refuses ([refusedMerge]) writes nothing through the view.
            "COLUMN" -> ValueSource(
                """WITH RECURSIVE
                     reach_insert(id, base) AS (SELECT ?1, ?1 UNION ${writtenThroughViews("reach_insert", ViewWrite.INSERT)}),
                     reach_update(id, base) AS (SELECT ?1, ?1 UNION ${writtenThroughViews("reach_update", ViewWrite.UPDATE)}),
                     reach_merge(id, base) AS (SELECT ?1, ?1 UNION ${writtenThroughViews("reach_merge", ViewWrite.MERGE)}),
                     reach_other(id, base) AS (SELECT ?1, ?1 UNION ${writtenThroughViews("reach_other", ViewWrite.OTHER)})
                   SELECT w.occurrence_id, w.value_kind, w.value FROM written_value w JOIN occurrence o ON o.id = w.occurrence_id
                   WHERE o.entity_id = ?1
                      OR (CASE WHEN EXISTS (SELECT 1 FROM sql_statement s WHERE s.id = o.statement_id AND s.kind = 'MERGE')
                                 THEN o.entity_id IN (SELECT id FROM reach_merge)
                               WHEN instr(',' || o.roles || ',', ',SQL_INSERT,') > 0 THEN o.entity_id IN (SELECT id FROM reach_insert)
                               WHEN instr(',' || o.roles || ',', ',SQL_UPDATE,') > 0 THEN o.entity_id IN (SELECT id FROM reach_update)
                               ELSE o.entity_id IN (SELECT id FROM reach_other) END
                          AND NOT ${refusedMerge("o")})""", listOf(id)
            )
            else -> ValueSource("SELECT occurrence_id, value_kind, value FROM call_argument WHERE 0", emptyList())
        }
    }
}

/**
 * The joins from a `PARAMETER` entity `e` to its partners `p`: the parameter of the same name of the spec or body its
 * routine `IMPLEMENTS`, or that implements it.
 */
internal const val PARAMETER_PARTNERS = """JOIN relation r ON r.kind = 'IMPLEMENTS' AND (r.from_id = e.parent_id OR r.to_id = e.parent_id)
    JOIN entity p ON p.parent_id = CASE WHEN r.from_id = e.parent_id THEN r.to_id ELSE r.from_id END AND p.kind = 'PARAMETER' AND p.name = e.name"""
