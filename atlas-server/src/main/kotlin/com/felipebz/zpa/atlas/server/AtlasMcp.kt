//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.ktor.http.content.TextContent as HttpText

/**
 * The settings of `/mcp`.
 *
 * @param allowedHosts the host names `/mcp` accepts besides `localhost`, `127.0.0.1` and `[::1]`; a server reached
 *     by any other name (another machine's name for it, its address on the network) must list it
 * @param heartbeat how often an open event stream gets a comment, so idle connections are not dropped on the way
 * @param maxAnswerChars the longest tool answer, in characters; a longer one keeps its first rows
 */
data class McpSettings(
    val allowedHosts: List<String> = emptyList(),
    val heartbeat: Duration = 30.seconds,
    val maxAnswerChars: Int = McpAnswers.MAX_CHARS
)

/** The host names `/mcp` always accepts, as the MCP SDK's default protection against DNS rebinding does. */
val MCP_LOCAL_HOSTS = listOf("localhost", "127.0.0.1", "[::1]")

/**
 * Mounts MCP over Streamable HTTP at `/mcp`: read-only tools over the same [AtlasIndex] calls as `/api`, each answer
 * the JSON of the call's result in its `Answer` envelope. Requests whose `Host` (or `Origin`) names a host outside
 * [MCP_LOCAL_HOSTS], [McpSettings.allowedHosts] and the host of [AuthSettings.publicUrl] are refused. The token check
 * of [AuthSettings] is not here: it answers before this route is reached.
 */
fun Application.atlasMcp(index: AtlasIndex, settings: McpSettings, auth: AuthSettings? = null, basePath: BasePath = BasePath.ROOT) =
    mountMcp(settings, auth, basePath) { atlasMcpServer(index, settings.maxAnswerChars) }

/** `/mcp` as [atlasMcp] mounts it, each session served by a new [server]. */
internal fun Application.mountMcp(settings: McpSettings, auth: AuthSettings?, basePath: BasePath, server: () -> Server) {
    val hosts = (MCP_LOCAL_HOSTS + settings.allowedHosts + listOfNotNull(auth?.publicHost)).distinct()
    install(ClosedToolSchemas) { path = basePath.of("/mcp") }
    mcpStreamableHttp(
        path = basePath.of("/mcp"),
        allowedHosts = hosts,
        // With hosts of its own the SDK checks no Origin: check it against the same names.
        allowedOrigins = hosts.map { "http://$it" },
        sseHeartbeatConfig = { period = settings.heartbeat }
    ) { server() }
}

/**
 * Adds `"additionalProperties": false` to every input schema of a `tools/list` answer on `/mcp`. The tools refuse an
 * argument they do not know; the SDK's `ToolSchema` has no field to say so, so the answer's JSON is completed here.
 */
private class ClosedToolSchemasConfig {
    /** The path of the MCP endpoint, under the base path. */
    var path = "/mcp"
}

private val ClosedToolSchemas = createApplicationPlugin("ClosedToolSchemas", ::ClosedToolSchemasConfig) {
    val path = pluginConfig.path
    // Installed before the SDK's ContentNegotiation, so the answer is still the SDK's object here, not compressed bytes.
    onCallRespond { call, body ->
        if (call.request.path() != path || body !is JSONRPCResponse || body.result !is ListToolsResult) return@onCallRespond
        transformBody {
            val answer = McpJson.encodeToJsonElement(JSONRPCResponse.serializer(), body).jsonObject
            val result = answer.getValue("result").jsonObject
            val closed = JsonArray(result.getValue("tools").jsonArray.map { tool ->
                val schema = tool.jsonObject.getValue("inputSchema").jsonObject
                JsonObject(tool.jsonObject + ("inputSchema" to JsonObject(schema + ("additionalProperties" to JsonPrimitive(false)))))
            })
            val text = McpJson.encodeToString(JsonElement.serializer(), JsonObject(answer + ("result" to JsonObject(result + ("tools" to closed)))))
            HttpText(text, ContentType.Application.Json)
        }
    }
}

/** The version of this build, as `initialize` reports it. */
internal val ATLAS_VERSION: String = Properties().apply {
    AtlasMcpTools::class.java.getResourceAsStream("/zpa-atlas-server.properties")?.use { load(it) }
}.getProperty("version", "unknown")

/** One MCP server with the atlas tools; the SDK creates one per session. */
internal fun atlasMcpServer(index: AtlasIndex, maxAnswerChars: Int = McpAnswers.MAX_CHARS): Server {
    val server = Server(
        Implementation(name = "zpa-atlas", version = ATLAS_VERSION),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = AtlasMcpTools.INSTRUCTIONS
    )
    AtlasMcpTools(index, maxAnswerChars).register(server)
    return server
}

/** The tools, their schemas and their argument reading; every answer at most [maxChars] characters, but for a single row. */
internal class AtlasMcpTools(private val index: AtlasIndex, private val maxChars: Int = McpAnswers.MAX_CHARS) {
    private val codes = indexCodes()

    /** One tool: what `tools/list` shows of it, and what answers a call, from the call's [Arguments]. */
    class Tool(val name: String, val description: String, val schema: ToolSchema, val body: (Arguments) -> JsonElement)

    /** The tools, in the order `tools/list` gives them, each answering from this index. */
    val tools: List<Tool> = listOf(
        Tool("search_entities", SEARCH, searchSchema(), ::searchEntities),
        Tool("get_entity", ENTITY, entitySchema(), ::getEntity),
        Tool("find_occurrences", FIND, findSchema(), ::findOccurrences),
        Tool("find_usages", USAGES, usagesSchema(), ::findUsages),
        Tool("group_usages", GROUP_USAGES, groupUsagesSchema(), ::groupUsages),
        Tool("group_occurrences", GROUP, groupSchema(), ::groupOccurrences),
        Tool("get_source", SOURCE, sourceSchema(), ::getSource),
        Tool("search_text", TEXT, textSchema(), ::searchText),
        Tool("get_values", VALUES, valuesSchema(), ::getValues),
        Tool("find_value_sites", VALUE_SITES, valueSitesSchema(), ::findValueSites),
        Tool("find_impact", IMPACT, impactSchema(), ::findImpact)
    )

