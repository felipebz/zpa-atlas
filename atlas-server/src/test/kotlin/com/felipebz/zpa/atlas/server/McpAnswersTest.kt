//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.query.Answer
import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.query.GroupDimension
import com.felipebz.zpa.atlas.query.OccurrenceQuery
import com.felipebz.zpa.atlas.server.McpAnswers.ToolJson
import com.felipebz.zpa.atlas.server.McpAnswers.toolAnswer
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** MCP tool answers against the same AtlasIndex calls, with list limits, size caps and paging, on the fixture index. */
class McpAnswersTest {
    private val index: AtlasIndex get() = ServerFixture.index
    private val temp: Path get() = ServerFixture.temp

    @Test
    fun `each tool answers what the same AtlasIndex call answers`() = mcp { client ->
        assertThat(client.call("search_entities", args { put("name", "run_job"); put("limit", 5) }).json())
            .isEqualTo(toolAnswer(index.searchEntities("run_job", null, true, 5)))
        // Besides the entity's detail, get_entity gives where the bodies of a routine's spec are, in their own field
        // (see below), instead of the detail's partners.
        val entity = client.call("get_entity", args { put("key", runJob5) }).json().jsonObject
        val expected = toolAnswer(index.entity(runJob5).let { Answer(it.run, it.result!!.copy(partners = emptyList())) })
        assertThat(JsonObject(entity + ("result" to JsonObject(entity["result"]!!.jsonObject - "bodies")))).isEqualTo(expected)
        val query = OccurrenceQuery(container = "db:HR.PACK_PROCESS", roles = setOf("SQL_UPDATE", "WRITE"))
        val found = client.call("find_occurrences", args {
            put("container", "db:HR.PACK_PROCESS"); put("roles", strings("SQL_UPDATE", "WRITE")); put("limit", 7)
        }).json()
        assertThat(found).isEqualTo(toolAnswer(index.queryOccurrences(query, null, 7, summary = true)))
        // The next page.
        val after = found.jsonObject["result"]!!.jsonObject["nextAfter"]!!.jsonPrimitive.content.toLong()
        assertThat(client.call("find_occurrences", args {
            put("container", "db:HR.PACK_PROCESS"); put("roles", strings("SQL_UPDATE", "WRITE")); put("limit", 7); put("after", after)
        }).json()).isEqualTo(toolAnswer(index.queryOccurrences(query, after, 7, summary = true)))
        // The CRUD matrix of a package.
        val crud = OccurrenceQuery(containerName = "pack_process", roles = setOf("SQL_SELECT", "SQL_INSERT", "SQL_UPDATE", "SQL_DELETE"))
        assertThat(client.call("group_occurrences", args {
            put("container_name", "pack_process"); put("roles", strings("SQL_SELECT", "SQL_INSERT", "SQL_UPDATE", "SQL_DELETE"))
            put("group_by", strings("CONTAINER", "ENTITY", "ROLE"))
        }).json()).isEqualTo(toolAnswer(
            index.groupOccurrences(crud, listOf(GroupDimension.CONTAINER, GroupDimension.ENTITY, GroupDimension.ROLE), McpLimits.GROUP_DEFAULT)
        ))
        assertThat(client.call("get_source", args { put("path", "hr/pack_process.pkb"); put("line", 10); put("context", 1) }).json())
            .isEqualTo(toolAnswer(index.source("hr/pack_process.pkb", 10, 1)))
    }

    @Test
    fun `an entity's long lists are cut to list_limit, with their full sizes`() = mcp { client ->
        val answer = client.call("get_entity", args { put("key", "db:HR.COMPANY"); put("list_limit", 2) }).json().jsonObject
        val full = index.entity("db:HR.COMPANY").result!!
        assertThat(full.children.size).isGreaterThan(2)
        assertThat(answer["result"]!!.jsonObject["children"]!!.jsonArray).hasSize(2)
        assertThat(answer["truncated"]!!.jsonObject["children"]!!.jsonPrimitive.content.toInt()).isEqualTo(full.children.size)
        assertThat(answer["result"]!!.jsonObject["children"]!!.jsonArray.toList())
            .isEqualTo(McpAnswers.compact(ToolJson.encodeToJsonElement(full.children.take(2))).jsonArray.toList())
    }

