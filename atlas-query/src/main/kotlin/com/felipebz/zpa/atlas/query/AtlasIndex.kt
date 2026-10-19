//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import org.sqlite.ProgressHandler
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Duration

/**
 * Read-only queries over one index file. No connection is kept: each call opens a read-only connection and closes it,
 * so the indexer can replace the file between requests (on Windows a move fails while the file is open).
 *
 * @param sourcesRoot the folder the indexed paths are relative to, or null when sources are not available
 * @param queryTimeout how long an occurrence query may run before it is interrupted ([QueryTimeoutException])
 */
class AtlasIndex(val database: Path, val sourcesRoot: Path?, private val queryTimeout: Duration = DEFAULT_QUERY_TIMEOUT) {

    fun run(): Answer<RunSummary> = read { connection, _ ->
        val (version, root, started) = connection.query("SELECT schema_version, sources_root, started_at FROM run") {
            Triple(it.getInt(1), it.getString(2), it.getString(3))
        }.single()
        RunSummary(
            version, root, started,
            counts(connection, "SELECT parse_status, count(*) FROM source_file GROUP BY parse_status ORDER BY parse_status"),
            counts(connection, "SELECT status, count(*) FROM occurrence GROUP BY status ORDER BY status"),
            counts(connection, "SELECT kind, count(*) FROM coverage_note GROUP BY kind ORDER BY kind")
        )
    }

    /**
     * Without a dot, [q] is a case-insensitive prefix of the entity name. With a dot, the last segment is a name
     * prefix and the segments before it name the parent chain (the leftmost may be the schema); when nothing
     * matches, [q] is a case-insensitive substring of the display. Exact name matches first, then declared before
     * external, then display.
     */
    fun searchEntities(q: String, kind: String? = null, includeExternal: Boolean = true, limit: Int = 50): Answer<List<SearchHit>> {
        if (q.isBlank()) throw InvalidQueryException("The search text must not be empty")
        if (limit !in 1..200) throw InvalidQueryException("limit must be between 1 and 200")
        if (kind != null && kind !in ENTITY_KINDS) throw InvalidQueryException("Unknown entity kind: $kind")
        val text = q.trim()
        val segments = text.split('.')
        return read { connection, _ ->
            fun search(joins: String, match: String, parameters: List<Any?>) = connection.query(
                """SELECT e.key, e.kind, e.display, e.external,
                          (SELECT count(*) FROM occurrence o WHERE o.entity_id = e.id AND o.roles NOT IN ('DEFINITION', 'DECLARATION')), e.name
                   FROM entity e $joins
                   WHERE $match ${if (kind != null) "AND e.kind = ?" else ""} ${if (includeExternal) "" else "AND e.external = 0"}
                   ORDER BY CASE WHEN e.name = ? COLLATE NOCASE THEN 0 ELSE 1 END, e.external, e.display, e.key
                   LIMIT ?""",
                parameters + listOfNotNull(kind, text, limit)
            ) { SearchHit(it.getString(1), it.getString(2), it.getString(3), it.getInt(4) == 1, it.getLong(5), it.getString(6)) }

            if (segments.size == 1) return@read search("", "e.name LIKE ? ESCAPE '\\'", listOf("${escapeLike(text)}%"))
            val structural = if (segments.size > OccurrenceQuerySql.MAX_SEGMENTS || segments.any { it.isEmpty() }) {
                emptyList()
            } else {
                // The last segment is a name prefix, the others name the parents exactly.
                val (joins, match, parameters) = OccurrenceQuerySql.parentChain("e", segments) { column, segment, last ->
                    if (last) "$column LIKE ? ESCAPE '\\'" to listOf("${escapeLike(segment)}%") else "$column = ? COLLATE NOCASE" to listOf(segment)
                }
                search(joins, match, parameters)
            }
            structural.ifEmpty { search("", "e.display LIKE ? ESCAPE '\\'", listOf("%${escapeLike(text)}%")) }
        }
    }