    fun register(server: Server) {
        for (tool in tools) {
            val accepted = tool.schema.properties?.keys.orEmpty()
            server.addTool(tool.name, tool.description, tool.schema, toolAnnotations = READ_ONLY) { call { tool.body(Arguments(tool.name, accepted, it.arguments)) } }
        }
    }

    // ---- the calls --------------------------------------------------------------------------------------------------

    private fun searchEntities(args: Arguments): JsonElement {
        val answer = index.searchEntities(args.requiredString("query"), args.string("kind"), args.boolean("include_external") ?: true,
            args.int("limit", 1..SEARCH_MAX) ?: SEARCH_DEFAULT)
        return McpAnswers.fitList(toolAnswer(answer), maxChars, { it.getValue("result").jsonArray }, { a, hits -> JsonObject(a + ("result" to hits)) })
    }

    private fun getEntity(args: Arguments): JsonElement {
        val key = args.requiredString("key")
        val listLimit = args.int("list_limit", 1..LIST_MAX) ?: LIST_DEFAULT
        val read = index.entityWithPartners(key)
        val (detail, partners) = read.result ?: throw ToolException("Unknown entity: $key. Entity keys come from search_entities or from earlier answers.")
        // The bodies of a spec (IMPLEMENTS links coming in; the same body in two files gives two) and the spec of a body
        // (the link going out), with their files and lines. No list limit cuts them (see truncateLists).
        fun place(link: RelationLink, definitions: List<Location>) = JsonObject(mapOf(
            "key" to JsonPrimitive(link.entity.key), "definitions" to McpAnswers.compact(ToolJson.encodeToJsonElement(definitions))
        ))
        val (bodies, specs) = partners.entries.partition { it.key.direction == "IN" }
        var full = toolAnswer(Answer(read.run, detail))
        if (bodies.isNotEmpty()) full = full.withResult(BODIES, JsonArray(bodies.map { place(it.key, it.value) }))
        specs.firstOrNull()?.let { full = full.withResult("spec", place(it.key, it.value)) }
        // Over the size cap, every list is cut further, halving the limit, down to one item each.
        var limit = listLimit
        var fitted = truncateLists(full, limit)
        while (McpAnswers.length(fitted) > maxChars && limit > 1) {
            limit /= 2
            fitted = truncateLists(full, limit)
        }
        return if (limit == listLimit) fitted else JsonObject(fitted + ("cutForSize" to JsonObject(mapOf("listLimit" to JsonPrimitive(limit)))))
    }

    private fun findOccurrences(args: Arguments): JsonElement {
        val query = args.query()
        val after = args.long("after")
        val limit = args.int("limit", 1..FIND_MAX) ?: FIND_DEFAULT
        // A client that drops the arguments it does not know (a misspelled filter) would otherwise list the whole index.
        if (!filtered(query)) {
            throw InvalidQueryException("find_occurrences needs at least one filter: ${QUERY_FILTERS.joinToString(", ")} " +
                "(limit and after are not filters; for counts over the whole index, use group_occurrences)")
        }
        val answer = index.queryOccurrences(query, after, limit, summary = true)
        // after is an occurrence id (rows come in id order), so a cut page goes on right after its last row kept.
        return McpAnswers.fitList(toolAnswer(answer), maxChars, { it.result().getValue("rows").jsonArray }, { a, rows -> a.withResult("rows", rows) }) { a, rows ->
            a.withResult("nextAfter", rows.last().jsonObject.getValue("id"))
        }
    }

    private fun findUsages(args: Arguments): JsonElement {
        val key = args.requiredString("key")
        val include = include(args, key)
        val answer = index.occurrences(
            key, args.list("roles").toSet(), args.list("statuses").toSet().ifEmpty { null }, (include + Scope.SELF).toSet(),
            args.boolean("candidates") ?: true, args.long("after"), args.int("limit", 1..FIND_MAX) ?: FIND_DEFAULT, via(args)
        )
        if (answer.result == null) throw unknownEntity(key)
        val answered = JsonObject(toolAnswer(answer) + ("included" to included(include)))
        // after is an occurrence id here too (rows in id order), so a cut page goes on right after its last row kept.
        return McpAnswers.fitList(answered, maxChars, { it.result().getValue("rows").jsonArray }, { a, rows -> a.withResult("rows", rows) }) { a, rows ->
            a.withResult("nextAfter", rows.last().jsonObject.getValue("id"))
        }
    }

    private fun groupUsages(args: Arguments): JsonElement {
        val key = args.requiredString("key")
        val dimensions = dimensions(args)
        val include = include(args, key)
        val answer = index.groupUsages(
            key, dimensions, args.list("roles").toSet(), args.list("statuses").toSet().ifEmpty { null }, (include + Scope.SELF).toSet(),
            args.boolean("candidates") ?: true, via(args), args.int("limit", 1..GROUP_MAX) ?: GROUP_DEFAULT
        )
        if (answer.result == null) throw unknownEntity(key)
        val answered = JsonObject(toolAnswer(answer) + ("included" to included(include)))
        return McpAnswers.fitList(answered, maxChars, { it.result().getValue("groups").jsonArray }, { a, groups -> a.withResult("groups", groups) })
    }

    private fun unknownEntity(key: String) = ToolException("Unknown entity: $key. Entity keys come from search_entities or from earlier answers.")

    private fun included(include: List<Scope>) = JsonArray(include.map { JsonPrimitive(it.name) })

    private fun via(args: Arguments) = args.string("via")?.let { value ->
        UsagePath.entries.firstOrNull { it.name == value } ?: throw InvalidQueryException("Unknown via value: $value")
    }

    private fun dimensions(args: Arguments) = args.requiredList("group_by").map { value ->
        GroupDimension.entries.firstOrNull { it.name == value } ?: throw InvalidQueryException("Unknown group_by dimension: $value")
    }

    /** The include asked for, else the entity page's default for the kind: a package's usages are its members'. */
    private fun include(args: Arguments, key: String): List<Scope> =
        if (args.has("include")) {
            args.list("include").map { value -> USAGE_SCOPES.firstOrNull { it.name == value } ?: throw InvalidQueryException("Unknown include value: $value") }
        } else {
            defaultUsageScopes(index.entity(key).result?.kind ?: throw unknownEntity(key))
        }