    @Test
    fun `a routine's spec gives where its body is, and its body where its spec is`() = mcp { client ->
        val spec = "db:HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE)"
        suspend fun place(key: String, field: String): Pair<String, List<String>> {
            val result = client.call("get_entity", args { put("key", key) }).json().jsonObject["result"]!!.jsonObject
            val partner = (result[field] as? JsonArray)?.single()?.jsonObject ?: result[field]!!.jsonObject
            return partner["key"]!!.jsonPrimitive.content to partner["definitions"]!!.jsonArray.map {
                it.jsonObject["path"]!!.jsonPrimitive.content + ":" + it.jsonObject["span"]!!.jsonObject["startLine"]!!.jsonPrimitive.content
            }
        }
        val body = place(spec, "bodies")
        assertThat(body.first).isEqualTo("$spec@BODY")
        assertThat(body.second).isEqualTo(index.entity("$spec@BODY").result!!.definitions.map { "${it.path}:${it.span.startLine}" })
        assertThat(body.second).allSatisfy { assertThat(it).startsWith("hr/pack_process.pkb:") }
        val back = place("$spec@BODY", "spec")
        assertThat(back.first).isEqualTo(spec)
        assertThat(back.second).isEqualTo(index.entity(spec).result!!.definitions.map { "${it.path}:${it.span.startLine}" })
        assertThat(back.second).allSatisfy { assertThat(it).startsWith("hr/pack_process.pks:") }
    }

    @Test
    fun `each overload's spec gives its own body, even with list_limit 1`() = mcp { client ->
        for (key in listOf("db:HR.PACK_IMP_A.OV(P IN NUMBER)", "db:HR.PACK_IMP_A.OV(P IN VARCHAR2)")) {
            val answer = client.call("get_entity", args { put("key", key); put("list_limit", 1) }).json().jsonObject
            val body = answer["result"]!!.jsonObject["bodies"]!!.jsonArray.single().jsonObject
            assertThat(body["key"]!!.jsonPrimitive.content).isEqualTo("$key@BODY")
            val lines = body["definitions"]!!.jsonArray.map { it.jsonObject["span"]!!.jsonObject["startLine"]!!.jsonPrimitive.content.toInt() }
            assertThat(lines).isNotEmpty.isEqualTo(index.entity("$key@BODY").result!!.definitions.map { it.span.startLine })
        }
        val lines = listOf("NUMBER", "VARCHAR2").map { type ->
            index.entity("db:HR.PACK_IMP_A.OV(P IN $type)@BODY").result!!.definitions.single().span.startLine
        }
        assertThat(lines.distinct()).hasSize(2)
    }

    @Test
    fun `a spec whose body is in two files lists both bodies, with list_limit 1`() {
        val sources = Files.createDirectories(temp.resolve("dup-src/old"))
        Files.writeString(sources.resolveSibling("pack_dup.pks"), "create or replace package pack_dup is\n  procedure run(p in number);\nend pack_dup;\n/\n")
        val body = "create or replace package body pack_dup is\n  procedure run(p in number) is\n  begin\n    null;\n  end;\nend pack_dup;\n/\n"
        Files.writeString(sources.resolveSibling("pack_dup.pkb"), body)
        Files.writeString(sources.resolve("pack_dup.pkb"), "-- the copy kept from an older release\n$body")
        val dup = temp.resolve("dup.db")
        Indexer().index(IndexOptions(sources.parent, dup, compilationSchema = "HR", threads = 1))
        val spec = "db:HR.PACK_DUP.RUN(P IN NUMBER)"
        mcp(AtlasIndex(dup, sources.parent)) { client ->
            val answer = client.call("get_entity", args { put("key", spec); put("list_limit", 1) }).json().jsonObject
            val bodies = answer["result"]!!.jsonObject["bodies"]!!.jsonArray.map { it.jsonObject }
            assertThat(bodies.map { it["key"]!!.jsonPrimitive.content }).containsExactlyInAnyOrder("$spec@BODY", "$spec@BODY#2")
            assertThat(bodies.map { b ->
                b["definitions"]!!.jsonArray.single().jsonObject.let { "${it["path"]!!.jsonPrimitive.content}:${it["span"]!!.jsonObject["startLine"]!!.jsonPrimitive.content}" }
            }).containsExactlyInAnyOrder("pack_dup.pkb:2", "old/pack_dup.pkb:3")
            assertThat(answer["truncated"]?.jsonObject?.get("bodies")).isNull()
        }
    }

