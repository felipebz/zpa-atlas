//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.server.AtlasMcpTools.Companion.ToolJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The shape and the size of a tool answer. An answer is the JSON of the `/api` result made compact for an agent:
 * signatures make keys long, and a key repeated in every argument of a call row grows with the square of the parameter
 * count. Then a cap keeps the answer within what an agent reads at once, whatever the signatures.
 */
internal object McpAnswers {
    /** The longest answer by default, in characters: about 20,000 tokens of JSON, under the 25,000 Claude Code accepts. */
    const val MAX_CHARS = 60_000

    /** The longest JSON string of a source line; a longer line is cut. */
    const val SOURCE_LINE_MAX = 500

    /**
     * [element] with each `display` left out where it is the key without `db:`, and each call argument's `parameter`
     * reduced to its name. A row's `entity` is the subprogram called, so its key plus `.` plus the name is the
     * parameter's key; where it is not, the argument also carries `parameterKey`.
     */
    fun compact(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { compact(it) })
        is JsonObject -> compactObject(element)
        else -> element
    }

    private fun compactObject(value: JsonObject): JsonObject {
        val key = (value["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val display = (value["display"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val rowEntity = (value["entity"] as? JsonObject)?.get("key")?.jsonPrimitive?.content
        val out = LinkedHashMap<String, JsonElement>()
        for ((name, field) in value) {
            when {
                name == "display" && key != null && display == key.removePrefix("db:") -> Unit
                name == "arguments" && field is JsonArray -> out[name] = JsonArray(field.map { argument(it.jsonObject, rowEntity) })
                else -> out[name] = compact(field)
            }
        }
        return JsonObject(out)
    }

    private fun argument(argument: JsonObject, called: String?): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((name, field) in argument) {
            val parameter = field as? JsonObject
            if (name == "parameter" && parameter != null) {
                val parameterName = parameter.getValue("name")
                out[name] = parameterName
                val parameterKey = parameter.getValue("key").jsonPrimitive.content
                if (called == null || parameterKey != "$called.${parameterName.jsonPrimitive.content}") out["parameterKey"] = JsonPrimitive(parameterKey)
            } else {
                out[name] = compact(field)
            }
        }
        return JsonObject(out)
    }

    /**
     * [answer] with [list] (read and replaced by [get] and [put]) cut to its first items that keep the whole answer
     * within [maxChars], at least one. A cut answer gets `cutForSize`: the items kept and the items the list had.
     * [afterCut] adjusts the answer for the items kept (a page's `nextAfter`).
     */
    fun fitList(
        answer: JsonObject,
        maxChars: Int,
        get: (JsonObject) -> JsonArray,
        put: (JsonObject, JsonArray) -> JsonObject,
        afterCut: (JsonObject, JsonArray) -> JsonObject = { it, _ -> it }
    ): JsonObject {
        if (length(answer) <= maxChars) return answer
        val items = get(answer)
        val marker = cutMarker(items.size, items.size)
        var budget = maxChars - length(put(answer, JsonArray(emptyList()))) - length(marker) - ",\"cutForSize\":".length
        var kept = 0
        for (item in items) {
            budget -= length(item) + (if (kept > 0) 1 else 0)
            if (budget < 0 && kept > 0) break
            kept++
        }
        if (kept == items.size) return answer
        val keptItems = JsonArray(items.take(kept))
        return JsonObject(afterCut(put(answer, keptItems), keptItems) + ("cutForSize" to cutMarker(kept, items.size)))
    }

    private fun cutMarker(kept: Int, of: Int) = JsonObject(mapOf("kept" to JsonPrimitive(kept), "of" to JsonPrimitive(of)))

    /** [snippet]'s lines with each text cut so its JSON string is at most [SOURCE_LINE_MAX] characters; `cutForSize` counts them. */
    fun fitSource(snippet: JsonObject): JsonObject = fitLines(snippet, "lines")

    /** The `text` of each item of the result's [list] cut as [fitSource] cuts a line. */
    fun fitLines(snippet: JsonObject, list: String): JsonObject {
        val result = snippet.getValue("result").jsonObject
        var cut = 0
        val lines = JsonArray(result.getValue(list).jsonArray.map { line ->
            val text = line.jsonObject.getValue("text").jsonPrimitive.content
            if (length(JsonPrimitive(text)) <= SOURCE_LINE_MAX) return@map line
            cut++
            var end = minOf(text.length, SOURCE_LINE_MAX)
            while (length(JsonPrimitive(text.take(end) + "…")) > SOURCE_LINE_MAX) end -= maxOf(1, end / 10)
            JsonObject(line.jsonObject + ("text" to JsonPrimitive(text.take(end) + "…")))
        })
        if (cut == 0) return snippet
        return JsonObject(snippet + ("result" to JsonObject(result + (list to lines))) + ("cutForSize" to JsonObject(mapOf("lines" to JsonPrimitive(cut)))))
    }

    fun length(element: JsonElement) = ToolJson.encodeToString(JsonElement.serializer(), element).length
}
