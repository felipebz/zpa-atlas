//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import java.sql.Connection

/** The transitive impact of an entity, over one open connection: see [AtlasIndex.impact]. */
internal class ImpactReader(private val connection: Connection) {

    /**
     * [AtlasIndex.impact] after its checks: [roleFilter] and [statuses] filter the first hop only. With [followTriggers],
     * a DML trigger node is followed through the writes that fire it ([TriggerWrites]).
     */
    fun impact(
        key: String,
        roleFilter: Set<String>,
        statuses: Set<String>?,
        include: List<Scope>?,
        maxDepth: Int,
        stop: ImpactStop?,
        offset: Int,
        limit: Int,
        followTriggers: Boolean
    ): ImpactGraph? {
        val (rootId, root) = connection.query("SELECT id, key, kind, display, external, name FROM entity WHERE key = ?", listOf(key)) {
            it.getLong(1) to EntitySummary(it.getString(2), it.getString(3), it.getString(4), it.getInt(5) == 1, it.getString(6))
        }.singleOrNull() ?: return null
        val scopes = include ?: defaultUsageScopes(root.kind)
        val rootTwins = twinsOf(rootId)
        val walk = Walk(rootTwins, followTriggers)

        // The first hop is "who uses", as find_usages lists it.
        val firstHits = UsageReader(connection).usageHits(key, roleFilter, statuses, (scopes + Scope.SELF).toSet(), true, null)!!.second
        val candidateHits = firstHits.filter { it.viaCandidate }.map { it.id }.toSet()
        val firstUses = connection.query(
            """SELECT o.id, o.container_entity_id, o.status, f.path, o.start_line, m.key
               FROM json_each(?) j JOIN occurrence o ON o.id = json_extract(j.value, '$[0]') JOIN source_file f ON f.id = o.file_id
               LEFT JOIN entity m ON m.id = json_extract(j.value, '$[1]')""",
            listOf(firstHits.joinToString(",", "[", "]") { "[${it.id},${it.matched}]" })
        ) {
            Use(null, it.getLong(1), (it.getObject(2) as Number?)?.toLong(), it.getString(3), it.getLong(1) in candidateHits, it.getString(4), it.getInt(5), it.getString(6))
        }
        // A DML trigger asked for: the code that fires it is the writes that fire it.
        val firing = if (followTriggers) {
            TriggerWrites(connection).firing(listOf(rootId)).filter { statuses.isNullOrEmpty() || it.status in statuses }.map {
                Use(null, it.occurrence, it.container, it.status, it.candidate, it.path, it.line, sure = it.sure, fires = it.fires)
            }
        } else {
            emptyList()
        }
        val first = firstUses + firing
        var frontier = walk.reach(first, 1, resolve(first))

        for (depth in 1 until maxDepth) {
            if (frontier.isEmpty()) break
            val uses = usesOf(frontier)
            for (node in frontier) node.read = true
            frontier = walk.reach(uses, depth + 1, resolve(uses))
        }
        return walk.graph(root, scopes, maxDepth, stop, offset, limit)
    }

    /**
     * One use: of node [from] (null on the first hop, where [matched] is the key it matched: the entity, a partner, a
     * member or a view), in [container] (null for code outside any).
     */
    private class Use(
        val from: Long?,
        val occurrence: Long,
        val container: Long?,
        val status: String,
        val candidate: Boolean,
        val path: String,
        val line: Int,
        val matched: String? = null,
        val sure: Boolean = true,
        val fires: String? = null
    )

    /** A node: an entity ([entity], [id] its spec when it has one) or a file ([path]). */
    private class Node(val id: Long?, val entity: EntitySummary?, val path: String?, val depth: Int) {
        var read = false
        val kind: String get() = entity?.kind ?: "FILE"
    }

