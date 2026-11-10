//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.server.McpAnswers.toolAnswer
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The MCP value tools and the run stamp left out of every answer, on the fixture index. */
class McpValuesTest {
    private val index: AtlasIndex get() = ServerFixture.index

    private val pCode = "db:HR.PACK_ARGS.P(P_CODE IN NUMBER,P_NAME IN VARCHAR2,P_FLAG IN BOOLEAN).P_CODE"
    private val pCodeBody = pCode.replace(").P_CODE", ")@BODY.P_CODE")
    private val stActive = "db:HR.COMPANY.ST_ACTIVE"

    @Test
    fun `no tool answer carries the run stamp`() = mcp { client ->
        val calls = listOf(
            "search_entities" to args { put("name", "run_job") },
            "get_entity" to args { put("key", "db:HR.COMPANY") },
            "find_occurrences" to args { put("container", "db:HR.PACK_PROCESS") },
            "group_occurrences" to args { put("group_by", strings("ROLE")) },
            "find_usages" to args { put("key", "db:HR.COMPANY") },
            "group_usages" to args { put("key", "db:HR.COMPANY"); put("group_by", strings("ROLE")) },
            "find_impact" to args { put("key", "db:HR.PACK_IMP_A.LEAF(P_N IN NUMBER)") },
            "find_referencing_keys" to args { put("key", "db:HR.KY_ORDERS") },
            "find_column_places" to args { put("key", "db:HR.COMPANY.COMPANY_NAME") },
            "get_source" to args { put("path", "hr/pack_process.pkb"); put("line", 10) },
            "get_values" to args { put("key", stActive) },
            "find_value_sites" to args { put("key", stActive); put("value_kind", "STRING"); put("value", "S") },
            "search_text" to args { put("text", "vFixTag") }
        )
        assertThat(calls.map { it.first }).containsExactlyInAnyOrderElementsOf(client.listTools().tools.map { it.name })
        for ((name, arguments) in calls) {
            val answer = client.call(name, arguments)
            assertThat(answer.isError).describedAs(name).isNotEqualTo(true)
            assertThat(answer.json().jsonObject).describedAs(name).containsKey("result").doesNotContainKey("run")
        }
    }

    private fun JsonElement.result() = jsonObject["result"]!!.jsonObject

    @Test
    fun `get_values answers what AtlasIndex values answers, and a body parameter its spec's`() = mcp { client ->
        for (key in listOf(stActive, pCode, pCodeBody)) {
            val answer = client.call("get_values", args { put("key", key) }).json()
            val api = toolAnswer(index.values(key))
            assertThat(answer.result()["groups"]).describedAs(key).isEqualTo(api["result"])
            assertThat(answer.result()["totalGroups"]!!.jsonPrimitive.content.toInt()).isEqualTo(api["result"]!!.jsonArray.size)
        }
        val written = client.call("get_values", args { put("key", stActive) }).json().result()["groups"]!!.jsonArray
        assertThat(written.take(3)).containsExactly(
            Json.parseToJsonElement("""{"valueKind":"STRING","value":"S","count":9}"""),
            Json.parseToJsonElement("""{"valueKind":"STRING","value":"N","count":7}"""),
            Json.parseToJsonElement("""{"valueKind":"NULL","count":3}""")
        )
        val spec = client.call("get_values", args { put("key", pCode) }).json().result()
        assertThat(spec["totalGroups"]!!.jsonPrimitive.content.toInt()).isEqualTo(12)
        assertThat(client.call("get_values", args { put("key", pCodeBody) }).json().result()).isEqualTo(spec)
        // limit keeps the first groups; the total still counts them all.
        val cut = client.call("get_values", args { put("key", pCode); put("limit", 3) }).json().result()
        assertThat(cut["groups"]).isEqualTo(JsonArray(spec["groups"]!!.jsonArray.take(3)))
        assertThat(cut["totalGroups"]!!.jsonPrimitive.content.toInt()).isEqualTo(12)
    }

