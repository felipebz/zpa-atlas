//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.EntitySummary
import com.felipebz.zpa.atlas.query.ImpactNode
import com.felipebz.zpa.atlas.server.AtlasMcpTools.Companion.ToolJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The nodes of one impact graph as `find_impact` answers them. A full key is the longest part of a node (a signature
 * makes it long, and `via.uses` is another one), so by default a node has its number in the whole graph (`n`, in the
 * graph's order, from 1) and a short `name`; `via.uses` is the `n` of the node it uses, and a `key` is given only where
 * another node has the same name. With [keys] a node is the one the index answers, plus its `n`.
 *
 * [nodes] is the whole graph: a number does not depend on the page, nor on the stop filter.
 */
internal class McpImpact(private val root: EntitySummary, private val nodes: List<ImpactNode>, private val keys: Boolean) {
    /** Each entity node's name, null for a file node (which has its `path`). */
    private val names: List<String?> = nodes.map { node -> node.entity?.let { nameOf(it.display) } }

    /** The names more than one node has: those nodes give their key. */
    private val repeated: Set<String> = names.filterNotNull().groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    /** The number of each entity node, by its key: what `via.uses` names. */
    private val numbers: Map<String, Int> = nodes.withIndex().mapNotNull { (index, node) -> node.entity?.let { it.key to index + 1 } }.toMap()

    /** The node at [index] of the whole graph. */
    fun node(index: Int): JsonObject {
        val node = nodes[index]
        val base = McpAnswers.compact(ToolJson.encodeToJsonElement(ImpactNode.serializer(), node)).jsonObject
        val out = LinkedHashMap<String, JsonElement>()
        out["n"] = JsonPrimitive(index + 1)
        if (keys) return JsonObject(out + base)
        names[index]?.let { name ->
            out["name"] = JsonPrimitive(name)
            if (name in repeated) out["key"] = JsonPrimitive(node.entity!!.key)
        }
        for ((field, value) in base) {
            when (field) {
                "entity" -> if (value.jsonObject["external"]?.jsonPrimitive?.booleanOrNull == true) out["external"] = JsonPrimitive(true)
                "via" -> out[field] = via(node, value.jsonObject)
                else -> out[field] = value
            }
        }
        return JsonObject(out)
    }

    /**
     * [via] with `uses` as a number: the node it uses, or 0 on the first hop, where the entity asked for (and its spec
     * or body) is used; when the first hop matched another entity (a member, a view), `usesName` names it.
     */
    private fun via(node: ImpactNode, via: JsonObject): JsonObject {
        val uses = via.getValue("uses").jsonPrimitive.content
        val out = LinkedHashMap<String, JsonElement>()
        for ((field, value) in via) {
            if (field != "uses") {
                out[field] = value
                continue
            }
            // Depth 1 is the first hop: the node's use is of the entity asked for, or of what the first hop matched.
            if (node.depth == 1) {
                out[field] = JsonPrimitive(0)
                if (uses != root.key) out["usesName"] = JsonPrimitive(nameOf(uses.removePrefix("db:")))
            } else {
                out[field] = JsonPrimitive(numbers.getValue(uses))
            }
        }
        return JsonObject(out)
    }

    companion object {
        /**
         * [display] without its parameter list, outside quoted identifiers: `HR.PKG.RUN(P_A IN NUMBER)@BODY` is
         * `HR.PKG.RUN@BODY`, so a private routine still says it is a body, and `#2` after the list is kept.
         */
        fun nameOf(display: String): String {
            var quoted = false
            var open = -1
            for (i in display.indices) {
                val c = display[i]
                if (c == '"') quoted = !quoted
                else if (!quoted && c == '(') { open = i; break }
            }
            if (open < 0) return display
            var depth = 0
            quoted = false
            for (i in open until display.length) {
                val c = display[i]
                when {
                    c == '"' -> quoted = !quoted
                    !quoted && c == '(' -> depth++
                    !quoted && c == ')' && --depth == 0 -> return display.substring(0, open) + display.substring(i + 1)
                }
            }
            return display.substring(0, open)
        }
    }
}
