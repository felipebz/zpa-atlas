//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.model.EntityKind
import com.felipebz.zpa.atlas.model.Origin
import com.felipebz.zpa.atlas.model.UnitKind
import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.query.GroupDimension
import com.felipebz.zpa.atlas.query.ImpactStop
import com.felipebz.zpa.atlas.query.OccurrenceQuery
import com.felipebz.zpa.atlas.query.Scope
import com.felipebz.zpa.atlas.query.UsagePath
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.server.AtlasMcpTools.Companion.ToolJson
import com.felipebz.zpa.atlas.server.AtlasMcpTools.Companion.toolAnswer
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.milliseconds

/** The MCP endpoint, through the SDK's own client over Streamable HTTP, on the fixture index. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpServerTest {
    private val temp: Path = Files.createTempDirectory("atlas-mcp-test")
    private lateinit var database: Path
    private lateinit var index: AtlasIndex

    private val runJob5 = "db:HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE,P_E IN NUMBER)"

    @BeforeAll
    fun build() {
        database = Fixture.index(temp.resolve("fixture.db"))
        index = AtlasIndex(database, Fixture.sources)
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private fun mcp(atlas: AtlasIndex = index, settings: McpSettings = McpSettings(), test: suspend ApplicationTestBuilder.(Client) -> Unit) = testApplication {
        application { atlasModule(atlas, settings) }
        // The test engine sends no Host header, which /mcp refuses: a real client always sends one.
        val http = createClient { install(SSE); defaultRequest { header(HttpHeaders.Host, "localhost:8080") } }
        val client = Client(Implementation("atlas-test", "1"))
        client.connect(StreamableHttpClientTransport(http, "/mcp"))
        try {
            test(client)
        } finally {
            client.close()
        }
    }

    private suspend fun Client.call(name: String, arguments: JsonObject = JsonObject(emptyMap())): CallToolResult =
        callTool(CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)))

    private fun CallToolResult.text() = (content.single() as TextContent).text

    private fun CallToolResult.json(): JsonElement {
        assertThat(isError).describedAs(text()).isNotEqualTo(true)
        return Json.parseToJsonElement(text())
    }

    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

    private fun strings(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

    @Test
    fun `initialize names the server, and the tools list their arguments without reserved codes`() = mcp { client ->
        assertThat(client.serverVersion!!.name).isEqualTo("zpa-atlas")
        assertThat(client.serverVersion!!.version).matches("\\d+\\.\\d+\\.\\d+.*")
        val tools = client.listTools().tools.associateBy { it.name }
        assertThat(tools.keys).containsExactlyInAnyOrder("search_entities", "get_entity", "find_occurrences", "find_usages", "group_occurrences", "group_usages", "get_source",
            "get_values", "find_value_sites", "find_impact")
        assertThat(tools.mapValues { it.value.inputSchema.required.orEmpty() }).isEqualTo(mapOf(
            "search_entities" to listOf("query"), "get_entity" to listOf("key"), "find_occurrences" to emptyList(),
            "find_usages" to listOf("key"), "group_occurrences" to listOf("group_by"), "group_usages" to listOf("key", "group_by"), "get_source" to listOf("path", "line"),
            "get_values" to listOf("key"), "find_value_sites" to listOf("key", "value_kind"), "find_impact" to listOf("key")
        ))
        assertThat(tools.values).allMatch { it.annotations?.readOnlyHint == true }
        // Every enum of every schema, arrays' items included.
        fun enums(element: JsonElement): List<String> = when (element) {
            is JsonObject -> element.flatMap { (key, value) -> if (key == "enum") value.jsonArray.map { it.jsonPrimitive.content } else enums(value) }
            is JsonArray -> element.flatMap { enums(it) }
            else -> emptyList()
        }
        val offered = tools.values.flatMap { enums(it.inputSchema.properties!!) }.toSet()
        val reserved = (EntityKind.entries.filter { it.reserved } + UnitKind.entries.filter { it.reserved } + Origin.entries.filter { it.reserved })
            .map { it.name }.toSet()
        assertThat(offered).contains("TABLE", "SYNONYM", "SQL_UPDATE", "RESOLVED", "STATIC", "PACKAGE_BODY", "CONTAINER")
        assertThat(offered).doesNotContainAnyElementsOf(reserved)
        val roles = tools.getValue("find_occurrences").inputSchema.properties!!.getValue("roles").jsonObject
        assertThat(roles["type"]!!.jsonPrimitive.content).isEqualTo("array")
    }

    @Test
    fun `the instructions name every tool, and they and every description fit an older client's limit`() = mcp { client ->
        val instructions = client.serverInstructions!!
        val tools = client.listTools().tools
        assertThat(tools.map { it.name }).allSatisfy { assertThat(instructions).contains(it) }
        assertThat(instructions.length).isLessThanOrEqualTo(2_048)
        assertThat(tools).allSatisfy { assertThat(it.description!!.length).describedAs(it.name).isLessThanOrEqualTo(2_048) }
    }

    @Test
    fun `each tool answers what the same AtlasIndex call answers`() = mcp { client ->
        assertThat(client.call("search_entities", args { put("query", "run_job"); put("limit", 5) }).json())
            .isEqualTo(toolAnswer(index.searchEntities("run_job", null, true, 5)))
        assertThat(client.call("get_entity", args { put("key", runJob5) }).json())
            .isEqualTo(toolAnswer(index.entity(runJob5)))
        val query = OccurrenceQuery(container = "db:HR.PACK_PROCESS", roles = setOf("SQL_UPDATE", "WRITE"))
        val found = client.call("find_occurrences", args {
            put("container", "db:HR.PACK_PROCESS"); put("roles", strings("SQL_UPDATE", "WRITE")); put("limit", 7)
        }).json()
        assertThat(found).isEqualTo(toolAnswer(index.queryOccurrences(query, null, 7, summary = true)))
        // The next page.
        val after = found.jsonObject["result"]!!.jsonObject["nextAfter"]!!.jsonPrimitive.content.toLong()
        assertThat(client.call("find_occurrences", args {
            put("container", "db:HR.PACK_PROCESS"); put("roles", strings("SQL_UPDATE", "WRITE")); put("limit", 7); put("after", after)
        }).json()).isEqualTo(toolAnswer(index.queryOccurrences(query, after, 7, summary = true)))
        // The CRUD matrix of a package.
        val crud = OccurrenceQuery(containerName = "pack_process", roles = setOf("SQL_SELECT", "SQL_INSERT", "SQL_UPDATE", "SQL_DELETE"))
        assertThat(client.call("group_occurrences", args {
            put("container_name", "pack_process"); put("roles", strings("SQL_SELECT", "SQL_INSERT", "SQL_UPDATE", "SQL_DELETE"))
            put("group_by", strings("CONTAINER", "ENTITY", "ROLE"))
        }).json()).isEqualTo(toolAnswer(
            index.groupOccurrences(crud, listOf(GroupDimension.CONTAINER, GroupDimension.ENTITY, GroupDimension.ROLE), AtlasMcpTools.GROUP_DEFAULT)
        ))
        assertThat(client.call("get_source", args { put("path", "hr/pack_process.pkb"); put("line", 10); put("context", 1) }).json())
            .isEqualTo(toolAnswer(index.source("hr/pack_process.pkb", 10, 1)))
    }

    @Test
    fun `an entity's long lists are cut to list_limit, with their full sizes`() = mcp { client ->
        val answer = client.call("get_entity", args { put("key", "db:HR.COMPANY"); put("list_limit", 2) }).json().jsonObject
        val full = index.entity("db:HR.COMPANY").result!!
        assertThat(full.children.size).isGreaterThan(2)
        assertThat(answer["result"]!!.jsonObject["children"]!!.jsonArray).hasSize(2)
        assertThat(answer["truncated"]!!.jsonObject["children"]!!.jsonPrimitive.content.toInt()).isEqualTo(full.children.size)
        assertThat(answer["result"]!!.jsonObject["children"]!!.jsonArray.toList())
            .isEqualTo(McpAnswers.compact(ToolJson.encodeToJsonElement(full.children.take(2))).jsonArray.toList())
    }

    @Test
    fun `a call argument names its parameter, and an entity summary leaves out a display that is its key`() = mcp { client ->
        val rows = client.call("find_occurrences", args { put("entity", runJob5); put("roles", strings("CALL")); put("limit", 40) })
            .json().jsonObject["result"]!!.jsonObject["rows"]!!.jsonArray.map { it.jsonObject }
        val withArguments = rows.filter { it["entity"] != null && it["arguments"]!!.jsonArray.isNotEmpty() }
        assertThat(withArguments).isNotEmpty()
        for (row in withArguments) {
            val called = row["entity"]!!.jsonObject["key"]!!.jsonPrimitive.content
            val apiRow = index.queryOccurrences(OccurrenceQuery(entity = runJob5, roles = setOf("CALL")), row["id"]!!.jsonPrimitive.content.toLong() - 1, 1)
                .result.rows.single()
            for ((argument, api) in row["arguments"]!!.jsonArray.map { it.jsonObject }.zip(apiRow.arguments)) {
                val parameter = api.parameter ?: continue
                assertThat(argument["parameter"]).isEqualTo(JsonPrimitive(parameter.name))
                assertThat("$called.${parameter.name}").isEqualTo(parameter.key)
                assertThat(argument).doesNotContainKey("parameterKey")
            }
            assertThat(row["matched"]!!.jsonObject).doesNotContainKey("display").containsKeys("key", "kind", "name")
            assertThat(row["entity"]!!.jsonObject).doesNotContainKey("display")
        }
    }

    @Test
    fun `calls of a procedure with forty long parameters fit the size cap, and their pages lose and repeat no row`() {
        val sources = Files.createDirectories(temp.resolve("wide"))
        val parameters = (1..40).map { "I_PARAMETER_WITH_A_LONG_NAME_NUMBER_%02d".format(it) }
        val declaration = "procedure take_all(" + parameters.joinToString(", ") { "$it in varchar2" } + ")"
        Files.writeString(sources.resolve("pack_wide.pks"), "create or replace package pack_wide is\n  $declaration;\nend;\n/\n")
        Files.writeString(sources.resolve("pack_wide.pkb"), "create or replace package body pack_wide is\n  $declaration is\n  begin\n    null;\n  end;\nend;\n/\n")
        val call = "  pack_wide.take_all(" + (1..40).joinToString(", ") { "'value $it'" } + ");\n"
        Files.writeString(sources.resolve("call_wide.prc"), "create or replace procedure call_wide is\nbegin\n" + call.repeat(40) + "end;\n/\n")
        val database = temp.resolve("wide.db")
        Indexer({ Instant.EPOCH }).index(IndexOptions(sources, database, compilationSchema = "HR", threads = 1))
        val wide = AtlasIndex(database, sources)
        val query = OccurrenceQuery(name = "pack_wide.take_all", roles = setOf("CALL"))
        val all = wide.queryOccurrences(query, null, 500).result.rows
        assertThat(all).hasSize(40).allMatch { it.arguments.size == 40 }
        mcp(wide) { client ->
            val ids = ArrayList<Long>()
            var after: Long? = null
            do {
                val answer = client.call("find_occurrences", args {
                    put("name", "pack_wide.take_all"); put("roles", strings("CALL")); put("limit", AtlasMcpTools.FIND_MAX); after?.let { put("after", it) }
                })
                assertThat(answer.text().length).isLessThanOrEqualTo(McpAnswers.MAX_CHARS)
                val json = answer.json().jsonObject
                val rows = json["result"]!!.jsonObject["rows"]!!.jsonArray
                ids += rows.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
                if (ids.size < all.size) assertThat(json["cutForSize"]!!.jsonObject["kept"]!!.jsonPrimitive.content.toInt()).isEqualTo(rows.size)
                after = json["result"]!!.jsonObject["nextAfter"]?.jsonPrimitive?.content?.toLong()
            } while (after != null)
            assertThat(ids).isEqualTo(all.map { it.id })
        }
    }

    @Test
    fun `over the size cap a list keeps its first items, says so, and a page goes on after its last row`() =
        mcp(settings = McpSettings(maxAnswerChars = 8_000)) { client ->
            val query = OccurrenceQuery(container = "db:HR.PACK_PROCESS")
            val all = index.queryOccurrences(query, null, 500).result.rows.map { it.id }
            assertThat(all.size).isGreaterThan(AtlasMcpTools.FIND_MAX)
            val ids = ArrayList<Long>()
            var after: Long? = null
            var cutPages = 0
            do {
                val answer = client.call("find_occurrences", args { put("container", "db:HR.PACK_PROCESS"); put("limit", 40); after?.let { put("after", it) } })
                    .json().jsonObject
                val rows = answer["result"]!!.jsonObject["rows"]!!.jsonArray
                answer["cutForSize"]?.jsonObject?.let { cut ->
                    cutPages++
                    assertThat(cut["kept"]!!.jsonPrimitive.content.toInt()).isEqualTo(rows.size)
                    assertThat(cut["of"]!!.jsonPrimitive.content.toInt()).isGreaterThan(rows.size)
                    assertThat(ToolJson.encodeToString(JsonElement.serializer(), answer).length).isLessThanOrEqualTo(8_000)
                }
                ids += rows.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
                after = answer["result"]!!.jsonObject["nextAfter"]?.jsonPrimitive?.content?.toLong()
            } while (after != null)
            assertThat(cutPages).isGreaterThan(0)
            assertThat(ids).isEqualTo(all)
            // The groups and the hits kept are the first ones of the uncut answer.
            val groups = client.call("group_occurrences", args { put("group_by", strings("CONTAINER", "ENTITY", "ROLE")); put("limit", 100) }).json().jsonObject
            val kept = groups["cutForSize"]!!.jsonObject["kept"]!!.jsonPrimitive.content.toInt()
            val allGroups = toolAnswer(index.groupOccurrences(OccurrenceQuery(), listOf(GroupDimension.CONTAINER, GroupDimension.ENTITY, GroupDimension.ROLE), 100))
            assertThat(groups["result"]!!.jsonObject["groups"]!!.jsonArray.toList())
                .isEqualTo(allGroups["result"]!!.jsonObject["groups"]!!.jsonArray.take(kept))
            val hits = client.call("search_entities", args { put("query", "p"); put("limit", 100) }).json().jsonObject
            assertThat(hits["cutForSize"]!!.jsonObject["kept"]!!.jsonPrimitive.content.toInt()).isEqualTo(hits["result"]!!.jsonArray.size).isLessThan(100)
        }

    @Test
    fun `a row longer than the size cap is still sent, alone`() = mcp(settings = McpSettings(maxAnswerChars = 100)) { client ->
        val answer = client.call("find_occurrences", args { put("limit", 5) }).json().jsonObject
        assertThat(answer["result"]!!.jsonObject["rows"]!!.jsonArray).hasSize(1)
        assertThat(answer["cutForSize"]).isEqualTo(Json.parseToJsonElement("""{"kept":1,"of":5}"""))
        assertThat(answer["result"]!!.jsonObject["nextAfter"]).isEqualTo(answer["result"]!!.jsonObject["rows"]!!.jsonArray[0].jsonObject["id"])
        // An entity's lists are cut further, down to one item each; the answer says to which limit.
        val entity = client.call("get_entity", args { put("key", "db:HR.COMPANY"); put("list_limit", 100) }).json().jsonObject
        assertThat(entity["cutForSize"]).isEqualTo(Json.parseToJsonElement("""{"listLimit":1}"""))
        assertThat(entity["result"]!!.jsonObject["children"]!!.jsonArray).hasSize(1)
    }

    private fun total(answer: JsonElement) = answer.jsonObject["result"]!!.jsonObject["total"]!!.jsonPrimitive.content.toInt()

    @Test
    fun `find_usages answers what AtlasIndex occurrences answers, and include widens the entity as the page does`() = mcp { client ->
        fun withIncluded(answer: JsonObject, vararg scopes: String) = JsonObject(answer + ("included" to strings(*scopes)))
        val body = "$runJob5@BODY"
        assertThat(client.call("find_usages", args { put("key", body) }).json())
            .isEqualTo(withIncluded(toolAnswer(index.occurrences(body, scope = setOf(Scope.SELF, Scope.IMPLEMENTATIONS), limit = AtlasMcpTools.FIND_DEFAULT)), "IMPLEMENTATIONS"))
        val view = "db:HR.V_COMPANY_LIST"
        assertThat(client.call("find_usages", args { put("key", view); put("include", strings("CHILDREN", "VIEWS")) }).json())
            .isEqualTo(withIncluded(toolAnswer(index.occurrences(view, scope = setOf(Scope.SELF, Scope.CHILDREN, Scope.VIEWS), limit = AtlasMcpTools.FIND_DEFAULT)), "CHILDREN", "VIEWS"))
        // Without include, the page's default for the kind: a package with its spec or body and its members.
        val pack = client.call("find_usages", args { put("key", "db:HR.PACK_PROCESS") }).json()
        assertThat(pack).isEqualTo(withIncluded(toolAnswer(index.occurrences("db:HR.PACK_PROCESS",
            scope = setOf(Scope.SELF, Scope.IMPLEMENTATIONS, Scope.CHILDREN), limit = AtlasMcpTools.FIND_DEFAULT)), "IMPLEMENTATIONS", "CHILDREN"))
        assertThat(total(pack)).isEqualTo(53)
        // A table: the table alone.
        val table = client.call("find_usages", args { put("key", "db:HR.COMPANY") }).json()
        assertThat(table).isEqualTo(withIncluded(toolAnswer(index.occurrences("db:HR.COMPANY", limit = AtlasMcpTools.FIND_DEFAULT))))
        assertThat(total(table)).isEqualTo(120)
        // The body alone has no usage: its callers bind the spec.
        val alone = client.call("find_usages", args { put("key", body); putJsonArray("include") {} }).json()
        assertThat(total(alone)).isEqualTo(0)
        assertThat(alone.jsonObject["included"]).isEqualTo(JsonArray(emptyList()))
        assertThat(total(client.call("find_usages", args { put("key", body) }).json())).isEqualTo(4)
        assertThat(total(client.call("find_usages", args { put("key", view); putJsonArray("include") {} }).json())).isEqualTo(3)
        assertThat(total(client.call("find_usages", args { put("key", view); put("include", strings("VIEWS")) }).json())).isEqualTo(7)
        assertThat(total(client.call("find_usages", args { put("key", view); put("include", strings("CHILDREN")) }).json())).isEqualTo(9)
        assertThat(total(client.call("find_usages", args { put("key", view); put("include", strings("CHILDREN", "VIEWS")) }).json())).isEqualTo(16)
    }

    @Test
    fun `find_usages pages lose and repeat no row, under a small size cap too`() {
        val all = index.occurrences("db:HR.PACK_PROCESS", scope = setOf(Scope.SELF, Scope.CHILDREN), limit = 500).result!!.rows.map { it.id }
        assertThat(all.size).isGreaterThan(AtlasMcpTools.FIND_MAX)
        for (cap in listOf(McpAnswers.MAX_CHARS, 6_000)) {
            mcp(settings = McpSettings(maxAnswerChars = cap)) { client ->
                val ids = ArrayList<Long>()
                var after: Long? = null
                var cut = 0
                do {
                    val answer = client.call("find_usages", args {
                        put("key", "db:HR.PACK_PROCESS"); put("include", strings("CHILDREN")); put("limit", AtlasMcpTools.FIND_MAX); after?.let { put("after", it) }
                    }).json().jsonObject
                    val rows = answer["result"]!!.jsonObject["rows"]!!.jsonArray
                    answer["cutForSize"]?.let { cut++; assertThat(it.jsonObject["kept"]!!.jsonPrimitive.content.toInt()).isEqualTo(rows.size) }
                    ids += rows.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
                    after = answer["result"]!!.jsonObject["nextAfter"]?.jsonPrimitive?.content?.toLong()
                } while (after != null)
                assertThat(ids).isEqualTo(all)
                if (cap < McpAnswers.MAX_CHARS) assertThat(cut).isGreaterThan(0)
            }
        }
    }

    @Test
    fun `group_usages answers what AtlasIndex groupUsages answers, and find_usages keeps the rows via asks for`() = mcp { client ->
        val views = setOf(Scope.SELF, Scope.VIEWS)
        val byViewAndRoutine = listOf(GroupDimension.ENTITY, GroupDimension.CONTAINER)
        val grouped = client.call("group_usages", args {
            put("key", "db:HR.COMPANY"); put("include", strings("VIEWS")); put("via", "THROUGH_VIEWS"); put("group_by", strings("ENTITY", "CONTAINER"))
        }).json()
        assertThat(grouped).isEqualTo(JsonObject(toolAnswer(
            index.groupUsages("db:HR.COMPANY", byViewAndRoutine, scope = views, via = UsagePath.THROUGH_VIEWS, limit = AtlasMcpTools.GROUP_DEFAULT)
        ) + ("included" to strings("VIEWS"))))
        // Without include, the default by kind: a package with its spec or body and its members.
        assertThat(client.call("group_usages", args { put("key", "db:HR.PACK_PROCESS"); put("group_by", strings("ROLE")) }).json())
            .isEqualTo(JsonObject(toolAnswer(index.groupUsages("db:HR.PACK_PROCESS", listOf(GroupDimension.ROLE),
                scope = setOf(Scope.SELF, Scope.IMPLEMENTATIONS, Scope.CHILDREN), limit = AtlasMcpTools.GROUP_DEFAULT)) + ("included" to strings("IMPLEMENTATIONS", "CHILDREN"))))
        for (via in UsagePath.entries) {
            assertThat(client.call("find_usages", args { put("key", "db:HR.COMPANY"); put("include", strings("VIEWS")); put("via", via.name) }).json())
                .isEqualTo(JsonObject(toolAnswer(index.occurrences("db:HR.COMPANY", scope = views, via = via, limit = AtlasMcpTools.FIND_DEFAULT)) +
                    ("included" to strings("VIEWS"))))
        }
    }

    @Test
    fun `group_usages refuses an unknown entity, via value, dimension or argument, and four dimensions`() = mcp { client ->
        fun CallToolResult.error(): String {
            assertThat(isError).describedAs(text()).isTrue()
            return text()
        }
        suspend fun group(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = client.call("group_usages", args { put("key", "db:HR.COMPANY"); block() }).error()
        assertThat(client.call("group_usages", args { put("key", "db:NO.SUCH"); put("group_by", strings("ENTITY")) }).error())
            .isEqualTo("Unknown entity: db:NO.SUCH. Entity keys come from search_entities or from earlier answers.")
        assertThat(group { put("group_by", strings("ENTITY")); put("via", "VIEWS") }).isEqualTo("Unknown via value: VIEWS")
        assertThat(client.call("find_usages", args { put("key", "db:HR.COMPANY"); put("via", "SIDEWAYS") }).error()).isEqualTo("Unknown via value: SIDEWAYS")
        assertThat(group { put("group_by", strings("COLOR")) }).isEqualTo("Unknown group_by dimension: COLOR")
        assertThat(group { put("group_by", strings("ENTITY", "CONTAINER", "ROLE", "STATUS")) }).isEqualTo("groupBy takes one to three dimensions")
        assertThat(group { put("group_by", strings("ENTITY")); put("after", 3) }).startsWith("Unknown argument: after. group_usages takes: key, include,")
    }

    @Test
    fun `find_usages refuses an unknown entity, include value or argument`() = mcp { client ->
        fun CallToolResult.error(): String {
            assertThat(isError).describedAs(text()).isTrue()
            return text()
        }
        assertThat(client.call("find_usages", args { put("key", "db:NO.SUCH") }).error())
            .isEqualTo("Unknown entity: db:NO.SUCH. Entity keys come from search_entities or from earlier answers.")
        assertThat(client.call("find_usages", args { put("key", runJob5); put("include", strings("SELF")) }).error()).isEqualTo("Unknown include value: SELF")
        assertThat(client.call("find_usages", args { put("key", runJob5); put("scope", strings("VIEWS")) }).error())
            .startsWith("Unknown argument: scope. find_usages takes: key, include,")
    }

    private fun JsonElement.impactNodes() = jsonObject["result"]!!.jsonObject["nodes"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.field(name: String) = get(name)?.jsonPrimitive?.content

    private fun JsonObject.number() = getValue("n").jsonPrimitive.content.toInt()

    private val impactLeaf = "db:HR.PACK_IMP_A.LEAF(P_N IN NUMBER)"

    @Test
    fun `find_impact numbers the nodes of the whole graph, names them and points via at a number`() = mcp { client ->
        val nodes = client.call("find_impact", args { put("key", impactLeaf) }).json().impactNodes()
        val graph = index.impact(impactLeaf, include = listOf(Scope.IMPLEMENTATIONS), limit = AtlasMcpTools.IMPACT_DEFAULT).result!!.nodes
        assertThat(nodes.map { it.number() }).isEqualTo((1..graph.size).toList())
        assertThat(nodes.map { it.field("name") }).containsExactly(
            "HR.IMP_FN", "HR.PACK_IMP_A.MID", "HR.PACK_IMP_A.OV", "HR.PACK_IMP_A.PONG", "HR.PACK_IMP_B", "HR.IMP_AMB", "HR.IMP_REC",
            "HR.PACK_IMP_A.PING", "HR.PACK_IMP_B.CURS_USER", "HR.PACK_IMP_B.HELPER@BODY", "HR.PACK_IMP_B.TOP", "HR.TRG_IMP", "HR.V_IMP", null, "HR.IMP_READ_VIEW"
        )
        // The first hop uses the entity asked for; the others use the node whose key the index gives.
        val numberOf = graph.withIndex().filter { it.value.entity != null }.associate { it.value.entity!!.key to it.index + 1 }
        assertThat(nodes.map { it.getValue("via").jsonObject.getValue("uses").jsonPrimitive.content.toInt() })
            .isEqualTo(graph.map { if (it.depth == 1) 0 else numberOf.getValue(it.via.uses) })
        // Only names two nodes share carry a key: here none, the VARCHAR2 overload of OV is not a node.
        assertThat(nodes.filter { it["key"] != null }).isEmpty()
        // A file has its path and no name.
        assertThat(nodes.single { it.field("kind") == "FILE" }.let { it["name"] to it.field("path") }).isEqualTo(null to "scripts/imp_run.sql")
    }

    @Test
    fun `find_impact gives a key to the nodes whose name repeats`() = mcp { client ->
        val nodes = client.call("find_impact", args { put("key", "db:HR.IMP_OVL.BASE()") }).json().impactNodes()
        assertThat(nodes.map { it.field("name") }).containsExactly("HR.IMP_OVL.RUN", "HR.IMP_OVL.RUN")
        assertThat(nodes.map { it.field("key") }).containsExactly("db:HR.IMP_OVL.RUN(P IN NUMBER)", "db:HR.IMP_OVL.RUN(P IN VARCHAR2)")
    }

    @Test
    fun `find_impact names what the first hop matched when it is not the entity asked for`() = mcp { client ->
        // The package: the first hop matches its members.
        val member = client.call("find_impact", args { put("key", "db:HR.PACK_IMP_A"); put("max_depth", 1) }).json().impactNodes()
            .single { it.field("name") == "HR.IMP_REC" }
        assertThat(member.getValue("via").jsonObject.let { it.field("uses") to it.field("usesName") }).isEqualTo("0" to "HR.PACK_IMP_A.MID")
        // A routine used itself: no usesName.
        val direct = client.call("find_impact", args { put("key", "db:HR.IMP_FN(P_N IN NUMBER)"); put("max_depth", 1) }).json().impactNodes()
        assertThat(direct.map { it.getValue("via").jsonObject["usesName"] }).containsOnlyNulls()
    }

    @Test
    fun `find_impact with keys answers the index nodes plus their number, and n does not move with stop or paging`() = mcp { client ->
        val graph = index.impact(impactLeaf, include = listOf(Scope.IMPLEMENTATIONS), limit = AtlasMcpTools.IMPACT_DEFAULT)
        val keyed = client.call("find_impact", args { put("key", impactLeaf); put("keys", true) }).json().impactNodes()
        val base = toolAnswer(graph).jsonObject["result"]!!.jsonObject["nodes"]!!.jsonArray.map { it.jsonObject }
        assertThat(keyed.map { JsonObject(it - "n") }).isEqualTo(base)
        assertThat(keyed.map { it.number() }).isEqualTo((1..base.size).toList())
        val entry = client.call("find_impact", args { put("key", impactLeaf); put("stop", "ENTRY_POINT"); put("keys", true) }).json().impactNodes()
        assertThat(entry).isNotEmpty
        assertThat(entry).allSatisfy { assertThat(it).isEqualTo(keyed.single { k -> k.number() == it.number() }) }
        assertThat(entry.size).isLessThan(keyed.size)
        val page = client.call("find_impact", args { put("key", impactLeaf); put("offset", 4); put("limit", 3) }).json().impactNodes()
        assertThat(page.map { it.number() }).containsExactly(5, 6, 7)
        assertThat(client.call("find_impact", args { put("key", impactLeaf); put("stop", "SOMEWHERE") }).text()).isEqualTo("Unknown stop value: SOMEWHERE")
        assertThat(client.call("find_impact", args { put("key", "db:NO.SUCH") }).text()).startsWith("Unknown entity: db:NO.SUCH.")
    }

    @Test
    fun `find_impact under a small cap pages on by offset, each node once`() = mcp(settings = McpSettings(maxAnswerChars = 2_000)) { client ->
        val seen = ArrayList<Int>()
        var offset: Int? = 0
        var cut = 0
        while (offset != null) {
            val answer = client.call("find_impact", args { put("key", impactLeaf); put("offset", offset!!) }).json().jsonObject
            val nodes = answer.impactNodes()
            answer["cutForSize"]?.let { cut++; assertThat(it.jsonObject["kept"]!!.jsonPrimitive.content.toInt()).isEqualTo(nodes.size) }
            seen += nodes.map { it.number() }
            offset = answer["result"]!!.jsonObject["nextOffset"]?.jsonPrimitive?.content?.toInt()
        }
        assertThat(cut).isGreaterThan(0)
        assertThat(seen).isEqualTo((1..15).toList())
    }

    @Test
    fun `a request the index cannot answer is an error result with the reason`() = mcp { client ->
        fun CallToolResult.error(): String {
            assertThat(isError).describedAs(text()).isTrue()
            return text()
        }
        assertThat(client.call("get_entity", args { put("key", "db:NO.SUCH") }).error()).contains("Unknown entity: db:NO.SUCH", "search_entities")
        assertThat(client.call("find_occurrences", args { put("roles", strings("NOPE")) }).error()).isEqualTo("Unknown role: NOPE")
        assertThat(client.call("group_occurrences", args { putJsonArray("group_by") { add(JsonPrimitive("COLOR")) } }).error())
            .isEqualTo("Unknown group_by dimension: COLOR")
        assertThat(client.call("get_entity").error()).isEqualTo("Missing argument: key")
        assertThat(client.call("group_occurrences").error()).isEqualTo("Missing argument: group_by")
        assertThat(client.call("get_source", args { put("path", "hr/pack_process.pkb") }).error()).isEqualTo("Missing argument: line")
        assertThat(client.call("find_occurrences", args { put("limit", 1000) }).error()).isEqualTo("limit must be between 1 and ${AtlasMcpTools.FIND_MAX}")
        assertThat(client.call("find_occurrences", args { put("roles", "WRITE") }).error()).isEqualTo("roles must be an array of strings")
    }

    @Test
    fun `a query past its time limit is an error result`() = mcp(AtlasIndex(database, null, queryTimeout = Duration.ZERO)) { client ->
        val result = client.call("group_occurrences", args { put("group_by", strings("ENTITY")) })
        assertThat(result.isError).isTrue()
        assertThat(result.text()).contains("narrow the filters")
    }

    @Test
    fun `an index of another schema version is an error result`() {
        val other = temp.resolve("other.db")
        Files.copy(database, other)
        DriverManager.getConnection("jdbc:sqlite:$other").use { it.createStatement().execute("UPDATE run SET schema_version = 2") }
        mcp(AtlasIndex(other, null)) { client ->
            val result = client.call("search_entities", args { put("query", "run_job") })
            assertThat(result.isError).isTrue()
            assertThat(result.text()).contains("schema version 2", "Re-run `zpa-atlas index`")
        }
    }

    private val pCode = "db:HR.PACK_ARGS.P(P_CODE IN NUMBER,P_NAME IN VARCHAR2,P_FLAG IN BOOLEAN).P_CODE"
    private val pCodeBody = pCode.replace(").P_CODE", ")@BODY.P_CODE")
    private val stActive = "db:HR.COMPANY.ST_ACTIVE"

    private fun CallToolResult.error(): String {
        assertThat(isError).describedAs(text()).isTrue()
        return text()
    }

    private fun JsonElement.result() = jsonObject["result"]!!.jsonObject

    @Test
    fun `get_values answers what AtlasIndex values answers, and a body parameter its spec's`() = mcp { client ->
        for (key in listOf(stActive, pCode, pCodeBody)) {
            val answer = client.call("get_values", args { put("key", key) }).json()
            val api = toolAnswer(index.values(key))
            assertThat(answer.jsonObject["run"]).isEqualTo(api["run"])
            assertThat(answer.result()["groups"]).describedAs(key).isEqualTo(api["result"])
            assertThat(answer.result()["totalGroups"]!!.jsonPrimitive.content.toInt()).isEqualTo(api["result"]!!.jsonArray.size)
        }
        val written = client.call("get_values", args { put("key", stActive) }).json().result()["groups"]!!.jsonArray
        assertThat(written.take(3)).containsExactly(
            Json.parseToJsonElement("""{"valueKind":"STRING","value":"S","count":9}"""),
            Json.parseToJsonElement("""{"valueKind":"STRING","value":"N","count":7}"""),
            Json.parseToJsonElement("""{"valueKind":"NULL","count":3}""")
        )
        val spec = client.call("get_values", args { put("key", pCode) }).json().result()
        assertThat(spec["totalGroups"]!!.jsonPrimitive.content.toInt()).isEqualTo(12)
        assertThat(client.call("get_values", args { put("key", pCodeBody) }).json().result()).isEqualTo(spec)
        // limit keeps the first groups; the total still counts them all.
        val cut = client.call("get_values", args { put("key", pCode); put("limit", 3) }).json().result()
        assertThat(cut["groups"]).isEqualTo(JsonArray(spec["groups"]!!.jsonArray.take(3)))
        assertThat(cut["totalGroups"]!!.jsonPrimitive.content.toInt()).isEqualTo(12)
    }

    @Test
    fun `find_value_sites answers what AtlasIndex valueSites answers, with only the parameter's own argument`() = mcp { client ->
        for ((key, kind, value) in listOf(Triple(pCode, "NUMBER", "1"), Triple(pCodeBody, "NUMBER", "1"), Triple(pCode, "EXPRESSION", null))) {
            val api = index.valueSites(key, kind, value, null, 40)
            val page = api.result!!
            val own = page.copy(rows = page.rows.map { row -> row.copy(arguments = row.arguments.filter { it.parameter?.name == "P_CODE" }) })
            val answer = client.call("find_value_sites", args { put("key", key); put("value_kind", kind); value?.let { put("value", it) }; put("limit", 40) }).json()
            assertThat(answer).isEqualTo(toolAnswer(api.copy(result = own)))
            val rows = answer.result()["rows"]!!.jsonArray.map { it.jsonObject }
            assertThat(rows).isNotEmpty()
            for (row in rows) {
                val argument = row["arguments"]!!.jsonArray.single().jsonObject
                assertThat(argument["parameter"]).isEqualTo(JsonPrimitive("P_CODE"))
                assertThat(argument["valueKind"]).isEqualTo(JsonPrimitive(kind))
            }
        }
        // p(1, ...) calls pass other arguments too: they are left out.
        assertThat(index.valueSites(pCode, "NUMBER", "1").result!!.rows.map { it.arguments.size }).contains(2)
        // A column's rows are as the index gives them.
        assertThat(client.call("find_value_sites", args { put("key", stActive); put("value_kind", "STRING"); put("value", "S"); put("limit", 40) }).json())
            .isEqualTo(toolAnswer(index.valueSites(stActive, "STRING", "S", null, 40)))
    }

    @Test
    fun `find_value_sites pages lose and repeat no row, under a small size cap too`() {
        for ((key, kind, value) in listOf(Triple(stActive, "STRING", "S"), Triple(pCode, "NUMBER", "1"))) {
            val all = index.valueSites(key, kind, value, null, 500).result!!.rows.map { it.id }
            assertThat(all.size).isGreaterThan(3)
            for (cap in listOf(McpAnswers.MAX_CHARS, 1_500)) {
                mcp(settings = McpSettings(maxAnswerChars = cap)) { client ->
                    val ids = ArrayList<Long>()
                    var after: Long? = null
                    var cut = 0
                    do {
                        val answer = client.call("find_value_sites", args {
                            put("key", key); put("value_kind", kind); put("value", value); put("limit", 3); after?.let { put("after", it) }
                        }).json().jsonObject
                        val rows = answer["result"]!!.jsonObject["rows"]!!.jsonArray
                        answer["cutForSize"]?.let { cut++; assertThat(it.jsonObject["kept"]!!.jsonPrimitive.content.toInt()).isEqualTo(rows.size) }
                        ids += rows.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
                        after = answer["result"]!!.jsonObject["nextAfter"]?.jsonPrimitive?.content?.toLong()
                    } while (after != null)
                    assertThat(ids).describedAs("$key at $cap").isEqualTo(all)
                    if (cap < McpAnswers.MAX_CHARS) assertThat(cut).describedAs("$key at $cap").isGreaterThan(0)
                }
            }
        }
    }

    @Test
    fun `the value tools refuse another kind of entity, an unknown entity, value kind or argument, and a STRING without value`() = mcp { client ->
        assertThat(client.call("get_values", args { put("key", "db:HR.COMPANY") }).error())
            .isEqualTo("get_values answers for a PARAMETER or a COLUMN; db:HR.COMPANY is a TABLE.")
        assertThat(client.call("find_value_sites", args { put("key", runJob5); put("value_kind", "NULL") }).error())
            .isEqualTo("find_value_sites answers for a PARAMETER or a COLUMN; $runJob5 is a PROCEDURE.")
        val unknown = "Unknown entity: db:NO.SUCH. Entity keys come from search_entities or from earlier answers."
        assertThat(client.call("get_values", args { put("key", "db:NO.SUCH") }).error()).isEqualTo(unknown)
        assertThat(client.call("find_value_sites", args { put("key", "db:NO.SUCH"); put("value_kind", "NULL") }).error()).isEqualTo(unknown)
        assertThat(client.call("find_value_sites", args { put("key", stActive); put("value_kind", "COLOR") }).error()).isEqualTo("Unknown value kind: COLOR")
        assertThat(client.call("find_value_sites", args { put("key", stActive); put("value_kind", "STRING") }).error()).isEqualTo("Missing argument: value")
        assertThat(client.call("find_value_sites", args { put("key", stActive); put("kind", "STRING") }).error())
            .isEqualTo("Unknown argument: kind. find_value_sites takes: key, value_kind, value, after, limit")
        assertThat(client.call("get_values", args { put("key", stActive); put("limit", 1000) }).error()).isEqualTo("limit must be between 1 and ${AtlasMcpTools.VALUES_MAX}")
    }

    private val initialize = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
        """"capabilities":{},"clientInfo":{"name":"raw","version":"1"}}}"""

    @Test
    fun `a Host the server does not know is refused, an allowed one is accepted`() = testApplication {
        application { atlasModule(index, McpSettings(allowedHosts = listOf("atlas.lan"))) }
        suspend fun initializeAs(host: String) = client.post("/mcp") {
            header(HttpHeaders.Host, host)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(initialize)
        }
        assertThat(initializeAs("evil.example").status).isEqualTo(HttpStatusCode.Forbidden)
        assertThat(initializeAs("atlas.lan:8080").status).isEqualTo(HttpStatusCode.OK)
        assertThat(initializeAs("localhost:8080").status).isEqualTo(HttpStatusCode.OK)
        // Without the extra host, the same name is refused.
        assertThat(client.post("/mcp") {
            header(HttpHeaders.Host, "atlas.lan.example"); header(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json); setBody(initialize)
        }.status).isEqualTo(HttpStatusCode.Forbidden)
    }

    @Test
    fun `every tool refuses an argument its schema does not name`() = mcp { client ->
        val known = mapOf("search_entities" to "query", "get_entity" to "key", "find_occurrences" to "entity",
            "group_occurrences" to "group_by", "get_source" to "line")
        val tools = client.listTools().tools.associateBy { it.name }
        for ((name, accepted) in known) {
            val result = client.call(name, args { put("rolez", strings("WRITE")) })
            assertThat(result.isError).describedAs(name).isTrue()
            val takes = tools.getValue(name).inputSchema.properties!!.keys.joinToString(", ")
            assertThat(result.text()).isEqualTo("Unknown argument: rolez. $name takes: $takes").contains(accepted)
        }
        // The /api spelling of a filter is not the tool's: it is refused, not ignored.
        val camel = client.call("find_occurrences", args { put("entityKinds", strings("TABLE")) })
        assertThat(camel.isError).isTrue()
        assertThat(camel.text()).startsWith("Unknown argument: entityKinds. find_occurrences takes: entity, inside, entity_kinds,")
    }

    @Test
    fun `the JSON-RPC answer leaves nulls out, the schemas are closed, and the event stream is not compressed`() {
        // A real server: the test engine does not hand an unfinished response to the client.
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { atlasModule(index, McpSettings(heartbeat = 100.milliseconds)) }.start()
        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            val url = URI("http://127.0.0.1:$port/mcp")
            val http = HttpClient.newHttpClient()
            fun post(body: String, session: String? = null) = http.send(
                HttpRequest.newBuilder(url).header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json")
                    .header("Accept-Encoding", "gzip").apply { if (session != null) header("Mcp-Session-Id", session) }
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray()
            )
            fun HttpResponse<ByteArray>.text() =
                (if (headers().firstValue("Content-Encoding").orElse("") == "gzip") GZIPInputStream(body().inputStream()).readAllBytes() else body())
                    .decodeToString()
            val answer = post(initialize)
            assertThat(answer.statusCode()).isEqualTo(200)
            val session = answer.headers().firstValue("Mcp-Session-Id").get()
            val body = Json.parseToJsonElement(answer.text()).jsonObject
            assertThat(body["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("zpa-atlas")
            // McpJson writes no null field and no class discriminator: the JSON of /api would write both differently.
            assertThat(answer.text()).doesNotContain(":null").doesNotContain("\"type\":\"io.")
            post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", session)
            // The listing is large enough to be gzipped: the closed schemas must survive compression.
            val listing = http.send(
                HttpRequest.newBuilder(url).header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json")
                    .header("Accept-Encoding", "gzip").header("Mcp-Session-Id", session)
                    .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")).build(),
                HttpResponse.BodyHandlers.ofInputStream()
            )
            assertThat(listing.headers().firstValue("Content-Encoding")).hasValue("gzip")
            val listed = Json.parseToJsonElement(GZIPInputStream(listing.body()).readAllBytes().decodeToString())
                .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            assertThat(listed).hasSize(10)
            assertThat(listed.map { it.jsonObject["inputSchema"]!!.jsonObject["additionalProperties"] }).containsOnly(JsonPrimitive(false))
            val stream = http.sendAsync(
                HttpRequest.newBuilder(url).header("Accept", "text/event-stream").header("Accept-Encoding", "gzip, deflate")
                    .header("Mcp-Session-Id", session).GET().build(),
                HttpResponse.BodyHandlers.ofLines()
            ).get(10, TimeUnit.SECONDS)
            assertThat(stream.statusCode()).isEqualTo(200)
            assertThat(stream.headers().firstValue("Content-Type").get()).startsWith("text/event-stream")
            assertThat(stream.headers().firstValue("Content-Encoding")).isEmpty()
            // Two heartbeats arrive while the stream stays open: nothing holds the events back.
            val lines = CompletableFuture.supplyAsync { stream.body().filter { it.startsWith(":") }.limit(2).toList() }.get(10, TimeUnit.SECONDS)
            assertThat(lines).hasSize(2)
        } finally {
            server.stop(0, 0)
        }
    }
}