    private fun groupOccurrences(args: Arguments): JsonElement {
        val dimensions = dimensions(args)
        val query = args.query()
        val answer = index.groupOccurrences(query, dimensions, args.int("limit", 1..GROUP_MAX) ?: GROUP_DEFAULT)
        // Without a filter the groups are the whole index's: said in the answer, for an agent that thought it sent one.
        val answered = toolAnswer(answer).let { if (filtered(query)) it else JsonObject(it + ("wholeIndex" to JsonPrimitive(true))) }
        return McpAnswers.fitList(answered, maxChars, { it.result().getValue("groups").jsonArray }, { a, groups -> a.withResult("groups", groups) })
    }

    /** Whether [query] narrows the occurrences: a modifier alone (inside, container_inside, candidates) does not. */
    private fun filtered(query: OccurrenceQuery) = query.copy(inside = false, containerInside = true, candidates = true) != OccurrenceQuery()

    private fun findImpact(args: Arguments): JsonElement {
        val key = args.requiredString("key")
        val include = include(args, key)
        val stop = args.string("stop")?.let { value -> ImpactStop.entries.firstOrNull { it.name == value } ?: throw InvalidQueryException("Unknown stop value: $value") }
        val offset = args.int("offset", 0..Int.MAX_VALUE) ?: 0
        val limit = args.int("limit", 1..IMPACT_MAX) ?: IMPACT_DEFAULT
        val keys = args.boolean("keys") ?: false
        val followTriggers = args.boolean("follow_triggers") ?: true
        // The whole graph, so that a node keeps its number whatever the page and the stop filter.
        val answer = index.impactGraph(
            key, args.list("roles").toSet(), args.list("statuses").toSet().ifEmpty { null }, include,
            args.int("max_depth", 1..IMPACT_DEPTH_MAX) ?: IMPACT_DEPTH_DEFAULT, followTriggers
        )
        val graph = answer.result ?: throw unknownEntity(key)
        val impact = McpImpact(graph.entity, graph.nodes, keys)
        val kept = graph.nodes.indices.filter { stop == null || graph.nodes[it].stop == stop.name }
        val page = kept.drop(offset).take(limit)
        val paged = graph.copy(total = kept.size, offset = offset, nodes = emptyList(), nextOffset = (offset + page.size).takeIf { it < kept.size })
        val nodes = JsonArray(page.map { impact.node(it) })
        val full = toolAnswer(answer.copy(result = paged)).withResult("nodes", nodes)
        // The nodes are in a fixed order, so a cut page goes on at the offset after its last node kept.
        return McpAnswers.fitList(full, maxChars, { it.result().getValue("nodes").jsonArray }, { a, items -> a.withResult("nodes", items) }) { a, items ->
            a.withResult("nextOffset", JsonPrimitive(offset + items.size))
        }
    }

    private fun getValues(args: Arguments): JsonElement {
        val key = args.requiredString("key")
        val limit = args.int("limit", 1..VALUES_MAX) ?: VALUES_DEFAULT
        valueEntity(key, "get_values")
        val answer = index.values(key)
        if (answer.result == null) throw unknownEntity(key)
        val full = toolAnswer(answer)
        val groups = full.getValue("result").jsonArray
        val page = JsonObject(mapOf("groups" to JsonArray(groups.take(limit)), "totalGroups" to JsonPrimitive(groups.size)))
        return McpAnswers.fitList(JsonObject(full + ("result" to page)), maxChars, { it.result().getValue("groups").jsonArray }, { a, kept -> a.withResult("groups", kept) })
    }

    private fun findValueSites(args: Arguments): JsonElement {
        val key = args.requiredString("key")
        val valueKind = args.requiredString("value_kind")
        val value = args.string("value")
        if (value == null && valueKind in VALUE_TEXT_KINDS) throw InvalidQueryException("Missing argument: value")
        val after = args.long("after")
        val limit = args.int("limit", 1..FIND_MAX) ?: FIND_DEFAULT
        val entity = valueEntity(key, "find_value_sites")
        val answer = index.valueSites(key, valueKind, value, after, limit)
        val page = answer.result ?: throw unknownEntity(key)
        // A call's other arguments say nothing about this parameter, and with long signatures they are most of a row.
        val kept = if (entity.kind != "PARAMETER") answer else boundKeys(entity).let { keys ->
            answer.copy(result = page.copy(rows = page.rows.map { row -> row.copy(arguments = row.arguments.filter { it.parameter?.key in keys }) }))
        }
        // after is an occurrence id here too (rows in id order), so a cut page goes on right after its last row kept.
        return McpAnswers.fitList(toolAnswer(kept), maxChars, { it.result().getValue("rows").jsonArray }, { a, rows -> a.withResult("rows", rows) }) { a, rows ->
            a.withResult("nextAfter", rows.last().jsonObject.getValue("id"))
        }
    }

    /**
     * [key]'s entity, which must be a PARAMETER or a COLUMN: the index answers no values for another kind, and an
     * empty answer would read as "no values".
     */
    private fun valueEntity(key: String, tool: String): EntityDetail {
        val entity = index.entity(key).result ?: throw unknownEntity(key)
        if (entity.kind !in VALUE_ENTITY_KINDS) throw ToolException("$tool answers for a PARAMETER or a COLUMN; $key is a ${entity.kind}.")
        return entity
    }

    /** The keys of [parameter] and of its twin: the parameter of the same name in its subprogram's spec or body. */
    private fun boundKeys(parameter: EntityDetail): Set<String> {
        val subprogram = parameter.parent?.let { index.entity(it.key).result } ?: return setOf(parameter.key)
        val twins = subprogram.relations.filter { it.kind == "IMPLEMENTS" }.mapNotNull { index.entity(it.entity.key).result }
            .flatMap { it.children }.map { it.entity }.filter { it.kind == "PARAMETER" && it.name == parameter.name }
        return setOf(parameter.key) + twins.map { it.key }
    }

