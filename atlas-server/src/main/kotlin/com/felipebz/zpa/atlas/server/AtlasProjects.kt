//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.query.CodeColorer
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** The path segment the projects are under: `<base path>/p/<name>/`. */
internal const val PROJECTS_SEGMENT = "p"

/**
 * A project the server serves: [name] is a segment of its URLs, [indexFile] the index `index --project` writes, and
 * [sources] its sources folder, null when it is not on this machine. The index file may not exist yet: the project is
 * then not indexed, and is served as soon as the file appears. [title] and [description] tell people and agents which
 * codebase it is. [colorer] colors its files' text on the file page.
 */
class ServedProject(
    val name: String,
    val indexFile: Path,
    val sources: Path?,
    val title: String? = null,
    val description: String? = null,
    colorer: CodeColorer? = null
) {
    init {
        require(NAME.matches(name)) { "A project name is lower-case letters, digits, '-' and '_', starting with a letter or a digit: $name" }
    }

    val index = AtlasIndex(indexFile, sources, colorer = colorer)

    /** Whether the index file exists: the project has been indexed. */
    val indexed: Boolean get() = Files.isRegularFile(indexFile)

    /** The project as the list of projects shows it, read now. */
    fun summary(): ProjectSummary {
        val path = "$PROJECTS_SEGMENT/$name/"
        if (!indexed) return ProjectSummary(name, path, ProjectState.NOT_INDEXED, null, null, null, title, description)
        return try {
            val answer = index.run()
            ProjectSummary(name, path, ProjectState.READY, answer.run.finishedAt, answer.result.filesByParseStatus.sumOf { it.count }, null, title, description)
        } catch (e: Exception) {
            ProjectSummary(name, path, ProjectState.UNAVAILABLE, null, null, e.message ?: e.javaClass.simpleName, title, description)
        }
    }

    private companion object {
        val NAME = Regex("[a-z0-9][a-z0-9_-]*")
    }
}

enum class ProjectState { READY, NOT_INDEXED, UNAVAILABLE }

/**
 * A project in `/api/projects`: [path] is its pages' folder below the base path; [finishedAt] (when its index was
 * written) and [files] only when it is [ProjectState.READY], [error] only when it is [ProjectState.UNAVAILABLE].
 */
@Serializable
data class ProjectSummary(
    val name: String,
    val path: String,
    val state: ProjectState,
    val finishedAt: String?,
    val files: Long?,
    val error: String?,
    val title: String? = null,
    val description: String? = null
)

/**
 * The JSON of `/api/projects`: the fields of [ProjectSummary], but `title` and `description` only when the project has
 * them, and `"default": true` only on the [defaultProject].
 */
private fun projectsJson(summaries: List<ProjectSummary>, defaultProject: String?): String = buildJsonObject {
    putJsonArray("projects") {
        for (summary in summaries) {
            val fields = ApiJson.encodeToJsonElement(ProjectSummary.serializer(), summary).jsonObject
            add(JsonObject(fields.filter { (key, value) -> key !in OMITTED_WHEN_NULL || value != JsonNull } +
                if (summary.name == defaultProject) mapOf("default" to JsonPrimitive(true)) else emptyMap()))
        }
    }
}.toString()

private val OMITTED_WHEN_NULL = setOf("title", "description")

/** The pages a project serves with its name in a `<meta name="atlas-project">`: the page knows its project only from it. */
private val PAGES = listOf("index.html", "entity.html", "file.html", "query.html", "impact.html", "text.html")

/**
 * Several [projects], each under `<base path>/p/<name>/`: its pages, and its API at `p/<name>/api/…`, the API of `/api`
 * over its own index. The list of projects is the page at the base path and `/api/projects`, and `/mcp` serves every
 * project, picked by the tools' `project` argument. With [auth], one gate covers every project's API, the list and
 * `/mcp`, and the sign-in stays at the base path.
 *
 * @throws AuthDiscoveryException when [auth] is given and its issuer's discovery document cannot be read
 */
fun Application.atlasProjectsModule(
    projects: List<ServedProject>,
    mcp: McpSettings = McpSettings(),
    auth: AuthSettings? = null,
    basePath: BasePath = BasePath.ROOT,
    defaultProject: String? = null
) =
    atlasProjectsModule(projects, mcp, auth, { oidcIdentity(it) }, basePath, defaultProject) {
        HttpClient(io.ktor.client.engine.cio.CIO).also { client -> monitor.subscribe(ApplicationStopped) { client.close() } }
    }

