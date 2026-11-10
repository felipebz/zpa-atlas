//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.client.plugins.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat

/** Runs [test] with the MCP SDK's own client, over Streamable HTTP, against the endpoint served from [atlas]. */
internal fun mcp(atlas: AtlasIndex = ServerFixture.index, settings: McpSettings = McpSettings(), test: suspend ApplicationTestBuilder.(Client) -> Unit) = testApplication {
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

internal suspend fun Client.call(name: String, arguments: JsonObject = JsonObject(emptyMap())): CallToolResult =
    callTool(CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)))

internal fun CallToolResult.text() = (content.single() as TextContent).text

internal fun CallToolResult.json(): JsonElement {
    assertThat(isError).describedAs(text()).isNotEqualTo(true)
    return Json.parseToJsonElement(text())
}

internal fun args(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(block)

internal fun strings(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

internal fun CallToolResult.error(): String {
    assertThat(isError).describedAs(text()).isTrue()
    return text()
}
