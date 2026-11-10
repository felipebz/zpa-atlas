//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.*
import com.felipebz.zpa.atlas.server.McpAnswers.toolAnswer
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The MCP find_usages, group_usages and find_occurrences tools and their refusals, on the fixture index. */
class McpUsagesTest {
    private val index: AtlasIndex get() = ServerFixture.index

    private fun total(answer: JsonElement) = answer.jsonObject["result"]!!.jsonObject["total"]!!.jsonPrimitive.content.toInt()

    @Test
    fun `find_usages answers what AtlasIndex occurrences answers, and include widens the entity as the page does`() = mcp { client ->
        fun withIncluded(answer: JsonObject, vararg scopes: String) = JsonObject(answer + ("included" to strings(*scopes)))
        val body = "$runJob5@BODY"
        assertThat(client.call("find_usages", args { put("key", body) }).json())
            .isEqualTo(withIncluded(toolAnswer(index.occurrences(body, UsageFilter(scope = setOf(Scope.SELF, Scope.IMPLEMENTATIONS)), limit = McpLimits.FIND_DEFAULT)), "IMPLEMENTATIONS"))
        val view = "db:HR.V_COMPANY_LIST"
        assertThat(client.call("find_usages", args { put("key", view); put("include", strings("CHILDREN", "VIEWS")) }).json())
            .isEqualTo(withIncluded(toolAnswer(index.occurrences(view, UsageFilter(scope = setOf(Scope.SELF, Scope.CHILDREN, Scope.VIEWS)), limit = McpLimits.FIND_DEFAULT)), "CHILDREN", "VIEWS"))
        // Without include, the page's default for the kind: a package with its spec or body and its members.
        val pack = client.call("find_usages", args { put("key", "db:HR.PACK_PROCESS") }).json()
        assertThat(pack).isEqualTo(withIncluded(toolAnswer(index.occurrences(
            "db:HR.PACK_PROCESS",
            UsageFilter(scope = setOf(Scope.SELF, Scope.IMPLEMENTATIONS, Scope.CHILDREN)),
            limit = McpLimits.FIND_DEFAULT
        )), "IMPLEMENTATIONS", "CHILDREN"))
        // The members include the body's own globals (`pack_process.g_private :=` in the body), the routines'
        // parameters, read and written in the body, and the record fields (`g_rec.msg`, `pack_process.g_events(1).id`,
        // and those an anchor names: `pack_types.g_last.msg%type`, `g_last.id%type`).
        assertThat(total(pack)).isEqualTo(73)
        // A table: the table alone.
        val table = client.call("find_usages", args { put("key", "db:HR.COMPANY") }).json()
        assertThat(table).isEqualTo(withIncluded(toolAnswer(index.occurrences("db:HR.COMPANY", limit = McpLimits.FIND_DEFAULT))))
        // hr/pack_globals.pkb reads COMPANY once, hr/pack_chains.pkb twice.
        assertThat(total(table)).isEqualTo(123)
        // The body alone has no usage: its callers bind the spec.
        val alone = client.call("find_usages", args { put("key", body); putJsonArray("include") {} }).json()
        assertThat(total(alone)).isEqualTo(0)
        assertThat(alone.jsonObject["included"]).isEqualTo(JsonArray(emptyList()))
        assertThat(total(client.call("find_usages", args { put("key", body) }).json())).isEqualTo(4)
        assertThat(total(client.call("find_usages", args { put("key", view); putJsonArray("include") {} }).json())).isEqualTo(3)
        assertThat(total(client.call("find_usages", args { put("key", view); put("include", strings("VIEWS")) }).json())).isEqualTo(7)
        assertThat(total(client.call("find_usages", args { put("key", view); put("include", strings("CHILDREN")) }).json())).isEqualTo(9)
        assertThat(total(client.call("find_usages", args { put("key", view); put("include", strings("CHILDREN", "VIEWS")) }).json())).isEqualTo(16)
    }