    @Test
    fun `find_value_sites answers what AtlasIndex valueSites answers, with only the parameter's own argument`() = mcp { client ->
        for ((key, kind, value) in listOf(Triple(pCode, "NUMBER", "1"), Triple(pCodeBody, "NUMBER", "1"), Triple(pCode, "EXPRESSION", null))) {
            val api = index.valueSites(key, kind, value, null, 40)
            val page = api.result!!
            val own = page.copy(rows = page.rows.map { row -> row.copy(arguments = row.arguments.filter { it.parameter?.name == "P_CODE" }) })
            val answer = client.call("find_value_sites", args { put("key", key); put("value_kind", kind); value?.let { put("value", it) }; put("limit", 40) }).json()
            assertThat(answer).isEqualTo(toolAnswer(api.copy(result = own)))
            val rows = answer.result()["rows"]!!.jsonArray.map { it.jsonObject }
            assertThat(rows).isNotEmpty()
            for (row in rows) {
                val argument = row["arguments"]!!.jsonArray.single().jsonObject
                assertThat(argument["parameter"]).isEqualTo(JsonPrimitive("P_CODE"))
                assertThat(argument["valueKind"]).isEqualTo(JsonPrimitive(kind))
            }
        }
        // p(1, ...) calls pass other arguments too: they are left out.
        assertThat(index.valueSites(pCode, "NUMBER", "1").result!!.rows.map { it.arguments.size }).contains(2)
        // A column's rows are as the index gives them.
        assertThat(client.call("find_value_sites", args { put("key", stActive); put("value_kind", "STRING"); put("value", "S"); put("limit", 40) }).json())
            .isEqualTo(toolAnswer(index.valueSites(stActive, "STRING", "S", null, 40)))
    }

    @Test
    fun `find_value_sites pages lose and repeat no row, under a small size cap too`() {
        for ((key, kind, value) in listOf(Triple(stActive, "STRING", "S"), Triple(pCode, "NUMBER", "1"))) {
            val all = index.valueSites(key, kind, value, null, 500).result!!.rows.map { it.id }
            assertThat(all.size).isGreaterThan(3)
            for (cap in listOf(McpAnswers.MAX_CHARS, 1_500)) {
                mcp(settings = McpSettings(maxAnswerChars = cap)) { client ->
                    val ids = ArrayList<Long>()
                    var after: Long? = null
                    var cut = 0
                    do {
                        val answer = client.call("find_value_sites", args {
                            put("key", key); put("value_kind", kind); put("value", value); put("limit", 3); after?.let { put("after", it) }
                        }).json().jsonObject
                        val rows = answer["result"]!!.jsonObject["rows"]!!.jsonArray
                        answer["cutForSize"]?.let { cut++; assertThat(it.jsonObject["kept"]!!.jsonPrimitive.content.toInt()).isEqualTo(rows.size) }
                        ids += rows.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() }
                        after = answer["result"]!!.jsonObject["nextAfter"]?.jsonPrimitive?.content?.toLong()
                    } while (after != null)
                    assertThat(ids).describedAs("$key at $cap").isEqualTo(all)
                    if (cap < McpAnswers.MAX_CHARS) assertThat(cut).describedAs("$key at $cap").isGreaterThan(0)
                }
            }
        }
    }

    @Test
    fun `the value tools refuse another kind of entity, an unknown entity, value kind or argument, and a STRING without value`() = mcp { client ->
        assertThat(client.call("get_values", args { put("key", "db:HR.COMPANY") }).error())
            .isEqualTo("get_values answers for a PARAMETER or a COLUMN; db:HR.COMPANY is a TABLE.")
        assertThat(client.call("find_value_sites", args { put("key", runJob5); put("value_kind", "NULL") }).error())
            .isEqualTo("find_value_sites answers for a PARAMETER or a COLUMN; $runJob5 is a PROCEDURE.")
        val unknown = "Unknown entity: db:NO.SUCH. Entity keys come from search_entities or from earlier answers."
        assertThat(client.call("get_values", args { put("key", "db:NO.SUCH") }).error()).isEqualTo(unknown)
        assertThat(client.call("find_value_sites", args { put("key", "db:NO.SUCH"); put("value_kind", "NULL") }).error()).isEqualTo(unknown)
        assertThat(client.call("find_value_sites", args { put("key", stActive); put("value_kind", "COLOR") }).error()).isEqualTo("Unknown value kind: COLOR")
        assertThat(client.call("find_value_sites", args { put("key", stActive); put("value_kind", "STRING") }).error()).isEqualTo("Missing argument: value")
        assertThat(client.call("find_value_sites", args { put("key", stActive); put("kind", "STRING") }).error())
            .isEqualTo("Unknown argument: kind. find_value_sites takes: key, value_kind, value, after, limit")
        assertThat(client.call("get_values", args { put("key", stActive); put("limit", 1000) }).error()).isEqualTo("limit must be between 1 and ${McpLimits.VALUES_MAX}")
    }
}
