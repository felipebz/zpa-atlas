//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.server.AtlasMcpTools.Companion.ToolJson
import com.felipebz.zpa.atlas.server.AtlasMcpTools.ToolException
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * One MCP server over several [projects]: the tools of [AtlasMcpTools], each with a `project` argument that picks the
 * index it reads (required with more than one project), and `list_projects`. A tool answers as it answers on a server
 * of that project's index alone.
 */
internal fun projectsMcpServer(projects: List<ServedProject>, maxAnswerChars: Int = McpAnswers.MAX_CHARS): Server {
    val sorted = projects.sortedBy { it.name }
    val server = Server(
        Implementation(name = "zpa-atlas", version = ATLAS_VERSION),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = projectsInstructions(sorted.map { it.name })
    )
    val tools = sorted.associate { it.name to AtlasMcpTools(it.index, maxAnswerChars).tools.associateBy { tool -> tool.name } }
    val first = AtlasMcpTools(sorted.first().index, maxAnswerChars)
    val names = sorted.joinToString(", ") { it.name }
    for (tool in first.tools) {
        val schema = withProject(tool.schema, sorted)
        val accepted = schema.properties?.keys.orEmpty()
        server.addTool(tool.name, tool.description, schema, toolAnnotations = AtlasMcpTools.READ_ONLY) { request ->
            first.call {
                val arguments = AtlasMcpTools.Arguments(tool.name, accepted, request.arguments)
                val project = when (val name = arguments.string("project")) {
                    null -> sorted.singleOrNull()
                        ?: throw ToolException("Missing argument: project. This server holds several projects, separate codebases: $names. " +
                            "Call list_projects, or ask the user which one.")
                    else -> sorted.firstOrNull { it.name == name } ?: throw ToolException("Unknown project: $name. The projects are: $names.")
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
            "be read, with the error), when its index was written (finishedAt) and its file count.",
        ToolSchema(properties = JsonObject(emptyMap())),
        toolAnnotations = AtlasMcpTools.READ_ONLY
    ) { request ->
        first.call {
            AtlasMcpTools.Arguments("list_projects", emptySet(), request.arguments)
            val answer = ToolJson.encodeToJsonElement(McpProjectList(sorted.map { McpProject.of(it.summary()) })).jsonObject
            McpAnswers.fitList(answer, maxAnswerChars, { it.getValue("projects").jsonArray }, { a, rows -> JsonObject(a + ("projects" to rows)) })
        }
    }
    return server
}

/** [schema] with `project` first: required when there are several [projects]. */
private fun withProject(schema: ToolSchema, projects: List<ServedProject>): ToolSchema {
    val several = projects.size > 1
    val project = buildJsonObject {
        put("type", "string")
        put(
            "description",
            "The project to read: one codebase, indexed apart from the others. Take its name from list_projects" +
                if (several) "." else "; optional, as this server holds one project."
        )
    }
    val properties = JsonObject(linkedMapOf<String, JsonElement>("project" to project) + schema.properties.orEmpty())
    val required = if (several) listOf("project") + schema.required.orEmpty() else schema.required
    return ToolSchema(properties = properties, required = required)
}

/** The instructions of a projects server: what a project is and its [names], then those of one index. */
internal fun projectsInstructions(names: List<String>): String {
    val projects = if (names.size == 1) {
        "This server holds one project, ${names.single()}: the project argument of every tool may be left out.\n"
    } else {
        "This server holds several projects, separate codebases indexed apart: ${names.joinToString(", ")}. Every " +
            "tool takes project; when the question does not say which, call list_projects or ask the user, rather " +
            "than guess. An entity key, a path or a node number of one project means nothing in another.\n"
    }
    return projects + AtlasMcpTools.INSTRUCTIONS
}

/** The refusal of a project whose index file does not exist yet, on `/api` and `/mcp`. */
internal fun notIndexed(project: ServedProject) =
    "The project ${project.name} is not indexed yet: its index ${project.indexFile} does not exist"

/** A project as `list_projects` gives it: [ProjectSummary] without the pages' path. */
@Serializable
internal data class McpProject(val name: String, val state: ProjectState, val finishedAt: String?, val files: Long?, val error: String?) {
    companion object {
        fun of(summary: ProjectSummary) = McpProject(summary.name, summary.state, summary.finishedAt, summary.files, summary.error)
    }
}

@Serializable
internal data class McpProjectList(val projects: List<McpProject>)