    @Test
    fun `get_entity and find_usages say when a body global's uses may be incomplete, and say nothing when they are not`() = mcp { client ->
        suspend fun result(tool: String, key: String) = client.call(tool, args { put("key", key) }).json().jsonObject.getValue("result").jsonObject
        val hidden = "db:HR.PACK_SWITCH.G_LEVEL@BODY"
        val reasons = strings("DECLARATION_OUTSIDE_FILE", "CONDITIONAL_COMPILATION")
        assertThat(result("get_entity", hidden)["usesIncomplete"]).isEqualTo(reasons)
        assertThat(result("find_usages", hidden)["usesIncomplete"])
            .isEqualTo(JsonArray(listOf(JsonObject(mapOf("key" to JsonPrimitive(hidden), "reasons" to reasons)))))
        // The package lists it on each of its globals, not on its routine.
        val children = result("get_entity", "db:HR.PACK_SWITCH")["children"]!!.jsonArray.map { it.jsonObject }
        assertThat(children.associate { it.getValue("entity").jsonObject.getValue("name").jsonPrimitive.content to (it["usesIncomplete"] != null) })
            .isEqualTo(mapOf("G_LEVEL" to true, "G_QUIET" to true, "RUN" to false))
        // A body without a $IF: nothing.
        val complete = "db:HR.PACK_CHAINS.G_REC@BODY"
        assertThat(result("get_entity", complete)).doesNotContainKey("usesIncomplete")
        assertThat(result("find_usages", complete)).doesNotContainKey("usesIncomplete")
    }

    @Test
    fun `find_usages pages lose and repeat no row, under a small size cap too`() {
        val all = index.occurrences("db:HR.PACK_PROCESS", UsageFilter(scope = setOf(Scope.SELF, Scope.CHILDREN)), limit = 500).result!!.rows.map { it.id }
        assertThat(all.size).isGreaterThan(McpLimits.FIND_MAX)
        for (cap in listOf(McpAnswers.MAX_CHARS, 6_000)) {
            mcp(settings = McpSettings(maxAnswerChars = cap)) { client ->
                val ids = ArrayList<Long>()
                var after: Long? = null
                var cut = 0
                do {
                    val answer = client.call("find_usages", args {
                        put("key", "db:HR.PACK_PROCESS"); put("include", strings("CHILDREN")); put("limit", McpLimits.FIND_MAX); after?.let { put("after", it) }
                    }).json().jsonObject
                    val rows = answer["result"]!!.jsonObject["rows"]!!.jsonArray
                    answer["cutForSize"]?.let { cut++; assertThat(it.jsonObject["kept"]!!.jsonPrimitive.content.toInt()).isEqualTo(rows.size) }
                    ids += rows.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
                    after = answer["result"]!!.jsonObject["nextAfter"]?.jsonPrimitive?.content?.toLong()
                } while (after != null)
                assertThat(ids).isEqualTo(all)
                if (cap < McpAnswers.MAX_CHARS) assertThat(cut).isGreaterThan(0)
            }
        }
    }

    @Test
    fun `group_usages answers what AtlasIndex groupUsages answers, and find_usages keeps the rows via asks for`() = mcp { client ->
        val views = setOf(Scope.SELF, Scope.VIEWS)
        val byViewAndRoutine = listOf(GroupDimension.ENTITY, GroupDimension.CONTAINER)
        val grouped = client.call("group_usages", args {
            put("key", "db:HR.COMPANY"); put("include", strings("VIEWS")); put("via", "THROUGH_VIEWS"); put("group_by", strings("ENTITY", "CONTAINER"))
        }).json()
        assertThat(grouped).isEqualTo(JsonObject(toolAnswer(
            index.groupUsages("db:HR.COMPANY", byViewAndRoutine, UsageFilter(scope = views, via = UsagePath.THROUGH_VIEWS), limit = McpLimits.GROUP_DEFAULT)
        ) + ("included" to strings("VIEWS"))))
        // Without include, the default by kind: a package with its spec or body and its members.
        assertThat(client.call("group_usages", args { put("key", "db:HR.PACK_PROCESS"); put("group_by", strings("ROLE")) }).json())
            .isEqualTo(JsonObject(toolAnswer(index.groupUsages(
                "db:HR.PACK_PROCESS",
                listOf(GroupDimension.ROLE),
                UsageFilter(scope = setOf(Scope.SELF, Scope.IMPLEMENTATIONS, Scope.CHILDREN)),
                limit = McpLimits.GROUP_DEFAULT
            )) + ("included" to strings("IMPLEMENTATIONS", "CHILDREN"))))
        for (via in UsagePath.entries) {
            assertThat(client.call("find_usages", args { put("key", "db:HR.COMPANY"); put("include", strings("VIEWS")); put("via", via.name) }).json())
                .isEqualTo(JsonObject(toolAnswer(index.occurrences("db:HR.COMPANY", UsageFilter(scope = views, via = via), limit = McpLimits.FIND_DEFAULT)) +
                    ("included" to strings("VIEWS"))))
        }
    }

