package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
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
            "/api/source" to mapOf("path" to "hr/pack_calls.pkb", "line" to "32", "context" to "1")
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
    }

    @Test
    fun `bad parameters are 400 and unknown things are 404`() = api {
        assertThat(client.get("/api/entity").status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/entities") { parameter("q", "x"); parameter("limit", "999") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/occurrences") { parameter("key", runJob5); parameter("roles", "NOPE") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/occurrences") { parameter("key", runJob5); parameter("status", "NOPE") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/occurrences") { parameter("key", runJob5); parameter("scope", "ALL") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/source") { parameter("path", "../x.sql"); parameter("line", "1") }.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(client.get("/api/entity") { parameter("key", "db:NO.SUCH") }.status).isEqualTo(HttpStatusCode.NotFound)
        assertThat(client.get("/api/occurrences") { parameter("key", "db:NO.SUCH") }.status).isEqualTo(HttpStatusCode.NotFound)
        val error = json(client.get("/api/entity").bodyAsText())
        assertThat(error["error"]!!.jsonPrimitive.content).isEqualTo("Missing parameter: key")
    }

    @Test
    fun `source is 404 without a sources folder`() = testApplication {
        application { atlasModule(AtlasIndex(temp.resolve("fixture.db"), null)) }
        assertThat(client.get("/api/source") { parameter("path", "hr/proc_standalone.prc"); parameter("line", "1") }.status)
            .isEqualTo(HttpStatusCode.NotFound)
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
        for (path in listOf("/", "/entity.html", "/app.css", "/common.js", "/entity.js", "/search.js")) {
            val response = client.get(path)
            assertThat(response.status).describedAs(path).isEqualTo(HttpStatusCode.OK)
        }
        assertThat(client.get("/").bodyAsText()).contains("zpa-atlas")
    }
}
