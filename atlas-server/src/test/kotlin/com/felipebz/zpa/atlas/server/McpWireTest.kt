//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.milliseconds

/** The MCP endpoint at the wire: the Host allow-list and raw JSON-RPC over a real server. */
class McpWireTest {
    private val index: AtlasIndex get() = ServerFixture.index

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
            assertThat(listed).hasSize(13)
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
