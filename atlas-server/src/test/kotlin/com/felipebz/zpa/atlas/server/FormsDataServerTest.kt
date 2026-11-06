//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.plugins.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/**
 * The API and the MCP tools on the uses Forms makes without code, on the invented module `DATA_DESK`
 * (`forms/data_desk_fmb.xml`) against the fixture's dictionary: a block's and an item's page data, and the usage and
 * impact answers whose rows have no line.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FormsDataServerTest {
    private val temp: Path = Files.createTempDirectory("atlas-forms-data-test")
    private lateinit var index: AtlasIndex

    @BeforeAll
    fun build() {
        val root = Files.createDirectories(temp.resolve("src"))
        javaClass.getResourceAsStream("/forms/data_desk_fmb.xml")!!.use { Files.copy(it, root.resolve("data_desk_fmb.xml")) }
        val db = temp.resolve("atlas.db")
        Indexer().index(IndexOptions(root, db, compilationSchema = "HR", dictionary = Fixture.dictionary, threads = 1))
        index = AtlasIndex(db, root)
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private fun api(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { atlasModule(index) }
        test()
    }

    private suspend fun ApplicationTestBuilder.result(path: String, vararg parameters: Pair<String, String>): JsonElement {
        val response = client.get(path) { parameters.forEach { (k, v) -> parameter(k, v) } }
        assertThat(response.status).describedAs(path).isEqualTo(HttpStatusCode.OK)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("result")
    }

    private fun JsonElement.field(name: String) = jsonObject.getValue(name)

    private fun JsonElement.text(name: String) = jsonObject.getValue(name).jsonPrimitive.content

    /** The `FORMS_IMPLICIT` rows of a page, checking they have no line. */
    private fun implicitRows(page: JsonElement) = page.field("rows").jsonArray.filter { it.text("origin") == "FORMS_IMPLICIT" }.onEach { row ->
        for (field in listOf("span", "unit", "code", "statementKind")) assertThat(row.field(field)).describedAs(field).isEqualTo(JsonNull)
    }

    @Test
    fun `a block's page has its data, its operations and the trigger that replaces one`() = api {
        val company = result("/api/entity", "key" to "forms:DATA_DESK/COMPANY").field("formsBlock")
        assertThat(company.field("source")).isEqualTo(Json.parseToJsonElement(
            """{"type":"TABLE","name":"V_COMPANY","entity":{"key":"db:HR.V_COMPANY","kind":"VIEW","display":"HR.V_COMPANY","external":false,"name":"V_COMPANY"}}"""))
        assertThat(company.field("target").field("entity").text("key")).isEqualTo("db:HR.COMPANY")
        // The module's ON-INSERT replaces the insert of a block without its own: linked as the replacing trigger.
        val operations = company.field("operations").jsonArray
        assertThat(operations.map { "${it.text("operation")} ${it.text("state")}" })
            .containsExactly("QUERY DONE", "INSERT REPLACED", "UPDATE DONE", "DELETE DONE")
        assertThat(operations[1].field("trigger").text("key")).isEqualTo("forms:DATA_DESK/ON-INSERT")
        assertThat(company.text("where") to company.text("orderBy")).isEqualTo("st_active = 'Y'" to "company_name")
        val events = result("/api/entity", "key" to "forms:DATA_DESK/EVENTS").field("formsBlock").field("operations").jsonArray
        assertThat(events.map { it.jsonObject.keys }).containsExactly(
            setOf("operation", "state"), setOf("operation", "state"), setOf("operation", "state", "trigger"), setOf("operation", "state"))
        assertThat(events[2].field("trigger").text("key")).isEqualTo("forms:DATA_DESK/EVENTS/ON-UPDATE")
        // A DML target that is no table has no entity.
        val queue = result("/api/entity", "key" to "forms:DATA_DESK/QUEUE").field("formsBlock")
        assertThat(queue.field("target")).isEqualTo(Json.parseToJsonElement("""{"type":"NONE","name":"SEND_QUEUE"}"""))
        // Another entity's page has neither field.
        assertThat(result("/api/entity", "key" to "db:HR.COMPANY").jsonObject).doesNotContainKeys("formsBlock", "formsItem")
    }

    @Test
    fun `an item's page has its column, its target's column and what Forms does with it`() = api {
        val id = result("/api/entity", "key" to "forms:DATA_DESK/COMPANY/ID").jsonObject
        assertThat(id).doesNotContainKey("formsBlock")
        val item = id.getValue("formsItem")
        assertThat(listOf("database", "kind", "columnName", "read", "insertWrite", "updateWrite").map { item.text(it) })
            .containsExactly("true", "TEXT_ITEM", "", "true", "false", "false")
        assertThat(item.field("column").text("key") to item.field("targetColumn").text("key")).isEqualTo("db:HR.V_COMPANY.ID" to "db:HR.COMPANY.ID")
        // An item on no column leaves both out.
        assertThat(result("/api/entity", "key" to "forms:DATA_DESK/COMPANY/SAVE").field("formsItem").jsonObject).doesNotContainKeys("column", "targetColumn")
    }

    @Test
    fun `the usage, query, impact and line answers take the uses without a line`() = api {
        val uses = result("/api/occurrences", "key" to "db:HR.COMPANY", "scope" to "SELF,CHILDREN")
        val rows = implicitRows(uses)
        assertThat(rows.map { "${it.field("container").text("key")} ${it.field("roles").jsonArray.joinToString(",") { r -> r.jsonPrimitive.content }}" })
            .contains("forms:DATA_DESK/COMPANY IMPLICIT,SQL_UPDATE", "forms:DATA_DESK/COMPANY/NAME IMPLICIT,SQL_UPDATE,WRITE")
        assertThat(rows.map { it.text("path") }.distinct()).containsExactly("data_desk_fmb.xml")
        assertThat(uses.field("facets").field("bySide").jsonArray.map { it.text("value") }).containsExactly("OUTSIDE")
        val query = result("/api/query", "origins" to "FORMS_IMPLICIT", "limit" to "500")
        assertThat(implicitRows(query)).hasSize(21)
        assertThat(query.field("facets").field("byUnitKind").jsonArray).isEmpty()
        val groups = result("/api/query/groups", "origins" to "FORMS_IMPLICIT", "groupBy" to "UNIT")
        assertThat(groups.field("groups").jsonArray.map { it.field("values").jsonArray.single().jsonObject.values.toSet() }).containsExactly(setOf(JsonNull))
        val ids = rows.joinToString(",") { it.text("id") }
        assertThat(result("/api/occurrence-lines", "ids" to ids).jsonArray).isEmpty()
        val impact = result("/api/impact", "key" to "db:HR.COMPANY")
        val via = impact.field("nodes").jsonArray.single { it.field("entity").text("key") == "forms:DATA_DESK/COMPANY" }.field("via")
        assertThat(via.field("line")).isEqualTo(JsonNull)
        assertThat(via.text("origin") to via.text("path")).isEqualTo("FORMS_IMPLICIT" to "data_desk_fmb.xml")
        val graph = result("/api/impact/graph", "key" to "db:HR.COMPANY")
        val viewVia = graph.field("nodes").jsonArray.map { it.field("via") }.single { it.jsonObject["origin"] != null && it.field("line") == JsonNull }
        assertThat(viewVia.text("origin")).isEqualTo("FORMS_IMPLICIT")
    }

    private fun mcp(test: suspend ApplicationTestBuilder.(Client) -> Unit) = testApplication {
        application { atlasModule(index) }
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

    private suspend fun Client.call(name: String, arguments: JsonObject): JsonObject {
        val result = callTool(CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)))
        val text = (result.content.single() as TextContent).text
        assertThat(result.isError).describedAs(text).isNotEqualTo(true)
        return Json.parseToJsonElement(text).jsonObject.getValue("result").jsonObject
    }

    @Test
    fun `the MCP tools answer the uses without a line, leaving out what they lack`() = mcp { client ->
        val usages = client.call("find_usages", buildJsonObject { put("key", "db:HR.COMPANY") })
        val implicit = usages.getValue("rows").jsonArray.filter { it.text("origin") == "FORMS_IMPLICIT" }
        assertThat(implicit).hasSize(2).allSatisfy { assertThat(it.jsonObject).doesNotContainKeys("span", "unit", "code", "statementKind").containsKey("container") }
        val found = client.call("find_occurrences", buildJsonObject { put("origins", JsonArray(listOf(JsonPrimitive("FORMS_IMPLICIT")))) })
        assertThat(found.getValue("rows").jsonArray).allSatisfy { assertThat(it.jsonObject).doesNotContainKey("span") }
        val impact = client.call("find_impact", buildJsonObject { put("key", "db:HR.COMPANY") })
        val node = impact.getValue("nodes").jsonArray.single { it.jsonObject["name"]?.jsonPrimitive?.content == "DATA_DESK/COMPANY" }
        assertThat(node.field("via").jsonObject).doesNotContainKey("line").containsEntry("origin", JsonPrimitive("FORMS_IMPLICIT"))
        val entity = client.call("get_entity", buildJsonObject { put("key", "forms:DATA_DESK/EVENTS") })
        assertThat(entity.getValue("formsBlock").field("operations").jsonArray.map { it.text("state") })
            .containsExactly("DONE", "NOT_ALLOWED", "REPLACED", "DONE")
    }
}