    private fun searchText(args: Arguments): JsonElement {
        val answer = index.searchText(
            args.requiredString("text"), args.string("path"), args.string("container"), args.string("container_name"),
            args.long("after"), args.int("limit", 1..TEXT_MAX) ?: TEXT_DEFAULT
        )
        // A scoped search leaves wholeIndex out, as group_occurrences does; an unscoped one says how to narrow it.
        val full = toolAnswer(answer)
        val scoped = if (answer.result.wholeIndex) full.withResult("narrow", JsonPrimitive(NARROW))
        else JsonObject(full + ("result" to JsonObject(full.result() - "wholeIndex")))
        // Long lines are cut first, as get_source cuts them; then rows, the page going on after the last row kept.
        val lines = McpAnswers.fitLines(scoped, "rows")
        val fitted = McpAnswers.fitList(lines, maxChars, { it.result().getValue("rows").jsonArray }, { a, rows -> a.withResult("rows", rows) }) { a, rows ->
            a.withResult("nextAfter", rows.last().jsonObject.getValue("id"))
        }
        return fitted
    }

    private fun getSource(args: Arguments): JsonElement {
        val path = args.requiredString("path")
        val answer = index.source(path, args.requiredInt("line"), args.int("context", 0..SOURCE_CONTEXT_MAX) ?: SOURCE_CONTEXT_DEFAULT)
        if (answer.result == null) throw ToolException("Source not available for $path: the server has no sources folder, or the file is not there.")
        return McpAnswers.fitSource(toolAnswer(answer))
    }

    private fun JsonObject.result() = getValue("result").jsonObject

    private fun JsonObject.withResult(name: String, value: JsonElement) = JsonObject(this + ("result" to JsonObject(result() + (name to value))))

    /**
     * Every list of an entity answer cut to [limit] items; `truncated` gives the full size of each list that was cut,
     * so a package with hundreds of members stays one answer the agent can read. The bodies of a spec are kept whole:
     * where they are is what the answer is asked for, and there are few.
     */
    private fun truncateLists(answer: JsonObject, limit: Int): JsonObject {
        val result = answer.getValue("result").jsonObject
        val cut = LinkedHashMap<String, Int>()
        val lists = result.mapValues { (name, value) ->
            if (value is JsonArray && value.size > limit && name != BODIES) {
                cut[name] = value.size
                JsonArray(value.take(limit))
            } else {
                value
            }
        }
        if (cut.isEmpty()) return answer
        return JsonObject(answer + ("result" to JsonObject(lists)) + ("truncated" to JsonObject(cut.mapValues { JsonPrimitive(it.value) })))
    }

    /** Runs [body] off the request thread; a refused request is an error result the agent can act on, not a protocol error. */
    internal suspend fun call(body: () -> JsonElement): CallToolResult = withContext(Dispatchers.IO) {
        try {
            CallToolResult(content = listOf(TextContent(ToolJson.encodeToString(JsonElement.serializer(), body()))))
        } catch (e: ToolException) {
            error(e.message!!)
        } catch (e: InvalidQueryException) {
            error(e.message ?: "Invalid request")
        } catch (e: QueryTimeoutException) {
            error(e.message!!)
        } catch (e: SchemaMismatchException) {
            error(e.message!!)
        }
    }

    private fun error(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)

    // ---- the schemas ------------------------------------------------------------------------------------------------

    private fun produced(list: List<Code>) = list.filterNot { it.reserved }.map { it.code }

    private fun searchSchema() = schema(listOf("query")) {
        string("query", "A name prefix (run_job), or a dotted name (pack_process.run_job; the first part may be the schema).")
        enumString("kind", produced(codes.entityKinds), "Only entities of this kind.")
        boolean("include_external", "Include entities outside the indexed sources (dictionary only). Default true.")
        integer("limit", 1..SEARCH_MAX, SEARCH_DEFAULT)
    }

    private fun entitySchema() = schema(listOf("key")) {
        string("key", KEY_DESCRIPTION)
        integer("list_limit", 1..LIST_MAX, LIST_DEFAULT, "Items kept in each list of the answer; `truncated` gives the size of a cut list.")
    }

    private fun findSchema() = schema(emptyList()) {
        queryFilters()
        integer("after", null, null, AFTER_DESCRIPTION)
        integer("limit", 1..FIND_MAX, FIND_DEFAULT)
    }

    private fun usagesSchema() = schema(listOf("key")) {
        usageFilters()
        integer("after", null, null, AFTER_DESCRIPTION)
        integer("limit", 1..FIND_MAX, FIND_DEFAULT)
    }

    private fun groupUsagesSchema() = schema(listOf("key", "group_by")) {
        usageFilters()
        enumArray("group_by", codesOf(codes.groupDimensions), "One to three dimensions, as in group_occurrences.", minItems = 1, maxItems = 3)
        integer("limit", 1..GROUP_MAX, GROUP_DEFAULT, GROUP_LIMIT_DESCRIPTION)
    }

    private fun impactSchema() = schema(listOf("key")) {
        string("key", KEY_DESCRIPTION)
        enumArray("include", USAGE_SCOPES.map { it.name }, "What the first hop adds to the entity, as in find_usages; the same default by kind.")
        enumArray("roles", produced(codes.roles), "The first hop's uses have any of these roles. Without it, every role but DEFINITION and DECLARATION.")
        enumArray("statuses", produced(codes.statuses), "The first hop's uses have one of these statuses.")
        integer("max_depth", 1..IMPACT_DEPTH_MAX, IMPACT_DEPTH_DEFAULT, "Hops followed; nodes at this depth stop with DEPTH_LIMIT.")
        enumString("stop", ImpactStop.entries.map { it.name }, "Only the nodes that stop for this reason, such as ENTRY_POINT; levels and stops still count the whole graph.")
        integer("offset", 0..Int.MAX_VALUE, 0, "The nextOffset of the previous page.")
        integer("limit", 1..IMPACT_MAX, IMPACT_DEFAULT)
        boolean("keys", "Every node gives its full key, and via.uses is a key, as the index answers; use it with stop or offset to get the keys of the nodes another tool will be called on. Default false: n and name.")
        boolean("follow_triggers", "Default true: a DML trigger node is followed through the writes that fire it. False: every trigger stops with TRIGGER, as calls alone reach code.")
    }

    private fun valuesSchema() = schema(listOf("key")) {
        string("key", "A PARAMETER or COLUMN key, as search_entities or another answer gives it.")
        integer("limit", 1..VALUES_MAX, VALUES_DEFAULT, GROUP_LIMIT_DESCRIPTION)
    }

