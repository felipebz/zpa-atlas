//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration

/** The HTTP API's endpoint contract and its query endpoints, on the fixture index. */
class ServerQueriesTest {
    private val index: AtlasIndex get() = ServerFixture.index
    private val temp: Path get() = ServerFixture.temp

    @Test
    fun `every endpoint answers with the run and its result`() = api {
        for ((path, parameters) in listOf(
            "/api/run" to emptyMap(),
            "/api/top-packages" to emptyMap(),
            "/api/entities" to mapOf("q" to "exec"),
            "/api/entity" to mapOf("key" to runJob5),
            "/api/occurrences" to mapOf("key" to runJob5, "scope" to "SELF,IMPLEMENTATIONS", "roles" to "CALL"),
            "/api/source" to mapOf("path" to "hr/pack_calls.pkb", "line" to "32", "context" to "1"),
            "/api/file" to mapOf("path" to "hr/pack_calls.pkb"),
            "/api/values" to mapOf("key" to "db:HR.PACK_ARGS.P(P_CODE IN NUMBER,P_NAME IN VARCHAR2,P_FLAG IN BOOLEAN).P_CODE"),
            "/api/value-sites" to mapOf("key" to "db:HR.PACK_ARGS.P(P_CODE IN NUMBER,P_NAME IN VARCHAR2,P_FLAG IN BOOLEAN).P_CODE", "kind" to "NUMBER", "value" to "1.5")
        )) {
            val response = client.get(path) { parameters.forEach { (k, v) -> parameter(k, v) } }
            assertThat(response.status).describedAs(path).isEqualTo(HttpStatusCode.OK)
            val body = json(response.bodyAsText())
            assertThat(body["run"]!!.jsonObject["compilationSchema"]!!.jsonPrimitive.content).isEqualTo("HR")
            assertThat(body).containsKey("result")
        }
        val entity = json(client.get("/api/entity") { parameter("key", runJob5) }.bodyAsText())["result"]!!.jsonObject
        assertThat(entity["overloads"]!!.jsonArray).hasSize(4)
        val page = json(client.get("/api/occurrences") { parameter("key", runJob5) }.bodyAsText())["result"]!!.jsonObject
        assertThat(page["rows"]!!.jsonArray.map { it.jsonObject["viaCandidate"]!!.jsonPrimitive.content }).contains("true", "false")
        assertThat(page).containsKeys("nextAfter", "total", "facets")
        val file = json(client.get("/api/file") { parameter("path", "hr/pack_calls.pkb") }.bodyAsText())["result"]!!.jsonObject
        assertThat(file["stale"]!!.jsonPrimitive.content).isEqualTo("false")
        assertThat(file["occurrences"]!!.jsonArray.first().jsonObject).containsOnlyKeys("at", "roles", "status", "reason", "entity", "candidates")
    }

    @Test
    fun `bad parameters are 400 and unknown things are 404`() = api {
        assertThat(client.get("/api/entity").status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/entities") { parameter("q", "x"); parameter("limit", "999") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/occurrences") { parameter("key", runJob5); parameter("roles", "NOPE") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/occurrences") { parameter("key", runJob5); parameter("status", "NOPE") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/occurrences") { parameter("key", runJob5); parameter("scope", "ALL") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/occurrences") { parameter("key", runJob5); parameter("side", "NEAR") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/source") { parameter("path", "../x.sql"); parameter("line", "1") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/file") { parameter("path", "hr/no_such.sql") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/file").status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/entity") { parameter("key", "db:NO.SUCH") }.status).isEqualTo(HttpStatusCode.NotFound)
        assertThat(client.get("/api/occurrences") { parameter("key", "db:NO.SUCH") }.status).isEqualTo(HttpStatusCode.NotFound)
        val error = json(client.get("/api/entity").bodyAsText())
        assertThat(error["error"]!!.jsonPrimitive.content).isEqualTo("Missing parameter: key")
    }

