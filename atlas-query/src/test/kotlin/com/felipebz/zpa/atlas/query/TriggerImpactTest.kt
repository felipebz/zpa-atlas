//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/**
 * The impact of `IT_AUDIT` in the fixture (`hr/it_*`, `scripts/it_load.sql`), which every trigger there calls. By hand:
 * - depth 1, the code that calls it: the triggers `TRG_IT_ORDERS_BI` (before insert on `IT_ORDERS`, row),
 *   `TRG_IT_ORDERS_STATUS` (after update of `STATUS`, row), `TRG_IT_ORDERS_DEL` (after delete, statement),
 *   `TRG_IT_V_IO` (instead of insert on the view `IT_V_ORDERS_IO`), `TRG_IT_UNWRITTEN`, `TRG_IT_PONG` and the system
 *   trigger `TRG_IT_LOGON`: 7 nodes.
 * - depth 2, the writes that fire them:
 *   - INSERT into `IT_ORDERS`: `IT_P`; `IT_MERGE` (its insert clause); `IT_WRITE_VIEW` (the view `IT_V_ORDERS`, whose
 *     columns each have one base, `IT_ORDERS`); `IT_DYN` (`execute immediate 'insert …'`); the trigger `TRG_IT_STAGE`;
 *     and `scripts/it_load.sql`, a file node.
 *   - UPDATE OF STATUS: `IT_SET_STATUS` (sets `status`); `IT_MERGE` again (its update clause sets `status`: the probe
 *     shows a MERGE fires `UPDATE OF` by its SET list, as an UPDATE does); `IT_SET_ROW` (`set row = r`: its columns are not
 *     known, so the hop is not certain). `IT_SET_AMOUNT` sets only `amount`: not a hop, not a node.
 *   - DELETE: `IT_DELETE`, and `IT_WRITE_SYN` through the private synonym `IT_ORDERS_SYN`.
 *   - the INSTEAD OF trigger: `IT_WRITE_VIEW_IO`. That insert does not fire `TRG_IT_ORDERS_BI`: Oracle runs the
 *     INSTEAD OF trigger instead.
 *   - `TRG_IT_PONG`: `TRG_IT_PING`, which writes `IT_PONG`.
 *   - `TRG_IT_UNWRITTEN`: nothing writes `IT_UNWRITTEN`. `TRG_IT_LOGON` is not fired by writes.
 *   Not nodes: `IT_WRITE_CALC` writes the view column `MIXED`, computed from two columns, which the chain does not take
 *   to `IT_ORDERS`; `IT_TRUNCATE` truncates, which fires no DML trigger. So 12 nodes.
 * - depth 3: `IT_Q` calls `IT_P`; `IT_STAGE_LOAD` inserts into `IT_STAGE`, which fires `TRG_IT_STAGE`. `TRG_IT_PING`'s
 *   writer is `TRG_IT_PONG`, already a node: an edge, not a node. So 2 nodes.
 * Stops: `TRG_IT_PING` and `TRG_IT_PONG` only write each other's tables, so both are entry points; `TRG_IT_UNWRITTEN`
 * is one because nothing writes its table; `TRG_IT_LOGON` keeps TRIGGER. Followed: the four triggers with writers, `IT_P`
 * and `TRG_IT_STAGE`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TriggerImpactTest {
    private val temp: Path = Files.createTempDirectory("atlas-trigger-impact-test")
    private lateinit var index: AtlasIndex

    private val audit = "db:HR.IT_AUDIT(P_WHAT IN VARCHAR2)"

    @BeforeAll
    fun build() {
        index = AtlasIndex(Fixture.index(temp.resolve("fixture.db")), Fixture.sources)
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private fun graph(key: String = audit, followTriggers: Boolean = true, maxDepth: Int = 10) =
        index.impact(key, maxDepth = maxDepth, followTriggers = followTriggers).result!!

    /** Each node as `depth stop certain name <- uses @ file:line status fires`. */
    private fun ImpactGraph.lines() = nodes.map {
        "${it.depth} ${it.stop} ${it.certain} ${it.entity?.key ?: it.path} <- ${it.via.uses} @ ${it.via.path}:${it.via.line} ${it.via.status}" +
            (it.via.fires?.let { fires -> " fires $fires" } ?: "")
    }

    @Test
    fun `a trigger is followed through the writes that fire it`() {
        val graph = graph()
        assertThat(graph.levels).containsExactly(7, 12, 2)
        assertThat(graph.stops.map { "${it.value} ${it.count}" }).containsExactly("FOLLOWED 6", "ENTRY_POINT 13", "TRIGGER 1", "NO_CONTAINER 1")
        assertThat(graph.lines()).containsExactly(
            "1 TRIGGER true db:HR.TRG_IT_LOGON <- $audit @ hr/it_triggers.trg:60 RESOLVED",
            "1 FOLLOWED true db:HR.TRG_IT_ORDERS_BI <- $audit @ hr/it_triggers.trg:5 RESOLVED",
            "1 FOLLOWED true db:HR.TRG_IT_ORDERS_DEL <- $audit @ hr/it_triggers.trg:18 RESOLVED",
            "1 FOLLOWED true db:HR.TRG_IT_ORDERS_STATUS <- $audit @ hr/it_triggers.trg:12 RESOLVED",
            "1 ENTRY_POINT true db:HR.TRG_IT_PONG <- $audit @ hr/it_triggers.trg:54 RESOLVED",
            "1 ENTRY_POINT true db:HR.TRG_IT_UNWRITTEN <- $audit @ hr/it_triggers.trg:32 RESOLVED",
            "1 FOLLOWED true db:HR.TRG_IT_V_IO <- $audit @ hr/it_triggers.trg:25 RESOLVED",
            "2 ENTRY_POINT true db:HR.IT_DELETE(P_ID IN NUMBER) <- db:HR.TRG_IT_ORDERS_DEL @ hr/it_writers.sql:28 RESOLVED fires DELETE",
            "2 ENTRY_POINT true db:HR.IT_DYN(P_ID IN NUMBER) <- db:HR.TRG_IT_ORDERS_BI @ hr/it_writers.sql:61 RESOLVED fires INSERT",
            "2 ENTRY_POINT true db:HR.IT_MERGE(P_ID IN NUMBER,P_STATUS IN VARCHAR2) <- db:HR.TRG_IT_ORDERS_BI @ hr/it_writers.sql:33 RESOLVED fires INSERT",
            "2 FOLLOWED true db:HR.IT_P(P_ID IN NUMBER) <- db:HR.TRG_IT_ORDERS_BI @ hr/it_writers.sql:3 RESOLVED fires INSERT",
            "2 ENTRY_POINT false db:HR.IT_SET_ROW(R IN IT_ORDERS%ROWTYPE) <- db:HR.TRG_IT_ORDERS_STATUS @ hr/it_writers.sql:23 RESOLVED fires UPDATE",
            "2 ENTRY_POINT true db:HR.IT_SET_STATUS(P_ID IN NUMBER) <- db:HR.TRG_IT_ORDERS_STATUS @ hr/it_writers.sql:13 RESOLVED fires UPDATE",
            "2 ENTRY_POINT true db:HR.IT_WRITE_SYN(P_ID IN NUMBER) <- db:HR.TRG_IT_ORDERS_DEL @ hr/it_writers.sql:56 RESOLVED fires DELETE",
            "2 ENTRY_POINT true db:HR.IT_WRITE_VIEW(P_ID IN NUMBER) <- db:HR.TRG_IT_ORDERS_BI @ hr/it_writers.sql:46 RESOLVED fires INSERT",
            "2 ENTRY_POINT true db:HR.IT_WRITE_VIEW_IO(P_ID IN NUMBER) <- db:HR.TRG_IT_V_IO @ hr/it_writers.sql:41 RESOLVED fires INSERT",
            "2 ENTRY_POINT true db:HR.TRG_IT_PING <- db:HR.TRG_IT_PONG @ hr/it_triggers.trg:46 RESOLVED fires INSERT",
            "2 FOLLOWED true db:HR.TRG_IT_STAGE <- db:HR.TRG_IT_ORDERS_BI @ hr/it_triggers.trg:39 RESOLVED fires INSERT",
            "2 NO_CONTAINER true scripts/it_load.sql <- db:HR.TRG_IT_ORDERS_BI @ scripts/it_load.sql:1 RESOLVED fires INSERT",
            "3 ENTRY_POINT true db:HR.IT_Q() <- db:HR.IT_P(P_ID IN NUMBER) @ hr/it_writers.sql:8 RESOLVED",
            "3 ENTRY_POINT true db:HR.IT_STAGE_LOAD(P_ID IN NUMBER) <- db:HR.TRG_IT_STAGE @ hr/it_writers.sql:71 RESOLVED fires INSERT"
        )
    }

    @Test
    fun `a write that does not fire the trigger, a view column with two bases and a truncate are not hops`() {
        val keys = graph().nodes.mapNotNull { it.entity?.key }
        assertThat(keys).doesNotContain(
            "db:HR.IT_SET_AMOUNT(P_ID IN NUMBER)", "db:HR.IT_WRITE_CALC(P_ID IN NUMBER)", "db:HR.IT_TRUNCATE()"
        )
    }

    /** The writers of [trigger] by container, with what they fire and whether the hop is sure. */
    private fun writers(trigger: String): List<String> = java.sql.DriverManager.getConnection("jdbc:sqlite:${index.database}").use { connection ->
        val id = connection.query("SELECT id FROM entity WHERE key = ?", listOf(trigger)) { it.getLong(1) }.single()
        TriggerWrites(connection).firing(listOf(id)).map { write ->
            val container = write.container?.let { c -> connection.query("SELECT key FROM entity WHERE id = ?", listOf(c)) { it.getString(1) }.single() }
            "${container ?: write.path} ${write.fires} ${write.sure}"
        }.sorted()
    }

    @Test
    fun `each trigger's events against each write's statement`() {
        // INSERT: the insert, the MERGE's insert clause, the view with one base, the dynamic literal, the trigger, the script.
        assertThat(writers("db:HR.TRG_IT_ORDERS_BI")).containsExactly(
            "db:HR.IT_DYN(P_ID IN NUMBER) INSERT true",
            "db:HR.IT_MERGE(P_ID IN NUMBER,P_STATUS IN VARCHAR2) INSERT true",
            "db:HR.IT_P(P_ID IN NUMBER) INSERT true",
            "db:HR.IT_WRITE_VIEW(P_ID IN NUMBER) INSERT true",
            "db:HR.TRG_IT_STAGE INSERT true",
            "scripts/it_load.sql INSERT true"
        )
        // UPDATE OF STATUS: the MERGE's update clause sets STATUS; SET ROW is not known; SET AMOUNT does not fire it.
        assertThat(writers("db:HR.TRG_IT_ORDERS_STATUS")).containsExactly(
            "db:HR.IT_MERGE(P_ID IN NUMBER,P_STATUS IN VARCHAR2) UPDATE true",
            "db:HR.IT_SET_ROW(R IN IT_ORDERS%ROWTYPE) UPDATE false",
            "db:HR.IT_SET_STATUS(P_ID IN NUMBER) UPDATE true"
        )
        // DELETE: directly and through the private synonym. The truncate is not a hop.
        assertThat(writers("db:HR.TRG_IT_ORDERS_DEL")).containsExactly(
            "db:HR.IT_DELETE(P_ID IN NUMBER) DELETE true",
            "db:HR.IT_WRITE_SYN(P_ID IN NUMBER) DELETE true"
        )
        assertThat(writers("db:HR.TRG_IT_V_IO")).containsExactly("db:HR.IT_WRITE_VIEW_IO(P_ID IN NUMBER) INSERT true")
        assertThat(writers("db:HR.TRG_IT_UNWRITTEN")).isEmpty()
        assertThat(writers("db:HR.TRG_IT_LOGON")).isEmpty()
    }

    @Test
    fun `a trigger nothing writes is an entry point with its table and events, and a system trigger keeps its stop`() {
        val nodes = graph().nodes.associateBy { it.entity?.key }
        val unwritten = nodes.getValue("db:HR.TRG_IT_UNWRITTEN")
        assertThat(unwritten.stop).isEqualTo("ENTRY_POINT")
        assertThat(unwritten.trigger!!.table!!.key).isEqualTo("db:HR.IT_UNWRITTEN")
        assertThat(unwritten.trigger!!.events.map { "${it.timing} ${it.level} ${it.event}" }).containsExactly("AFTER ROW INSERT")
        val logon = nodes.getValue("db:HR.TRG_IT_LOGON")
        assertThat(logon.stop).isEqualTo("TRIGGER")
        assertThat(logon.trigger!!.table).isNull()
        assertThat(logon.trigger!!.events.map { it.event }).containsExactly("LOGON")
        assertThat(nodes.getValue("db:HR.TRG_IT_ORDERS_STATUS").trigger!!.events.map { "${it.event} ${it.column?.name}" }).containsExactly("UPDATE STATUS")
    }

    @Test
    fun `a trigger at the depth limit stops there, and its writers are one hop further`() {
        val shallow = graph(maxDepth = 1)
        assertThat(shallow.stops.map { "${it.value} ${it.count}" }).containsExactly("TRIGGER 1", "DEPTH_LIMIT 6")
        assertThat(graph(maxDepth = 2).levels).containsExactly(7, 12)
    }

    @Test
    fun `without following triggers, every trigger stops as before and nothing reaches its writers`() {
        val off = graph(followTriggers = false)
        assertThat(off.levels).containsExactly(7)
        assertThat(off.stops.map { "${it.value} ${it.count}" }).containsExactly("TRIGGER 7")
        assertThat(off.nodes.map { it.via.fires }).containsOnlyNulls()
        // The trigger events are still given.
        assertThat(off.nodes.single { it.entity?.key == "db:HR.TRG_IT_UNWRITTEN" }.trigger!!.table!!.key).isEqualTo("db:HR.IT_UNWRITTEN")
    }

    @Test
    fun `a DML trigger asked for has the writes that fire it as its first hop, and a system trigger has none`() {
        val bi = "db:HR.TRG_IT_ORDERS_BI"
        assertThat(graph(bi).lines()).containsExactly(
            "1 ENTRY_POINT true db:HR.IT_DYN(P_ID IN NUMBER) <- $bi @ hr/it_writers.sql:61 RESOLVED fires INSERT",
            "1 ENTRY_POINT true db:HR.IT_MERGE(P_ID IN NUMBER,P_STATUS IN VARCHAR2) <- $bi @ hr/it_writers.sql:33 RESOLVED fires INSERT",
            "1 FOLLOWED true db:HR.IT_P(P_ID IN NUMBER) <- $bi @ hr/it_writers.sql:3 RESOLVED fires INSERT",
            "1 ENTRY_POINT true db:HR.IT_WRITE_VIEW(P_ID IN NUMBER) <- $bi @ hr/it_writers.sql:46 RESOLVED fires INSERT",
            "1 FOLLOWED true db:HR.TRG_IT_STAGE <- $bi @ hr/it_triggers.trg:39 RESOLVED fires INSERT",
            "1 NO_CONTAINER true scripts/it_load.sql <- $bi @ scripts/it_load.sql:1 RESOLVED fires INSERT",
            "2 ENTRY_POINT true db:HR.IT_Q() <- db:HR.IT_P(P_ID IN NUMBER) @ hr/it_writers.sql:8 RESOLVED",
            "2 ENTRY_POINT true db:HR.IT_STAGE_LOAD(P_ID IN NUMBER) <- db:HR.TRG_IT_STAGE @ hr/it_writers.sql:71 RESOLVED fires INSERT"
        )
        assertThat(graph("db:HR.TRG_IT_LOGON").nodes).isEmpty()
        // Without following triggers, nothing calls a trigger: the answer is empty, as before.
        assertThat(graph(bi, followTriggers = false).nodes).isEmpty()
    }

    @Test
    fun `the answer does not change between calls`() {
        assertThat(index.impact(audit)).isEqualTo(index.impact(audit))
    }
}
