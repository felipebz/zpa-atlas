//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.server.AtlasMcpTools.Companion.ToolJson
import com.felipebz.zpa.atlas.server.AtlasMcpTools.ToolException
import com.felipebz.zpa.atlas.query.InvalidQueryException
import com.felipebz.zpa.atlas.query.QueryTimeoutException
import com.felipebz.zpa.atlas.query.SchemaMismatchException
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * One MCP server over several [projects]: the tools of [AtlasMcpTools], each with a `project` argument that picks the
 * index it reads, and `list_projects`. A tool answers as it answers on a server of that project's index alone.
 * `project` is required with more than one project, unless there is a [defaultProject]: a call without it then reads
 * that one, and its answer (or its error) says so, since projects may share names and a wrong one would look right.
 */
internal fun projectsMcpServer(projects: List<ServedProject>, defaultProject: String? = null, maxAnswerChars: Int = McpAnswers.MAX_CHARS): Server {
    val sorted = projects.sortedBy { it.name }
    val fallback = defaultProject?.let { name -> sorted.first { it.name == name } }
    val server = Server(
        Implementation(name = "zpa-atlas", version = ATLAS_VERSION),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = projectsInstructions(sorted, fallback?.name)
    )
    val tools = sorted.associate { it.name to AtlasMcpTools(it.index, maxAnswerChars).tools.associateBy { tool -> tool.name } }
    val first = AtlasMcpTools(sorted.first().index, maxAnswerChars)
    val names = sorted.joinToString(", ") { it.name }
    for (tool in first.tools) {
        val schema = withProject(tool.schema, sorted, fallback?.name)
        val accepted = schema.properties?.keys.orEmpty()
        server.addTool(tool.name, tool.description, schema, toolAnnotations = AtlasMcpTools.READ_ONLY) { request ->
            first.call {
                val arguments = AtlasMcpTools.Arguments(tool.name, accepted, request.arguments)
                val named = arguments.string("project")
                if (named == null && fallback != null) return@call readDefault(fallback) { tools.getValue(fallback.name).getValue(tool.name).body(arguments) }
                val project = when (named) {
                    null -> sorted.singleOrNull()
                        ?: throw ToolException("Missing argument: project. This server holds several projects, separate codebases: $names. " +
                            "Call list_projects, or ask the user which one.")
                    else -> sorted.firstOrNull { it.name == named } ?: throw ToolException("Unknown project: $named. The projects are: $names.")
                }
                if (!project.indexed) throw ToolException(notIndexed(project))
                tools.getValue(project.name).getValue(tool.name).body(arguments)
            }
        }
    }
    server.addTool(
        "list_projects",
        "The projects of this server: separate codebases, each indexed apart. For each one its name (the project " +
            "argument of every other tool), its state (READY, NOT_INDEXED: no index yet, UNAVAILABLE: its index cannot " +
            "be read, with the error), when its index was written (finishedAt) and its file count." +
            if (fallback != null || sorted.any { it.title != null || it.description != null }) {
                " Also its title and description when it has them" + (if (fallback != null) ", and default: true on the default project." else ".")
            } else {
                ""
            },
        ToolSchema(properties = JsonObject(emptyMap())),
        toolAnnotations = AtlasMcpTools.READ_ONLY
    ) { request ->
        first.call {
            AtlasMcpTools.Arguments("list_projects", emptySet(), request.arguments)
            val answer = ToolJson.encodeToJsonElement(McpProjectList(sorted.map { McpProject.of(it.summary(), it.name == fallback?.name) })).jsonObject
            McpAnswers.fitList(answer, maxAnswerChars, { it.getValue("projects").jsonArray }, { a, rows -> JsonObject(a + ("projects" to rows)) })
        }
    }
    return server
}

/**
 * What [body] answers from the [project] a call without `project` falls on: its answer with `"project"` first, or its
 * error ending with a sentence saying so. An unknown entity may well be in another project.
 */