    @Test
    fun `a package's uses split into those of other programs and those of its own spec and body`() = api {
        val pack = "db:HR.PACK_CALLS"
        suspend fun page(side: String?) = json(client.get("/api/occurrences") {
            parameter("key", pack); parameter("scope", "SELF,IMPLEMENTATIONS,CHILDREN"); parameter("limit", "500")
            side?.let { parameter("side", it) }
        }.bodyAsText())["result"]!!.jsonObject
        fun JsonObject.total() = getValue("total").jsonPrimitive.long
        fun JsonObject.units() = getValue("rows").jsonArray.map { it.jsonObject.getValue("unit").jsonObject["entity"]?.takeIf { e -> e != JsonNull }?.jsonObject?.get("key")?.jsonPrimitive?.content }
        fun JsonObject.bySide() = getValue("facets").jsonObject.getValue("bySide").jsonArray.associate {
            it.jsonObject.getValue("value").jsonPrimitive.content to it.jsonObject.getValue("count").jsonPrimitive.long
        }
        val all = page(null)
        val outside = page("OUTSIDE")
        val inside = page("INSIDE")
        // The same rule as the home page's packages most used from outside: the unit's entity is the package or not.
        assertThat(inside.units()).isNotEmpty().allMatch { it == pack }
        assertThat(outside.units()).isNotEmpty().allMatch { it != pack }
        assertThat(outside.total() + inside.total()).isEqualTo(all.total())
        // Both sides are counted whatever side is listed; the role facet counts the side listed.
        for (p in listOf(all, outside, inside)) assertThat(p.bySide()).isEqualTo(mapOf("INSIDE" to inside.total(), "OUTSIDE" to outside.total()))
        val roles = { p: JsonObject -> p.getValue("facets").jsonObject.getValue("byRole").jsonArray.sumOf { it.jsonObject.getValue("count").jsonPrimitive.long } }
        assertThat(roles(outside) + roles(inside)).isEqualTo(roles(all))
        val home = json(client.get("/api/top-packages").bodyAsText())["result"]!!.jsonArray.single { it.jsonObject["entity"]!!.jsonObject["key"]!!.jsonPrimitive.content == pack }
        assertThat(outside.total()).isEqualTo(home.jsonObject["uses"]!!.jsonPrimitive.long)
    }