internal fun Application.atlasProjectsModule(
    projects: List<ServedProject>,
    mcp: McpSettings,
    auth: AuthSettings?,
    identity: IdentityInstaller,
    basePath: BasePath = BasePath.ROOT,
    defaultProject: String? = null,
    providerClient: () -> HttpClient
) {
    require(projects.isNotEmpty()) { "No project to serve" }
    require(defaultProject == null || projects.any { it.name == defaultProject }) { "The default project $defaultProject is not one of the projects" }
    require(projects.map { it.name }.toSet().size == projects.size) { "Two projects have the same name" }
    val sorted = projects.sortedBy { it.name }
    val names = sorted.map { it.name }.toSet()
    // Any other project name is not served: its 404 says so. (A route for it would also take the served projects' assets.)
    atlasCommon(auth, identity, basePath, providerClient) { call ->
        val (segment, name) = basePath.below(call.request.path(), 2) ?: return@atlasCommon
        if (segment == PROJECTS_SEGMENT && name !in names) call.respondJson(ApiError("Unknown project: $name"), HttpStatusCode.NotFound)
    }
    routing {
        route(basePath.of("/api")) {
            get("/projects") {
                call.respondText(projectsJson(sorted.map { it.summary() }, defaultProject), ContentType.Application.Json)
            }
        }
        for (project in sorted) {
            val root = basePath.of("/$PROJECTS_SEGMENT/${project.name}")
            // The pages link and ask relatively: the bare folder resolves against its parent, as the bare base path does.
            get(root) {
                val query = call.request.queryString()
                call.respondRedirect("$root/" + if (query.isEmpty()) "" else "?$query")
            }
            route("$root/api") {
                install(ProjectIndexed) { this.project = project }
                indexApi(project.index)
            }
            get("$root/") { call.projectPage(project, "index.html") }
            for (page in PAGES) get("$root/$page") { call.projectPage(project, page) }
            staticResources("$root/assets", "web/assets", index = null)
        }
        route(basePath.of("/health")) {
            get { call.projectsHealth(sorted, head = false) }
            head { call.projectsHealth(sorted, head = true) }
        }
        get(basePath.of("/")) { call.page("projects.html", null) }
        get(basePath.of("/projects.html")) { call.page("projects.html", null) }
        staticResources(basePath.of("/assets"), "web/assets", index = null)
    }
    mountMcp(mcp, auth, basePath) { projectsMcpServer(sorted, defaultProject, mcp.maxAnswerChars) }
}

private class ProjectIndexedConfig {
    lateinit var project: ServedProject
}

/** A project's API answers 503 while its index file does not exist; it answers once `index` has written it. */
private val ProjectIndexed = createRouteScopedPlugin("ProjectIndexed", ::ProjectIndexedConfig) {
    val project = pluginConfig.project
    onCall { call ->
        if (!project.indexed) {
            call.respondJson(ApiError(notIndexed(project)), HttpStatusCode.ServiceUnavailable)
        }
    }
}

private val pageTexts = java.util.concurrent.ConcurrentHashMap<String, String>()

/**
 * The page [name] of the bundle, with [project]'s name in a meta tag right after `<head>` when there is one, and its
 * [title] in another when it has one.
 */
private suspend fun ApplicationCall.page(name: String, project: String?, title: String? = null) {
    val text = pageTexts.computeIfAbsent(name) {
        val resource = ServedProject::class.java.classLoader.getResource("web/$it") ?: error("The page $it is not in the bundle")
        resource.readText()
    }
    val titled = title?.let { "<meta name=\"atlas-project-title\" content=\"${attribute(it)}\">" }.orEmpty()
    val served = if (project == null) text else text.replaceFirst("<head>", "<head><meta name=\"atlas-project\" content=\"$project\">$titled")
    respondText(served, ContentType.Text.Html.withParameter("charset", "utf-8"))
}

private suspend fun ApplicationCall.projectPage(project: ServedProject, name: String) = page(name, project.name, project.title)

/** [text] as the value of a double-quoted HTML attribute. */
private fun attribute(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * 200 when the index of every project that has one opens; a project not indexed yet does not fail it, so that a
 * container waiting for its first index is not restarted. The body has one line per project.
 */
private suspend fun ApplicationCall.projectsHealth(projects: List<ServedProject>, head: Boolean) {
    val states = projects.map { project ->
        val state = when {
            !project.indexed -> "not indexed"
            else -> try {
                project.index.stamp()
                "ok"
            } catch (e: Exception) {
                application.log.debug("Health check: the index of {} is unavailable: {}", project.name, e.message)
                "unavailable"
            }
        }
        project.name to state
    }
    val status = if (states.any { it.second == "unavailable" }) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
    response.header(HttpHeaders.CacheControl, "no-store")
    if (head) respond(status) else respondText(states.joinToString("") { (name, state) -> "$name: $state\n" }, ContentType.Text.Plain, status)
}

/**
 * Starts the server over [projects] and blocks.
 *
 * @throws AuthDiscoveryException when [auth] is given and its issuer's discovery document cannot be read: the server
 *     does not start
 */
fun serveProjects(
    projects: List<ServedProject>,
    host: String,
    port: Int,
    mcp: McpSettings = McpSettings(),
    auth: AuthSettings? = null,
    basePath: BasePath = BasePath.ROOT,
    defaultProject: String? = null
) {
    val server = embeddedServer(CIO, host = host, port = port) { atlasProjectsModule(projects, mcp, auth, basePath, defaultProject) }
    try {
        server.start(wait = true)
    } catch (e: Throwable) {
        throw generateSequence(e) { it.cause }.filterIsInstance<AuthDiscoveryException>().firstOrNull() ?: e
    }
}
