//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/**
 * The transitive impact of `PACK_IMP_A.LEAF` in the fixture (`hr/pack_imp_a.*`, `hr/pack_imp_b.*`, `hr/imp_*`,
 * `hr/v_imp.sql`, `hr/trg_imp.trg`, `scripts/imp_run.sql`). By hand, from the sources:
 * - depth 1, the code that calls `leaf`: `MID`, `PONG` and `OV(P IN NUMBER)` in `pack_imp_a.pkb`, `IMP_FN`, and the
 *   initialization of `PACK_IMP_B` (package level). `LEAF` calling itself is its own body: not a node.
 * - depth 2: `HELPER` (private, so its body key) and `TOP` call `MID`; so do `TRG_IMP` (a trigger on `COMPANY`) and
 *   `scripts/imp_run.sql` (no container), and `IMP_REC`, which also calls itself; `PING` calls `PONG`; `V_IMP` and the
 *   cursor in `CURS_USER` call `IMP_FN`;
 *   `IMP_AMB` calls `ov(1)`, AMBIGUOUS between the two `OV`. `TOP` also calls `HELPER` (depth 3 that way): the
 *   shorter path wins.
 * - depth 3: `IMP_READ_VIEW` selects from `V_IMP`.
 * So 5, 9 and 1 nodes. Followed, called by code outside their own cycle: `MID`, `OV`, `IMP_FN`, `HELPER` (by `TOP`),
 * `V_IMP` (by `IMP_READ_VIEW`). Entry points: `TOP`, `CURS_USER`, `IMP_AMB`, `IMP_READ_VIEW`, which nothing calls;
 * `IMP_REC`, called only by itself; `PING` and `PONG`, called only by each other. One trigger, one package level, one
 * file.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImpactTest {
    private val temp: Path = Files.createTempDirectory("atlas-impact-test")
    private lateinit var index: AtlasIndex

    private val leaf = "db:HR.PACK_IMP_A.LEAF(P_N IN NUMBER)"
    private val mid = "db:HR.PACK_IMP_A.MID(P_N IN NUMBER)"
    private val pong = "db:HR.PACK_IMP_A.PONG(P_N IN NUMBER)"
    private val ov = "db:HR.PACK_IMP_A.OV(P IN NUMBER)"
    private val impFn = "db:HR.IMP_FN(P_N IN NUMBER)"

    @BeforeAll
    fun build() {
        index = AtlasIndex(Fixture.index(temp.resolve("fixture.db")), Fixture.sources)
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private fun graph(key: String = leaf, maxDepth: Int = 10, stop: ImpactStop? = null, offset: Int = 0, limit: Int = 100) =
        index.impact(key, maxDepth = maxDepth, stop = stop, offset = offset, limit = limit).result!!

    private fun ImpactNode.name() = entity?.key ?: path!!

    /** Each node as `depth stop certain name <- uses @ file:line status`. */
    private fun ImpactGraph.lines() = nodes.map { "${it.depth} ${it.stop} ${it.certain} ${it.name()} <- ${it.via.uses} @ ${it.via.path}:${it.via.line} ${it.via.status}" }

    @Test
    fun `each node has its shortest depth, one use on that path, how sure it is and why the walk stops`() {
        val graph = graph()
        assertThat(graph.included).containsExactly("IMPLEMENTATIONS")
        assertThat(graph.levels).containsExactly(5, 9, 1)
        assertThat(graph.stops.map { "${it.value} ${it.count}" })
            .containsExactly("FOLLOWED 5", "ENTRY_POINT 7", "TRIGGER 1", "PACKAGE_LEVEL 1", "NO_CONTAINER 1")
        assertThat(graph.lines()).containsExactly(
            "1 FOLLOWED true $impFn <- $leaf @ hr/imp_fn.fun:3 RESOLVED",
            "1 FOLLOWED true $mid <- $leaf @ hr/pack_imp_a.pkb:11 RESOLVED",
            "1 FOLLOWED true $ov <- $leaf @ hr/pack_imp_a.pkb:27 RESOLVED",
            // PONG and PING call each other, and nothing else calls either: one of them is run from outside the index.
            "1 ENTRY_POINT true $pong <- $leaf @ hr/pack_imp_a.pkb:22 RESOLVED",
            "1 PACKAGE_LEVEL true db:HR.PACK_IMP_B <- $leaf @ hr/pack_imp_b.pkb:21 RESOLVED",
            // Reached only through the AMBIGUOUS call: a path, but not a certain one.
            "2 ENTRY_POINT false db:HR.IMP_AMB() <- $ov @ hr/imp_amb.prc:3 AMBIGUOUS",
            // Its only other use is its own recursive call.
            "2 ENTRY_POINT true db:HR.IMP_REC(P_N IN NUMBER) <- $mid @ hr/imp_rec.prc:6 RESOLVED",
            "2 ENTRY_POINT true db:HR.PACK_IMP_A.PING(P_N IN NUMBER) <- $pong @ hr/pack_imp_a.pkb:16 RESOLVED",
            // The cursor's query is the routine's code.
            "2 ENTRY_POINT true db:HR.PACK_IMP_B.CURS_USER() <- $impFn @ hr/pack_imp_b.pkb:14 RESOLVED",
            "2 FOLLOWED true db:HR.PACK_IMP_B.HELPER()@BODY <- $mid @ hr/pack_imp_b.pkb:4 RESOLVED",
            "2 ENTRY_POINT true db:HR.PACK_IMP_B.TOP() <- $mid @ hr/pack_imp_b.pkb:10 RESOLVED",
            "2 TRIGGER true db:HR.TRG_IMP <- $mid @ hr/trg_imp.trg:5 RESOLVED",
            "2 FOLLOWED true db:HR.V_IMP <- $impFn @ hr/v_imp.sql:1 RESOLVED",
            "2 NO_CONTAINER true scripts/imp_run.sql <- $mid @ scripts/imp_run.sql:2 RESOLVED",
            "3 ENTRY_POINT true db:HR.IMP_READ_VIEW() <- db:HR.V_IMP @ hr/imp_read_view.prc:4 RESOLVED"
        )
        val trigger = graph.nodes.single { it.kind == "TRIGGER" }.trigger!!
        assertThat(trigger.table!!.key).isEqualTo("db:HR.COMPANY")
        assertThat(trigger.events.map { "${it.timing} ${it.level} ${it.event}" }).containsExactly("AFTER ROW INSERT")
        val file = graph.nodes.single { it.kind == "FILE" }
        assertThat(file.uses).isEqualTo(1)
        assertThat(file.firstLine).isEqualTo(2)
    }

    @Test
    fun `the entity and its spec or body are never nodes, and either key gives the same walk`() {
        val fromSpec = graph()
        val fromBody = graph("$leaf@BODY")
        assertThat(fromSpec.nodes.map { it.name() }).doesNotContain(leaf, "$leaf@BODY")
        assertThat(fromBody.copy(entity = fromSpec.entity)).isEqualTo(fromSpec)
        // A spec and its body are one node, named by the spec: MID is called in its spec's name, its calls are in its body.
        assertThat(fromSpec.nodes.map { it.name() }).doesNotContain("$mid@BODY")
    }

    @Test
    fun `maxDepth stops the walk at that depth, with that reason`() {
        val shallow = graph(maxDepth = 1)
        assertThat(shallow.levels).containsExactly(5)
        assertThat(shallow.lines().map { it.substringBefore(" <-") }).containsExactly(
            "1 DEPTH_LIMIT true $impFn", "1 DEPTH_LIMIT true $mid", "1 DEPTH_LIMIT true $ov", "1 DEPTH_LIMIT true $pong",
            "1 PACKAGE_LEVEL true db:HR.PACK_IMP_B"
        )
        // At depth 2, the seven routines and views of that level are not read: IMP_AMB, IMP_REC, PING, CURS_USER, HELPER,
        // TOP, V_IMP. PONG, at depth 1, was read but its caller PING was not: PING's callers are unknown, so PONG is followed.
        val two = graph(maxDepth = 2)
        assertThat(two.stops.single { it.value == "DEPTH_LIMIT" }.count).isEqualTo(7)
        assertThat(two.nodes.single { it.name() == pong }.stop).isEqualTo("FOLLOWED")
    }

    @Test
    fun `the stop filter keeps one reason and the counts of the whole graph, and pages go on by offset`() {
        val entries = graph(stop = ImpactStop.ENTRY_POINT)
        assertThat(entries.nodes.map { it.name() })
            .containsExactly(
                pong, "db:HR.IMP_AMB()", "db:HR.IMP_REC(P_N IN NUMBER)", "db:HR.PACK_IMP_A.PING(P_N IN NUMBER)",
                "db:HR.PACK_IMP_B.CURS_USER()", "db:HR.PACK_IMP_B.TOP()", "db:HR.IMP_READ_VIEW()"
            )
        assertThat(entries.total).isEqualTo(7)
        assertThat(entries.levels).isEqualTo(graph().levels)
        assertThat(entries.stops).isEqualTo(graph().stops)
        val whole = graph().nodes
        val pages = generateSequence(graph(limit = 5)) { page -> page.nextOffset?.let { graph(offset = it, limit = 5) } }.toList()
        assertThat(pages.map { it.offset }).containsExactly(0, 5, 10)
        assertThat(pages.flatMap { it.nodes }).isEqualTo(whole)
        assertThat(pages.last().nextOffset).isNull()
    }

    @Test
    fun `a routine that only calls itself is an entry point`() {
        // IMP_REC calls MID and itself; nothing else calls it.
        assertThat(graph(mid).lines().filter { "IMP_REC" in it })
            .containsExactly("1 ENTRY_POINT true db:HR.IMP_REC(P_N IN NUMBER) <- $mid @ hr/imp_rec.prc:6 RESOLVED")
    }

    @Test
    fun `a routine called only by the entity is followed when the entity has other callers, an entry point when it has none`() {
        // IMP_ECHO_B is called only by IMP_ECHO_A, and calls it back; scripts/imp_run.sql also calls IMP_ECHO_A.
        assertThat(graph("db:HR.IMP_ECHO_A(P_N IN NUMBER)").lines().map { it.substringBefore(" <-") }).containsExactly(
            "1 FOLLOWED true db:HR.IMP_ECHO_B(P_N IN NUMBER)", "1 NO_CONTAINER true scripts/imp_run.sql"
        )
        // PING is called only by PONG, which only PING calls: nothing outside the two runs them.
        assertThat(graph(pong).lines().map { it.substringBefore(" <-") }).containsExactly("1 ENTRY_POINT true db:HR.PACK_IMP_A.PING(P_N IN NUMBER)")
    }

    @Test
    fun `the same request gives the same answer`() {
        assertThat(index.impact(leaf)).isEqualTo(index.impact(leaf))
        assertThat(index.impact("db:HR.COMPANY.ID")).isEqualTo(index.impact("db:HR.COMPANY.ID"))
    }

    @Test
    fun `a package cursor's first hop is the code that reads it`() {
        val cursor = graph("db:HR.PACK_PROCESS.C_BY_STATUS")
        assertThat(cursor.nodes.first().let { "${it.depth} ${it.name()} ${it.via.uses}" })
            .isEqualTo("1 db:HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE) db:HR.PACK_PROCESS.C_BY_STATUS")
    }

    @Test
    fun `bad arguments are refused and an unknown entity is null`() {
        assertThat(index.impact("db:NO.SUCH").result).isNull()
        assertThatThrownBy { index.impact(leaf, maxDepth = 0) }.hasMessage("maxDepth must be between 1 and 50")
        assertThatThrownBy { index.impact(leaf, maxDepth = 51) }.hasMessage("maxDepth must be between 1 and 50")
        assertThatThrownBy { index.impact(leaf, roles = setOf("NOPE")) }.hasMessage("Unknown role: NOPE")
        assertThatThrownBy { index.impact(leaf, offset = -1) }.hasMessage("offset must not be negative")
    }
}