    @Test
    fun `group_usages refuses an unknown entity, via value, dimension or argument, and four dimensions`() = mcp { client ->
        suspend fun group(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = client.call("group_usages", args { put("key", "db:HR.COMPANY"); block() }).error()
        assertThat(client.call("group_usages", args { put("key", "db:NO.SUCH"); put("group_by", strings("ENTITY")) }).error())
            .isEqualTo("Unknown entity: db:NO.SUCH. Entity keys come from search_entities or from earlier answers.")
        assertThat(group { put("group_by", strings("ENTITY")); put("via", "VIEWS") }).isEqualTo("Unknown via value: VIEWS")
        assertThat(client.call("find_usages", args { put("key", "db:HR.COMPANY"); put("via", "SIDEWAYS") }).error()).isEqualTo("Unknown via value: SIDEWAYS")
        assertThat(group { put("group_by", strings("COLOR")) }).isEqualTo("Unknown group_by dimension: COLOR")
        assertThat(group { put("group_by", strings("ENTITY", "CONTAINER", "ROLE", "STATUS")) }).isEqualTo("groupBy takes one to three dimensions")
        assertThat(group { put("group_by", strings("ENTITY")); put("after", 3) }).startsWith("Unknown argument: after. group_usages takes: key, include,")
    }

    @Test
    fun `find_usages refuses an unknown entity, include value or argument`() = mcp { client ->
        assertThat(client.call("find_usages", args { put("key", "db:NO.SUCH") }).error())
            .isEqualTo("Unknown entity: db:NO.SUCH. Entity keys come from search_entities or from earlier answers.")
        assertThat(client.call("find_usages", args { put("key", runJob5); put("include", strings("SELF")) }).error()).isEqualTo("Unknown include value: SELF")
        assertThat(client.call("find_usages", args { put("key", runJob5); put("scope", strings("VIEWS")) }).error())
            .startsWith("Unknown argument: scope. find_usages takes: key, include,")
    }

    @Test
    fun `find_occurrences without a filter is refused and names the filters, group_occurrences says it counts the whole index`() = mcp { client ->
        for (arguments in listOf(args {}, args { put("limit", 5) }, args { put("after", 10) }, args { put("candidates", false); put("inside", true) })) {
            val refused = client.call("find_occurrences", arguments)
            assertThat(refused.isError).describedAs(arguments.toString()).isTrue()
            assertThat(refused.text()).startsWith("find_occurrences needs at least one filter: entity, entity_kinds, name,")
                .contains("limit and after are not filters", "group_occurrences")
        }
        // One filter answers as before.
        assertThat(client.call("find_occurrences", args { put("name", "st_active") }).json())
            .isEqualTo(toolAnswer(index.queryOccurrences(OccurrenceQuery(name = "st_active"), null, McpLimits.FIND_DEFAULT, summary = true)))
        val whole = client.call("group_occurrences", args { put("group_by", strings("ROLE")) }).json().jsonObject
        assertThat(whole["wholeIndex"]).isEqualTo(JsonPrimitive(true))
        assertThat(JsonObject(whole - "wholeIndex")).isEqualTo(toolAnswer(index.groupOccurrences(OccurrenceQuery(), listOf(GroupDimension.ROLE), McpLimits.GROUP_DEFAULT)))
        assertThat(client.call("group_occurrences", args { put("group_by", strings("ROLE")); put("path", "hr/") }).json().jsonObject).doesNotContainKey("wholeIndex")
    }
}
