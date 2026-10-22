//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

@file:OptIn(ExperimentalKtorApi::class)

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.auth.oidc.OpenIdProviderMetadata
import io.ktor.server.auth.oidc.OpenIdTestKeys
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ExperimentalKtorApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** `/mcp` of `serve --projects`: every tool with a `project` argument, and `list_projects`. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpProjectsTest {
    private val temp: Path = Files.createTempDirectory("atlas-mcp-projects-test")
    private lateinit var hrIndex: Path
    private lateinit var billingIndex: Path
    private lateinit var billingSources: Path

    @BeforeAll
    fun build() {
        hrIndex = Fixture.index(temp.resolve("hr.db"))
        billingSources = Files.createDirectories(temp.resolve("billing-src"))
        Files.writeString(
            billingSources.resolve("invoices.sql"),
            "create table invoice (id number, amount number, status varchar2(1));\n" +
                "create or replace procedure close_invoice(p_id number) is\nbegin\n  update invoice set status = 'C' where id = p_id;\nend;\n/\n" +
                "create or replace procedure run_close is\nbegin\n  close_invoice(1);\nend;\n/\n"
        )
        billingIndex = temp.resolve("billing.db")
        var tick = 0L
        Indexer({ Instant.ofEpochSecond(tick++) }).index(IndexOptions(billingSources, billingIndex, compilationSchema = "BILLING", threads = 1))
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private val hr get() = ServedProject("hr", hrIndex, Fixture.sources)
    private val billing get() = ServedProject("billing", billingIndex, billingSources)

    /** An MCP client of the app [module] builds, at [path]. */
    private fun mcp(module: Application.() -> Unit, path: String = "/mcp", test: suspend ApplicationTestBuilder.(Client) -> Unit) = testApplication {
        application(module)
        val http = createClient { install(SSE); defaultRequest { header(HttpHeaders.Host, "localhost:8080") } }
        val client = Client(Implementation("atlas-test", "1"))
        client.connect(StreamableHttpClientTransport(http, path))
        try {
            test(client)
        } finally {
            client.close()
        }
    }

    private fun projects(vararg projects: ServedProject, test: suspend ApplicationTestBuilder.(Client) -> Unit) =
        mcp({ atlasProjectsModule(projects.toList(), McpSettings(), null) }, test = test)

    private suspend fun Client.call(name: String, arguments: JsonObject = JsonObject(emptyMap())): CallToolResult =
        callTool(CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)))

    private fun CallToolResult.text() = (content.single() as TextContent).text

    private fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

    private fun JsonObject.with(project: String) = JsonObject(this + ("project" to JsonPrimitive(project)))

    /** One call per tool of [AtlasMcpTools], on the fixture. */
    private val hrCalls = listOf(
        "search_entities" to args { put("query", "run_job") },
        "get_entity" to args { put("key", "db:HR.COMPANY") },
        "find_occurrences" to args { put("container", "db:HR.PACK_PROCESS"); put("limit", 5) },
        "find_usages" to args { put("key", "db:HR.COMPANY") },
        "group_usages" to args { put("key", "db:HR.COMPANY"); putJsonArray("group_by") { add(JsonPrimitive("ROLE")) } },
        "group_occurrences" to args { putJsonArray("group_by") { add(JsonPrimitive("ROLE")) }; put("path", "hr/") },
        "get_source" to args { put("path", "hr/pack_process.pkb"); put("line", 10) },
        "search_text" to args { put("text", "vFixTag") },
        "get_values" to args { put("key", "db:HR.COMPANY.ST_ACTIVE") },
        "find_value_sites" to args { put("key", "db:HR.COMPANY.ST_ACTIVE"); put("value_kind", "STRING"); put("value", "S") },
        "find_impact" to args { put("key", "db:HR.PACK_IMP_A.LEAF(P_N IN NUMBER)") }
    )

    private val billingCalls = listOf(
        "search_entities" to args { put("query", "close_invoice") },
        "get_entity" to args { put("key", "db:BILLING.INVOICE") },
        "find_occurrences" to args { put("entity", "db:BILLING.INVOICE.STATUS") },
        "find_usages" to args { put("key", "db:BILLING.INVOICE") },
        "group_usages" to args { put("key", "db:BILLING.INVOICE"); putJsonArray("group_by") { add(JsonPrimitive("ROLE")) } },
        "group_occurrences" to args { putJsonArray("group_by") { add(JsonPrimitive("ROLE")) }; put("path", "invoices") },
        "get_source" to args { put("path", "invoices.sql"); put("line", 4) },
        "search_text" to args { put("text", "status") },
        "get_values" to args { put("key", "db:BILLING.INVOICE.STATUS") },
        "find_value_sites" to args { put("key", "db:BILLING.INVOICE.STATUS"); put("value_kind", "STRING"); put("value", "C") },
        "find_impact" to args { put("key", "db:BILLING.CLOSE_INVOICE(P_ID IN NUMBER)") }
    )

    /** What a `--db` server on [index] answers to [calls]: each result, as JSON. */
    private fun single(index: Path, sources: Path, calls: List<Pair<String, JsonObject>>): List<String> {
        val answers = ArrayList<String>()
        mcp({ atlasModule(AtlasIndex(index, sources)) }) { client -> for ((name, arguments) in calls) answers += Json.encodeToString(CallToolResult.serializer(), client.call(name, arguments)) }
        return answers
    }

    @Test
    fun `every tool takes project, and answers on each project as a server of that index alone answers`() {
        val alone = mapOf("hr" to single(hrIndex, Fixture.sources, hrCalls), "billing" to single(billingIndex, billingSources, billingCalls))
        projects(hr, billing) { client ->
            val tools = client.listTools().tools
            assertThat(tools.map { it.name }).containsExactly(
                "search_entities", "get_entity", "find_occurrences", "find_usages", "group_usages", "group_occurrences", "get_source",
                "search_text", "get_values", "find_value_sites", "find_impact", "list_projects"
            )
            for (tool in tools.filter { it.name != "list_projects" }) {
                assertThat(tool.inputSchema.properties!!.keys.first()).describedAs(tool.name).isEqualTo("project")
                assertThat(tool.inputSchema.required).describedAs(tool.name).contains("project")
            }
            for ((project, calls) in listOf("hr" to hrCalls, "billing" to billingCalls)) {
                for ((i, call) in calls.withIndex()) {
                    val answer = client.call(call.first, call.second.with(project))
                    assertThat(answer.isError).describedAs("$project ${call.first}: ${answer.text()}").isNotEqualTo(true)
                    assertThat(Json.encodeToString(CallToolResult.serializer(), answer)).describedAs("$project ${call.first}").isEqualTo(alone.getValue(project)[i])
                }
            }
        }
    }

    @Test
    fun `nothing crosses over between projects`() = projects(hr, billing) { client ->
        val hrInBilling = client.call("get_entity", args { put("key", "db:HR.COMPANY") }.with("billing"))
        assertThat(hrInBilling.isError).isTrue()
        assertThat(hrInBilling.text()).startsWith("Unknown entity: db:HR.COMPANY.")
        assertThat(client.call("get_entity", args { put("key", "db:BILLING.INVOICE") }.with("hr")).isError).isTrue()
        assertThat(client.call("search_entities", args { put("query", "close_invoice") }.with("hr")).text()).doesNotContain("CLOSE_INVOICE")
        assertThat(client.call("get_source", args { put("path", "invoices.sql"); put("line", 1) }.with("hr")).isError).isTrue()
    }

    @Test
    fun `a project missing, unknown or not indexed yet is refused, with the names`() {
        val pending = ServedProject("pending", temp.resolve("pending.db"), null)
        projects(hr, billing, pending) { client ->
            val missing = client.call("get_entity", args { put("key", "db:HR.COMPANY") })
            assertThat(missing.isError).isTrue()
            assertThat(missing.text()).isEqualTo(
                "Missing argument: project. This server holds several projects, separate codebases: billing, hr, pending. Call list_projects, or ask the user which one."
            )
            assertThat(client.call("get_entity", args { put("key", "db:HR.COMPANY") }.with("payroll")).text())
                .isEqualTo("Unknown project: payroll. The projects are: billing, hr, pending.")
            assertThat(client.call("search_entities", args { put("query", "x") }.with("pending")).text())
                .isEqualTo("The project pending is not indexed yet: its index ${temp.resolve("pending.db")} does not exist")
            // An unknown argument is refused before the project is read, and the message lists project.
            assertThat(client.call("search_entities", args { put("qeury", "x") }).text())
                .isEqualTo("Unknown argument: qeury. search_entities takes: project, query, kind, include_external, limit")
        }
    }

    @Test
    fun `with one project, project may be left out, and given it is accepted`() {
        val alone = single(hrIndex, Fixture.sources, hrCalls.take(2))
        projects(hr) { client ->
            val tool = client.listTools().tools.first { it.name == "get_entity" }
            assertThat(tool.inputSchema.properties!!.keys).contains("project")
            assertThat(tool.inputSchema.required).containsExactly("key")
            for ((i, call) in hrCalls.take(2).withIndex()) {
                assertThat(Json.encodeToString(CallToolResult.serializer(), client.call(call.first, call.second))).isEqualTo(alone[i])
                assertThat(Json.encodeToString(CallToolResult.serializer(), client.call(call.first, call.second.with("hr")))).isEqualTo(alone[i])
            }
            assertThat(client.serverInstructions).startsWith("This server holds one project, hr: the project argument of every tool may be left out.\n")
        }
    }

    @Test
    fun `list_projects gives each project's state, date and files`() {
        val broken = temp.resolve("broken.db").also { Files.writeString(it, "not an index") }
        projects(hr, ServedProject("pending", temp.resolve("pending.db"), null), ServedProject("broken", broken, null)) { client ->
            val answer = Json.parseToJsonElement(client.call("list_projects").text()).jsonObject["projects"]!!.jsonArray.map { it.jsonObject }
            assertThat(answer.map { it["name"]!!.jsonPrimitive.content }).containsExactly("broken", "hr", "pending")
            assertThat(answer.map { it["state"]!!.jsonPrimitive.content }).containsExactly("UNAVAILABLE", "READY", "NOT_INDEXED")
            assertThat(answer[0]["error"]!!.jsonPrimitive.content).isNotBlank()
            assertThat(answer[1]["finishedAt"]!!.jsonPrimitive.content).isEqualTo("1970-01-01T00:00:01Z")
            assertThat(answer[1]["files"]!!.jsonPrimitive.content.toLong()).isEqualTo(Fixture.query(hrIndex, "SELECT count(*) FROM source_file").single().toLong())
            assertThat(answer[2].keys).containsExactly("name", "state")
            assertThat(client.call("list_projects", args { put("project", "hr") }).text()).isEqualTo("Unknown argument: project. list_projects takes: ")
            val instructions = client.serverInstructions!!
            assertThat(instructions).startsWith(
                "This server holds several projects, separate codebases indexed apart: broken, hr, pending. Every tool takes project; " +
                    "when the question does not say which, call list_projects or ask the user, rather than guess. An entity key, a path " +
                    "or a node number of one project means nothing in another.\n"
            ).endsWith(AtlasMcpTools.INSTRUCTIONS)
        }
    }

    @Test
    fun `with --db, no tool has project and there is no list_projects`() = mcp({ atlasModule(AtlasIndex(hrIndex, Fixture.sources)) }) { client ->
        val tools = client.listTools().tools
        assertThat(tools.map { it.name }).doesNotContain("list_projects")
        assertThat(tools).allSatisfy { assertThat(it.inputSchema.properties!!.keys).describedAs(it.name).doesNotContain("project") }
        assertThat(client.serverInstructions).isEqualTo(AtlasMcpTools.INSTRUCTIONS)
    }

    @Test
    fun `under a base path the endpoint is at it`() =
        mcp({ atlasProjectsModule(listOf(hr, billing), McpSettings(), null, BasePath.parse("/atlas")) }, path = "/atlas/mcp") { client ->
            assertThat(client.call("search_entities", args { put("query", "run_job") }.with("hr")).text()).contains("RUN_JOB")
        }

    private val issuer = "https://id.example.com/realms/atlas"
    private val keys = OpenIdTestKeys.rsa(issuer = issuer, audience = "https://atlas.example.com/atlas/mcp")
    private val metadata = OpenIdProviderMetadata(issuer = issuer, authorizationEndpoint = "$issuer/auth", tokenEndpoint = "$issuer/token", jwksUri = "$issuer/certs")

    @Test
    fun `with authentication, no token gets 401 with the metadata, and one token reads every project`() = testApplication {
        val basePath = BasePath.parse("/atlas")
        val idp = createClient { }
        application {
            atlasProjectsModule(listOf(hr, billing), McpSettings(), AuthSettings(issuer, "https://atlas.example.com/atlas", basePath = basePath), { a ->
                oidcIdentity(a) {
                    metadata = this@McpProjectsTest.metadata
                    jwt { jwkProviderFactory = { keys.jwkProvider } }
                }
            }, basePath) { idp }
        }
        val refused = createClient { }.post("/atlas/mcp") {
            header(HttpHeaders.Host, "atlas.example.com")
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        }
        assertThat(refused.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(refused.headers[HttpHeaders.WWWAuthenticate])
            .isEqualTo("""Bearer resource_metadata="https://atlas.example.com/atlas/.well-known/oauth-protected-resource/mcp"""")
        val token = keys.accessToken { subject = "ana" }
        val http = createClient {
            install(SSE)
            defaultRequest { header(HttpHeaders.Host, "atlas.example.com"); header(HttpHeaders.Authorization, "Bearer $token") }
        }
        val client = Client(Implementation("atlas-test", "1"))
        client.connect(StreamableHttpClientTransport(http, "/atlas/mcp"))
        try {
            assertThat(client.call("search_entities", args { put("query", "run_job") }.with("hr")).text()).contains("RUN_JOB")
            assertThat(client.call("search_entities", args { put("query", "close_invoice") }.with("billing")).text()).contains("CLOSE_INVOICE")
        } finally {
            client.close()
        }
    }
}
