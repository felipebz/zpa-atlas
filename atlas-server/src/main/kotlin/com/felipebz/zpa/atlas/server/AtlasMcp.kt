//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.AtlasIndex
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.ktor.http.content.TextContent as HttpText

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
 * [MCP_LOCAL_HOSTS], [McpSettings.allowedHosts] and the host of [AuthSettings.publicUrl] are refused. The token check
 * of [AuthSettings] is not here: it answers before this route is reached.
 */
fun Application.atlasMcp(index: AtlasIndex, settings: McpSettings, auth: AuthSettings? = null, basePath: BasePath = BasePath.ROOT) =
    mountMcp(settings, auth, basePath) { atlasMcpServer(index, settings.maxAnswerChars) }

/** `/mcp` as [atlasMcp] mounts it, each session served by a new [server]. */
internal fun Application.mountMcp(settings: McpSettings, auth: AuthSettings?, basePath: BasePath, server: () -> Server) {
    val hosts = (MCP_LOCAL_HOSTS + settings.allowedHosts + listOfNotNull(auth?.publicHost)).distinct()
    install(ClosedToolSchemas) { path = basePath.of("/mcp") }
    mcpStreamableHttp(
        path = basePath.of("/mcp"),
        allowedHosts = hosts,
        // With hosts of its own the SDK checks no Origin: check it against the same names.
        allowedOrigins = hosts.map { "http://$it" },
        sseHeartbeatConfig = { period = settings.heartbeat }
    ) { server() }
}

/**
 * Adds `"additionalProperties": false` to every input schema of a `tools/list` answer on `/mcp`. The tools refuse an
 * argument they do not know; the SDK's `ToolSchema` has no field to say so, so the answer's JSON is completed here.
 */
private class ClosedToolSchemasConfig {
    /** The path of the MCP endpoint, under the base path. */
    var path = "/mcp"
}

private val ClosedToolSchemas = createApplicationPlugin("ClosedToolSchemas", ::ClosedToolSchemasConfig) {
    val path = pluginConfig.path
    // Installed before the SDK's ContentNegotiation, so the answer is still the SDK's object here, not compressed bytes.
    onCallRespond { call, body ->
        if (call.request.path() != path || body !is JSONRPCResponse || body.result !is ListToolsResult) return@onCallRespond
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
        instructions = McpToolTexts.INSTRUCTIONS
    )
    AtlasMcpTools(index, maxAnswerChars).register(server)
    return server
}
