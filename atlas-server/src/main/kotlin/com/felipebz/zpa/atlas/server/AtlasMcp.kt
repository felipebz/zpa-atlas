//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.query.Code
import com.felipebz.zpa.atlas.query.GroupDimension
import com.felipebz.zpa.atlas.query.InvalidQueryException
import com.felipebz.zpa.atlas.query.OccurrenceQuery
import com.felipebz.zpa.atlas.query.QueryTimeoutException
import com.felipebz.zpa.atlas.query.Scope
import com.felipebz.zpa.atlas.query.SchemaMismatchException
import com.felipebz.zpa.atlas.query.UsagePath
import com.felipebz.zpa.atlas.query.indexCodes
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.http.content.TextContent as HttpText
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Properties
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 * [MCP_LOCAL_HOSTS] and [McpSettings.allowedHosts] are refused.
 */
fun Application.atlasMcp(index: AtlasIndex, settings: McpSettings) {
    val hosts = (MCP_LOCAL_HOSTS + settings.allowedHosts).distinct()
    install(ClosedToolSchemas)
    mcpStreamableHttp(
        path = "/mcp",
        allowedHosts = hosts,
        // With hosts of its own the SDK checks no Origin: check it against the same names.
        allowedOrigins = hosts.map { "http://$it" },
        sseHeartbeatConfig = { period = settings.heartbeat }
    ) { atlasMcpServer(index, settings.maxAnswerChars) }
}

/**
 * Adds `"additionalProperties": false` to every input schema of a `tools/list` answer on `/mcp`. The tools refuse an
 * argument they do not know; the SDK's `ToolSchema` has no field to say so, so the answer's JSON is completed here.
 */
