//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.query.ImpactOptions
import com.felipebz.zpa.atlas.query.Scope
import com.felipebz.zpa.atlas.server.McpAnswers.toolAnswer
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The MCP find_impact tool: its numbered graph, keys, triggers and paging, on the fixture index. */
class McpImpactTest {
    private val index: AtlasIndex get() = ServerFixture.index

    private fun JsonElement.impactNodes() = jsonObject["result"]!!.jsonObject["nodes"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.field(name: String) = get(name)?.jsonPrimitive?.content

    private fun JsonObject.number() = getValue("n").jsonPrimitive.content.toInt()

    private val impactLeaf = "db:HR.PACK_IMP_A.LEAF(P_N IN NUMBER)"

    @Test
    fun `find_impact numbers the nodes of the whole graph, names them and points via at a number`() = mcp { client ->
        // Without following triggers: the graph of ImpactTest.
        val nodes = client.call("find_impact", args { put("key", impactLeaf); put("follow_triggers", false) }).json().impactNodes()
        val graph = index.impact(impactLeaf, ImpactOptions(include = listOf(Scope.IMPLEMENTATIONS), followTriggers = false), limit = McpLimits.IMPACT_DEFAULT).result!!.nodes
        assertThat(nodes.map { it.number() }).isEqualTo((1..graph.size).toList())
        assertThat(nodes.map { it.field("name") }).containsExactly(
            "HR.IMP_FN", "HR.PACK_IMP_A.MID", "HR.PACK_IMP_A.OV", "HR.PACK_IMP_A.PONG", "HR.PACK_IMP_B", "HR.IMP_AMB", "HR.IMP_REC",
            "HR.PACK_IMP_A.PING", "HR.PACK_IMP_B.CURS_USER", "HR.PACK_IMP_B.HELPER@BODY", "HR.PACK_IMP_B.TOP", "HR.TRG_IMP", "HR.V_IMP", null, "HR.IMP_READ_VIEW"
        )
        // The first hop uses the entity asked for; the others use the node whose key the index gives.
        val numberOf = graph.withIndex().filter { it.value.entity != null }.associate { it.value.entity!!.key to it.index + 1 }
        assertThat(nodes.map { it.getValue("via").jsonObject.getValue("uses").jsonPrimitive.content.toInt() })
            .isEqualTo(graph.map { if (it.depth == 1) 0 else numberOf.getValue(it.via.uses) })
        // Only names two nodes share carry a key: here none, the VARCHAR2 overload of OV is not a node.
        assertThat(nodes.filter { it["key"] != null }).isEmpty()
        // A file has its path and no name.
        assertThat(nodes.single { it.field("kind") == "FILE" }.let { it["name"] to it.field("path") }).isEqualTo(null to "scripts/imp_run.sql")
    }

    @Test
    fun `find_impact gives a key to the nodes whose name repeats`() = mcp { client ->
        val nodes = client.call("find_impact", args { put("key", "db:HR.IMP_OVL.BASE()") }).json().impactNodes()
        assertThat(nodes.map { it.field("name") }).containsExactly("HR.IMP_OVL.RUN", "HR.IMP_OVL.RUN")
        assertThat(nodes.map { it.field("key") }).containsExactly("db:HR.IMP_OVL.RUN(P IN NUMBER)", "db:HR.IMP_OVL.RUN(P IN VARCHAR2)")
    }

    @Test
    fun `find_impact names what the first hop matched when it is not the entity asked for`() = mcp { client ->
        // The package: the first hop matches its members.
        val member = client.call("find_impact", args { put("key", "db:HR.PACK_IMP_A"); put("max_depth", 1) }).json().impactNodes()
            .single { it.field("name") == "HR.IMP_REC" }
        assertThat(member.getValue("via").jsonObject.let { it.field("uses") to it.field("usesName") }).isEqualTo("0" to "HR.PACK_IMP_A.MID")
        // A routine used itself: no usesName.
        val direct = client.call("find_impact", args { put("key", "db:HR.IMP_FN(P_N IN NUMBER)"); put("max_depth", 1) }).json().impactNodes()
        assertThat(direct.map { it.getValue("via").jsonObject["usesName"] }).containsOnlyNulls()
    }

    @Test
    fun `find_impact with keys answers the index nodes plus their number, and n does not move with stop or paging`() = mcp { client ->
        val graph = index.impact(impactLeaf, ImpactOptions(include = listOf(Scope.IMPLEMENTATIONS)), limit = McpLimits.IMPACT_DEFAULT)
        val keyed = client.call("find_impact", args { put("key", impactLeaf); put("keys", true) }).json().impactNodes()
        val base = toolAnswer(graph).jsonObject["result"]!!.jsonObject["nodes"]!!.jsonArray.map { it.jsonObject }
        assertThat(keyed.map { JsonObject(it - "n") }).isEqualTo(base)
        assertThat(keyed.map { it.number() }).isEqualTo((1..base.size).toList())
        val entry = client.call("find_impact", args { put("key", impactLeaf); put("stop", "ENTRY_POINT"); put("keys", true) }).json().impactNodes()
        assertThat(entry).isNotEmpty
        assertThat(entry).allSatisfy { assertThat(it).isEqualTo(keyed.single { k -> k.number() == it.number() }) }
        assertThat(entry.size).isLessThan(keyed.size)
        val page = client.call("find_impact", args { put("key", impactLeaf); put("offset", 4); put("limit", 3) }).json().impactNodes()
        assertThat(page.map { it.number() }).containsExactly(5, 6, 7)
        assertThat(client.call("find_impact", args { put("key", impactLeaf); put("stop", "SOMEWHERE") }).text()).isEqualTo("Unknown stop value: SOMEWHERE")
        assertThat(client.call("find_impact", args { put("key", "db:NO.SUCH") }).text()).startsWith("Unknown entity: db:NO.SUCH.")
    }

    @Test
    fun `find_impact follows the writes that fire a trigger, and follow_triggers=false stops at it`() = mcp { client ->
        val key = "db:HR.IT_AUDIT(P_WHAT IN VARCHAR2)"
        val nodes = client.call("find_impact", args { put("key", key) }).json().impactNodes()
        val numbers = nodes.associate { it.field("name") to it.number() }
        val writer = nodes.single { it.field("name") == "HR.IT_P" }
        assertThat(writer.getValue("via").jsonObject.let { it.field("uses")!!.toInt() to it.field("fires") }).isEqualTo(numbers.getValue("HR.TRG_IT_ORDERS_BI") to "INSERT")
        assertThat(nodes.single { it.field("name") == "HR.IT_Q" }.getValue("via").jsonObject["fires"]).isNull()
        val keyed = client.call("find_impact", args { put("key", key); put("keys", true) }).json().impactNodes()
        assertThat(keyed.map { JsonObject(it - "n") })
            .isEqualTo(toolAnswer(index.impact(key, ImpactOptions(include = listOf(Scope.IMPLEMENTATIONS)), limit = McpLimits.IMPACT_DEFAULT)).jsonObject["result"]!!.jsonObject["nodes"]!!.jsonArray.map { it.jsonObject })
        val off = client.call("find_impact", args { put("key", key); put("follow_triggers", false); put("keys", true) }).json().impactNodes()
        assertThat(off.map { JsonObject(it - "n") }).isEqualTo(
            toolAnswer(index.impact(key, ImpactOptions(include = listOf(Scope.IMPLEMENTATIONS), followTriggers = false), limit = McpLimits.IMPACT_DEFAULT))
                .jsonObject["result"]!!.jsonObject["nodes"]!!.jsonArray.map { it.jsonObject }
        )
        assertThat(off.map { it.field("stop") }.distinct()).containsExactly("TRIGGER")
        assertThat(client.call("find_impact", args { put("key", key); put("follow_triggers", "yes") }).text()).isEqualTo("follow_triggers must be true or false")
    }

    @Test
    fun `find_impact under a small cap pages on by offset, each node once`() = mcp(settings = McpSettings(maxAnswerChars = 2_000)) { client ->
        val seen = ArrayList<Int>()
        var offset: Int? = 0
        var cut = 0
        while (offset != null) {
            val answer = client.call("find_impact", args { put("key", impactLeaf); put("offset", offset!!); put("follow_triggers", false) }).json().jsonObject
            val nodes = answer.impactNodes()
            answer["cutForSize"]?.let { cut++; assertThat(it.jsonObject["kept"]!!.jsonPrimitive.content.toInt()).isEqualTo(nodes.size) }
            seen += nodes.map { it.number() }
            offset = answer["result"]!!.jsonObject["nextOffset"]?.jsonPrimitive?.content?.toInt()
        }
        assertThat(cut).isGreaterThan(0)
        assertThat(seen).isEqualTo((1..15).toList())
    }
}
