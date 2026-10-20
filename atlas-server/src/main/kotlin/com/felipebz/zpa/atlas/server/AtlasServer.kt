//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.query.GroupDimension
import com.felipebz.zpa.atlas.query.InvalidQueryException
import com.felipebz.zpa.atlas.query.OccurrenceQuery
import com.felipebz.zpa.atlas.query.QueryTimeoutException
import com.felipebz.zpa.atlas.query.SchemaMismatchException
import com.felipebz.zpa.atlas.query.Scope
import com.felipebz.zpa.atlas.query.indexCodes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ApiError(val error: String)

const val COMPRESSION_MINIMUM_BYTES = 1024L

/**
 * The HTTP API (`/api/…`, JSON, GET only) and the static web. Routes only parse parameters, call one [AtlasIndex]
 * method and serialize its answer; a future MCP endpoint can mount on the same [Application].
 */
fun Application.atlasModule(index: AtlasIndex) {
    install(Compression) {
        gzip { priority = 1.0; minimumSize(COMPRESSION_MINIMUM_BYTES) }
        deflate { priority = 0.9; minimumSize(COMPRESSION_MINIMUM_BYTES) }
    }
    install(ContentNegotiation) {
        json(Json { explicitNulls = true; encodeDefaults = true })
    }
    install(StatusPages) {
        exception<InvalidQueryException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Bad request")) }
        exception<QueryTimeoutException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "The query took too long")) }
        exception<SchemaMismatchException> { call, e -> call.respond(HttpStatusCode.ServiceUnavailable, ApiError(e.message ?: "Schema mismatch")) }
    }
    routing {
        route("/api") {
            get("/run") { call.respond(index.run()) }
            // The code lists read no index: this answers without one, so it has no run stamp.
            get("/codes") { call.respond(indexCodes()) }
            get("/entities") {
                val q = call.required("q")
                call.respond(index.searchEntities(q, call.optional("kind"), call.optional("external")?.toBooleanStrictOrNull() ?: true,
                    call.int("limit") ?: 50))
            }
            get("/entity") {
                val answer = index.entity(call.required("key"))
                if (answer.result == null) call.respond(HttpStatusCode.NotFound, ApiError("Unknown entity")) else call.respond(answer)
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
                if (answer.result == null) call.respond(HttpStatusCode.NotFound, ApiError("Unknown entity")) else call.respond(answer)
            }
            get("/query") {
                call.respond(index.queryOccurrences(call.occurrenceQuery(), call.long("after"), call.int("limit") ?: 100, call.boolean("summary") ?: true))
            }
            get("/query/summary") { call.respond(index.querySummary(call.occurrenceQuery())) }
            get("/query/groups") {
                val dimensions = (call.list("groupBy") ?: throw InvalidQueryException("Missing parameter: groupBy")).map { value ->
                    GroupDimension.entries.firstOrNull { it.name == value } ?: throw InvalidQueryException("Unknown groupBy dimension: $value")
                }
                call.respond(index.groupOccurrences(call.occurrenceQuery(), dimensions, call.int("limit") ?: 100))
            }
            get("/values") {
                val answer = index.values(call.required("key"))
                if (answer.result == null) call.respond(HttpStatusCode.NotFound, ApiError("Unknown entity")) else call.respond(answer)
            }
            get("/value-sites") {
                val answer = index.valueSites(call.required("key"), call.required("kind"), call.request.queryParameters["value"],
                    call.long("after"), call.int("limit") ?: 100)
                if (answer.result == null) call.respond(HttpStatusCode.NotFound, ApiError("Unknown entity")) else call.respond(answer)
            }
            get("/source") {
                val answer = index.source(call.required("path"), call.int("line") ?: throw InvalidQueryException("Missing parameter: line"),
                    call.int("context") ?: 3)
                if (answer.result == null) call.respond(HttpStatusCode.NotFound, ApiError("Source not available")) else call.respond(answer)
            }
            get("/file") {
                val answer = index.file(call.required("path"))
                if (answer.result == null) call.respond(HttpStatusCode.NotFound, ApiError("Source not available")) else call.respond(answer)
            }
        }
        staticResources("/", "web", index = "index.html")
    }
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

/** Starts the server and blocks. */
fun serve(index: AtlasIndex, host: String, port: Int) {
    embeddedServer(CIO, host = host, port = port) { atlasModule(index) }.start(wait = true)
}
