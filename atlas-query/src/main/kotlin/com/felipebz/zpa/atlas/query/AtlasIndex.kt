//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Read-only queries over one index file. No connection is kept: each call opens a read-only connection and closes it,
 * so the indexer can replace the file between requests (on Windows a move fails while the file is open).
 *
 * @param sourcesRoot the folder the indexed paths are relative to, or null when sources are not available
 */
class AtlasIndex(val database: Path, val sourcesRoot: Path?) {

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
     * Without a dot, [q] is a case-insensitive prefix of the entity name; with a dot, a case-insensitive substring
     * of its display. Exact name matches first, then declared before external, then display.
     */
    fun searchEntities(q: String, kind: String? = null, includeExternal: Boolean = true, limit: Int = 50): Answer<List<SearchHit>> {
        if (q.isBlank()) throw InvalidQueryException("The search text must not be empty")
        if (limit !in 1..200) throw InvalidQueryException("limit must be between 1 and 200")
        if (kind != null && kind !in ENTITY_KINDS) throw InvalidQueryException("Unknown entity kind: $kind")
        val text = escapeLike(q.trim())
        val match = if ('.' in q) "e.display LIKE ? ESCAPE '\\'" else "e.name LIKE ? ESCAPE '\\'"
        val pattern = if ('.' in q) "%$text%" else "$text%"
        return read { connection, _ ->
            connection.query(
                """SELECT e.key, e.kind, e.display, e.external,
                          (SELECT count(*) FROM occurrence o WHERE o.entity_id = e.id AND o.roles NOT IN ('DEFINITION', 'DECLARATION'))
                   FROM entity e
                   WHERE $match ${if (kind != null) "AND e.kind = ?" else ""} ${if (includeExternal) "" else "AND e.external = 0"}
                   ORDER BY CASE WHEN e.name = ? COLLATE NOCASE THEN 0 ELSE 1 END, e.external, e.display, e.key
                   LIMIT ?""",
                listOfNotNull(pattern, kind, q.trim(), limit)
            ) { SearchHit(it.getString(1), it.getString(2), it.getString(3), it.getInt(4) == 1, it.getLong(5)) }
        }
    }

