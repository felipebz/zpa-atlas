//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import java.nio.file.Path
import java.sql.Connection
import java.time.Duration

/**
 * Read-only queries over one index file. No connection is kept: each call opens a read-only connection and closes it,
 * so the indexer can replace the file between requests (on Windows a move fails while the file is open).
 *
 * @param sourcesRoot the folder the indexed paths are relative to, or null when sources are not available
 * @param queryTimeout how long an occurrence query may run before it is interrupted ([QueryTimeoutException])
 */
class AtlasIndex(val database: Path, val sourcesRoot: Path?, private val queryTimeout: Duration = DEFAULT_QUERY_TIMEOUT) {

    fun run(): Answer<RunSummary> = read { connection, _ -> runSummary(connection) }

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
        return read { connection, _ -> EntityReader(connection).searchEntities(text, segments, kind, includeExternal, limit) }
    }

    fun entity(key: String): Answer<EntityDetail?> = read { connection, _ -> EntityReader(connection).entity(key) }

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
        return read { connection, _ -> UsageReader(connection).occurrences(key, roleFilter, statuses, scope, includeCandidates, after, limit) }
    }

    /**
     * The values an entity receives: for a `PARAMETER`, the arguments bound to it (and, for a parameter of a body
     * subprogram, to the same-named parameter of its specification, where calls bind); for a `COLUMN`, the values its
     * writes write. Grouped by value kind and value, most frequent first; `BIND` and `EXPRESSION` are one group each.
     * Empty for any other kind; null when there is no such entity.
     */
    fun values(key: String): Answer<List<ValueGroup>?> = read { connection, _ -> UsageReader(connection).values(key) }

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
        return read { connection, _ -> UsageReader(connection).valueSites(key, valueKind, value, anyValue, after, limit) }
    }

    /**
     * The lines around [line] of an indexed file, or null when sources are not available (no root, or no file).
     *
     * @throws InvalidQueryException when [path] is not a file of the index or leaves the sources root
     */
    fun source(path: String, line: Int, context: Int = 3): Answer<SourceSnippet?> {
        if (line < 1) throw InvalidQueryException("line must be at least 1")
        if (context !in 0..50) throw InvalidQueryException("context must be between 0 and 50")
        return read { connection, _ -> SourceReader(connection, sourcesRoot).source(path, line, context) }
    }

    /**
     * The whole of an indexed file with its occurrences at their name spans, or null when sources are not available.
     * A stale file has no occurrences: their spans would point at the wrong text.
     *
     * @throws InvalidQueryException when [path] is not a file of the index or leaves the sources root
     */
    fun file(path: String): Answer<FileView?> = read { connection, _ -> SourceReader(connection, sourcesRoot).file(path) }

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
        return read(timed = true) { connection, _ -> OccurrenceQueryReader(connection).queryOccurrences(query, after, limit, summary) }
    }

    /** The number of occurrences [query] matches and its facets, as [queryOccurrences] with summary gives them. */
    fun querySummary(query: OccurrenceQuery): Answer<QuerySummary> {
        validate(query)
        return read(timed = true) { connection, _ -> OccurrenceQueryReader(connection).querySummary(query) }
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
        return read(timed = true) { connection, _ -> OccurrenceQueryReader(connection).groupOccurrences(query, dimensions, limit) }
    }

    private fun <T> read(timed: Boolean = false, body: (Connection, RunStamp) -> T): Answer<T> = readIndex(database, queryTimeout, timed, body)

    companion object {
        val DEFAULT_QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** The index schema version this module reads; it must equal the writer's `SCHEMA_VERSION`. */
        const val SCHEMA_VERSION = 12

        /** Decodes an indexed file with the charset recorded for it (`source_file.encoding`); a leading BOM is dropped. */
        fun decode(bytes: ByteArray, encoding: String): String =
            String(bytes, java.nio.charset.Charset.forName(encoding)).let { if (it.startsWith('\uFEFF')) it.substring(1) else it }
    }
}
