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
class AtlasIndex(
    val database: Path,
    val sourcesRoot: Path?,
    private val queryTimeout: Duration = DEFAULT_QUERY_TIMEOUT,
    /** The most nodes [impactView] answers. */
    private val maxViewNodes: Int = MAX_VIEW_NODES
) {

    /**
     * The run summary last read, with the run it was read from. Its counts scan every occurrence, a second or more on
     * a large index, and they cannot change while the file holds the same run: `index` writes a new file, with a new
     * stamp, on every run.
     */
    @Volatile
    private var summary: Answer<RunSummary>? = null

    /** The run of the index and its counts; read again only when the file holds another run than the last read. */
    fun run(): Answer<RunSummary> = read { connection, stamp ->
        summary?.takeIf { it.run == stamp }?.result ?: runSummary(connection).also { summary = Answer(stamp, it) }
    }

    /** The run stamp alone: the index opens and has the schema version this module reads. Cheap, for a health check. */
    fun stamp(): RunStamp = read { _, stamp -> stamp }.run

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
        limit: Int = 100,
        via: UsagePath? = null
    ): Answer<OccurrencePage?> {
        if (limit !in 1..500) throw InvalidQueryException("limit must be between 1 and 500")
        val roleFilter = usageRoles(roles, statuses)
        return read { connection, _ -> UsageReader(connection).occurrences(key, roleFilter, statuses, scope, includeCandidates, via, after, limit) }
    }

    /**
     * The rows [occurrences] lists for the same arguments, grouped and counted as [groupOccurrences] groups: `ROLE`
     * counts a row under each of its roles the role filter keeps, `ENTITY` is the matched entity (on a row through a
     * view, the view or view column). Null when there is no such entity.
     */
    fun groupUsages(
        key: String,
        dimensions: List<GroupDimension>,
        roles: Set<String>? = null,
        statuses: Set<String>? = null,
        scope: Set<Scope> = setOf(Scope.SELF),
        includeCandidates: Boolean = true,
        via: UsagePath? = null,
        limit: Int = 100
    ): Answer<GroupPage?> {
        checkGroups(dimensions, limit)
        val roleFilter = usageRoles(roles, statuses)
        return read(timed = true) { connection, _ ->
            UsageReader(connection).groupUsages(key, roleFilter, statuses, scope, includeCandidates, via, dimensions, limit)
        }
    }

    /**
     * The transitive impact of [key]: who uses it, who uses those, and so on, breadth first, up to code nothing in the
     * index uses. The first hop is "who uses" as [occurrences] lists it, for [roles], [statuses] and [include] (null:
     * the default by kind, [defaultUsageScopes]), candidates included. From the second hop on, the walk follows the uses
     * that run a node, not those that only name it: calls of a routine or a type, SQL on a view or materialized view,
     * reading a cursor. A node is the container of a use (`occurrence.container_entity_id`), a spec and its bodies being
     * one node named by the spec; the entity and its spec or bodies are never nodes. A view is followed as a whole: from
     * a column, every user of a view computed from it, not only the users of the view columns computed from that column.
     * With [followTriggers], a DML trigger is followed through the writes that fire it ([TriggerWrites]): an INSERT,
     * UPDATE, DELETE or MERGE of its table, directly, through a synonym or through a view with one base, and with its
     * events (`UPDATE OF` columns included); the hop is not certain when the table or the event is not. A trigger no
     * such write fires is an entry point. Without it, and for system triggers, the walk stops at the trigger, as it does
     * at package-level code and code outside any container ([ImpactStop]).
     *
     * The whole graph is computed on each call, so [offset] pages in its order (depth, then key or path); [stop] keeps
     * only the nodes of one stop reason, while [ImpactGraph.levels] and [ImpactGraph.stops] count the whole graph. A
     * graph too large for the time limit ends with [QueryTimeoutException]. Null when there is no such entity.
     */
    fun impact(
        key: String,
        roles: Set<String>? = null,
        statuses: Set<String>? = null,
        include: List<Scope>? = null,
        maxDepth: Int = 10,
        stop: ImpactStop? = null,
        offset: Int = 0,
        limit: Int = 100,
        followTriggers: Boolean = true
    ): Answer<ImpactGraph?> {
        if (maxDepth !in 1..50) throw InvalidQueryException("maxDepth must be between 1 and 50")
        if (offset < 0) throw InvalidQueryException("offset must not be negative")
        if (limit !in 1..500) throw InvalidQueryException("limit must be between 1 and 500")
        include?.firstOrNull { it == Scope.SELF }?.let { throw InvalidQueryException("Unknown include: SELF") }
        val roleFilter = usageRoles(roles, statuses)
        return read(timed = true) { connection, _ ->
            ImpactReader(connection).impact(key, roleFilter, statuses, include, maxDepth, stop, offset, limit, followTriggers)
        }
    }

    /**
     * [impact] with every node of the graph in its order, without stop, offset or limit: for a caller that numbers the
     * nodes of the whole graph and pages them itself. The graph is computed once.
     */
    fun impactGraph(
        key: String,
        roles: Set<String>? = null,
        statuses: Set<String>? = null,
        include: List<Scope>? = null,
        maxDepth: Int = 10,
        followTriggers: Boolean = true
    ): Answer<ImpactGraph?> {
        if (maxDepth !in 1..50) throw InvalidQueryException("maxDepth must be between 1 and 50")
        include?.firstOrNull { it == Scope.SELF }?.let { throw InvalidQueryException("Unknown include: SELF") }
        val roleFilter = usageRoles(roles, statuses)
        return read(timed = true) { connection, _ ->
            ImpactReader(connection).impact(key, roleFilter, statuses, include, maxDepth, null, 0, Int.MAX_VALUE, followTriggers)
        }
    }

    /**
     * The whole impact graph of [key] in one compact answer ([ImpactView]), for a page that shows it at once: the graph
     * of [impactGraph] for [include], [maxDepth] and [followTriggers], computed once. A graph of more than
     * the index's node limit ([MAX_VIEW_NODES] by default) is refused with [GraphTooLargeException], so that one answer
     * stays one a browser can hold. Null when there is no such entity.
     */
    fun impactView(
        key: String,
        include: List<Scope>? = null,
        maxDepth: Int = 10,
        followTriggers: Boolean = true
    ): Answer<ImpactView?> {
        if (maxDepth !in 1..50) throw InvalidQueryException("maxDepth must be between 1 and 50")
        include?.firstOrNull { it == Scope.SELF }?.let { throw InvalidQueryException("Unknown include: SELF") }
        return read(timed = true) { connection, _ ->
            val graph = ImpactReader(connection).impact(key, USAGE_ROLES, null, include, maxDepth, null, 0, Int.MAX_VALUE, followTriggers)
            if (graph != null && graph.nodes.size > maxViewNodes) throw GraphTooLargeException(graph.nodes.size, maxViewNodes)
            graph?.let { ImpactViewBuilder(connection).build(it, followTriggers) }
        }
    }

    /** The roles a usage filter keeps: [roles], else every usage role; an unknown role or status is an error. */
    private fun usageRoles(roles: Set<String>?, statuses: Set<String>?): Set<String> {
        roles?.firstOrNull { it !in ROLES }?.let { throw InvalidQueryException("Unknown role: $it") }
        statuses?.firstOrNull { it !in STATUSES }?.let { throw InvalidQueryException("Unknown status: $it") }
        return roles?.ifEmpty { null } ?: USAGE_ROLES
    }

    private fun checkGroups(dimensions: List<GroupDimension>, limit: Int) {
        if (dimensions.size !in 1..3) throw InvalidQueryException("groupBy takes one to three dimensions")
        if (dimensions.toSet().size != dimensions.size) throw InvalidQueryException("A groupBy dimension is repeated")
        if (limit !in 1..1000) throw InvalidQueryException("limit must be between 1 and 1000")
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

    /**
     * The lines of the indexed files that contain [text], a literal piece of text compared without case (`*` and every
     * other character stand for themselves), in file and line order. The text is the one the indexer decoded, whole
     * files with comments, strings and inactive conditional branches; one line at a time, so a text that spans a line
     * break is never found. [path] (a prefix, or a pattern with `*`), [container] (a key) and [containerName] (a dotted
     * name) narrow it, a container with its contents; [TextPage.wholeIndex] says none was given.
     *
     * @throws InvalidQueryException when [text] has fewer than three characters, which a trigram index cannot find
     */
    fun searchText(text: String, path: String? = null, container: String? = null, containerName: String? = null, after: Long? = null, limit: Int = 100): Answer<TextPage> {
        if (text.codePointCount(0, text.length) < MIN_TEXT) throw InvalidQueryException("The text must have at least $MIN_TEXT characters: the text index cannot find a shorter one")
        if (limit !in 1..500) throw InvalidQueryException("limit must be between 1 and 500")
        if (after != null && after < 0) throw InvalidQueryException("after must not be negative")
        if (containerName != null && containerName.split('.').size > OccurrenceQuerySql.MAX_SEGMENTS) {
            throw InvalidQueryException("containerName has more than ${OccurrenceQuerySql.MAX_SEGMENTS} segments")
        }
        return read(timed = true) { connection, _ -> TextSearchReader(connection, sourcesRoot).search(text, path, container, containerName, after, limit) }
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
        checkGroups(dimensions, limit)
        validate(query)
        return read(timed = true) { connection, _ -> OccurrenceQueryReader(connection).groupOccurrences(query, dimensions, limit) }
    }

    private fun <T> read(timed: Boolean = false, body: (Connection, RunStamp) -> T): Answer<T> = readIndex(database, queryTimeout, timed, body)

    companion object {
        val DEFAULT_QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** The index schema version this module reads; it must equal the writer's `SCHEMA_VERSION`. */
        const val SCHEMA_VERSION = 13

        /** The fewest characters a text search takes: one trigram. */
        const val MIN_TEXT = 3

        /** The most nodes [impactView] answers by default. */
        const val MAX_VIEW_NODES = 50_000

        /** Decodes an indexed file with the charset recorded for it (`source_file.encoding`); a leading BOM is dropped. */
        fun decode(bytes: ByteArray, encoding: String): String =
            String(bytes, java.nio.charset.Charset.forName(encoding)).let { if (it.startsWith('\uFEFF')) it.substring(1) else it }
    }
}