    private fun valueSitesSchema() = schema(listOf("key", "value_kind")) {
        string("key", "A PARAMETER or COLUMN key, as search_entities or another answer gives it.")
        enumString("value_kind", produced(codes.valueKinds), "The valueKind of a get_values group.")
        string("value", "The value of that group, exactly as get_values gives it: S, not 'S'. Required for STRING, " +
            "NUMBER, BOOLEAN and NAME; ignored for the other kinds.")
        integer("after", null, null, AFTER_DESCRIPTION)
        integer("limit", 1..FIND_MAX, FIND_DEFAULT)
    }

    private fun SchemaBuilder.usageFilters() {
        string("key", KEY_DESCRIPTION)
        enumArray("include", USAGE_SCOPES.map { it.name }, "Entities added to the one asked for: IMPLEMENTATIONS its spec or " +
            "body, CHILDREN its contents, VIEWS the view columns computed from it and the views reading it, transitively. " +
            "Default by kind, as the entity page: IMPLEMENTATIONS for a procedure or function, IMPLEMENTATIONS and CHILDREN " +
            "for a package, CHILDREN for a type, none otherwise; [] for the entity alone. The answer's included lists what was followed.")
        enumArray("roles", produced(codes.roles), "The occurrence has any of these roles. Without it, every role but DEFINITION and DECLARATION.")
        enumArray("statuses", produced(codes.statuses), "The occurrence has one of these statuses.")
        boolean("candidates", "Include AMBIGUOUS occurrences where one of the entities is a candidate. Default true.")
        enumString("via", UsagePath.entries.map { it.name }, "DIRECT: only rows on the entity, its spec or body, or its " +
            "contents; THROUGH_VIEWS: only rows reached through a view (viaView). Default both.")
    }

    private fun groupSchema() = schema(listOf("group_by")) {
        queryFilters()
        enumArray("group_by", codesOf(codes.groupDimensions), "One to three dimensions.", minItems = 1, maxItems = 3)
        integer("limit", 1..GROUP_MAX, GROUP_DEFAULT, GROUP_LIMIT_DESCRIPTION)
    }

    private fun textSchema() = schema(listOf("text")) {
        string("text", "A literal piece of text, 3 characters or more, compared without case; * is a character like any other.")
        string("path", "Only files whose path starts with this, or with * a pattern of the whole path.")
        string("container", "Only lines inside this entity key (a package, a subprogram), its contents included.")
        string("container_name", "Only lines inside every entity this dotted name matches (pack, pack.proc, * as wildcard), contents included.")
        integer("after", null, null, AFTER_DESCRIPTION)
        integer("limit", 1..TEXT_MAX, TEXT_DEFAULT)
    }

    private fun sourceSchema() = schema(listOf("path", "line")) {
        string("path", "A file path as an answer gives it (relative to the sources folder).")
        integer("line", 1..Int.MAX_VALUE, null, "The line, from 1.")
        integer("context", 0..SOURCE_CONTEXT_MAX, SOURCE_CONTEXT_DEFAULT, "Lines shown before and after it.")
    }

    private fun codesOf(list: List<Code>) = list.map { it.code }

    private fun SchemaBuilder.queryFilters() {
        string("entity", "Occurrences of this entity key.")
        boolean("inside", "With entity: also its contents (a package's members, a table's columns). Default false.")
        enumArray("entity_kinds", produced(codes.entityKinds), "The occurrence's entity has one of these kinds.")
        string("name", "The entity's name, or a dotted name (company.st_active, hr.company.st_active); * is a wildcard.")
        string("schema", "The entity's schema, exact.")
        boolean("external", "true: only entities outside the indexed sources; false: only inside.")
        string("container", "Occurrences inside this entity key (a package, a subprogram).")
        string("container_name", "Occurrences inside every entity this dotted name matches, as for name.")
        boolean("container_inside", "container and container_name include their contents. Default true.")
        enumArray("unit_kinds", produced(codes.unitKinds), "The occurrence is in a unit of one of these kinds.")
        string("path", "The file path: a prefix, or with * a pattern of the whole path.")
        enumArray("roles", produced(codes.roles), "The occurrence has any of these roles. Without it, every role, definitions included.")
        enumArray("statuses", produced(codes.statuses), "The occurrence has one of these statuses.")
        stringArray("reasons", "The occurrence has one of these reasons (such as NOT_IN_DICTIONARY).")
        enumArray("origins", produced(codes.origins), "STATIC for code, DYNAMIC_LITERAL for a name inside dynamic SQL text.")
        enumArray("statement_kinds", produced(codes.statementKinds), "The occurrence is in a SQL statement of one of these kinds.")
        enumString("value_kind", produced(codes.valueKinds), "A value written or passed of this kind.")
        string("value", "The value written or passed, exact or with *; with value_kind, or any kind with text.")
        boolean("candidates", "An AMBIGUOUS occurrence matches the entity filters through its candidates. Default true.")
    }

    // ---- reading the arguments ---------------------------------------------------------------------------------------

    /** The arguments of one call; a value of the wrong type is refused with the argument's name. */
    class Arguments(tool: String, accepted: Set<String>, arguments: JsonObject?) {
        private val values = arguments ?: JsonObject(emptyMap())

        init {
            values.keys.firstOrNull { it !in accepted }?.let {
                throw InvalidQueryException("Unknown argument: $it. $tool takes: ${accepted.joinToString(", ")}")
            }
        }

        private fun primitive(name: String): JsonPrimitive? = when (val value = values[name]) {
            null, JsonNull -> null
            is JsonPrimitive -> value
            else -> throw InvalidQueryException("$name must be a single value")
        }

        fun string(name: String): String? =
            primitive(name)?.let { if (it.isString) it.content.ifEmpty { null } else throw InvalidQueryException("$name must be a string") }

        fun requiredString(name: String) = string(name) ?: throw InvalidQueryException("Missing argument: $name")

        fun boolean(name: String): Boolean? = primitive(name)?.let { it.booleanOrNull ?: throw InvalidQueryException("$name must be true or false") }

        fun long(name: String): Long? {
            val value = primitive(name) ?: return null
            return (if (value.isString) null else value.longOrNull) ?: throw InvalidQueryException("$name must be an integer")
        }

        fun int(name: String, range: IntRange? = null): Int? {
            val value = primitive(name) ?: return null
            val number = (if (value.isString) null else value.intOrNull) ?: throw InvalidQueryException("$name must be an integer")
            if (range != null && number !in range) throw InvalidQueryException("$name must be between ${range.first} and ${range.last}")
            return number
        }