private val ClosedToolSchemas = createApplicationPlugin("ClosedToolSchemas") {
    // Installed before the SDK's ContentNegotiation, so the answer is still the SDK's object here, not compressed bytes.
    onCallRespond { call, body ->
        if (call.request.path() != "/mcp" || body !is JSONRPCResponse || body.result !is ListToolsResult) return@onCallRespond
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

    fun register(server: Server) {
        val readOnly = ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)
        fun add(name: String, description: String, schema: ToolSchema, body: (Arguments) -> JsonElement) {
            val accepted = schema.properties?.keys.orEmpty()
            server.addTool(name, description, schema, toolAnnotations = readOnly) { call { body(Arguments(name, accepted, it.arguments)) } }
        }
        add("search_entities", SEARCH, searchSchema(), ::searchEntities)
        add("get_entity", ENTITY, entitySchema(), ::getEntity)
        add("find_occurrences", FIND, findSchema(), ::findOccurrences)
        add("find_usages", USAGES, usagesSchema(), ::findUsages)
        add("group_usages", GROUP_USAGES, groupUsagesSchema(), ::groupUsages)
        add("group_occurrences", GROUP, groupSchema(), ::groupOccurrences)
        add("get_source", SOURCE, sourceSchema(), ::getSource)
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
        val answer = index.entity(key)
        if (answer.result == null) throw ToolException("Unknown entity: $key. Entity keys come from search_entities or from earlier answers.")
        val full = toolAnswer(answer)
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
        val answer = index.queryOccurrences(args.query(), args.long("after"), args.int("limit", 1..FIND_MAX) ?: FIND_DEFAULT, summary = true)
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
            val kind = index.entity(key).result?.kind ?: throw unknownEntity(key)
            listOfNotNull(
                Scope.IMPLEMENTATIONS.takeIf { kind in setOf("PROCEDURE", "FUNCTION", "PACKAGE") },
                Scope.CHILDREN.takeIf { kind in setOf("PACKAGE", "TYPE") }
            )
        }

    private fun groupOccurrences(args: Arguments): JsonElement {
        val dimensions = dimensions(args)
        val answer = index.groupOccurrences(args.query(), dimensions, args.int("limit", 1..GROUP_MAX) ?: GROUP_DEFAULT)
        return McpAnswers.fitList(toolAnswer(answer), maxChars, { it.result().getValue("groups").jsonArray }, { a, groups -> a.withResult("groups", groups) })
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
     * so a package with hundreds of members stays one answer the agent can read.
     */
    private fun truncateLists(answer: JsonObject, limit: Int): JsonObject {
        val result = answer["result"]!!.jsonObject
        val cut = LinkedHashMap<String, Int>()
        val lists = result.mapValues { (name, value) ->
            if (value is JsonArray && value.size > limit) {
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
    private suspend fun call(body: () -> JsonElement): CallToolResult = withContext(Dispatchers.IO) {
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
        string("key", "An entity key, as search_entities or another answer gives it.")
        integer("list_limit", 1..LIST_MAX, LIST_DEFAULT, "Items kept in each list of the answer; `truncated` gives the size of a cut list.")
    }

    private fun findSchema() = schema(emptyList()) {
        queryFilters()
        integer("after", null, null, "The nextAfter of the previous page.")
        integer("limit", 1..FIND_MAX, FIND_DEFAULT)
    }

    private fun usagesSchema() = schema(listOf("key")) {
        usageFilters()
        integer("after", null, null, "The nextAfter of the previous page.")
        integer("limit", 1..FIND_MAX, FIND_DEFAULT)
    }

    private fun groupUsagesSchema() = schema(listOf("key", "group_by")) {
        usageFilters()
        enumArray("group_by", codesOf(codes.groupDimensions), "One to three dimensions, as in group_occurrences.", minItems = 1, maxItems = 3)
        integer("limit", 1..GROUP_MAX, GROUP_DEFAULT, "The first groups by count; totalGroups counts them all.")
    }

    private fun SchemaBuilder.usageFilters() {
        string("key", "An entity key, as search_entities or another answer gives it.")
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
        integer("limit", 1..GROUP_MAX, GROUP_DEFAULT, "The first groups by count; totalGroups counts them all.")
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
    private class Arguments(tool: String, accepted: Set<String>, arguments: JsonObject?) {
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

    private class ToolException(message: String) : RuntimeException(message)

    companion object {
        // The limits keep an answer within what an agent reads at once: a typical page under about 10,000 tokens, the
        // largest under 25,000 (the size Claude Code refuses by default).
        const val SEARCH_DEFAULT = 20
        const val SEARCH_MAX = 100
        const val LIST_DEFAULT = 30
        const val LIST_MAX = 100
        const val FIND_DEFAULT = 20
        const val FIND_MAX = 40
        const val GROUP_DEFAULT = 50
        const val GROUP_MAX = 100
        const val SOURCE_CONTEXT_DEFAULT = 3
        const val SOURCE_CONTEXT_MAX = 50

        /** The JSON of a tool answer: the `/api` model, a null field left out to save the agent's context. */
        val ToolJson = Json { explicitNulls = false; encodeDefaults = true }

        /** [answer] as a tool gives it, before any cut for size: its JSON made compact by [McpAnswers.compact]. */
        internal inline fun <reified T> toolAnswer(answer: T): JsonObject = McpAnswers.compact(ToolJson.encodeToJsonElement(answer)).jsonObject

        const val INSTRUCTIONS =
            "zpa-atlas answers questions about an indexed PL/SQL code base: where an entity is defined, who reads, " +
                "writes or calls it, and what a program uses. Find keys with search_entities, then ask get_entity, " +
                "find_occurrences or group_occurrences. Every answer carries the run it was read from; null fields " +
                "are left out, and so is an entity's display when it is its key without db: (the usual case). An " +
                "answer over ${McpAnswers.MAX_CHARS} characters is cut, and cutForSize says how: the rows kept (page on " +
                "from nextAfter), the lower list limit of an entity, or the source lines shortened."

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

        const val SEARCH = "Finds entities by name: tables, views, columns, packages, procedures, functions, " +
            "parameters, types, sequences, synonyms, triggers. Exact name matches first. $KEYS"

        const val ENTITY = "One entity: its kind, parent, definitions (file and line), overloads, children (members " +
            "or columns, each with its number of uses), relations, triggers and the views that read it. $KEYS"

        const val FIND = "Lists occurrences (places in the source where entities are defined or used), with total " +
            "and facets counted over every match. Every filter is optional and they combine with AND. Pages: pass " +
            "the answer's nextAfter as after; nextAfter is absent on the last page. Each row has its status and " +
            "reason; facets.byStatus counts the statuses of every match. A call's arguments name their parameter " +
            "(P_A); its key is the row's entity key, a dot and that name. $STATUSES $ROLES"

        const val USAGES = "The usages of one entity, as its page lists them: use it instead of find_occurrences " +
            "for who uses an entity, following its spec or body and the views built on it. Rows as in find_occurrences, " +
            "in source order, definitions and declarations left out unless roles asks for them; viaView is true on a row " +
            "reached through a view (a view column computed from the entity, or a view reading it), viaCandidate on an " +
            "AMBIGUOUS row where the entity is one of the candidates. Calls through another subprogram are not followed " +
            "(a standalone procedure that only calls the package one): ask again for that subprogram's usages. Pages as in " +
            "find_occurrences. $STATUSES"

        const val GROUP_USAGES = "Counts an entity's usages, the rows find_usages lists for the same arguments, grouped " +
            "by one to three dimensions as in group_occurrences; ENTITY is the entity a row matched, so on a row " +
            "through a view it names the view or view column. Example, who uses a table through views, by view and " +
            "routine, in one call: key the table, include [VIEWS], via THROUGH_VIEWS, group_by ENTITY, CONTAINER. " +
            "include defaults by kind as in find_usages; the answer's included lists what was followed."

        private val USAGE_SCOPES = listOf(Scope.IMPLEMENTATIONS, Scope.CHILDREN, Scope.VIEWS)

        const val GROUP = "Counts occurrences grouped by one to three dimensions, with the same filters as " +
            "find_occurrences. A CRUD matrix of a package: container_name or container the package, roles " +
            "SQL_SELECT, SQL_INSERT, SQL_UPDATE, SQL_DELETE, group_by CONTAINER, ENTITY, ROLE. ROLE counts an " +
            "occurrence once per role. A group value without entity is an occurrence tied to no entity; add STATUS " +
            "to group_by to tell proven occurrences from the others. To follow an entity's spec or body, contents or " +
            "views, use group_usages. $STATUSES"

        const val SOURCE = "Lines of an indexed source file around a line, as the index read it; stale is true " +
            "when the file changed since it was indexed."
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