    fun entity(key: String): Answer<EntityDetail?> = read { connection, _ ->
        val row = connection.query(
            "SELECT id, key, kind, schema_name, name, display, external, parent_id FROM entity WHERE key = ?", listOf(key)
        ) { Entity(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getInt(7) == 1, it.getObject(8) as Number?) }
            .singleOrNull() ?: return@read null
        val parent = row.parentId?.let { summary(connection, it.toLong()) }
        val children = connection.query(
            """SELECT c.key, c.kind, c.display, c.external FROM relation r JOIN entity c ON c.id = r.to_id
               WHERE r.from_id = ? AND r.kind = 'CONTAINS' ORDER BY c.kind, c.name, c.key""", listOf(row.id), ::entitySummary
        )
        // VIEW_BASE edges have their own lists (baseColumns, viewColumns).
        val relations = connection.query(
            """SELECT r.kind, 'OUT', t.key, t.kind, t.display, t.external FROM relation r JOIN entity t ON t.id = r.to_id
               WHERE r.from_id = ? AND r.kind NOT IN ('CONTAINS', 'VIEW_BASE')
               UNION ALL
               SELECT r.kind, 'IN', f.key, f.kind, f.display, f.external FROM relation r JOIN entity f ON f.id = r.from_id
               WHERE r.to_id = ? AND r.kind NOT IN ('CONTAINS', 'VIEW_BASE')
               ORDER BY 1, 2, 3""", listOf(row.id, row.id)
        ) { RelationLink(it.getString(1), it.getString(2), EntitySummary(it.getString(3), it.getString(4), it.getString(5), it.getInt(6) == 1)) }
        val definitions = connection.query(
            """SELECT f.path, o.start_line, o.start_col, o.end_line, o.end_col, o.roles, o.status, o.written
               FROM occurrence o JOIN source_file f ON f.id = o.file_id
               WHERE o.entity_id = ? AND o.roles IN ('DEFINITION', 'DECLARATION') ORDER BY o.id""", listOf(row.id)
        ) { Location(it.getString(1), span(it, 2), roles(it.getString(6)), it.getString(7), it.getString(8)) }
        val overloads = if (row.kind == "PROCEDURE" || row.kind == "FUNCTION") {
            connection.query(
                """SELECT key, kind, display, external FROM entity
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
            """SELECT e.key, e.kind, e.display, e.external FROM relation r JOIN entity e ON e.id = r.$far
               WHERE r.$near = ? AND r.kind = 'VIEW_BASE' ORDER BY e.key""", listOf(columnId), ::entitySummary
        )
    }

    // Both derive from the SQL_SELECT occurrences: no relation rows are stored for them.
    private fun viewReads(connection: Connection, viewId: Long): List<EntitySummary> = connection.query(
        """SELECT DISTINCT e.key, e.kind, e.display, e.external FROM occurrence o JOIN entity e ON e.id = o.entity_id
           WHERE o.container_entity_id = ? AND (',' || o.roles || ',') LIKE '%,SQL_SELECT,%' ORDER BY e.key""", listOf(viewId), ::entitySummary
    )

    private fun viewsReading(connection: Connection, relationId: Long): List<EntitySummary> = connection.query(
        """SELECT DISTINCT v.key, v.kind, v.display, v.external FROM occurrence o JOIN entity v ON v.id = o.container_entity_id
           WHERE o.entity_id = ? AND v.kind IN ($VIEW_KINDS_SQL) AND (',' || o.roles || ',') LIKE '%,SQL_SELECT,%' ORDER BY v.key""", listOf(relationId), ::entitySummary
    )

    /** The trigger events [EntityDetail.triggerEvents] lists for an entity of [kind]. */
    private fun triggerEvents(connection: Connection, id: Long, kind: String, parentId: Long?): List<TriggerEvent> {
        val select = """SELECT t.key, t.kind, t.display, t.external, e.timing, e.level, e.event, c.key, c.kind, c.display, c.external
               FROM trigger_event e JOIN entity t ON t.id = e.trigger_id LEFT JOIN entity c ON c.id = e.column_id"""
        val order = " ORDER BY t.key, e.event, e.timing, c.key"
        val map = { rs: ResultSet ->
            TriggerEvent(EntitySummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8)?.let { EntitySummary(it, rs.getString(9), rs.getString(10), rs.getInt(11) == 1) })
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
     * The values passed to the parameter [key]: its bound arguments grouped by value kind and value, most frequent
     * first. `BIND` and `EXPRESSION` are one group each. A parameter of a body subprogram counts the arguments bound to
     * its specification's parameter too, where calls bind. Null when there is no such entity.
     */
    fun parameterValues(key: String): Answer<List<ValueGroup>?> = read { connection, _ ->
        val ids = parameterIds(connection, key) ?: return@read null
        connection.query(
            """SELECT value_kind, CASE WHEN value_kind IN ('BIND', 'EXPRESSION') THEN NULL ELSE value END AS shown, count(*) AS n
               FROM call_argument WHERE parameter_entity_id IN (${ids.joinToString(",") { "?" }})
               GROUP BY value_kind, shown ORDER BY n DESC, value_kind, shown""", ids
        ) { ValueGroup(it.getString(1), it.getString(2), it.getLong(3)) }
    }

    /**
     * The calls that pass [value] (of [valueKind]; ignored for `NULL`, `BIND` and `EXPRESSION`) to the parameter [key],
     * as [parameterValues] groups them: a page of call occurrences in source order, as "who uses" rows. Null when there
     * is no such entity.
     */
    fun parameterValueCalls(key: String, valueKind: String, value: String?, after: Long? = null, limit: Int = 100): Answer<OccurrencePage?> {
        if (limit !in 1..500) throw InvalidQueryException("limit must be between 1 and 500")
        if (valueKind !in VALUE_KINDS) throw InvalidQueryException("Unknown value kind: $valueKind")
        val anyValue = valueKind in VALUES_UNSHOWN
        if (!anyValue && value == null) throw InvalidQueryException("Missing parameter: value")
        return read { connection, _ ->
            val ids = parameterIds(connection, key) ?: return@read null
            val hits = connection.query(
                """SELECT DISTINCT o.id, o.entity_id, o.roles, o.status FROM call_argument a JOIN occurrence o ON o.id = a.occurrence_id
                   WHERE a.parameter_entity_id IN (${ids.joinToString(",") { "?" }}) AND a.value_kind = ? AND (? OR a.value = ?)
                   ORDER BY o.id""", ids + listOf(valueKind, anyValue, value)
            ) { Hit(it.getLong(1), it.getLong(2), false, false, roles(it.getString(3)), it.getString(4)) }
            val remaining = hits.filter { after == null || it.id > after }
            val page = remaining.take(limit)
            val nextAfter = if (remaining.size > limit) page.last().id else null
            OccurrencePage(rows(connection, page), nextAfter, hits.size.toLong(),
                Facets(tally(hits.flatMap { it.roles }), tally(hits.map { it.status })))
        }
    }

    /** The parameter [key] and, for a parameter of a body subprogram, the same-named parameter of its specification. */
    private fun parameterIds(connection: Connection, key: String): List<Long>? {
        val id = connection.query("SELECT id FROM entity WHERE key = ?", listOf(key)) { it.getLong(1) }.singleOrNull() ?: return null
        val twins = connection.query(
            """SELECT s.id FROM entity p JOIN relation r ON r.from_id = p.parent_id AND r.kind = 'IMPLEMENTS'
               JOIN entity s ON s.parent_id = r.to_id AND s.name = p.name AND s.kind = 'PARAMETER'
               WHERE p.id = ? AND p.kind = 'PARAMETER'""", listOf(id)
        ) { it.getLong(1) }
        return listOf(id) + twins
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
            connection.query("SELECT id, key, kind, display, external FROM entity WHERE id IN (${chunk.joinToString(",") { "?" }})", chunk) {
                it.getLong(1) to EntitySummary(it.getString(2), it.getString(3), it.getString(4), it.getInt(5) == 1)
            }.forEach { summaries[it.first] = it.second }
        }
        FileView(path, false, text.lines, entityIndexes.keys.map { summaries.getValue(it) }, occurrences)
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

    private class Hit(val id: Long, val matched: Long, val viaCandidate: Boolean, val viaView: Boolean, val roles: List<String>, val status: String)

    /** The hit an occurrence hit several ways reports: not added by [Scope.VIEWS] first, then direct before candidate. */
    private val hitPreference = compareBy<Hit>({ it.viaView }, { it.viaCandidate }, { it.matched })

    private fun rows(connection: Connection, page: List<Hit>): List<OccurrenceRow> {
        if (page.isEmpty()) return emptyList()
        val marks = page.joinToString(",") { "?" }
        val ids = page.map { it.id }
        val summaries = HashMap<Long, EntitySummary>()
        fun entityOf(id: Long?): EntitySummary? = id?.let { summaries.getOrPut(it) { summary(connection, it) } }
        val candidates = connection.query(
            "SELECT c.occurrence_id, e.key, e.kind, e.display, e.external FROM occurrence_candidate c JOIN entity e ON e.id = c.entity_id WHERE c.occurrence_id IN ($marks) ORDER BY c.occurrence_id, e.id",
            ids
        ) { it.getLong(1) to EntitySummary(it.getString(2), it.getString(3), it.getString(4), it.getInt(5) == 1) }
            .groupBy({ it.first }, { it.second })
        val arguments = connection.query(
            """SELECT a.occurrence_id, a.ordinal, a.label, e.key, e.kind, e.display, e.external, a.start_line, a.start_col, a.end_line, a.end_col,
                      a.value_kind, a.value
               FROM call_argument a LEFT JOIN entity e ON e.id = a.parameter_entity_id WHERE a.occurrence_id IN ($marks) ORDER BY a.occurrence_id, a.ordinal""",
            ids
        ) {
            it.getLong(1) to CallArgument(it.getInt(2), it.getString(3),
                it.getString(4)?.let { k -> EntitySummary(k, it.getString(5), it.getString(6), it.getInt(7) == 1) }, span(it, 8),
                it.getString(12), it.getString(13))
        }.groupBy({ it.first }, { it.second })
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
                entityOf(hit.matched)!!, hit.viaCandidate, hit.viaView, null, null, UnitRef(rs.getString(14), rs.getString(15)), rs.getString(16),
                candidates[id].orEmpty(), arguments[id].orEmpty()
            ).let { it.copy(entity = entityOf(entityId), container = entityOf(containerId)) }
        }
    }

    private fun summary(connection: Connection, id: Long): EntitySummary =
        connection.query("SELECT key, kind, display, external FROM entity WHERE id = ?", listOf(id), ::entitySummary).single()

    private fun entitySummary(rs: ResultSet) = EntitySummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1)

    private fun span(rs: ResultSet, first: Int) = Span(rs.getInt(first), rs.getInt(first + 1), rs.getInt(first + 2), rs.getInt(first + 3))

    private fun roles(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split(',')

    private fun tally(values: List<String>): List<Count> = values.groupingBy { it }.eachCount().toSortedMap().map { Count(it.key, it.value.toLong()) }

    private fun counts(connection: Connection, sql: String) = connection.query(sql) { Count(it.getString(1), it.getLong(2)) }

    private fun escapeLike(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** One read-only connection for one request; the run stamp is read and the schema version checked first. */
    private fun <T> read(body: (Connection, RunStamp) -> T): Answer<T> {
        val config = SQLiteConfig().apply { setReadOnly(true) }
        config.createConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA query_only = 1") }
            val (version, stamp) = connection.query(
                "SELECT schema_version, finished_at, atlas_version, zpa_version, compilation_schema, metadata_origin FROM run"
            ) { it.getInt(1) to RunStamp(it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6)) }.single()
            if (version != SCHEMA_VERSION) throw SchemaMismatchException(version, SCHEMA_VERSION)
            return Answer(stamp, body(connection, stamp))
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
        /** The index schema version this module reads; it must equal the writer's `SCHEMA_VERSION`. */
        const val SCHEMA_VERSION = 11

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
        val VALUE_KINDS = listOf("STRING", "NUMBER", "NULL", "BOOLEAN", "NAME", "BIND", "EXPRESSION")

        /** The value kinds whose arguments form one group whatever they pass. */
        private val VALUES_UNSHOWN = setOf("NULL", "BIND", "EXPRESSION")
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