    @Test
    fun `the query endpoints read every filter from the parameters`() = api {
        val crud = mapOf("container" to "db:HR.PACK_PROCESS", "roles" to "SQL_SELECT,SQL_INSERT,SQL_UPDATE,SQL_DELETE")
        val page = json(client.get("/api/query") { crud.forEach { (k, v) -> parameter(k, v) }; parameter("limit", "3") }.bodyAsText())["result"]!!.jsonObject
        val total = page["total"]!!.jsonPrimitive.content.toLong()
        assertThat(page["rows"]!!.jsonArray).hasSize(3)
        assertThat(page["facets"]!!.jsonObject).containsKeys("byRole", "byStatus", "byOrigin", "byEntityKind", "byUnitKind", "byReason")
        val next = json(client.get("/api/query") {
            crud.forEach { (k, v) -> parameter(k, v) }; parameter("after", page["nextAfter"]!!.jsonPrimitive.content); parameter("limit", "500"); parameter("summary", "false")
        }.bodyAsText())["result"]!!.jsonObject
        assertThat(next["rows"]!!.jsonArray.size + 3L).isEqualTo(total)
        assertThat(next["total"]!!.jsonPrimitive.content).isEqualTo("-1")
        val summary = json(client.get("/api/query/summary") { crud.forEach { (k, v) -> parameter(k, v) } }.bodyAsText())["result"]!!.jsonObject
        assertThat(summary["total"]!!.jsonPrimitive.content.toLong()).isEqualTo(total)
        val reasons = json(client.get("/api/query/summary") { parameter("statuses", "UNRESOLVED") }.bodyAsText())["result"]!!.jsonObject["facets"]!!
            .jsonObject["byReason"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content }
        assertThat(reasons).contains("NOT_IN_PROJECT")
        val listed = json(client.get("/api/query") { parameter("statuses", "UNRESOLVED") }.bodyAsText())["result"]!!.jsonObject["facets"]!!
            .jsonObject["byReason"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content }
        assertThat(listed).isEqualTo(reasons)

        val matrix = json(client.get("/api/query/groups") { crud.forEach { (k, v) -> parameter(k, v) }; parameter("groupBy", "CONTAINER,ENTITY,ROLE") }.bodyAsText())["result"]!!.jsonObject
        assertThat(matrix["dimensions"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("CONTAINER", "ENTITY", "ROLE")
        val first = matrix["groups"]!!.jsonArray.first().jsonObject
        assertThat(first["values"]!!.jsonArray[0].jsonObject["entity"]!!.jsonObject["key"]!!.jsonPrimitive.content).startsWith("db:HR.PACK_PROCESS")
        assertThat(first["values"]!!.jsonArray[2].jsonObject["text"]!!.jsonPrimitive.content).startsWith("SQL_")

        // Every other parameter, each narrowing a query that still runs.
        for ((name, value) in listOf(
            "entity" to "db:HR.COMPANY", "inside" to "true", "entityKinds" to "TABLE,COLUMN", "name" to "comp*", "schema" to "HR", "external" to "false",
            "containerInside" to "false", "unitKinds" to "PACKAGE_BODY", "path" to "hr/", "statuses" to "RESOLVED", "reasons" to "NOT_IN_PROJECT",
            "origins" to "STATIC", "statementKinds" to "UPDATE", "valueKind" to "STRING", "value" to "S*", "candidates" to "false"
        )) {
            val response = client.get("/api/query/groups") { parameter(name, value); parameter("groupBy", "STATUS") }
            assertThat(response.status).describedAs(name).isEqualTo(HttpStatusCode.OK)
        }
        val narrowed = json(client.get("/api/query") { parameter("entity", "db:HR.COMPANY"); parameter("inside", "true"); parameter("entityKinds", "COLUMN") }
            .bodyAsText())["result"]!!.jsonObject
        assertThat(narrowed["facets"]!!.jsonObject["byEntityKind"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content }).containsExactly("COLUMN")
    }

    @Test
    fun `the text search answers as AtlasIndex searchText, and 400 for a short text`() = api {
        val answer = client.get("/api/text") { parameter("text", "vFixTag"); parameter("containerName", "text_tags"); parameter("limit", "2") }
        assertThat(answer.status).isEqualTo(HttpStatusCode.OK)
        assertThat(answer.bodyAsText()).isEqualTo(ApiJson.encodeToString(index.searchText("vFixTag", containerName = "text_tags", limit = 2)))
        assertThat(client.get("/api/text") { parameter("text", "vT") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/text").status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun `bad query parameters are 400 with the reason`() = api {
        for ((name, value) in listOf(
            "roles" to "NOPE", "statuses" to "NOPE", "entityKinds" to "NOPE", "unitKinds" to "NOPE", "origins" to "NOPE", "statementKinds" to "NOPE",
            "valueKind" to "NOPE", "entity" to "db:NO.SUCH", "container" to "db:NO.SUCH", "inside" to "yes", "limit" to "501"
        )) {
            val response = client.get("/api/query") { parameter(name, value) }
            assertThat(response.status).describedAs(name).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(json(response.bodyAsText())).containsKey("error")
        }
        assertThat(client.get("/api/query/groups").status).isEqualTo(HttpStatusCode.BadRequest)
        val dimension = client.get("/api/query/groups") { parameter("groupBy", "STATUS,COLOR") }
        assertThat(dimension.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(json(dimension.bodyAsText())["error"]!!.jsonPrimitive.content).isEqualTo("Unknown groupBy dimension: COLOR")
        assertThat(client.get("/api/query/groups") { parameter("groupBy", "STATUS"); parameter("limit", "1001") }.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun `a query past its time limit is 400 and asks for narrower filters`() = testApplication {
        application { atlasModule(AtlasIndex(temp.resolve("fixture.db"), null, queryTimeout = Duration.ZERO)) }
        for (path in listOf("/api/query", "/api/query/summary", "/api/query/groups?groupBy=ENTITY")) {
            val response = client.get(path)
            assertThat(response.status).describedAs(path).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(json(response.bodyAsText())["error"]!!.jsonPrimitive.content).describedAs(path).contains("narrow the filters")
        }
        assertThat(client.get("/api/run").status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun `an api answer writes a null field as null`() = api {
        val body = client.get("/api/query") { parameter("roles", "WRITE"); parameter("statuses", "RESOLVED"); parameter("limit", "1") }.bodyAsText()
        val row = json(body)["result"]!!.jsonObject["rows"]!!.jsonArray.single().jsonObject
        assertThat(row).containsKey("reason")
        assertThat(row["reason"]).isEqualTo(JsonNull)
        assertThat(body).contains("\"reason\":null")
    }
}