private fun readDefault(project: ServedProject, body: () -> JsonElement): JsonElement {
    val notice = " This call read the default project, ${project.name}; pass project to read another."
    // The sentence follows the error's own, ended with a period when it has none (`limit must be between 1 and 40`).
    fun told(message: String) = message.trimEnd().let { if (it.endsWith(".")) it else "$it." } + notice
    if (!project.indexed) throw ToolException(told(notIndexed(project)))
    val answer = try {
        body()
    } catch (e: RuntimeException) {
        if (e is ToolException || e is InvalidQueryException || e is QueryTimeoutException || e is SchemaMismatchException) {
            throw ToolException(told(e.message ?: "The call failed"))
        }
        throw e
    }
    return JsonObject(linkedMapOf<String, JsonElement>("project" to JsonPrimitive(project.name)) + answer.jsonObject)
}

/** [schema] with `project` first: required when there are several [projects] and no [defaultProject]. */
private fun withProject(schema: ToolSchema, projects: List<ServedProject>, defaultProject: String?): ToolSchema {
    val needed = projects.size > 1 && defaultProject == null
    val project = buildJsonObject {
        put("type", "string")
        put(
            "description",
            "The project to read: one codebase, indexed apart from the others. Take its name from list_projects" + when {
                defaultProject != null -> "; optional: without it, the tool reads the default project, $defaultProject."
                needed -> "."
                else -> "; optional, as this server holds one project."
            }
        )
    }
    val properties = JsonObject(linkedMapOf<String, JsonElement>("project" to project) + schema.properties.orEmpty())
    val required = if (needed) listOf("project") + schema.required.orEmpty() else schema.required
    return ToolSchema(properties = properties, required = required)
}

/**
 * The instructions of a projects server: what a project is, its [projects] (`name (title)`; the descriptions are in
 * list_projects) and the [defaultProject], then those of one index.
 */
internal fun projectsInstructions(projects: List<ServedProject>, defaultProject: String? = null): String {
    val names = projects.joinToString(", ") { project -> project.title?.let { "${project.name} ($it)" } ?: project.name }
    val text = when {
        defaultProject != null ->
            "This server holds several projects, separate codebases indexed apart: $names. The default project is " +
                "$defaultProject: a tool without project reads it, and its answer says \"project\": \"$defaultProject\". " +
                "When the question is about another project, pass its name as project. The projects may share names: " +
                "a key found in one does not show that the question was about it. An entity key, a path or a node " +
                "number of one project means nothing in another.\n"
        projects.size == 1 -> "This server holds one project, $names: the project argument of every tool may be left out.\n"
        else ->
            "This server holds several projects, separate codebases indexed apart: $names. Every tool takes project; " +
                "when the question does not say which, call list_projects or ask the user, rather than guess. An " +
                "entity key, a path or a node number of one project means nothing in another.\n"
    }
    return text + AtlasMcpTools.INSTRUCTIONS
}

/** The refusal of a project whose index file does not exist yet, on `/api` and `/mcp`. */
internal fun notIndexed(project: ServedProject) =
    "The project ${project.name} is not indexed yet: its index ${project.indexFile} does not exist"

/** A project as `list_projects` gives it: [ProjectSummary] without the pages' path, and [default] only on the default. */
@Serializable
internal data class McpProject(
    val name: String,
    val default: Boolean? = null,
    val title: String? = null,
    val description: String? = null,
    val state: ProjectState,
    val finishedAt: String?,
    val files: Long?,
    val error: String?
) {
    companion object {
        fun of(summary: ProjectSummary, isDefault: Boolean) = McpProject(
            summary.name, true.takeIf { isDefault }, summary.title, summary.description, summary.state, summary.finishedAt, summary.files, summary.error
        )
    }
}

@Serializable
internal data class McpProjectList(val projects: List<McpProject>)
