//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.query.ImpactOptions
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration

/** The HTTP API's impact and impact graph endpoints, with their node and time limits, on the fixture index. */
class ServerImpactTest {
    private val index: AtlasIndex get() = ServerFixture.index
    private val temp: Path get() = ServerFixture.temp

    @Test
    fun `the impact of an entity is its graph, 404 for an unknown key and 400 for a bad argument`() = api {
        val leaf = "db:HR.PACK_IMP_A.LEAF(P_N IN NUMBER)"
        // Without following triggers: the graph of ImpactTest.
        val answer = client.get("/api/impact") { parameter("key", leaf); parameter("stop", "ENTRY_POINT"); parameter("triggers", "false") }
        assertThat(answer.status).isEqualTo(HttpStatusCode.OK)
        val result = json(answer.bodyAsText())["result"]!!.jsonObject
        assertThat(result["levels"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("5", "9", "1")
        assertThat(result["nodes"]!!.jsonArray.map { it.jsonObject["entity"]!!.jsonObject["key"]!!.jsonPrimitive.content })
            .containsExactly(
                "db:HR.PACK_IMP_A.PONG(P_N IN NUMBER)", "db:HR.IMP_AMB()", "db:HR.IMP_REC(P_N IN NUMBER)", "db:HR.PACK_IMP_A.PING(P_N IN NUMBER)",
                "db:HR.PACK_IMP_B.CURS_USER()", "db:HR.PACK_IMP_B.TOP()", "db:HR.IMP_READ_VIEW()"
            )
        assertThat(client.get("/api/impact") { parameter("key", "db:NO.SUCH") }.status).isEqualTo(HttpStatusCode.NotFound)
        for ((name, value) in listOf("maxDepth" to "0", "maxDepth" to "51", "stop" to "NOPE", "scope" to "NOPE", "roles" to "NOPE", "status" to "NOPE", "limit" to "501", "triggers" to "maybe")) {
            val response = client.get("/api/impact") { parameter("key", leaf); parameter(name, value) }
            assertThat(response.status).describedAs("$name=$value").isEqualTo(HttpStatusCode.BadRequest)
            assertThat(json(response.bodyAsText())).containsKey("error")
        }
        assertThat(client.get("/api/impact").status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun `the impact follows triggers by default, and triggers=false answers as the walk without them`() = api {
        val key = "db:HR.IT_AUDIT(P_WHAT IN VARCHAR2)"
        val followed = client.get("/api/impact") { parameter("key", key) }.bodyAsText()
        assertThat(json(followed)["result"]!!.jsonObject["levels"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("8", "21", "2")
        assertThat(followed).contains("\"fires\":\"INSERT\"")
        val off = client.get("/api/impact") { parameter("key", key); parameter("triggers", "false") }.bodyAsText()
        assertThat(off).isEqualTo(ApiJson.encodeToString(index.impact(key, ImpactOptions(followTriggers = false))))
        assertThat(off).doesNotContain("\"fires\"")
        assertThat(json(off)["result"]!!.jsonObject["stops"]!!.jsonArray.map { it.jsonObject["value"]!!.jsonPrimitive.content }).containsExactly("TRIGGER")
    }

    @Test
    fun `the impact graph is the whole graph at once, 404 for an unknown key, 400 for a bad argument and a timeout said as one`() = api {
        val leaf = "db:HR.PACK_IMP_A.LEAF(P_N IN NUMBER)"
        val response = client.get("/api/impact/graph") { parameter("key", leaf); parameter("triggers", "false") }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.bodyAsText()).isEqualTo(ApiJson.encodeToString(index.impactView(leaf, followTriggers = false)))
        val result = json(response.bodyAsText())["result"]!!.jsonObject
        assertThat(result["nodes"]!!.jsonArray).hasSize(15)
        assertThat(result["levels"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("5", "9", "1")
        // The options reach the walk: a smaller depth, and triggers followed by default.
        val shallow = json(client.get("/api/impact/graph") { parameter("key", leaf); parameter("maxDepth", "1"); parameter("triggers", "false") }.bodyAsText())
        assertThat(shallow["result"]!!.jsonObject["levels"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("5")
        assertThat(json(client.get("/api/impact/graph") { parameter("key", leaf) }.bodyAsText())["result"]!!.jsonObject["followTriggers"]!!.jsonPrimitive.content)
            .isEqualTo("true")
        assertThat(client.get("/api/impact/graph") { parameter("key", "db:NO.SUCH") }.status).isEqualTo(HttpStatusCode.NotFound)
        for ((name, value) in listOf("maxDepth" to "0", "maxDepth" to "51", "scope" to "NOPE", "triggers" to "maybe")) {
            val bad = client.get("/api/impact/graph") { parameter("key", leaf); parameter(name, value) }
            assertThat(bad.status).describedAs("$name=$value").isEqualTo(HttpStatusCode.BadRequest)
            assertThat(json(bad.bodyAsText())).containsKey("error").doesNotContainKey("timeout")
        }
        assertThat(client.get("/api/impact/graph").status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun `an impact graph over the node limit is 400 and gives its size and the limit`() = testApplication {
        application { atlasModule(AtlasIndex(temp.resolve("fixture.db"), null, maxViewNodes = 14)) }
        val response = client.get("/api/impact/graph") { parameter("key", "db:HR.PACK_IMP_A.LEAF(P_N IN NUMBER)"); parameter("triggers", "false") }
        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        val body = json(response.bodyAsText())
        assertThat(body["tooLarge"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(body["nodes"]!!.jsonPrimitive.content).isEqualTo("15")
        assertThat(body["limit"]!!.jsonPrimitive.content).isEqualTo("14")
    }

    @Test
    fun `an impact graph past the time limit is 400 and says it timed out`() = testApplication {
        application { atlasModule(AtlasIndex(temp.resolve("fixture.db"), null, queryTimeout = Duration.ZERO)) }
        val response = client.get("/api/impact/graph") { parameter("key", "db:HR.PACK_IMP_A.LEAF(P_N IN NUMBER)") }
        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        val body = json(response.bodyAsText())
        assertThat(body["timeout"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(body["error"]!!.jsonPrimitive.content).contains("narrow the filters")
    }
}