    fun entity(key: String): Answer<EntityDetail?> = read { connection, _ ->
        val row = connection.query(
            "SELECT id, key, kind, schema_name, name, display, external, parent_id FROM entity WHERE key = ?", listOf(key)
        ) { Entity(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getInt(7) == 1, it.getObject(8) as Number?) }
            .singleOrNull() ?: return@read null
        val parent = row.parentId?.let { summary(connection, it.toLong()) }
        val children = connection.query(
            """SELECT c.key, c.kind, c.display, c.external, c.name,
                      (SELECT count(*) FROM occurrence o WHERE o.entity_id = c.id AND o.roles NOT IN ('DEFINITION', 'DECLARATION'))
               FROM relation r JOIN entity c ON c.id = r.to_id
               WHERE r.from_id = ? AND r.kind = 'CONTAINS' ORDER BY c.kind, c.name, c.key""", listOf(row.id)
        ) { Member(entitySummary(it), it.getLong(6)) }
        // VIEW_BASE edges have their own lists (baseColumns, viewColumns).
        val relations = connection.query(
            """SELECT r.kind, 'OUT', t.key, t.kind, t.display, t.external, t.name FROM relation r JOIN entity t ON t.id = r.to_id
               WHERE r.from_id = ? AND r.kind NOT IN ('CONTAINS', 'VIEW_BASE')
               UNION ALL
               SELECT r.kind, 'IN', f.key, f.kind, f.display, f.external, f.name FROM relation r JOIN entity f ON f.id = r.from_id
               WHERE r.to_id = ? AND r.kind NOT IN ('CONTAINS', 'VIEW_BASE')
               ORDER BY 1, 2, 3""", listOf(row.id, row.id)
        ) { RelationLink(it.getString(1), it.getString(2), EntitySummary(it.getString(3), it.getString(4), it.getString(5), it.getInt(6) == 1, it.getString(7))) }
        val definitions = connection.query(
            """SELECT f.path, o.start_line, o.start_col, o.end_line, o.end_col, o.roles, o.status, o.written
               FROM occurrence o JOIN source_file f ON f.id = o.file_id
               WHERE o.entity_id = ? AND o.roles IN ('DEFINITION', 'DECLARATION') ORDER BY o.id""", listOf(row.id)
        ) { Location(it.getString(1), span(it, 2), roles(it.getString(6)), it.getString(7), it.getString(8)) }
        val overloads = if (row.kind == "PROCEDURE" || row.kind == "FUNCTION") {
            connection.query(
                """SELECT key, kind, display, external, name FROM entity
                   WHERE parent_id IS ? AND name = ? AND kind = ? ORDER BY key""", listOf(row.parentId, row.name, row.kind), ::entitySummary
            ).map { Overload(it, isBody(it.key), it.key == row.key) }
        } else {
            emptyList()
        }
        val usages = connection.query(
            "SELECT roles, status FROM occurrence WHERE entity_id = ? AND roles NOT IN ('DEFINITION', 'DECLARATION')", listOf(row.id)
        ) { it.getString(1) to it.getString(2) }
        val asCandidate = connection.query("SELECT count(*) FROM occurrence_candidate WHERE entity_id = ?", listOf(row.id)) { it.getLong(1) }.single()
        EntityDetail(
            row.key, row.kind, row.schema, row.name, row.display, row.external, parent, children, relations, definitions, overloads,
            UsageSummary(tally(usages.flatMap { roles(it.first) }), tally(usages.map { it.second }), asCandidate),
            triggerEvents(connection, row.id, row.kind, row.parentId?.toLong()),
            if (row.kind in VIEW_KINDS) viewReads(connection, row.id) else emptyList(),
            if (row.kind in VIEWED_KINDS) viewsReading(connection, row.id) else emptyList(),
            if (row.kind == "COLUMN") viewBase(connection, row.id, outgoing = true) else emptyList(),
            if (row.kind == "COLUMN") viewBase(connection, row.id, outgoing = false) else emptyList()
        )
    }

    /** One hop of `VIEW_BASE` edges of a column: the columns it comes from ([outgoing]), or the view columns coming from it. */
    private fun viewBase(connection: Connection, columnId: Long, outgoing: Boolean): List<EntitySummary> {
        val (near, far) = if (outgoing) "from_id" to "to_id" else "to_id" to "from_id"
        return connection.query(
            """SELECT e.key, e.kind, e.display, e.external, e.name FROM relation r JOIN entity e ON e.id = r.$far
               WHERE r.$near = ? AND r.kind = 'VIEW_BASE' ORDER BY e.key""", listOf(columnId), ::entitySummary
        )
    }