        fun requiredInt(name: String) = int(name) ?: throw InvalidQueryException("Missing argument: $name")

        fun has(name: String) = values[name].let { it != null && it !is JsonNull }

        fun list(name: String): List<String> = when (val value = values[name]) {
            null, JsonNull -> emptyList()
            is JsonArray -> value.map { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw InvalidQueryException("$name must be an array of strings")
            }
            else -> throw InvalidQueryException("$name must be an array of strings")
        }

        fun requiredList(name: String) = list(name).ifEmpty { throw InvalidQueryException("Missing argument: $name") }

        fun query() = OccurrenceQuery(
            entity = string("entity"),
            inside = boolean("inside") ?: false,
            entityKinds = list("entity_kinds").toSet(),
            name = string("name"),
            schema = string("schema"),
            external = boolean("external"),
            container = string("container"),
            containerName = string("container_name"),
            containerInside = boolean("container_inside") ?: true,
            unitKinds = list("unit_kinds").toSet(),
            path = string("path"),
            roles = list("roles").toSet(),
            statuses = list("statuses").toSet(),
            reasons = list("reasons").toSet(),
            origins = list("origins").toSet(),
            statementKinds = list("statement_kinds").toSet(),
            valueKind = string("value_kind"),
            value = string("value"),
            candidates = boolean("candidates") ?: true
        )
    }

    internal class ToolException(message: String) : RuntimeException(message)

    companion object {
        private const val KEY_DESCRIPTION = "An entity key, as search_entities or another answer gives it."
        private const val AFTER_DESCRIPTION = "The nextAfter of the previous page."
        private const val GROUP_LIMIT_DESCRIPTION = "The first groups by count; totalGroups counts them all."

        // The limits keep an answer within what an agent reads at once: a typical page under about 10,000 tokens, the
        // largest under 25,000 (the size Claude Code refuses by default).
        const val SEARCH_DEFAULT = 20
        const val SEARCH_MAX = 100
        const val LIST_DEFAULT = 30
        const val LIST_MAX = 100
        const val FIND_DEFAULT = 20
        const val FIND_MAX = 40
        const val IMPACT_DEFAULT = 100
        const val IMPACT_MAX = 200
        const val IMPACT_DEPTH_DEFAULT = 10
        const val IMPACT_DEPTH_MAX = 50
        const val GROUP_DEFAULT = 50
        const val GROUP_MAX = 100
        const val VALUES_DEFAULT = 50
        const val VALUES_MAX = 200
        const val SOURCE_CONTEXT_DEFAULT = 3
        const val SOURCE_CONTEXT_MAX = 50
        const val TEXT_DEFAULT = 50
        const val TEXT_MAX = 200

        /** The JSON of a tool answer: the `/api` model, a null field left out to save the agent's context. */
        val ToolJson = Json { explicitNulls = false; encodeDefaults = true }

        /**
         * [answer] as a tool gives it, before any cut for size: its JSON made compact by [McpAnswers.compact], without
         * the run stamp the page shows in its banner. Nothing in it changes an agent's next call: a changed file is
         * told per row (stale), and the atlas version is in serverInfo.
         */
        internal inline fun <reified T> toolAnswer(answer: T): JsonObject =
            JsonObject(McpAnswers.compact(ToolJson.encodeToJsonElement(answer)).jsonObject - "run")

        /** The arguments of find_occurrences and group_occurrences that narrow the occurrences. */
        private val QUERY_FILTERS = listOf("entity", "entity_kinds", "name", "schema", "external", "container", "container_name",
            "unit_kinds", "path", "roles", "statuses", "reasons", "origins", "statement_kinds", "value_kind", "value")

        /** Every tool only reads the index. */
        val READ_ONLY = ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)

        /** The field of a spec's answer that lists its bodies. */
        private const val BODIES = "bodies"

        const val INSTRUCTIONS =
            "zpa-atlas is an index of the PL/SQL code of an Oracle database (packages, procedures, functions, " +
                "triggers, tables, views, columns): ask it who calls, reads or writes something, for impact analysis, " +
                "and for the values passed to a parameter or written to a column.\n" +
                "Which tool answers which question:\n" +
                "- search_entities: the key of an entity from its name; dotted names work (pack.proc, table.column).\n" +
                "- get_entity: what an entity is: kind, definitions, members or columns, the views reading it; for a " +
                "routine, where its spec and its bodies are.\n" +
                "- find_usages: who uses one entity, following its spec and body, its members and the views built on " +
                "it; group_usages counts them by routine, file or view. via keeps only the direct uses or only those " +
                "through views. For a table, CHILDREN with VIEWS also follows the view columns computed from its columns.\n" +
                "- find_occurrences, group_occurrences: questions across the code: every write in a package, calls by " +
                "name pattern, a CRUD matrix, rows by status or by value.\n" +
                "- get_values: the values a parameter is passed or a column is written with; then find_value_sites: " +
                "where one of them comes from.\n" +
                "- get_source: the text around a line. Sources are only on the server, paths relative to its folder: " +
                "not on the local disk.\n" +
                "- find_impact: impact analysis, who calls the callers up to the entry points; nodes are numbered (n), " +
                "keys=true gives their full keys.\n" +
                "- search_text: other text (XML tags, messages, comments, SQL in strings). In one call, pass the " +
                "package or routine the question names as container_name, or a file or folder as path; whole index " +
                "only if it names none.\n" +
                "Usual order: a group_ tool for the summary, then a find_ tool for the lines, then get_source for the text.\n" +
                "Null fields are left out of an answer, and so is an entity's " +
                "display when it is its key without db:. An answer over ${McpAnswers.MAX_CHARS} " +
                "characters is cut, and cutForSize says how: the rows kept (page on from nextAfter), the lower list " +
                "limit of an entity, or the source lines shortened. A usage tool's included lists what it followed."

        /** How an unscoped text search is narrowed. */
        private const val NARROW = "The whole index was searched: pass the package or routine name as container_name, or a " +
            "file or folder as path, to search only there."

        /** What the index does not answer, said by the tools that would otherwise seem to. */
        private const val NOT_ANSWERED = "Not indexed: Forms, reports and code outside the indexed sources; dynamic SQL is partial. " +
            "A status other than RESOLVED means the index is not sure: say so in the answer."