    @Test
    fun `a call argument names its parameter, and an entity summary leaves out a display that is its key`() = mcp { client ->
        val rows = client.call("find_occurrences", args { put("entity", runJob5); put("roles", strings("CALL")); put("limit", 40) })
            .json().jsonObject["result"]!!.jsonObject["rows"]!!.jsonArray.map { it.jsonObject }
        val withArguments = rows.filter { it["entity"] != null && it["arguments"]!!.jsonArray.isNotEmpty() }
        assertThat(withArguments).isNotEmpty()
        for (row in withArguments) {
            val called = row["entity"]!!.jsonObject["key"]!!.jsonPrimitive.content
            val apiRow = index.queryOccurrences(OccurrenceQuery(entity = runJob5, roles = setOf("CALL")), row["id"]!!.jsonPrimitive.content.toLong() - 1, 1)
                .result.rows.single()
            for ((argument, api) in row["arguments"]!!.jsonArray.map { it.jsonObject }.zip(apiRow.arguments)) {
                val parameter = api.parameter ?: continue
                assertThat(argument["parameter"]).isEqualTo(JsonPrimitive(parameter.name))
                assertThat("$called.${parameter.name}").isEqualTo(parameter.key)
                assertThat(argument).doesNotContainKey("parameterKey")
            }
            assertThat(row["matched"]!!.jsonObject).doesNotContainKey("display").containsKeys("key", "kind", "name")
            assertThat(row["entity"]!!.jsonObject).doesNotContainKey("display")
        }
    }

    @Test
    fun `calls of a procedure with forty long parameters fit the size cap, and their pages lose and repeat no row`() {
        val sources = Files.createDirectories(temp.resolve("wide"))
        val parameters = (1..40).map { "I_PARAMETER_WITH_A_LONG_NAME_NUMBER_%02d".format(it) }
        val declaration = "procedure take_all(" + parameters.joinToString(", ") { "$it in varchar2" } + ")"
        Files.writeString(sources.resolve("pack_wide.pks"), "create or replace package pack_wide is\n  $declaration;\nend;\n/\n")
        Files.writeString(sources.resolve("pack_wide.pkb"), "create or replace package body pack_wide is\n  $declaration is\n  begin\n    null;\n  end;\nend;\n/\n")
        val call = "  pack_wide.take_all(" + (1..40).joinToString(", ") { "'value $it'" } + ");\n"
        Files.writeString(sources.resolve("call_wide.prc"), "create or replace procedure call_wide is\nbegin\n" + call.repeat(40) + "end;\n/\n")
        val database = temp.resolve("wide.db")
        Indexer({ Instant.EPOCH }).index(IndexOptions(sources, database, compilationSchema = "HR", threads = 1))
        val wide = AtlasIndex(database, sources)
        val query = OccurrenceQuery(name = "pack_wide.take_all", roles = setOf("CALL"))
        val all = wide.queryOccurrences(query, null, 500).result.rows
        assertThat(all).hasSize(40).allMatch { it.arguments.size == 40 }
        mcp(wide) { client ->
            val ids = ArrayList<Long>()
            var after: Long? = null
            do {
                val answer = client.call("find_occurrences", args {
                    put("name", "pack_wide.take_all"); put("roles", strings("CALL")); put("limit", McpLimits.FIND_MAX); after?.let { put("after", it) }
                })
                assertThat(answer.text().length).isLessThanOrEqualTo(McpAnswers.MAX_CHARS)
                val json = answer.json().jsonObject
                val rows = json["result"]!!.jsonObject["rows"]!!.jsonArray
                ids += rows.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
                if (ids.size < all.size) assertThat(json["cutForSize"]!!.jsonObject["kept"]!!.jsonPrimitive.content.toInt()).isEqualTo(rows.size)
                after = json["result"]!!.jsonObject["nextAfter"]?.jsonPrimitive?.content?.toLong()
            } while (after != null)
            assertThat(ids).isEqualTo(all.map { it.id })
        }
    }

    @Test
    fun `over the size cap a list keeps its first items, says so, and a page goes on after its last row`() =
        mcp(settings = McpSettings(maxAnswerChars = 8_000)) { client ->
            val query = OccurrenceQuery(container = "db:HR.PACK_PROCESS")
            val all = index.queryOccurrences(query, null, 500).result.rows.map { it.id }
            assertThat(all.size).isGreaterThan(McpLimits.FIND_MAX)
            val ids = ArrayList<Long>()
            var after: Long? = null
            var cutPages = 0
            do {
                val answer = client.call("find_occurrences", args { put("container", "db:HR.PACK_PROCESS"); put("limit", 40); after?.let { put("after", it) } })
                    .json().jsonObject
                val rows = answer["result"]!!.jsonObject["rows"]!!.jsonArray
                answer["cutForSize"]?.jsonObject?.let { cut ->
                    cutPages++
                    assertThat(cut["kept"]!!.jsonPrimitive.content.toInt()).isEqualTo(rows.size)
                    assertThat(cut["of"]!!.jsonPrimitive.content.toInt()).isGreaterThan(rows.size)
                    assertThat(ToolJson.encodeToString(JsonElement.serializer(), answer).length).isLessThanOrEqualTo(8_000)
                }
                ids += rows.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
                after = answer["result"]!!.jsonObject["nextAfter"]?.jsonPrimitive?.content?.toLong()
            } while (after != null)
            assertThat(cutPages).isGreaterThan(0)
            assertThat(ids).isEqualTo(all)
            // The groups and the hits kept are the first ones of the uncut answer.
            val groups = client.call("group_occurrences", args { put("group_by", strings("CONTAINER", "ENTITY", "ROLE")); put("limit", 100) }).json().jsonObject
            val kept = groups["cutForSize"]!!.jsonObject["kept"]!!.jsonPrimitive.content.toInt()
            val allGroups = toolAnswer(index.groupOccurrences(OccurrenceQuery(), listOf(GroupDimension.CONTAINER, GroupDimension.ENTITY, GroupDimension.ROLE), 100))
            assertThat(groups["result"]!!.jsonObject["groups"]!!.jsonArray.toList())
                .isEqualTo(allGroups["result"]!!.jsonObject["groups"]!!.jsonArray.take(kept))
            val hits = client.call("search_entities", args { put("name", "p"); put("limit", 100) }).json().jsonObject
            assertThat(hits["cutForSize"]!!.jsonObject["kept"]!!.jsonPrimitive.content.toInt()).isEqualTo(hits["result"]!!.jsonArray.size).isLessThan(100)
        }