    /** The nodes reached and the uses between them; [rootTwins] (the entity and its spec or bodies) are never nodes. */
    private inner class Walk(private val rootTwins: Set<Long>, private val followTriggers: Boolean) {
        val nodes = LinkedHashMap<Any, Node>()
        val edges = ArrayList<Pair<Use, Node>>()

        /**
         * Who uses each node read, and the entity asked for ([ROOT]): the nodes holding its uses, [ROOT] for a use in
         * the entity or its spec or body, the node itself for a recursive call. Edges for these last two are not kept.
         */
        val callers = HashMap<Any, MutableSet<Any>>()

        /**
         * Adds the containers of [uses] as nodes at [depth] when they are new, and the uses as edges; [targets] gives each
         * container's node entity (its spec when it has one). Returns the new nodes the walk reads next.
         */
        fun reach(uses: List<Use>, depth: Int, targets: Map<Long, Pair<Long, EntitySummary>>): List<Node> {
            val added = ArrayList<Node>()
            for (use in uses.sortedBy { it.occurrence }) {
                val used: Any = use.from?.let { nodes.getValue(it) } ?: ROOT
                val node = if (use.container == null) {
                    nodes.getOrPut(use.path) { Node(null, null, use.path, depth).also { added += it } }
                } else {
                    val (id, entity) = targets.getValue(use.container)
                    when {
                        id in rootTwins -> { callers.getOrPut(used) { HashSet() } += ROOT; continue }
                        id == use.from -> { callers.getOrPut(used) { HashSet() } += used; continue }
                    }
                    nodes.getOrPut(id) { Node(id, entity, null, depth).also { added += it } }
                }
                callers.getOrPut(used) { HashSet() } += node
                edges += use to node
            }
            return added.filter { it.id != null && it.kind !in (if (followTriggers) FOLLOWED_STOPPING_KINDS else STOPPING_KINDS) }
        }

        /**
         * The nodes read whose callers are all in their own cycle: a recursive routine nothing else calls, routines that
         * only call each other. The entity asked for takes part as [ROOT]: a node called only by it is entered through
         * the entity's callers, unless the entity too is called only from within that cycle.
         */
        fun unentered(): Set<Any> {
            val component = components()
            val entered = HashSet<Int>()
            for ((vertex, its) in callers) {
                val own = component.getValue(vertex)
                if (its.any { component[it] != own }) entered += own
            }
            return callers.keys.filter { component.getValue(it) !in entered }.toSet()
        }

        /** The strongly connected components of the graph from each vertex to its [callers] (Tarjan, without recursion). */
        private fun components(): Map<Any, Int> {
            val index = HashMap<Any, Int>()
            val low = HashMap<Any, Int>()
            val component = HashMap<Any, Int>()
            val stack = ArrayDeque<Any>()
            val onStack = HashSet<Any>()
            var next = 0
            var count = 0
            val vertices = (callers.keys + callers.values.flatten()).toList()
            for (start in vertices) {
                if (start in index) continue
                val work = ArrayDeque<Pair<Any, Iterator<Any>>>()
                fun open(vertex: Any) {
                    index[vertex] = next; low[vertex] = next; next++
                    stack.addLast(vertex); onStack += vertex
                    work.addLast(vertex to callers[vertex].orEmpty().iterator())
                }
                open(start)
                while (work.isNotEmpty()) {
                    val (vertex, targets) = work.last()
                    if (targets.hasNext()) {
                        val to = targets.next()
                        if (to !in index) open(to) else if (to in onStack) low[vertex] = minOf(low.getValue(vertex), index.getValue(to))
                        continue
                    }
                    work.removeLast()
                    work.lastOrNull()?.let { (parent, _) -> low[parent] = minOf(low.getValue(parent), low.getValue(vertex)) }
                    if (low.getValue(vertex) == index.getValue(vertex)) {
                        do {
                            val member = stack.removeLast()
                            onStack -= member
                            component[member] = count
                        } while (member != vertex)
                        count++
                    }
                }
            }
            return component
        }

        fun graph(root: EntitySummary, scopes: List<Scope>, maxDepth: Int, stop: ImpactStop?, offset: Int, limit: Int): ImpactGraph {
            val byId = nodes.values.filter { it.id != null }.associateBy { it.id!! }
            fun depthOf(from: Long?) = if (from == null) 0 else byId.getValue(from).depth
            val unentered = unentered()
            // Followed, a trigger stops by the rules of any node unless it is a system trigger, which no write fires.
            val dml = if (followTriggers) TriggerWrites(connection).tables(nodes.values.filter { it.kind == "TRIGGER" }.map { it.id!! }).keys else emptySet()
            fun stopOf(node: Node) = when {
                node.id == null -> ImpactStop.NO_CONTAINER
                node.kind == "PACKAGE" -> ImpactStop.PACKAGE_LEVEL
                node.kind == "TRIGGER" && node.id !in dml -> ImpactStop.TRIGGER
                !node.read -> ImpactStop.DEPTH_LIMIT
                node in callers && node !in unentered -> ImpactStop.FOLLOWED
                else -> ImpactStop.ENTRY_POINT
            }
            // Certain: reached from the entity through RESOLVED uses only, at any depth; a write that may not fire its
            // trigger is not one.
            val certain = HashSet<Node>()
            val resolved = edges.filter { (use, _) -> use.status == "RESOLVED" && !use.candidate && use.sure }
            var grown = true
            while (grown) {
                grown = false
                for ((use, node) in resolved) {
                    if (node !in certain && (use.from == null || byId.getValue(use.from) in certain)) grown = certain.add(node) || grown
                }
            }
            val into = edges.groupBy({ it.second }, { it.first })
            val ordered = nodes.values.sortedWith(compareBy<Node>({ it.depth }, { it.entity?.key ?: it.path }))
            val stops = ordered.associateWith { stopOf(it) }
            val kept = ordered.filter { stop == null || stops.getValue(it) == stop }
            val page = kept.drop(offset).take(limit)
            val triggers = triggers(page.filter { it.kind == "TRIGGER" }.map { it.id!! })
            return ImpactGraph(
                root, scopes.map { it.name }, maxDepth,
                (1..(ordered.maxOfOrNull { it.depth } ?: 0)).map { d -> ordered.count { it.depth == d } },
                ImpactStop.entries.mapNotNull { s -> stops.values.count { it == s }.takeIf { it > 0 }?.let { Count(s.name, it.toLong()) } },
                kept.size, offset,
                page.map { node ->
                    val uses = into.getValue(node)
                    // One use on a shortest path: RESOLVED first, then the lowest occurrence id.
                    val via = uses.filter { depthOf(it.from) == node.depth - 1 }.minWith(compareBy({ it.status != "RESOLVED" || !it.sure }, { it.occurrence }, { it.fires }))
                    ImpactNode(
                        node.kind, node.entity, node.path,
                        uses = if (node.id == null) uses.map { it.occurrence }.distinct().size else null,
                        firstLine = if (node.id == null) uses.minOf { it.line } else null,
                        depth = node.depth,
                        via = ImpactVia(via.from?.let { byId.getValue(it).entity!!.key } ?: via.matched ?: root.key, via.occurrence, via.path, via.line, via.status, via.fires),
                        certain = node in certain,
                        stop = stops.getValue(node).name,
                        trigger = node.id?.let { triggers[it] }
                    )
                },
                (offset + page.size).takeIf { it < kept.size }
            )
        }
    }