        private const val PATHS = "Its paths are on the server, relative to its sources folder: read the text with get_source, not from a local disk."

        private const val KEYS =
            "Entity keys: db:SCHEMA.NAME; a member as db:SCHEMA.PACKAGE.MEMBER, a column as db:SCHEMA.TABLE.COLUMN. " +
                "A procedure or function ends with its parameter list, db:HR.PKG.RUN(P_A IN NUMBER,P_B IN VARCHAR2), " +
                "and its parameters add .P_A. Its body adds @BODY after the list; #2, #3 tell apart declarations with " +
                "the same signature. ~ is the schema when none is known (db:~.DBMS_SQL). An object through a database " +
                "link is db:SCHEMA.NAME@LINK, or db:NAME@LINK when no schema is written."

        private const val STATUSES =
            "status: RESOLVED is proven. The others say why the index is not sure: UNVERIFIED (not checked, no " +
                "dictionary), AMBIGUOUS (several candidates, listed in candidates), UNRESOLVED (named but not found; " +
                "reason says why), EXTERNAL (beyond a database link), DYNAMIC (dynamic SQL), PARTIAL_SOURCE (in code " +
                "the parser recovered)."

        private const val ROLES =
            "roles: DEFINITION, DECLARATION; READ, WRITE (a variable, or a column in SQL); CALL; SQL_SELECT, " +
                "SQL_INSERT, SQL_UPDATE, SQL_DELETE, SQL_MERGE, SQL_LOCK (a table, view or column in that statement); " +
                "TYPE_USE (as a type, %TYPE, %ROWTYPE); RAISE, HANDLE (exceptions); IMPLICIT (used without being " +
                "named: select *, insert without a column list); DDL_CREATE, DDL_ALTER, DDL_DROP, DDL_TRUNCATE, " +
                "DDL_ANALYZE (the object a DDL statement acts on)."

        const val SEARCH = "Finds the key of an entity by its name: tables, views, columns, packages, procedures, " +
            "functions, parameters, types, sequences, synonyms, triggers. Exact name matches first. $KEYS"

        const val ENTITY = "What one entity is: its kind, parent, definitions (file and line), overloads, children " +
            "(members or columns, each with its number of uses), relations, triggers and the views that read it. For a " +
            "package routine, bodies lists the key and definitions of every body (two when the body is in two files), " +
            "never cut; for a body, spec gives those of its spec: one call answers where both are. $PATHS $KEYS"

        const val FIND = "Where entities are defined, called, read or written across the code (occurrences), with " +
            "filters combined with AND (at least one; for the whole index, use group_occurrences), and total and " +
            "facets counted over every match. Pages: pass " +
            "the answer's nextAfter as after; nextAfter is absent on the last page. Each row has its status and " +
            "reason; facets.byStatus counts the statuses of every match. A call's arguments name their parameter " +
            "(P_A); its key is the row's entity key, a dot and that name. $PATHS $STATUSES $ROLES $NOT_ANSWERED"

        const val USAGES = "Who calls, reads or writes one entity, for impact analysis: use it instead of " +
            "find_occurrences for one entity, following its spec or body and the views built on it. For a table, " +
            "CHILDREN with VIEWS also follows the view columns computed from its columns. Rows as in find_occurrences, " +
            "in source order, definitions and declarations left out unless roles asks for them; viaView is true on a row " +
            "reached through a view (a view column computed from the entity, or a view reading it), viaCandidate on an " +
            "AMBIGUOUS row where the entity is one of the candidates. One hop only: for the callers of the callers, up to " +
            "the entry points, use find_impact. Pages as in find_occurrences. $PATHS $STATUSES $NOT_ANSWERED"

        const val IMPACT = "Impact analysis: who calls the callers of an entity, transitively, up to the entry " +
            "points (code nothing in the index calls, run from Forms, jobs or other systems). The first hop is who uses " +
            "the entity, as find_usages; then the walk follows the uses that run each node: calls of a routine or type, " +
            "SQL on a view, reading a cursor, and for a DML trigger the INSERT, UPDATE, DELETE or MERGE that fire it (its " +
            "table, through a synonym or a one-base view; UPDATE OF by the SET columns; via.fires names the events; " +
            "for a trigger asked for, they are the first hop). " +
            "A node is a routine (spec and body together), view, trigger, type, package " +
            "or, for script code, the file. A view reached from a column is followed as a whole. Each node has n (its " +
            "number in the whole graph, the same on every page), name (the display without the parameter list, @BODY " +
            "kept for a private routine; key too, only when another node has the same name, as overloads do), its depth " +
            "(the shortest path), via (one use on that path: uses, the n of the node it uses, or 0 on the first hop, with " +
            "usesName when that hop matched another entity than the one asked for; file and line), certain (some path of RESOLVED " +
            "uses only) and stop: FOLLOWED (code outside its own cycle uses it), ENTRY_POINT (nothing in the index calls it but " +
            "itself or the routines it calls back, so only outside code can; for a trigger, nothing writes its table), " +
            "TRIGGER (a system trigger, or follow_triggers=false; trigger gives table and events), PACKAGE_LEVEL (package initialization or " +
            "declarations, not followed), DEPTH_LIMIT, NO_CONTAINER (a script file). levels counts nodes per depth and " +
            "stops per reason; stop=ENTRY_POINT lists only the entry points. Pages: pass nextOffset as offset. keys=true " +
            "gives every node's full key and via.uses as a key: use it with stop or offset to get the keys of the " +
            "nodes another tool will be called on. $PATHS"

        const val GROUP_USAGES = "Counts who uses one entity, by routine, file or view: the rows find_usages lists for " +
            "the same arguments, grouped by one to three dimensions as in group_occurrences; ENTITY is the entity a " +
            "row matched, so on a row " +
            "through a view it names the view or view column. Example, who uses a table through views, by view and " +
            "routine, in one call: key the table, include [VIEWS], via THROUGH_VIEWS, group_by ENTITY, CONTAINER. " +
            "For a table, CHILDREN with VIEWS also follows the view columns computed from its columns. include " +
            "defaults by kind as in find_usages; the answer's included lists what was followed."

        private val VALUE_ENTITY_KINDS = setOf("PARAMETER", "COLUMN")

