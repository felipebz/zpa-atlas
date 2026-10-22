//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.query.GraphTooLargeException
import com.felipebz.zpa.atlas.query.GroupDimension
import com.felipebz.zpa.atlas.query.ImpactStop
import com.felipebz.zpa.atlas.query.InvalidQueryException
import com.felipebz.zpa.atlas.query.OccurrenceQuery
import com.felipebz.zpa.atlas.query.QueryTimeoutException
import com.felipebz.zpa.atlas.query.SchemaMismatchException
import com.felipebz.zpa.atlas.query.Scope
import com.felipebz.zpa.atlas.query.indexCodes
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ApiError(val error: String)

/** A request past the time limit, told apart from other refusals by [timeout]. */
@Serializable
data class ApiTimeout(val error: String, val timeout: Boolean = true)

/** An impact graph over the node limit: its [nodes] and the [limit], told apart by [tooLarge]. */
@Serializable
data class ApiTooLarge(val error: String, val nodes: Int, val limit: Int, val tooLarge: Boolean = true)

const val COMPRESSION_MINIMUM_BYTES = 1024L

/** The JSON of `/api`: every field written, a null one as `null`. */
internal val ApiJson = Json { explicitNulls = true; encodeDefaults = true }

/**
 * Answers [value] as `/api` JSON. `/api` writes its JSON itself rather than through `ContentNegotiation`, which the
 * MCP endpoint installs for the whole application with its own settings (nulls left out).
 */
