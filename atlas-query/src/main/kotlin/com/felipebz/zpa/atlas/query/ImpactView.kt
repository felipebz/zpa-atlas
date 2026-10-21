//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection

/**
 * A whole impact graph in one answer, for a page that shows it at once: each entity and each file path is written
 * once, in [entities] and [paths], and a node refers to them by index. [levels], [stops] and [certain] count the
 * nodes. The nodes are in the graph's order (depth, then key or path).
 *
 * A signature makes a key long, and the display is usually the key again: an entity of [entities] has its display
 * only when it is not the key without `db:` ([ImpactEntity]).
 */
@Serializable
data class ImpactView(
    val entity: EntitySummary,
    val included: List<String>,
    val maxDepth: Int,
    val followTriggers: Boolean,
    val levels: List<Int>,
    val stops: List<Count>,
    val certain: Int,
    val entities: List<ImpactEntity>,
    val paths: List<String>,
    val nodes: List<ImpactViewNode>
)

/** An [EntitySummary] whose [display] is null when it is the key without `db:`. */
@Serializable
data class ImpactEntity(val key: String, val kind: String, val display: String?, val external: Boolean, val name: String) {
    constructor(summary: EntitySummary) :
        this(summary.key, summary.kind, summary.display.takeIf { it != summary.key.removePrefix("db:") }, summary.external, summary.name)
}

/**
 * One node of an [ImpactView]: [entity] an index into [ImpactView.entities], or for a file node [path] into
 * [ImpactView.paths], with [uses] and [firstLine] as in [ImpactNode]. [owner] is the package or type the node belongs
 * to (an index into [ImpactView.entities]), null for standalone code, whose [schema] is given instead.
 */
@Serializable
data class ImpactViewNode(
    val kind: String,
    val entity: Int?,
    val path: Int?,
    val uses: Int?,
    val firstLine: Int?,
    val depth: Int,
    val certain: Boolean,
    val stop: String,
    val owner: Int?,
    val schema: String?,
    val via: ImpactViewVia,
    val trigger: ImpactTrigger?
)

/**
 * [ImpactVia] by index: [node] is the node it uses (an index into [ImpactView.nodes]), null on the first hop, where
 * [uses] (an index into [ImpactView.entities]) is the entity asked for or what the first hop matched (a member, a view).
 */
@Serializable
data class ImpactViewVia(val node: Int?, val uses: Int, val path: Int, val line: Int, val status: String, val fires: String?)

/** Builds an [ImpactView] from a whole [ImpactGraph], reading what the graph does not carry over [connection]. */
internal class ImpactViewBuilder(private val connection: Connection) {
    fun build(graph: ImpactGraph, followTriggers: Boolean): ImpactView {
        val entities = ArrayList<EntitySummary>()
        val entityIndex = HashMap<String, Int>()
        fun entity(summary: EntitySummary) = entityIndex.getOrPut(summary.key) { entities += summary; entities.size - 1 }
        val paths = ArrayList<String>()
        val pathIndex = HashMap<String, Int>()
        fun path(text: String) = pathIndex.getOrPut(text) { paths += text; paths.size - 1 }

        entity(graph.entity)
        val nodeIndex = HashMap<String, Int>()
        graph.nodes.forEachIndexed { index, node -> node.entity?.let { nodeIndex[it.key] = index; entity(it) } }
        val owners = owners(graph.nodes.mapNotNull { it.entity?.key })
        // What a first hop matched, when it is not the entity: a member, a spec or body, a view.
        summaries(graph.nodes.map { it.via.uses }.filter { it !in entityIndex }.distinct()).forEach { entity(it) }

        val nodes = graph.nodes.map { node ->
            val owner = node.entity?.let { owners[it.key] }
            ImpactViewNode(
                kind = node.kind,
                entity = node.entity?.let { entityIndex.getValue(it.key) },
                path = node.path?.let { path(it) },
                uses = node.uses,
                firstLine = node.firstLine,
                depth = node.depth,
                certain = node.certain,
                stop = node.stop,
                owner = owner?.first?.let { entity(it) },
                schema = owner?.second,
                via = ImpactViewVia(
                    node = if (node.depth == 1) null else nodeIndex.getValue(node.via.uses),
                    uses = entityIndex.getValue(node.via.uses),
                    path = path(node.via.path),
                    line = node.via.line,
                    status = node.via.status,
                    fires = node.via.fires
                ),
                trigger = node.trigger
            )
        }
        return ImpactView(
            graph.entity, graph.included, graph.maxDepth, followTriggers, graph.levels, graph.stops,
            graph.nodes.count { it.certain }, entities.map(::ImpactEntity), paths, nodes
        )
    }

    /** For each of [keys], the package or type it belongs to (its nearest such ancestor, or null) and its schema. */
    private fun owners(keys: List<String>): Map<String, Pair<EntitySummary?, String?>> {
        if (keys.isEmpty()) return emptyMap()
        val found = HashMap<String, Pair<EntitySummary?, String?>>()
        connection.query(
            """WITH RECURSIVE
                 n(key, id, schema) AS (SELECT e.key, e.id, e.schema_name FROM json_each(?) j JOIN entity e ON e.key = j.value),
                 up(key, id, kind) AS (
                   SELECT n.key, p.id, p.kind FROM n JOIN entity c ON c.id = n.id JOIN entity p ON p.id = c.parent_id
                   UNION ALL
                   SELECT up.key, p.id, p.kind FROM up JOIN entity c ON c.id = up.id JOIN entity p ON p.id = c.parent_id
                   WHERE up.kind NOT IN ('PACKAGE', 'TYPE'))
               SELECT n.key, n.schema, o.key, o.kind, o.display, o.external, o.name
               FROM n LEFT JOIN up ON up.key = n.key AND up.kind IN ('PACKAGE', 'TYPE') LEFT JOIN entity o ON o.id = up.id""",
            listOf(json(keys))
        ) {
            val owner = it.getString(3)?.let { key -> EntitySummary(key, it.getString(4), it.getString(5), it.getInt(6) == 1, it.getString(7)) }
            found[it.getString(1)] = owner to it.getString(2)
        }
        return found
    }

    private fun summaries(keys: List<String>): List<EntitySummary> {
        if (keys.isEmpty()) return emptyList()
        return connection.query(
            "SELECT e.key, e.kind, e.display, e.external, e.name FROM json_each(?) j JOIN entity e ON e.key = j.value ORDER BY j.key",
            listOf(json(keys)), ::entitySummary
        )
    }

    private fun json(keys: List<String>) = Json.encodeToString(keys)
}