    // Both derive from the SQL_SELECT occurrences: no relation rows are stored for them.
    private fun viewReads(connection: Connection, viewId: Long): List<EntitySummary> = connection.query(
        """SELECT DISTINCT e.key, e.kind, e.display, e.external, e.name FROM occurrence o JOIN entity e ON e.id = o.entity_id
           WHERE o.container_entity_id = ? AND (',' || o.roles || ',') LIKE '%,SQL_SELECT,%' ORDER BY e.key""", listOf(viewId), ::entitySummary
    )

    private fun viewsReading(connection: Connection, relationId: Long): List<EntitySummary> = connection.query(
        """SELECT DISTINCT v.key, v.kind, v.display, v.external, v.name FROM occurrence o JOIN entity v ON v.id = o.container_entity_id
           WHERE o.entity_id = ? AND v.kind IN ($VIEW_KINDS_SQL) AND (',' || o.roles || ',') LIKE '%,SQL_SELECT,%' ORDER BY v.key""", listOf(relationId), ::entitySummary
    )

    /** The trigger events [EntityDetail.triggerEvents] lists for an entity of [kind]. */
    private fun triggerEvents(connection: Connection, id: Long, kind: String, parentId: Long?): List<TriggerEvent> {
        val select = """SELECT t.key, t.kind, t.display, t.external, e.timing, e.level, e.event, c.key, c.kind, c.display, c.external, t.name, c.name
               FROM trigger_event e JOIN entity t ON t.id = e.trigger_id LEFT JOIN entity c ON c.id = e.column_id"""
        val order = " ORDER BY t.key, e.event, e.timing, c.key"
        val map = { rs: ResultSet ->
            TriggerEvent(EntitySummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1, rs.getString(12)), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8)?.let { EntitySummary(it, rs.getString(9), rs.getString(10), rs.getInt(11) == 1, rs.getString(13)) })
        }
        return when (kind) {
            "TRIGGER" -> connection.query("$select WHERE e.trigger_id = ?$order", listOf(id), map)
            "TABLE", "VIEW", "MATERIALIZED_VIEW", "OBJECT_TABLE", "UNKNOWN_RELATION" -> connection.query(
                "$select WHERE e.trigger_id IN (SELECT from_id FROM relation WHERE kind = 'TRIGGER_ON' AND to_id = ?)$order", listOf(id), map)
            // UPDATE OF this column, or an UPDATE without column list of a trigger on the column's relation. A NULL column
            // is also an unresolved UPDATE OF column: a trigger with a resolved one has a column list.
            "COLUMN" -> if (parentId == null) emptyList() else connection.query(
                """$select WHERE e.event = 'UPDATE' AND (e.column_id = ? OR (e.column_id IS NULL
                     AND e.trigger_id IN (SELECT from_id FROM relation WHERE kind = 'TRIGGER_ON' AND to_id = ?)
                     AND NOT EXISTS (SELECT 1 FROM trigger_event o WHERE o.trigger_id = e.trigger_id AND o.event = 'UPDATE' AND o.column_id IS NOT NULL)))$order""",
                listOf(id, parentId), map)
            else -> emptyList()
        }
    }

    /**
     * "Who uses" [key]: occurrences of the entities in [scope] (the entity itself, plus `IMPLEMENTS` partners in
     * either direction, plus transitive `CONTAINS` descendants, plus with [Scope.VIEWS] transitively the view columns
     * whose `VIEW_BASE` edges reach one of them and the views whose query reads a table, view or object table among
     * them), and with [includeCandidates] the `AMBIGUOUS` occurrences where one of them is a candidate. What
     * [Scope.VIEWS] adds gets no children: a join view's columns from another relation stay out. An occurrence hit
     * several ways reports one hit: a direct one before a candidate one, one not added by [Scope.VIEWS] before one
     * added by it ([OccurrenceRow.viaView]). Rows are in occurrence id order, which is source order. Facets count the
     * whole filtered set: roles under the status filter only, statuses under the role filter only.
     */
    fun occurrences(
        key: String,
        roles: Set<String>? = null,
        statuses: Set<String>? = null,
        scope: Set<Scope> = setOf(Scope.SELF),
        includeCandidates: Boolean = true,
        after: Long? = null,
        limit: Int = 100
    ): Answer<OccurrencePage?> {
        if (limit !in 1..500) throw InvalidQueryException("limit must be between 1 and 500")
        roles?.firstOrNull { it !in ROLES }?.let { throw InvalidQueryException("Unknown role: $it") }
        statuses?.firstOrNull { it !in STATUSES }?.let { throw InvalidQueryException("Unknown status: $it") }
        val roleFilter = roles?.ifEmpty { null } ?: USAGE_ROLES
        return read { connection, _ ->
            if (connection.query("SELECT 1 FROM entity WHERE key = ?", listOf(key)) { 1 }.isEmpty()) return@read null
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
            OccurrencePage(rows(connection, page), nextAfter, filtered.size.toLong(), facets)
        }
    }

    /**
     * The values an entity receives: for a `PARAMETER`, the arguments bound to it (and, for a parameter of a body
     * subprogram, to the same-named parameter of its specification, where calls bind); for a `COLUMN`, the values its
     * writes write. Grouped by value kind and value, most frequent first; `BIND` and `EXPRESSION` are one group each.
     * Empty for any other kind; null when there is no such entity.
     */
    fun values(key: String): Answer<List<ValueGroup>?> = read { connection, _ ->
        val source = valueSource(connection, key) ?: return@read null
        connection.query(
            """SELECT value_kind, CASE WHEN value_kind IN ('BIND', 'EXPRESSION') THEN NULL ELSE value END AS shown, count(*) AS n
               FROM (${source.sql}) GROUP BY value_kind, shown ORDER BY n DESC, value_kind, shown""", source.parameters
        ) { ValueGroup(it.getString(1), it.getString(2), it.getLong(3)) }
    }

    /**
     * The calls or writes that give [key] the value [value] (of [valueKind]; ignored for `NULL`, `BIND`, `EXPRESSION` and
     * `DEFAULT`), as [values] groups them: a page of occurrences in source order, as "who uses" rows. Null when there is
     * no such entity.
     */
    fun valueSites(key: String, valueKind: String, value: String?, after: Long? = null, limit: Int = 100): Answer<OccurrencePage?> {
        if (limit !in 1..500) throw InvalidQueryException("limit must be between 1 and 500")
        if (valueKind !in VALUE_KINDS) throw InvalidQueryException("Unknown value kind: $valueKind")
        val anyValue = valueKind in VALUES_UNSHOWN
        if (!anyValue && value == null) throw InvalidQueryException("Missing parameter: value")
        return read { connection, _ ->
            val source = valueSource(connection, key) ?: return@read null
            val hits = connection.query(
                """SELECT DISTINCT o.id, o.entity_id, o.roles, o.status FROM (${source.sql}) v JOIN occurrence o ON o.id = v.occurrence_id
                   WHERE v.value_kind = ? AND (? OR v.value = ?) ORDER BY o.id""", source.parameters + listOf(valueKind, anyValue, value)
            ) { Hit(it.getLong(1), it.getLong(2), false, false, roles(it.getString(3)), it.getString(4)) }
            val remaining = hits.filter { after == null || it.id > after }
            val page = remaining.take(limit)
            val nextAfter = if (remaining.size > limit) page.last().id else null
            OccurrencePage(rows(connection, page), nextAfter, hits.size.toLong(),
                Facets(tally(hits.flatMap { it.roles }), tally(hits.map { it.status })))
        }
    }

    /** The rows `(occurrence_id, value_kind, value)` of the values [values] reads for an entity. */
    private class ValueSource(val sql: String, val parameters: List<Any?>)

    /** The values of the entity [key] by its kind; null when there is no such entity. */
    private fun valueSource(connection: Connection, key: String): ValueSource? {
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

    /**
     * The lines around [line] of an indexed file, or null when sources are not available (no root, or no file).
     *
     * @throws InvalidQueryException when [path] is not a file of the index or leaves the sources root
     */
    fun source(path: String, line: Int, context: Int = 3): Answer<SourceSnippet?> {
        if (line < 1) throw InvalidQueryException("line must be at least 1")
        if (context !in 0..50) throw InvalidQueryException("context must be between 0 and 50")
        return read { connection, _ ->
            val file = sourceFile(connection, path)
            val text = readSource(file) ?: return@read null
            val first = (line - context).coerceAtLeast(1)
            val last = (line + context).coerceAtMost(text.lines.size)
            SourceSnippet(path, (first..last).map { SourceLine(it, text.lines[it - 1]) }, text.stale)
        }
    }

    /**
     * The whole of an indexed file with its occurrences at their name spans, or null when sources are not available.
     * A stale file has no occurrences: their spans would point at the wrong text.
     *
     * @throws InvalidQueryException when [path] is not a file of the index or leaves the sources root
     */
    fun file(path: String): Answer<FileView?> = read { connection, _ ->
        val file = sourceFile(connection, path)
        val text = readSource(file) ?: return@read null
        if (text.stale) return@read FileView(path, true, text.lines, emptyList(), emptyList())
        val entityIndexes = LinkedHashMap<Long, Int>()
        fun indexOf(id: Long) = entityIndexes.getOrPut(id) { entityIndexes.size }
        val candidates = connection.query(
            "SELECT c.occurrence_id, c.entity_id FROM occurrence o JOIN occurrence_candidate c ON c.occurrence_id = o.id WHERE o.file_id = ? ORDER BY c.occurrence_id, c.entity_id",
            listOf(file.id)
        ) { it.getLong(1) to it.getLong(2) }.groupBy({ it.first }, { it.second })
        // The name span when it was recorded apart, else the span; read through occurrence_file_line.
        val occurrences = connection.query(
            """SELECT o.id, coalesce(o.name_start_line, o.start_line), coalesce(o.name_start_col, o.start_col),
                      coalesce(o.name_end_line, o.end_line), coalesce(o.name_end_col, o.end_col), o.roles, o.status, o.reason, o.entity_id
               FROM occurrence o WHERE o.file_id = ? ORDER BY o.start_line, o.id""",
            listOf(file.id)
        ) { rs ->
            val entityId = (rs.getObject(9) as Number?)?.toLong()
            FileOccurrence(listOf(rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)), roles(rs.getString(6)), rs.getString(7), rs.getString(8),
                entityId?.let(::indexOf), candidates[rs.getLong(1)].orEmpty().map(::indexOf))
        }
        val summaries = HashMap<Long, EntitySummary>()
        for (chunk in entityIndexes.keys.chunked(500)) {
            connection.query("SELECT id, key, kind, display, external, name FROM entity WHERE id IN (${chunk.joinToString(",") { "?" }})", chunk) {
                it.getLong(1) to EntitySummary(it.getString(2), it.getString(3), it.getString(4), it.getInt(5) == 1, it.getString(6))
            }.forEach { summaries[it.first] = it.second }
        }
        FileView(path, false, text.lines, entityIndexes.keys.map { summaries.getValue(it) }, occurrences)
    }

    // ---- the occurrence query -----------------------------------------------------------------------------------

    /**
     * A page of the occurrences that [query] matches, in occurrence id order (source order), filtered and paged in SQL.
     * With [summary], [OccurrencePage.total] and the facets count the whole matching set; unlike "who uses", every
     * facet counts with every filter applied, its own included. Without it, total is -1 and the facets are empty:
     * [querySummary] gives them in a request of its own.
     */
    fun queryOccurrences(query: OccurrenceQuery, after: Long? = null, limit: Int = 100, summary: Boolean = true): Answer<OccurrencePage> {
        if (limit !in 1..500) throw InvalidQueryException("limit must be between 1 and 500")
        validate(query)
        return read(timed = true) { connection, _ ->
            val sql = sql(connection, query)
            val found = connection.query(
                """${sql.with}
                   SELECT o.id, ${sql.matched}, ${sql.via}, o.roles, o.status FROM ${sql.from}
                   ${sql.where("o.id > ?")} ORDER BY o.id LIMIT ?""",
                sql.parameters + listOf(after ?: 0L, limit + 1)
            ) { Hit(it.getLong(1), (it.getObject(2) as Number?)?.toLong(), it.getInt(3) == 1, false, roles(it.getString(4)), it.getString(5)) }
            val page = found.take(limit)
            val nextAfter = if (found.size > limit) page.last().id else null
            val (total, facets) = if (summary) summary(connection, sql) else -1L to Facets(emptyList(), emptyList())
            OccurrencePage(rows(connection, page), nextAfter, total, facets)
        }
    }

    /** The number of occurrences [query] matches and its facets, as [queryOccurrences] with summary gives them. */
    fun querySummary(query: OccurrenceQuery): Answer<QuerySummary> {
        validate(query)
        return read(timed = true) { connection, _ ->
            val (total, facets) = summary(connection, sql(connection, query))
            QuerySummary(total, facets)
        }
    }

    /**
     * The occurrences [query] matches, grouped by one to three [dimensions] and counted; the first [limit] groups by
     * count, descending, then by their values. `ROLE` counts an occurrence once under each of its roles that passes the
     * roles filter; `VALUE` groups the matching value rows (an occurrence counts once in each of its values' groups,
     * `BIND` and `EXPRESSION` one group each). `ENTITY` is the matched entity: the candidate for an occurrence matched
     * through one, null for an occurrence without entity.
     */
    fun groupOccurrences(query: OccurrenceQuery, dimensions: List<GroupDimension>, limit: Int = 100): Answer<GroupPage> {
        if (dimensions.size !in 1..3) throw InvalidQueryException("groupBy takes one to three dimensions")
        if (dimensions.toSet().size != dimensions.size) throw InvalidQueryException("A groupBy dimension is repeated")
        if (limit !in 1..1000) throw InvalidQueryException("limit must be between 1 and 1000")
        validate(query)
        return read(timed = true) { connection, _ ->
            val sql = sql(connection, query)
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
            GroupPage(dimensions.map { it.name }, groupValues(connection, dimensions, rawGroups), totalGroups)
        }
    }

    private class RawGroup(val values: List<Any?>, val count: Long)

    /** The values of [groups] for their [dimensions]: entities, units and files read once each. */
    private fun groupValues(connection: Connection, dimensions: List<GroupDimension>, groups: List<RawGroup>): List<OccurrenceGroup> {
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

    /** Rows of [select] (whose first column is the id) for [ids], read in chunks, by id. */
    private fun <T> byIds(connection: Connection, ids: Collection<Long>, select: String, idColumn: String = "id", map: (ResultSet) -> T): Map<Long, T> {
        val result = HashMap<Long, T>()
        for (chunk in ids.chunked(500)) {
            connection.query("$select WHERE $idColumn IN (${chunk.joinToString(",") { "?" }})", chunk) { it.getLong(1) to map(it) }
                .forEach { result[it.first] = it.second }
        }
        return result
    }

    /**
     * The total and the facets of the occurrences [sql] matches, counted while the rows stream by: on a large set a SQL
     * `GROUP BY` of these text columns spends most of its time sorting, a scan with a hash count does not. Each row is
     * read as one text, its six values joined by tabs, which the driver hands over in one call.
     */
    private fun summary(connection: Connection, sql: OccurrenceQuerySql): Pair<Long, Facets> {
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
    private fun sql(connection: Connection, query: OccurrenceQuery): OccurrenceQuerySql {
        fun id(key: String?, what: String) = key?.let {
            connection.query("SELECT id FROM entity WHERE key = ?", listOf(it)) { rs -> rs.getLong(1) }.singleOrNull()
                ?: throw InvalidQueryException("Unknown $what: $it")
        }
        return OccurrenceQuerySql(query, id(query.entity, "entity"), id(query.container, "container"))
    }

    private fun validate(query: OccurrenceQuery) {
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

    // ---- internals ----------------------------------------------------------------------------------------------

    private class SourceFile(val id: Long, val path: String, val sha256: String, val encoding: String)

    private class SourceText(val lines: List<String>, val stale: Boolean)

    private fun sourceFile(connection: Connection, path: String): SourceFile =
        connection.query("SELECT id, sha256, encoding FROM source_file WHERE path = ?", listOf(path)) {
            SourceFile(it.getLong(1), path, it.getString(2), it.getString(3))
        }.singleOrNull() ?: throw InvalidQueryException("Not a file of the index: $path")

    /** The lines of [file] under the sources root, or null when there is no root or no such file. */
    private fun readSource(file: SourceFile): SourceText? {
        val root = sourcesRoot?.toAbsolutePath()?.normalize() ?: return null
        val resolved = root.resolve(file.path).normalize()
        if (!resolved.startsWith(root)) throw InvalidQueryException("Path leaves the sources root: ${file.path}")
        if (!Files.isRegularFile(resolved)) return null
        val bytes = Files.readAllBytes(resolved)
        // The charset the indexer decoded this file with, so lines and columns match the stored spans.
        return SourceText(decode(bytes, file.encoding).lines(), sha256(bytes) != file.sha256)
    }

    private class Entity(val id: Long, val key: String, val kind: String, val schema: String?, val name: String, val display: String,
                         val external: Boolean, val parentId: Number?)

    private class Hit(val id: Long, val matched: Long?, val viaCandidate: Boolean, val viaView: Boolean, val roles: List<String>, val status: String)

    /** The hit an occurrence hit several ways reports: not added by [Scope.VIEWS] first, then direct before candidate. */
    private val hitPreference = compareBy<Hit>({ it.viaView }, { it.viaCandidate }, { it.matched })

    private fun rows(connection: Connection, page: List<Hit>): List<OccurrenceRow> {
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

    private fun summary(connection: Connection, id: Long): EntitySummary =
        connection.query("SELECT key, kind, display, external, name FROM entity WHERE id = ?", listOf(id), ::entitySummary).single()

    private fun entitySummary(rs: ResultSet) = EntitySummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1, rs.getString(5))

    private fun span(rs: ResultSet, first: Int) = Span(rs.getInt(first), rs.getInt(first + 1), rs.getInt(first + 2), rs.getInt(first + 3))

    private fun roles(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split(',')

    private fun tally(values: List<String>): List<Count> = values.groupingBy { it }.eachCount().toSortedMap().map { Count(it.key, it.value.toLong()) }

    private fun counts(connection: Connection, sql: String) = connection.query(sql) { Count(it.getString(1), it.getLong(2)) }

    private fun escapeLike(text: String) = OccurrenceQuerySql.escapeLike(text)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * One read-only connection for one request; the run stamp is read and the schema version checked first. With
     * [timed], the body is interrupted once [queryTimeout] has passed: `Statement.setQueryTimeout` does not stop a
     * statement that is running, a progress handler does (SQLite calls it every [PROGRESS_STEPS] virtual machine steps).
     */
    private fun <T> read(timed: Boolean = false, body: (Connection, RunStamp) -> T): Answer<T> {
        val config = SQLiteConfig().apply { setReadOnly(true) }
        config.createConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA query_only = 1") }
            val (version, stamp) = connection.query(
                "SELECT schema_version, finished_at, atlas_version, zpa_version, compilation_schema, metadata_origin FROM run"
            ) { it.getInt(1) to RunStamp(it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6)) }.single()
            if (version != SCHEMA_VERSION) throw SchemaMismatchException(version, SCHEMA_VERSION)
            if (!timed) return Answer(stamp, body(connection, stamp))
            val deadline = System.nanoTime() + queryTimeout.toNanos()
            ProgressHandler.setHandler(connection, PROGRESS_STEPS, object : ProgressHandler() {
                override fun progress(): Int = if (System.nanoTime() > deadline) 1 else 0
            })
            try {
                return Answer(stamp, body(connection, stamp))
            } catch (e: SQLiteException) {
                if (e.resultCode == SQLiteErrorCode.SQLITE_INTERRUPT) throw QueryTimeoutException(queryTimeout.toMillis() / 1000.0)
                throw e
            }
        }
    }

    private fun <T> Connection.query(sql: String, parameters: List<Any?> = emptyList(), map: (ResultSet) -> T): List<T> =
        prepareStatement(sql).use { statement ->
            statement.bind(parameters)
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }

    private fun PreparedStatement.bind(parameters: List<Any?>) {
        parameters.forEachIndexed { index, value ->
            when (value) {
                null -> setObject(index + 1, null)
                is Boolean -> setInt(index + 1, if (value) 1 else 0)
                is Int -> setInt(index + 1, value)
                is Long -> setLong(index + 1, value)
                is Number -> setLong(index + 1, value.toLong())
                else -> setString(index + 1, value.toString())
            }
        }
    }

    companion object {
        val DEFAULT_QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)

        private const val PROGRESS_STEPS = 1000

        /** The index schema version this module reads; it must equal the writer's `SCHEMA_VERSION`. */
        const val SCHEMA_VERSION = 12

        /** The relation kinds whose entity page lists the views that read them, and whose readers [Scope.VIEWS] follows. */
        val VIEWED_KINDS = setOf("TABLE", "VIEW", "MATERIALIZED_VIEW", "OBJECT_TABLE")

        /** The kinds whose query is read like a view's: the "views reading a relation" and [Scope.VIEWS] count them. */
        val VIEW_KINDS = setOf("VIEW", "MATERIALIZED_VIEW")
        private val VIEW_KINDS_SQL = VIEW_KINDS.joinToString { "'$it'" }
        private val VIEWED_KINDS_SQL = VIEWED_KINDS.joinToString { "'$it'" }

        /** Decodes an indexed file with the charset recorded for it (`source_file.encoding`); a leading BOM is dropped. */
        fun decode(bytes: ByteArray, encoding: String): String =
            String(bytes, java.nio.charset.Charset.forName(encoding)).let { if (it.startsWith('\uFEFF')) it.substring(1) else it }

        val ROLES = listOf(
            "DEFINITION", "DECLARATION", "READ", "WRITE", "CALL", "SQL_SELECT", "SQL_INSERT", "SQL_UPDATE", "SQL_DELETE",
            "SQL_MERGE", "SQL_LOCK", "TYPE_USE", "RAISE", "HANDLE", "IMPLICIT",
            "DDL_CREATE", "DDL_ALTER", "DDL_DROP", "DDL_TRUNCATE", "DDL_ANALYZE"
        )
        val VALUE_KINDS = listOf("STRING", "NUMBER", "NULL", "BOOLEAN", "NAME", "BIND", "EXPRESSION", "DEFAULT")

        val UNIT_KINDS = listOf(
            "PACKAGE", "PACKAGE_BODY", "PROCEDURE", "FUNCTION", "TRIGGER", "TYPE", "TYPE_BODY", "VIEW", "MATERIALIZED_VIEW",
            "ANONYMOUS_BLOCK", "SQL_STATEMENT", "UNPARSED", "FORMS_TRIGGER", "FORMS_PROGRAM_UNIT"
        )
        val ORIGINS = listOf("STATIC", "DYNAMIC_LITERAL", "FORMS_IMPLICIT", "HEURISTIC")
        val STATEMENT_KINDS = listOf("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "LOCK", "DYNAMIC", "DDL")

        /** The value kinds whose arguments form one group whatever they pass. */
        private val VALUES_UNSHOWN = setOf("NULL", "BIND", "EXPRESSION", "DEFAULT")
        val USAGE_ROLES: Set<String> = (ROLES - setOf("DEFINITION", "DECLARATION")).toSet()
        val STATUSES = listOf("RESOLVED", "UNVERIFIED", "AMBIGUOUS", "UNRESOLVED", "EXTERNAL", "DYNAMIC", "PARTIAL_SOURCE")
        val ENTITY_KINDS = listOf(
            "SCHEMA", "TABLE", "VIEW", "MATERIALIZED_VIEW", "OBJECT_TABLE", "COLUMN", "SYNONYM", "SEQUENCE", "TRIGGER", "PACKAGE", "PROCEDURE",
            "FUNCTION", "PARAMETER", "TYPE", "RECORD_FIELD", "PACKAGE_VARIABLE", "PACKAGE_CONSTANT", "EXCEPTION", "CURSOR",
            "LOCAL_VARIABLE", "LOCAL_CONSTANT", "LABEL", "FORMS_MODULE", "FORMS_BLOCK", "FORMS_ITEM", "FORMS_TRIGGER",
            "FORMS_PROGRAM_UNIT", "FORMS_LIBRARY", "UNKNOWN_RELATION"
        )

        fun isBody(key: String) = key.endsWith("@BODY") || key.substringBeforeLast('#').endsWith("@BODY")
    }
}