    @Test
    fun `a row longer than the size cap is still sent, alone`() = mcp(settings = McpSettings(maxAnswerChars = 100)) { client ->
        val answer = client.call("find_occurrences", args { put("path", "hr/"); put("limit", 5) }).json().jsonObject
        assertThat(answer["result"]!!.jsonObject["rows"]!!.jsonArray).hasSize(1)
        assertThat(answer["cutForSize"]).isEqualTo(Json.parseToJsonElement("""{"kept":1,"of":5}"""))
        assertThat(answer["result"]!!.jsonObject["nextAfter"]).isEqualTo(answer["result"]!!.jsonObject["rows"]!!.jsonArray[0].jsonObject["id"])
        // An entity's lists are cut further, down to one item each; the answer says to which limit.
        val entity = client.call("get_entity", args { put("key", "db:HR.COMPANY"); put("list_limit", 100) }).json().jsonObject
        assertThat(entity["cutForSize"]).isEqualTo(Json.parseToJsonElement("""{"listLimit":1}"""))
        assertThat(entity["result"]!!.jsonObject["children"]!!.jsonArray).hasSize(1)
    }

    @Test
    fun `search_text answers what AtlasIndex searchText answers, and refuses a short text`() = mcp { client ->
        // Scoped: the same rows, and no wholeIndex key, as group_occurrences leaves it out when filtered.
        val scoped = toolAnswer(index.searchText("vFixTag", container = "db:HR.TEXT_TAGS", limit = McpLimits.TEXT_DEFAULT)).jsonObject
        assertThat(client.call("search_text", args { put("text", "vFixTag"); put("container", "db:HR.TEXT_TAGS") }).json())
            .isEqualTo(JsonObject(scoped + ("result" to JsonObject(scoped.getValue("result").jsonObject - "wholeIndex"))))
        // Unscoped: wholeIndex, and how to narrow it.
        val whole = client.call("search_text", args { put("text", "vfixtag") }).json().jsonObject["result"]!!.jsonObject
        assertThat(whole["wholeIndex"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(whole["narrow"]!!.jsonPrimitive.content).contains("container_name", "path").doesNotContain("search_entities")
        assertThat(whole["total"]!!.jsonPrimitive.content).isEqualTo("4")
        assertThat(client.call("search_text", args { put("text", "vT") }).text())
            .isEqualTo("The text must have at least 3 characters: the text index cannot find a shorter one")
    }

    @Test
    fun `search_text under a small cap pages on by after, each line once`() = mcp(settings = McpSettings(maxAnswerChars = 700)) { client ->
        val seen = ArrayList<String>()
        var after: Long? = null
        var cut = 0
        do {
            val answer = client.call("search_text", args { put("text", "vFixTag"); after?.let { put("after", it) } }).json().jsonObject
            val rows = answer["result"]!!.jsonObject["rows"]!!.jsonArray.map { it.jsonObject }
            if (answer["cutForSize"] != null) cut++
            seen += rows.map { "${it["path"]!!.jsonPrimitive.content}:${it["line"]!!.jsonPrimitive.content}" }
            after = answer["result"]!!.jsonObject["nextAfter"]?.jsonPrimitive?.content?.toLong()
        } while (after != null)
        assertThat(cut).isGreaterThan(0)
        assertThat(seen).containsExactly("hr/text_tags.sql:6", "hr/text_tags.sql:10", "hr/text_tags.sql:12", "scripts/text_tags.sql:1")
    }
}