internal suspend inline fun <reified T> ApplicationCall.respondJson(value: T, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(ApiJson.encodeToString(value), ContentType.Application.Json, status)

/**
 * The HTTP API (`/api/…`, JSON, GET only), the static web and the MCP endpoint (`/mcp`). Routes only parse
 * parameters, call one [AtlasIndex] method and serialize its answer.
 *
 * @param mcp the host names `/mcp` accepts besides the local ones, and its stream settings
 * @param auth when given, `/mcp` and `/api` answer only requests with an access token of that issuer, and the
 *     protected resource metadata is served; the static page stays open. Without it, nothing is checked
 * @param basePath the path everything is served under; outside it, nothing is answered. [auth]'s must be the same
 * @throws AuthDiscoveryException when [auth] is given and its issuer's discovery document cannot be read
 */
fun Application.atlasModule(index: AtlasIndex, mcp: McpSettings = McpSettings(), auth: AuthSettings? = null, basePath: BasePath = BasePath.ROOT) =
    atlasModule(index, mcp, auth, { oidcIdentity(it) }, basePath) {
        HttpClient(io.ktor.client.engine.cio.CIO).also { client -> monitor.subscribe(ApplicationStopped) { client.close() } }
    }

internal fun Application.atlasModule(
    index: AtlasIndex,
    mcp: McpSettings,
    auth: AuthSettings?,
    identity: IdentityInstaller,
    basePath: BasePath = BasePath.ROOT,
    providerClient: () -> HttpClient
) {
    require(auth == null || auth.basePath == basePath) { "The authentication settings are for base path ${auth?.basePath}, not $basePath" }
    if (basePath != BasePath.ROOT) install(BareBasePathRedirect) { path = basePath.path }
    install(Compression) {
        gzip { priority = 1.0; minimumSize(COMPRESSION_MINIMUM_BYTES) }
        deflate { priority = 0.9; minimumSize(COMPRESSION_MINIMUM_BYTES) }
        // An event stream is never compressed: an encoder would hold the events back until its buffer fills.
        excludeContentType(ContentType.Text.EventStream)
    }
    install(StatusPages) {
        exception<InvalidQueryException> { call, e -> call.respondJson(ApiError(e.message ?: "Bad request"), HttpStatusCode.BadRequest) }
        exception<QueryTimeoutException> { call, e -> call.respondJson(ApiError(e.message ?: "The query took too long"), HttpStatusCode.BadRequest) }
        exception<SchemaMismatchException> { call, e -> call.respondJson(ApiError(e.message ?: "Schema mismatch"), HttpStatusCode.ServiceUnavailable) }
    }
    auth?.let { atlasAuth(it, identity, providerClient) }
    routing {
        route(basePath.of("/api")) {
            get("/run") { call.respondJson(index.run()) }
            // The code lists read no index: this answers without one, so it has no run stamp.
            get("/codes") { call.respondJson(indexCodes()) }
            get("/entities") {
                val q = call.required("q")
                call.respondJson(index.searchEntities(q, call.optional("kind"), call.optional("external")?.toBooleanStrictOrNull() ?: true,
                    call.int("limit") ?: 50))
            }
            get("/entity") {
                val answer = index.entity(call.required("key"))
                if (answer.result == null) call.respondJson(ApiError("Unknown entity"), HttpStatusCode.NotFound) else call.respondJson(answer)
            }
            get("/occurrences") {
                val scope = call.list("scope")?.map { value ->
                    Scope.entries.firstOrNull { it.name == value } ?: throw InvalidQueryException("Unknown scope: $value")
                }?.toSet() ?: setOf(Scope.SELF)
                val answer = index.occurrences(
                    key = call.required("key"),
                    roles = call.list("roles")?.toSet(),
                    statuses = call.list("status")?.toSet(),
                    scope = scope,
                    includeCandidates = call.optional("candidates")?.toBooleanStrictOrNull() ?: true,
                    after = call.long("after"),
                    limit = call.int("limit") ?: 100
                )
                if (answer.result == null) call.respondJson(ApiError("Unknown entity"), HttpStatusCode.NotFound) else call.respondJson(answer)
            }
            get("/impact") {
                val scope = call.list("scope")?.map { value ->
                    Scope.entries.firstOrNull { it.name == value } ?: throw InvalidQueryException("Unknown scope: $value")
                }?.filter { it != Scope.SELF }
                val stop = call.optional("stop")?.let { value ->
                    ImpactStop.entries.firstOrNull { it.name == value } ?: throw InvalidQueryException("Unknown stop: $value")
                }
                val answer = index.impact(
                    key = call.required("key"),
                    roles = call.list("roles")?.toSet(),
                    statuses = call.list("status")?.toSet(),
                    include = scope,
                    maxDepth = call.int("maxDepth") ?: 10,
                    stop = stop,
                    offset = call.int("offset") ?: 0,
                    limit = call.int("limit") ?: 100,
                    followTriggers = call.boolean("triggers") ?: true
                )
                if (answer.result == null) call.respondJson(ApiError("Unknown entity"), HttpStatusCode.NotFound) else call.respondJson(answer)
            }
            // The whole graph at once, for the impact page; a graph past the time limit or over the node limit says
            // so, so the page can suggest a smaller depth.
            get("/impact/graph") {
                val scope = call.list("scope")?.map { value ->
                    Scope.entries.firstOrNull { it.name == value } ?: throw InvalidQueryException("Unknown scope: $value")
                }?.filter { it != Scope.SELF }
                val key = call.required("key")
                val maxDepth = call.int("maxDepth") ?: 10
                val triggers = call.boolean("triggers") ?: true
                val answer = try {
                    index.impactView(key, scope, maxDepth, triggers)
                } catch (e: QueryTimeoutException) {
                    call.respondJson(ApiTimeout(e.message ?: "The query took too long"), HttpStatusCode.BadRequest)
                    return@get
                } catch (e: GraphTooLargeException) {
                    call.respondJson(ApiTooLarge(e.message!!, e.nodes, e.limit), HttpStatusCode.BadRequest)
                    return@get
                }
                if (answer.result == null) call.respondJson(ApiError("Unknown entity"), HttpStatusCode.NotFound) else call.respondJson(answer)
            }
            get("/query") {
                call.respondJson(index.queryOccurrences(call.occurrenceQuery(), call.long("after"), call.int("limit") ?: 100, call.boolean("summary") ?: true))
            }
            get("/query/summary") { call.respondJson(index.querySummary(call.occurrenceQuery())) }
            get("/query/groups") {
                val dimensions = (call.list("groupBy") ?: throw InvalidQueryException("Missing parameter: groupBy")).map { value ->
                    GroupDimension.entries.firstOrNull { it.name == value } ?: throw InvalidQueryException("Unknown groupBy dimension: $value")
                }
                call.respondJson(index.groupOccurrences(call.occurrenceQuery(), dimensions, call.int("limit") ?: 100))
            }
            get("/values") {
                val answer = index.values(call.required("key"))
                if (answer.result == null) call.respondJson(ApiError("Unknown entity"), HttpStatusCode.NotFound) else call.respondJson(answer)
            }
            get("/value-sites") {
                val answer = index.valueSites(call.required("key"), call.required("kind"), call.request.queryParameters["value"],
                    call.long("after"), call.int("limit") ?: 100)
                if (answer.result == null) call.respondJson(ApiError("Unknown entity"), HttpStatusCode.NotFound) else call.respondJson(answer)
            }
            get("/source") {
                val answer = index.source(call.required("path"), call.int("line") ?: throw InvalidQueryException("Missing parameter: line"),
                    call.int("context") ?: 3)
                if (answer.result == null) call.respondJson(ApiError("Source not available"), HttpStatusCode.NotFound) else call.respondJson(answer)
            }
            get("/text") {
                call.respondJson(index.searchText(call.required("text"), call.optional("path"), call.optional("container"),
                    call.optional("containerName"), call.long("after"), call.int("limit") ?: 100))
            }
            get("/file") {
                val answer = index.file(call.required("path"))
                if (answer.result == null) call.respondJson(ApiError("Source not available"), HttpStatusCode.NotFound) else call.respondJson(answer)
            }
        }
        // Outside the gate: a probe has no token. It answers whether the index opens, nothing of what is in it.
        route(basePath.of("/health")) {
            get { call.health(index, head = false) }
            head { call.health(index, head = true) }
        }
        staticResources(basePath.of("/"), "web", index = "index.html")
    }
    atlasMcp(index, mcp, auth, basePath)
}

private class BareBasePathConfig {
    var path = ""
}

/**
 * The bare base path (`/atlas`) answers with a redirect to `/atlas/`, its query string kept: the pages link and ask
 * relatively, and against `/atlas` they would resolve against the host's root.
 */
private val BareBasePathRedirect = createApplicationPlugin("BareBasePathRedirect", ::BareBasePathConfig) {
    val path = pluginConfig.path
    onCall { call ->
        if (call.request.path() != path) return@onCall
        val query = call.request.queryString()
        call.respondRedirect(path + "/" + if (query.isEmpty()) "" else "?$query")
    }
}

private suspend fun ApplicationCall.health(index: AtlasIndex, head: Boolean) {
    val ready = try {
        index.stamp()
        true
    } catch (e: Exception) {
        application.log.debug("Health check: the index is unavailable: {}", e.message)
        false
    }
    val status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
    response.header(HttpHeaders.CacheControl, "no-store")
    if (head) respond(status) else respondText(if (ready) "ok\n" else "unavailable\n", ContentType.Text.Plain, status)
}

private fun ApplicationCall.optional(name: String): String? = request.queryParameters[name]?.takeIf { it.isNotEmpty() }

private fun ApplicationCall.required(name: String): String = optional(name) ?: throw InvalidQueryException("Missing parameter: $name")

private fun ApplicationCall.list(name: String): List<String>? = optional(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }

private fun ApplicationCall.boolean(name: String): Boolean? =
    optional(name)?.let { it.toBooleanStrictOrNull() ?: throw InvalidQueryException("$name must be true or false") }

/** The filters of `/api/query…`; every parameter is optional, lists are comma-separated. */
private fun ApplicationCall.occurrenceQuery() = OccurrenceQuery(
    entity = optional("entity"),
    inside = boolean("inside") ?: false,
    entityKinds = list("entityKinds").orEmpty().toSet(),
    name = optional("name"),
    schema = optional("schema"),
    external = boolean("external"),
    container = optional("container"),
    containerName = optional("containerName"),
    containerInside = boolean("containerInside") ?: true,
    unitKinds = list("unitKinds").orEmpty().toSet(),
    path = optional("path"),
    roles = list("roles").orEmpty().toSet(),
    statuses = list("statuses").orEmpty().toSet(),
    reasons = list("reasons").orEmpty().toSet(),
    origins = list("origins").orEmpty().toSet(),
    statementKinds = list("statementKinds").orEmpty().toSet(),
    valueKind = optional("valueKind"),
    value = optional("value"),
    candidates = boolean("candidates") ?: true
)

private fun ApplicationCall.int(name: String): Int? =
    optional(name)?.let { it.toIntOrNull() ?: throw InvalidQueryException("$name must be an integer") }

private fun ApplicationCall.long(name: String): Long? =
    optional(name)?.let { it.toLongOrNull() ?: throw InvalidQueryException("$name must be an integer") }

/**
 * Starts the server and blocks.
 *
 * @throws AuthDiscoveryException when [auth] is given and its issuer's discovery document cannot be read: the server
 *     does not start
 */
fun serve(index: AtlasIndex, host: String, port: Int, mcp: McpSettings = McpSettings(), auth: AuthSettings? = null, basePath: BasePath = BasePath.ROOT) {
    val server = embeddedServer(CIO, host = host, port = port) { atlasModule(index, mcp, auth, basePath) }
    try {
        server.start(wait = true)
    } catch (e: Throwable) {
        throw generateSequence(e) { it.cause }.filterIsInstance<AuthDiscoveryException>().firstOrNull() ?: e
    }
}