        /** The value kinds that carry a value: find_value_sites needs one for them. */
        private val VALUE_TEXT_KINDS = setOf("STRING", "NUMBER", "BOOLEAN", "NAME")

        private const val VALUE_WHY =
            "Use it, not the value_kind and value filters of find_occurrences, for one parameter or column: those " +
                "filters match any argument or written value of an occurrence, not the one this parameter receives or " +
                "this column is written with; for a parameter of a body subprogram, the arguments bound to its spec's " +
                "parameter count too (calls bind the spec); for a column, the writes through the views that reach it " +
                "count too."

        private const val VALUE_KIND_NOTES =
            "STRING, NUMBER, BOOLEAN and NAME carry a value: a string without its quotes (S, not 'S'), a number as a " +
                "canonical number (1.5 for 1.50), TRUE or FALSE, a name as an identifier, upper case unless quoted (a " +
                "variable, constant, column or record field: R.CODE). BIND, EXPRESSION, NULL and DEFAULT carry none, and each is one " +
                "group: BIND a bind variable, EXPRESSION anything computed (a call, a concatenation, a sum), NULL " +
                "null or '', DEFAULT a column written with the keyword DEFAULT (insert ... values (default), update " +
                "... set c = default), so the column's default applies. An argument left out of a call is not indexed, " +
                "so a parameter's default is not counted."

        const val VALUES = "The values passed to a PARAMETER or written to a COLUMN, grouped by valueKind and " +
            "value, most frequent first, each with its count; totalGroups counts every group. $VALUE_WHY " +
            "$VALUE_KIND_NOTES Then find_value_sites, with a group's valueKind and value, lists where it comes from. " +
            "Any other kind of entity is an error."

        const val VALUE_SITES = "Where a PARAMETER is passed or a COLUMN is written with one value: the calls or " +
            "writes, for the valueKind and value of one get_values group. value is required for STRING, NUMBER, " +
            "BOOLEAN and NAME and matched " +
            "exactly as get_values gives it (S, not 'S'); for BIND, EXPRESSION, NULL and DEFAULT it is ignored and " +
            "every row of the kind matches. $VALUE_WHY Rows as in find_usages, in source order. On a parameter's " +
            "row, arguments keeps only the argument bound to that parameter (or to its spec or body twin): the " +
            "call's other arguments are left out. A column's row is whole, with the values written. Pages as in " +
            "find_occurrences. Any other kind of entity is an error. $PATHS"

        private val USAGE_SCOPES = listOf(Scope.IMPLEMENTATIONS, Scope.CHILDREN, Scope.VIEWS)

        const val GROUP = "Counts occurrences across the code, by routine, entity, role, file, status or value, with " +
            "one to three dimensions and the same filters as find_occurrences. A CRUD matrix of a package: " +
            "container_name or container the package, roles " +
            "SQL_SELECT, SQL_INSERT, SQL_UPDATE, SQL_DELETE, group_by CONTAINER, ENTITY, ROLE. ROLE counts an " +
            "occurrence once per role. A group value without entity is an occurrence tied to no entity; add STATUS " +
            "to group_by to tell proven occurrences from the others. To follow an entity's spec or body, contents or " +
            "views, use group_usages. Without any filter it counts the whole index, and the answer has wholeIndex. $STATUSES $NOT_ANSWERED"

        const val TEXT = "Lines of the indexed sources that contain a piece of text, compared without case: for text " +
            "that is not an entity or a value, such as an XML tag ('<vFixTag>' or XMLElement(\"vFixTag\")), a message, " +
            "a comment or SQL inside a string. Scope it in the same call: when the question names a package or " +
            "routine, pass that name as container_name (pack or pack.proc, as written); a file or folder, as path. " +
            "Search the whole index only when the question names no unit. Then call get_source on a row's path and line to read around " +
            "it. The text is literal, * included, and has at least 3 characters; each line is searched alone, so a text " +
            "across a line break is never found. Whole files are searched as indexed: comments, strings and inactive " +
            "conditional compilation branches included. path, container (a key) and container_name narrow the search, " +
            "combined with AND, a container with its contents; without them the whole index is searched, and the " +
            "answer has wholeIndex and narrow. Each row has path, line, text, and entity: the innermost package, " +
            "routine, trigger, view, type or cursor the line is in, when known. Rows come in file and line order; total counts every line found; pages: " +
            "pass nextAfter as after. stale is true when the file changed since indexing. $PATHS"

        const val SOURCE = "The source text of an indexed file around a line, as the index read it; stale is true " +
            "when the file changed since it was indexed. The files are on the server, not on the local disk: path is " +
            "relative to the server's sources folder, as the other tools give it."
    }
}

/** A tool's input schema, built property by property. */
private class SchemaBuilder {
    val properties = LinkedHashMap<String, JsonObject>()

    fun string(name: String, description: String) {
        properties[name] = buildJsonObject { put("type", "string"); put("description", description) }
    }

    fun boolean(name: String, description: String) {
        properties[name] = buildJsonObject { put("type", "boolean"); put("description", description) }
    }

    fun integer(name: String, range: IntRange?, default: Int?, description: String? = null) {
        properties[name] = buildJsonObject {
            put("type", "integer")
            if (range != null) {
                put("minimum", range.first)
                if (range.last != Int.MAX_VALUE) put("maximum", range.last)
            }
            if (default != null) put("default", default)
            if (description != null) put("description", description)
        }
    }

    fun enumString(name: String, values: List<String>, description: String) {
        properties[name] = buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
            put("description", description)
        }
    }

    fun enumArray(name: String, values: List<String>, description: String, minItems: Int? = null, maxItems: Int? = null) {
        properties[name] = buildJsonObject {
            put("type", "array")
            putJsonObject("items") {
                put("type", "string")
                putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
            }
            if (minItems != null) put("minItems", minItems)
            if (maxItems != null) put("maxItems", maxItems)
            put("description", description)
        }
    }

    fun stringArray(name: String, description: String) {
        properties[name] = buildJsonObject {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
            put("description", description)
        }
    }
}

private fun schema(required: List<String>, block: SchemaBuilder.() -> Unit): ToolSchema {
    val builder = SchemaBuilder().apply(block)
    return ToolSchema(properties = JsonObject(builder.properties), required = required.ifEmpty { null })
}