    /**
     * The uses that run each of [frontier]'s nodes, through any of its keys, candidates included; for a trigger, the
     * writes that fire it.
     */
    private fun usesOf(frontier: List<Node>): List<Use> {
        val (triggers, others) = frontier.partition { it.kind == "TRIGGER" }
        val fired = TriggerWrites(connection).firing(triggers.map { it.id!! }).map {
            Use(it.trigger, it.occurrence, it.container, it.status, it.candidate, it.path, it.line, sure = it.sure, fires = it.fires)
        }
        return fired + if (others.isEmpty()) emptyList() else callsOf(others)
    }

    private fun callsOf(frontier: List<Node>): List<Use> {
        val twins = connection.query(
            "SELECT r.to_id, r.from_id FROM relation r WHERE r.kind = 'IMPLEMENTS' AND r.to_id IN (SELECT value FROM json_each(?))",
            listOf(ids(frontier.map { it.id!! }))
        ) { it.getLong(2) to it.getLong(1) }
        // Pairs of an entity id and the node it belongs to: each node's own id, then its bodies.
        val pairs = frontier.map { it.id!! to it.id } + twins
        val roles = frontier.associate { it.id!! to followedRoles(it.kind) }
        val found = connection.query(
            """WITH f(id, node) AS (SELECT json_extract(value, '$[0]'), json_extract(value, '$[1]') FROM json_each(?))
               SELECT f.node, o.id, o.container_entity_id, o.status, 0, fi.path, o.start_line, o.roles
               FROM f JOIN occurrence o ON o.entity_id = f.id JOIN source_file fi ON fi.id = o.file_id
               UNION ALL
               SELECT f.node, o.id, o.container_entity_id, o.status, 1, fi.path, o.start_line, o.roles
               FROM f JOIN occurrence_candidate c ON c.entity_id = f.id JOIN occurrence o ON o.id = c.occurrence_id
               JOIN source_file fi ON fi.id = o.file_id""",
            listOf(pairs.joinToString(",", "[", "]") { (id, node) -> "[$id,$node]" })
        ) { rs ->
            val node = rs.getLong(1)
            Use(node, rs.getLong(2), (rs.getObject(3) as Number?)?.toLong(), rs.getString(4), rs.getInt(5) == 1, rs.getString(6), rs.getInt(7))
                .takeIf { roles(rs.getString(8)).any { it in roles.getValue(node) } }
        }.filterNotNull()
        // An occurrence of a node found directly is not counted again through a candidate.
        return found.groupBy { it.from to it.occurrence }.values.map { same -> same.minBy { if (it.candidate) 1 else 0 } }
    }

