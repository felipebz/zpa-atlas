//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.*
import com.felipebz.zpa.atlas.server.McpAnswers.ToolJson
import com.felipebz.zpa.atlas.server.McpAnswers.toolAnswer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * The tools: what `tools/list` shows of each ([McpToolTexts], [McpSchemas]) and what answers a call; every answer at
 * most [maxChars] characters, but for a single row.
 */
internal class AtlasMcpTools(
    private val index: AtlasIndex,
    private val maxChars: Int = McpAnswers.MAX_CHARS,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val schemas = McpSchemas(indexCodes())

    /** One tool: what `tools/list` shows of it, and what answers a call, from the call's [Arguments]. */
    class Tool(val name: String, val description: String, val schema: ToolSchema, val body: (Arguments) -> JsonElement)

    /** The tools, in the order `tools/list` gives them, each answering from this index. */
    val tools: List<Tool> = listOf(
        Tool("search_entities", McpToolTexts.SEARCH, schemas.searchEntities(), ::searchEntities),
        Tool("get_entity", McpToolTexts.ENTITY, schemas.getEntity(), ::getEntity),
        Tool("find_referencing_keys", McpToolTexts.REFERENCING, schemas.findReferencingKeys(), ::findReferencingKeys),
        Tool("find_column_places", McpToolTexts.PLACES, schemas.findColumnPlaces(), ::findColumnPlaces),
        Tool("find_occurrences", McpToolTexts.FIND, schemas.findOccurrences(), ::findOccurrences),
        Tool("find_usages", McpToolTexts.USAGES, schemas.findUsages(), ::findUsages),
        Tool("group_usages", McpToolTexts.GROUP_USAGES, schemas.groupUsages(), ::groupUsages),
        Tool("group_occurrences", McpToolTexts.GROUP, schemas.groupOccurrences(), ::groupOccurrences),
        Tool("get_source", McpToolTexts.SOURCE, schemas.getSource(), ::getSource),
        Tool("search_text", McpToolTexts.TEXT, schemas.searchText(), ::searchText),
        Tool("get_values", McpToolTexts.VALUES, schemas.getValues(), ::getValues),
        Tool("find_value_sites", McpToolTexts.VALUE_SITES, schemas.findValueSites(), ::findValueSites),
        Tool("find_impact", McpToolTexts.IMPACT, schemas.findImpact(), ::findImpact)
    )

    fun register(server: Server) {
        for (tool in tools) {
            val accepted = tool.schema.properties?.keys.orEmpty()
            server.addTool(tool.name, tool.description, tool.schema, toolAnnotations = READ_ONLY) {
                call {
                    tool.body(
                        Arguments(tool.name, accepted, it.arguments)
                    )
                }
            }
        }
    }

    // ---- the calls --------------------------------------------------------------------------------------------------

    private fun searchEntities(args: Arguments): JsonElement {
        val answer = index.searchEntities(
            args.requiredString("name"), args.string("kind"), args.boolean("include_external") ?: true,
            args.int("limit", 1..McpLimits.SEARCH_MAX) ?: McpLimits.SEARCH_DEFAULT
        )
        return McpAnswers.fitList(
            toolAnswer(answer),
            maxChars,
            { it.getValue(RESULT).jsonArray },
            { a, hits -> JsonObject(a + (RESULT to hits)) })
    }

    private fun getEntity(args: Arguments): JsonElement {
        val key = args.requiredString("key")
        val listLimit = args.int("list_limit", 1..McpLimits.LIST_MAX) ?: McpLimits.LIST_DEFAULT
        val read = index.entity(key)
        val detail = read.result ?: throw unknownEntity(key)

        // The bodies of a spec (IMPLEMENTS links coming in; the same body in two files gives two) and the spec of a body
        // (the link going out), with their files and lines, as their own fields. No list limit cuts them (see truncateLists).
        fun place(partner: Partner) = JsonObject(
            mapOf(
                "key" to JsonPrimitive(partner.entity.key),
                "definitions" to McpAnswers.compact(ToolJson.encodeToJsonElement(partner.definitions))
            )
        )
        val (bodies, specs) = detail.partners.partition { it.direction == "IN" }
        var full = toolAnswer(Answer(read.run, detail.copy(partners = emptyList())))
        if (bodies.isNotEmpty()) full = full.withResult(BODIES, JsonArray(bodies.map(::place)))
        specs.firstOrNull()?.let { full = full.withResult("spec", place(it)) }
        // Over the size cap, every list is cut further, halving the limit, down to one item each.
        var limit = listLimit
        var fitted = truncateLists(full, limit)
        while (McpAnswers.length(fitted) > maxChars && limit > 1) {
            limit /= 2
            fitted = truncateLists(full, limit)
        }
        return if (limit == listLimit) fitted else JsonObject(
            fitted + ("cutForSize" to JsonObject(
                mapOf(
                    "listLimit" to JsonPrimitive(
                        limit
                    )
                )
            ))
        )
    }

    private fun findReferencingKeys(args: Arguments): JsonElement {
        val key = args.requiredString("key")
        val offset = args.int("offset", 0..Int.MAX_VALUE) ?: 0
        val answer = index.referencingKeys(
            key,
            offset,
            args.int("limit", 1..McpLimits.REFERENCING_MAX) ?: McpLimits.REFERENCING_DEFAULT
        )
        if (answer.result == null) throw unknownEntity(key)
        // The offset counts rows, so a cut page goes on right after its last row kept.
        return fitResultList(toolAnswer(answer), ROWS, nextOffset(offset))
    }

    private fun findColumnPlaces(args: Arguments): JsonElement {
        val key = args.requiredString("key")
        val offset = args.int("offset", 0..Int.MAX_VALUE) ?: 0
        val concatenated = args.boolean("concatenated") ?: false
        val verdicts = args.list("verdicts").takeIf { it.isNotEmpty() }?.let(PlaceFilter::verdicts)
        val filter = PlaceFilter(args.int("size", 1..Int.MAX_VALUE), verdicts, concatenated = concatenated)
        val answer = index.columnPlaces(
            key,
            filter,
            offset,
            args.int("limit", 1..McpLimits.PLACES_MAX) ?: McpLimits.PLACES_DEFAULT
        )
        val page = answer.result ?: throw unknownEntity(key)
        // The concatenated places are never left unsaid: thousands of them behind "3 places" would mislead.
        // Each row's shown line by its number alone (and in Forms code, the code it is in): an agent reads the code with get_source.
        val lined = toolAnswer(answer).let { a ->
            a.withResult(
                ROWS,
                JsonArray(a.result().getValue(ROWS).jsonArray.map(::shownFlattened))
            )
        }
        val told = if (concatenated || page.concatenatedCount == 0) lined else lined.withResult(
            "otherPlaces",
            JsonPrimitive("${page.concatenatedCount} place(s) where the value is one part of a concatenation: ask again with concatenated true")
        )
        // The offset counts rows, so a cut page goes on right after its last row kept.
        return fitResultList(told, ROWS, nextOffset(offset))
    }

    private fun findOccurrences(args: Arguments): JsonElement {
        val query = args.occurrenceQuery()
        val after = args.long("after")
        val limit = args.int("limit", 1..McpLimits.FIND_MAX) ?: McpLimits.FIND_DEFAULT
        // A client that drops the arguments it does not know (a misspelled filter) would otherwise list the whole index.
        if (!filtered(query)) {
            throw InvalidQueryException(
                "find_occurrences needs at least one filter: ${QUERY_FILTERS.joinToString(", ")} " +
                    "(limit and after are not filters; for counts over the whole index, use group_occurrences)"
            )
        }
        val answer = index.queryOccurrences(query, after, limit, summary = true)
        // after is an occurrence id (rows come in id order), so a cut page goes on right after its last row kept.
        return fitResultList(toolAnswer(answer), ROWS, nextAfter)
    }

    private fun findUsages(args: Arguments): JsonElement {
        val key = args.requiredString("key")
        val include = include(args, key)
        val answer = index.occurrences(
            key,
            usageFilter(args, include),
            args.long("after"),
            args.int("limit", 1..McpLimits.FIND_MAX) ?: McpLimits.FIND_DEFAULT
        )
        if (answer.result == null) throw unknownEntity(key)
        val answered = JsonObject(toolAnswer(answer) + ("included" to included(include)))
        // after is an occurrence id here too (rows in id order), so a cut page goes on right after its last row kept.
        return fitResultList(answered, ROWS, nextAfter)
    }

    private fun groupUsages(args: Arguments): JsonElement {
        val key = args.requiredString("key")
        val dimensions = dimensions(args)
        val include = include(args, key)
        val answer = index.groupUsages(
            key,
            dimensions,
            usageFilter(args, include),
            args.int("limit", 1..McpLimits.GROUP_MAX) ?: McpLimits.GROUP_DEFAULT
        )
        if (answer.result == null) throw unknownEntity(key)
        val answered = JsonObject(toolAnswer(answer) + ("included" to included(include)))
        return fitResultList(answered, GROUPS)
    }

    private fun unknownEntity(key: String) =
        ToolException("Unknown entity: $key. Entity keys come from search_entities or from earlier answers.")

    private fun included(include: List<Scope>) = JsonArray(include.map { JsonPrimitive(it.name) })

    /** The uses find_usages and group_usages keep: the roles, statuses, candidates and via asked for, over [include]. */
    private fun usageFilter(args: Arguments, include: List<Scope>) = UsageFilter(
        args.list("roles").toSet(),
        args.list("statuses").toSet().ifEmpty { null },
        (include + Scope.SELF).toSet(),
        args.boolean("candidates") ?: true,
        via(args)
    )

    private fun via(args: Arguments) = args.string("via")?.let { value ->
        UsagePath.entries.firstOrNull { it.name == value } ?: throw InvalidQueryException("Unknown via value: $value")
    }

    private fun dimensions(args: Arguments) = args.requiredList("group_by").map { value ->
        GroupDimension.entries.firstOrNull { it.name == value }
            ?: throw InvalidQueryException("Unknown group_by dimension: $value")
    }

    /** The include asked for, else the entity page's default for the kind: a package's usages are its members'. */
    private fun include(args: Arguments, key: String): List<Scope> =
        if (args.has("include")) {
            args.list("include").map { value ->
                McpSchemas.USAGE_SCOPES.firstOrNull { it.name == value }
                    ?: throw InvalidQueryException("Unknown include value: $value")
            }
        } else {
            defaultUsageScopes(index.entity(key).result?.kind ?: throw unknownEntity(key))
        }

    private fun groupOccurrences(args: Arguments): JsonElement {
        val dimensions = dimensions(args)
        val query = args.occurrenceQuery()
        val answer = index.groupOccurrences(
            query,
            dimensions,
            args.int("limit", 1..McpLimits.GROUP_MAX) ?: McpLimits.GROUP_DEFAULT
        )
        // Without a filter the groups are the whole index's: said in the answer, for an agent that thought it sent one.
        val answered =
            toolAnswer(answer).let { if (filtered(query)) it else JsonObject(it + ("wholeIndex" to JsonPrimitive(true))) }
        return fitResultList(answered, GROUPS)
    }

    /** Whether [query] narrows the occurrences: a modifier alone (inside, container_inside, candidates) does not. */
    private fun filtered(query: OccurrenceQuery) =
        query.copy(inside = false, containerInside = true, candidates = true) != OccurrenceQuery()

    private fun findImpact(args: Arguments): JsonElement {
        val key = args.requiredString("key")
        val include = include(args, key)
        val stop = args.string("stop")?.let { value ->
            ImpactStop.entries.firstOrNull { it.name == value }
                ?: throw InvalidQueryException("Unknown stop value: $value")
        }
        val offset = args.int("offset", 0..Int.MAX_VALUE) ?: 0
        val limit = args.int("limit", 1..McpLimits.IMPACT_MAX) ?: McpLimits.IMPACT_DEFAULT
        val keys = args.boolean("keys") ?: false
        val followTriggers = args.boolean("follow_triggers") ?: true
        // The whole graph, so that a node keeps its number whatever the page and the stop filter.
        val answer = index.impactGraph(
            key,
            ImpactOptions(
                args.list("roles").toSet(), args.list("statuses").toSet().ifEmpty { null }, include,
                args.int("max_depth", 1..McpLimits.IMPACT_DEPTH_MAX) ?: McpLimits.IMPACT_DEPTH_DEFAULT, followTriggers
            )
        )
        val graph = answer.result ?: throw unknownEntity(key)
        val impact = McpImpact(graph.entity, graph.nodes, keys)
        val kept = graph.nodes.indices.filter { stop == null || graph.nodes[it].stop == stop.name }
        val page = kept.drop(offset).take(limit)
        val paged = graph.copy(
            total = kept.size,
            offset = offset,
            nodes = emptyList(),
            nextOffset = (offset + page.size).takeIf { it < kept.size })
        val nodes = JsonArray(page.map { impact.node(it) })
        val full = toolAnswer(answer.copy(result = paged)).withResult(NODES, nodes)
        // The nodes are in a fixed order, so a cut page goes on at the offset after its last node kept.
        return fitResultList(full, NODES, nextOffset(offset))
    }

    private fun getValues(args: Arguments): JsonElement {
        val key = args.requiredString("key")
        val limit = args.int("limit", 1..McpLimits.VALUES_MAX) ?: McpLimits.VALUES_DEFAULT
        requireValueEntity(key, "get_values")
        val answer = index.values(key)
        if (answer.result == null) throw unknownEntity(key)
        val full = toolAnswer(answer)
        val groups = full.getValue(RESULT).jsonArray
        val page =
            JsonObject(mapOf(GROUPS to JsonArray(groups.take(limit)), "totalGroups" to JsonPrimitive(groups.size)))
        return fitResultList(JsonObject(full + (RESULT to page)), GROUPS)
    }

    private fun findValueSites(args: Arguments): JsonElement {
        val key = args.requiredString("key")
        val valueKind = args.requiredString("value_kind")
        val value = args.string("value")
        if (value == null && valueKind in VALUE_TEXT_KINDS) throw InvalidQueryException("Missing argument: value")
        val after = args.long("after")
        val limit = args.int("limit", 1..McpLimits.FIND_MAX) ?: McpLimits.FIND_DEFAULT
        val entity = requireValueEntity(key, "find_value_sites")
        val answer = index.valueSites(key, valueKind, value, after, limit)
        val page = answer.result ?: throw unknownEntity(key)
        // A call's other arguments say nothing about this parameter, and with long signatures they are most of a row.
        val kept = if (entity.kind != "PARAMETER") answer else boundKeys(entity).let { keys ->
            answer.copy(result = page.copy(rows = page.rows.map { row -> row.copy(arguments = row.arguments.filter { it.parameter?.key in keys }) }))
        }
        // after is an occurrence id here too (rows in id order), so a cut page goes on right after its last row kept.
        return fitResultList(toolAnswer(kept), ROWS, nextAfter)
    }

    /**
     * [key]'s entity, which must be a PARAMETER or a COLUMN: the index answers no values for another kind, and an
     * empty answer would read as "no values". A call that needs only the check drops the entity.
     */
    private fun requireValueEntity(key: String, tool: String): EntityDetail {
        val entity = index.entity(key).result ?: throw unknownEntity(key)
        if (entity.kind !in VALUE_ENTITY_KINDS) throw ToolException("$tool answers for a PARAMETER or a COLUMN; $key is a ${entity.kind}.")
        return entity
    }

    /** The keys of [parameter] and of its twin: the parameter of the same name in its subprogram's spec or body. */
    private fun boundKeys(parameter: EntityDetail): Set<String> {
        val subprogram = parameter.parent?.let { index.entity(it.key).result } ?: return setOf(parameter.key)
        val twins =
            subprogram.relations.filter { it.kind == "IMPLEMENTS" }.mapNotNull { index.entity(it.entity.key).result }
                .flatMap { it.children }.map { it.entity }
                .filter { it.kind == "PARAMETER" && it.name == parameter.name }
        return setOf(parameter.key) + twins.map { it.key }
    }

    private fun searchText(args: Arguments): JsonElement {
        val answer = index.searchText(
            args.requiredString("text"), args.string("path"), args.string("container"), args.string("container_name"),
            args.long("after"), args.int("limit", 1..McpLimits.TEXT_MAX) ?: McpLimits.TEXT_DEFAULT
        )
        // A scoped search leaves wholeIndex out, as group_occurrences does; an unscoped one says how to narrow it.
        val full = toolAnswer(answer)
        val scoped = if (answer.result.wholeIndex) full.withResult("narrow", JsonPrimitive(McpToolTexts.NARROW))
        else JsonObject(full + (RESULT to JsonObject(full.result() - "wholeIndex")))
        // Long lines are cut first, as get_source cuts them; then rows, the page going on after the last row kept.
        val lines = McpAnswers.fitLines(scoped, ROWS)
        return fitResultList(lines, ROWS, nextAfter)
    }

    private fun getSource(args: Arguments): JsonElement {
        val path = args.requiredString("path")
        val answer = index.source(
            path,
            args.requiredInt("line"),
            args.int("context", 0..McpLimits.SOURCE_CONTEXT_MAX) ?: McpLimits.SOURCE_CONTEXT_DEFAULT
        )
        if (answer.result == null) throw ToolException("Source not available for $path: the server has no sources folder, or the file is not there.")
        return McpAnswers.fitSource(toolAnswer(answer))
    }

    private fun JsonObject.result() = getValue(RESULT).jsonObject

    private fun JsonObject.withResult(name: String, value: JsonElement) =
        JsonObject(this + (RESULT to JsonObject(result() + (name to value))))

    /**
     * [answer] with the result's [list] cut to fit [maxChars], at least one item; [afterCut] tells where a cut page
     * goes on (see [nextAfter] and [nextOffset]).
     */
    private fun fitResultList(
        answer: JsonObject,
        list: String,
        afterCut: (JsonObject, JsonArray) -> JsonObject = { it, _ -> it }
    ) =
        McpAnswers.fitList(
            answer,
            maxChars,
            { it.result().getValue(list).jsonArray },
            { a, items -> a.withResult(list, items) },
            afterCut
        )

    /** The next page of rows in id order starts right after the last row kept. */
    private val nextAfter: (JsonObject, JsonArray) -> JsonObject =
        { a, rows -> a.withResult(NEXT_AFTER, rows.last().jsonObject.getValue("id")) }

    /** The next page of a list in a fixed order starts at the first item not kept: the offset counts items. */
    private fun nextOffset(offset: Int): (JsonObject, JsonArray) -> JsonObject =
        { a, items -> a.withResult(NEXT_OFFSET, JsonPrimitive(offset + items.size)) }

    /** [row] with its `shown` place as `shownLine` and, in Forms code, `shownCode`. */
    private fun shownFlattened(row: JsonElement): JsonObject {
        val fields = row.jsonObject
        val shown = fields["shown"]?.jsonObject
        return JsonObject(
            fields - "shown" + listOfNotNull(
                shown?.get("line")?.let { "shownLine" to it },
                shown?.get("code")?.let { "shownCode" to it })
        )
    }

    /**
     * Every list of an entity answer cut to [limit] items; `truncated` gives the full size of each list that was cut,
     * so a package with hundreds of members stays one answer the agent can read. The bodies of a spec are kept whole:
     * where they are is what the answer is asked for, and there are few.
     */
    private fun truncateLists(answer: JsonObject, limit: Int): JsonObject {
        val result = answer.getValue(RESULT).jsonObject
        val cut = LinkedHashMap<String, Int>()
        val lists = result.mapValues { (name, value) ->
            if (value is JsonArray && value.size > limit && name != BODIES && name != USES_INCOMPLETE) {
                cut[name] = value.size
                JsonArray(value.take(limit))
            } else {
                value
            }
        }
        if (cut.isEmpty()) return answer
        return JsonObject(answer + (RESULT to JsonObject(lists)) + ("truncated" to JsonObject(cut.mapValues {
            JsonPrimitive(
                it.value
            )
        })))
    }

    /** Runs [body] off the request thread; a refused request is an error result the agent can act on, not a protocol error. */
    internal suspend fun call(body: () -> JsonElement): CallToolResult = withContext(io) {
        try {
            CallToolResult(content = listOf(TextContent(ToolJson.encodeToString(JsonElement.serializer(), body()))))
        } catch (e: ToolException) {
            refusal(e.message!!)
        } catch (e: InvalidQueryException) {
            refusal(e.message ?: "Invalid request")
        } catch (e: QueryTimeoutException) {
            refusal(e.message!!)
        } catch (e: SchemaMismatchException) {
            refusal(e.message!!)
        }
    }

    private fun refusal(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)

    companion object {
        /** The arguments of find_occurrences and group_occurrences that narrow the occurrences. */
        private val QUERY_FILTERS = listOf(
            "entity", "entity_kinds", "name", "schema", "external", "container", "container_name",
            "unit_kinds", "path", "roles", "statuses", "reasons", "origins", "statement_kinds", "value_kind", "value"
        )

        /** Every tool only reads the index. */
        val READ_ONLY =
            ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)

        /** The field of a spec's answer that lists its bodies. */
        private const val BODIES = "bodies"

        /** The field that says why an entity's recorded uses may be incomplete; a cut would hide a reason. */
        private const val USES_INCOMPLETE = "usesIncomplete"

        // The fields of an answer the tools read, cut or add.
        private const val RESULT = "result"
        private const val ROWS = "rows"
        private const val GROUPS = "groups"
        private const val NODES = "nodes"
        private const val NEXT_AFTER = "nextAfter"
        private const val NEXT_OFFSET = "nextOffset"

        private val VALUE_ENTITY_KINDS = setOf("PARAMETER", "COLUMN")

        /** The value kinds that carry a value: find_value_sites needs one for them. */
        private val VALUE_TEXT_KINDS = setOf("STRING", "NUMBER", "BOOLEAN", "NAME")
    }
}
