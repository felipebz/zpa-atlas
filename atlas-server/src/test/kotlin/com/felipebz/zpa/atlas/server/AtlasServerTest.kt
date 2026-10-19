//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AtlasServerTest {
    private val temp: Path = Files.createTempDirectory("atlas-test")

    private lateinit var index: AtlasIndex

    private val runJob5 = "db:HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE,P_E IN NUMBER)"

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    @BeforeAll
    fun build() {
        index = AtlasIndex(Fixture.index(temp.resolve("fixture.db")), Fixture.sources)
    }

    private fun api(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { atlasModule(index) }
        test()
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `every endpoint answers with the run and its result`() = api {
        for ((path, parameters) in listOf(
            "/api/run" to emptyMap(),
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
        assertThat(client.get("/api/source") { parameter("path", "../x.sql"); parameter("line", "1") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/file") { parameter("path", "hr/no_such.sql") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/file").status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/entity") { parameter("key", "db:NO.SUCH") }.status).isEqualTo(HttpStatusCode.NotFound)
        assertThat(client.get("/api/occurrences") { parameter("key", "db:NO.SUCH") }.status).isEqualTo(HttpStatusCode.NotFound)
        val error = json(client.get("/api/entity").bodyAsText())
        assertThat(error["error"]!!.jsonPrimitive.content).isEqualTo("Missing parameter: key")
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
    fun `large answers are gzipped for clients that accept it, small ones and other clients get plain bytes`() = api {
        val plain = client.get("/api/file") { parameter("path", "hr/pack_calls.pkb") }
        assertThat(plain.headers[HttpHeaders.ContentEncoding]).isNull()
        val plainBytes = plain.bodyAsBytes()
        assertThat(plainBytes.size.toLong()).isGreaterThan(COMPRESSION_MINIMUM_BYTES)
        val gzipped = client.get("/api/file") { parameter("path", "hr/pack_calls.pkb"); header(HttpHeaders.AcceptEncoding, "gzip") }
        assertThat(gzipped.headers[HttpHeaders.ContentEncoding]).isEqualTo("gzip")
        val raw = gzipped.bodyAsBytes()
        assertThat(java.util.zip.GZIPInputStream(raw.inputStream()).readBytes()).isEqualTo(plainBytes)
        assertThat(raw.size).isLessThan(plainBytes.size)

        val small = client.get("/api/entity") { parameter("key", "db:HR.TY_SHAPE_REFS") }
        assertThat(small.bodyAsBytes().size.toLong()).isLessThan(COMPRESSION_MINIMUM_BYTES)
        val smallGzip = client.get("/api/entity") { parameter("key", "db:HR.TY_SHAPE_REFS"); header(HttpHeaders.AcceptEncoding, "gzip") }
        assertThat(smallGzip.headers[HttpHeaders.ContentEncoding]).isNull()
    }

    @Test
    fun `source is 404 without a sources folder`() = testApplication {
        application { atlasModule(AtlasIndex(temp.resolve("fixture.db"), null)) }
        assertThat(client.get("/api/source") { parameter("path", "hr/proc_standalone.prc"); parameter("line", "1") }.status)
            .isEqualTo(HttpStatusCode.NotFound)
        assertThat(client.get("/api/file") { parameter("path", "hr/proc_standalone.prc") }.status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test
    fun `the English message catalog is served`() = api {
        val response = client.get("/i18n/en.js")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.bodyAsText()).contains("registerMessages(\"en\"", "\"usage.title\": \"Used by\"")
        val pt = client.get("/i18n/pt.js")
        assertThat(pt.status).isEqualTo(HttpStatusCode.OK)
        assertThat(pt.bodyAsText()).contains("registerMessages(\"pt\"", "\"usage.title\": \"Quem usa\"")
    }

    @Test
    fun `the web pages are served`() = api {
        for (path in listOf("/", "/entity.html", "/file.html", "/query.html", "/app.css", "/common.js", "/entity.js", "/file.js", "/query.js", "/search.js")) {
            val response = client.get(path)
            assertThat(response.status).describedAs(path).isEqualTo(HttpStatusCode.OK)
        }
        assertThat(client.get("/").bodyAsText()).contains("zpa-atlas")
    }
}