    /** Each container of [uses] with the entity of its node: its spec when it has one, else itself. */
    private fun resolve(uses: List<Use>): Map<Long, Pair<Long, EntitySummary>> {
        val containers = uses.mapNotNull { it.container }.distinct()
        if (containers.isEmpty()) return emptyMap()
        return connection.query(
            """WITH c(id, node) AS (
                 SELECT j.value, coalesce((SELECT r.to_id FROM relation r WHERE r.from_id = j.value AND r.kind = 'IMPLEMENTS' ORDER BY r.to_id LIMIT 1), j.value)
                 FROM json_each(?) j)
               SELECT c.id, e.id, e.key, e.kind, e.display, e.external, e.name FROM c JOIN entity e ON e.id = c.node""",
            listOf(ids(containers))
        ) { it.getLong(1) to (it.getLong(2) to EntitySummary(it.getString(3), it.getString(4), it.getString(5), it.getInt(6) == 1, it.getString(7))) }
            .toMap()
    }

    /** [id] with its spec and bodies (`IMPLEMENTS`, either direction, and the other bodies of its spec). */
    private fun twinsOf(id: Long): Set<Long> {
        val twins = mutableSetOf(id)
        var grown = true
        while (grown) {
            val found = connection.query(
                """SELECT r.from_id, r.to_id FROM relation r WHERE r.kind = 'IMPLEMENTS'
                   AND (r.from_id IN (SELECT value FROM json_each(?1)) OR r.to_id IN (SELECT value FROM json_each(?1)))""",
                listOf(ids(twins.toList()))
            ) { listOf(it.getLong(1), it.getLong(2)) }.flatten()
            grown = twins.addAll(found)
        }
        return twins
    }

    /** The table and events of each of [triggerIds]. */
    private fun triggers(triggerIds: List<Long>): Map<Long, ImpactTrigger> {
        if (triggerIds.isEmpty()) return emptyMap()
        val list = ids(triggerIds)
        val tables = connection.query(
            """SELECT r.from_id, t.key, t.kind, t.display, t.external, t.name FROM relation r JOIN entity t ON t.id = r.to_id
               WHERE r.kind = 'TRIGGER_ON' AND r.from_id IN (SELECT value FROM json_each(?))""",
            listOf(list)
        ) { it.getLong(1) to EntitySummary(it.getString(2), it.getString(3), it.getString(4), it.getInt(5) == 1, it.getString(6)) }.toMap()
        val events = connection.query(
            """SELECT e.trigger_id, e.timing, e.level, e.event, c.key, c.kind, c.display, c.external, c.name
               FROM trigger_event e LEFT JOIN entity c ON c.id = e.column_id
               WHERE e.trigger_id IN (SELECT value FROM json_each(?)) ORDER BY e.trigger_id, e.event, e.timing, c.key""",
            listOf(list)
        ) {
            val column = it.getString(5)?.let { key -> EntitySummary(key, it.getString(6), it.getString(7), it.getInt(8) == 1, it.getString(9)) }
            it.getLong(1) to ImpactTriggerEvent(it.getString(2), it.getString(3), it.getString(4), column)
        }.groupBy({ it.first }, { it.second })
        return triggerIds.associateWith { ImpactTrigger(tables[it], events[it].orEmpty()) }
    }

    private fun ids(values: List<Long>) = values.joinToString(",", "[", "]")

    companion object {
        /** The entity asked for, as a vertex of [Walk.callers]. */
        private val ROOT = Any()

        /** Containers whose uses the walk does not follow: their stop reason says why. */
        private val STOPPING_KINDS = setOf("PACKAGE", "TRIGGER")

        /** [STOPPING_KINDS] when triggers are followed. */
        private val FOLLOWED_STOPPING_KINDS = setOf("PACKAGE")

        private val SQL_ROLES = setOf("SQL_SELECT", "SQL_INSERT", "SQL_UPDATE", "SQL_DELETE", "SQL_MERGE", "SQL_LOCK")

        /**
         * The roles of a use that runs a node of [kind], from the second hop on: a call of a routine or a type (with or
         * without parentheses, the index writes `CALL`), SQL on a view or materialized view, reading a cursor. A use
         * that only names the node (`TYPE_USE`, a `DDL_` role, its `DEFINITION` or `DECLARATION`) does not run it.
         */
        fun followedRoles(kind: String): Set<String> = when (kind) {
            "PROCEDURE", "FUNCTION", "TYPE" -> setOf("CALL")
            "VIEW", "MATERIALIZED_VIEW" -> SQL_ROLES
            "CURSOR" -> setOf("READ")
            else -> SQL_ROLES + "CALL" + "READ"
        }
    }
}
