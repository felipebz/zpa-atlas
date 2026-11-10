//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.model.EntityKind
import com.felipebz.zpa.atlas.model.Origin
import com.felipebz.zpa.atlas.model.UnitKind
import com.felipebz.zpa.atlas.query.AtlasIndex
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration

/** The MCP tools list, its schemas and instructions, unknown arguments and the error results, on the fixture index. */
class McpToolsTest {
    private val database: Path get() = ServerFixture.database
    private val temp: Path get() = ServerFixture.temp

    @Test
    fun `initialize names the server, and the tools list their arguments without reserved codes`() = mcp { client ->
        assertThat(client.serverVersion!!.name).isEqualTo("zpa-atlas")
        assertThat(client.serverVersion!!.version).matches("\\d+\\.\\d+\\.\\d+.*")
        val tools = client.listTools().tools.associateBy { it.name }
        assertThat(tools.keys).containsExactlyInAnyOrder("search_entities", "get_entity", "find_referencing_keys", "find_column_places", "find_occurrences", "find_usages",
            "group_occurrences", "group_usages", "get_source", "get_values", "find_value_sites", "find_impact", "search_text")
        assertThat(tools.mapValues { it.value.inputSchema.required.orEmpty() }).isEqualTo(mapOf(
            "search_entities" to listOf("name"), "get_entity" to listOf("key"), "find_referencing_keys" to listOf("key"), "find_column_places" to listOf("key"),
            "find_occurrences" to emptyList(),
            "find_usages" to listOf("key"), "group_occurrences" to listOf("group_by"), "group_usages" to listOf("key", "group_by"), "get_source" to listOf("path", "line"),
            "get_values" to listOf("key"), "find_value_sites" to listOf("key", "value_kind"), "find_impact" to listOf("key"), "search_text" to listOf("text")
        ))
        assertThat(tools.values).allMatch { it.annotations?.readOnlyHint == true }
        // Every enum of every schema, arrays' items included.
        fun enums(element: JsonElement): List<String> = when (element) {
            is JsonObject -> element.flatMap { (key, value) -> if (key == "enum") value.jsonArray.map { it.jsonPrimitive.content } else enums(value) }
            is JsonArray -> element.flatMap { enums(it) }
            else -> emptyList()
        }
        val offered = tools.values.flatMap { enums(it.inputSchema.properties!!) }.toSet()
        val reserved = (EntityKind.entries.filter { it.reserved } + UnitKind.entries.filter { it.reserved } + Origin.entries.filter { it.reserved })
            .map { it.name }.toSet()
        assertThat(offered).contains("TABLE", "SYNONYM", "SQL_UPDATE", "RESOLVED", "STATIC", "PACKAGE_BODY", "CONTAINER")
        assertThat(offered).doesNotContainAnyElementsOf(reserved)
        val roles = tools.getValue("find_occurrences").inputSchema.properties!!.getValue("roles").jsonObject
        assertThat(roles["type"]!!.jsonPrimitive.content).isEqualTo("array")
    }

    @Test
    fun `the instructions name every tool, and they and every description fit an older client's limit`() = mcp { client ->
        val instructions = client.serverInstructions!!
        val tools = client.listTools().tools
        assertThat(tools.map { it.name }).allSatisfy { assertThat(instructions).contains(it) }
        assertThat(instructions.length).isLessThanOrEqualTo(2_048)
        assertThat(tools).allSatisfy { assertThat(it.description!!.length).describedAs(it.name).isLessThanOrEqualTo(2_048) }
    }

    @Test
    fun `the instructions and the tools that give paths say the sources are read on the server with get_source`() = mcp { client ->
        assertThat(client.serverInstructions!!).contains("get_source", "only on the server", "not on the local disk")
        val tools = client.listTools().tools.associateBy { it.name }
        for (name in listOf("get_entity", "find_occurrences", "find_usages", "find_value_sites", "find_impact")) {
            assertThat(tools.getValue(name).description).describedAs(name).contains("on the server", "get_source", "not from a local disk")
        }
        assertThat(tools.getValue("get_source").description).contains("on the server, not on the local disk")
    }

    @Test
    fun `every tool refuses an argument its schema does not name`() = mcp { client ->
        val known = mapOf("search_entities" to "name", "get_entity" to "key", "find_occurrences" to "entity",
            "group_occurrences" to "group_by", "get_source" to "line")
        val tools = client.listTools().tools.associateBy { it.name }
        for ((name, accepted) in known) {
            val result = client.call(name, args { put("rolez", strings("WRITE")) })
            assertThat(result.isError).describedAs(name).isTrue()
            val takes = tools.getValue(name).inputSchema.properties!!.keys.joinToString(", ")
            assertThat(result.text()).isEqualTo("Unknown argument: rolez. $name takes: $takes").contains(accepted)
        }
        // The /api spelling of a filter is not the tool's: it is refused, not ignored.
        val camel = client.call("find_occurrences", args { put("entityKinds", strings("TABLE")) })
        assertThat(camel.isError).isTrue()
        assertThat(camel.text()).startsWith("Unknown argument: entityKinds. find_occurrences takes: entity, inside, entity_kinds,")
    }

    @Test
    fun `a request the index cannot answer is an error result with the reason`() = mcp { client ->
        assertThat(client.call("get_entity", args { put("key", "db:NO.SUCH") }).error()).contains("Unknown entity: db:NO.SUCH", "search_entities")
        assertThat(client.call("find_occurrences", args { put("roles", strings("NOPE")) }).error()).isEqualTo("Unknown role: NOPE")
        assertThat(client.call("group_occurrences", args { putJsonArray("group_by") { add(JsonPrimitive("COLOR")) } }).error())
            .isEqualTo("Unknown group_by dimension: COLOR")
        assertThat(client.call("get_entity").error()).isEqualTo("Missing argument: key")
        assertThat(client.call("group_occurrences").error()).isEqualTo("Missing argument: group_by")
        assertThat(client.call("get_source", args { put("path", "hr/pack_process.pkb") }).error()).isEqualTo("Missing argument: line")
        assertThat(client.call("find_occurrences", args { put("limit", 1000) }).error()).isEqualTo("limit must be between 1 and ${McpLimits.FIND_MAX}")
        assertThat(client.call("find_occurrences", args { put("roles", "WRITE") }).error()).isEqualTo("roles must be an array of strings")
    }

    @Test
    fun `a query past its time limit is an error result`() = mcp(AtlasIndex(database, null, queryTimeout = Duration.ZERO)) { client ->
        val result = client.call("group_occurrences", args { put("group_by", strings("ENTITY")) })
        assertThat(result.isError).isTrue()
        assertThat(result.text()).contains("narrow the filters")
    }

    @Test
    fun `an index of another schema version is an error result`() {
        val other = temp.resolve("other.db")
        Files.copy(database, other)
        DriverManager.getConnection("jdbc:sqlite:$other").use { it.createStatement().execute("UPDATE run SET schema_version = 2") }
        mcp(AtlasIndex(other, null)) { client ->
            val result = client.call("search_entities", args { put("name", "run_job") })
            assertThat(result.isError).isTrue()
            assertThat(result.text()).contains("schema version 2", "Re-run `zpa-atlas index`")
        }
    }
}
