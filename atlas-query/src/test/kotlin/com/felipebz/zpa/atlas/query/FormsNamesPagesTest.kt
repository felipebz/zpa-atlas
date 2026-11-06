//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.forms.FormsModuleReaders
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * What the pages and the answers read of the names a Forms module's code gives its own objects, on the invented modules
 * `ROUTE_DESK` (`forms/route_desk_fmb.xml`), which sets the global `ROUTE_USER`, reads its parameter `P_CITY`, goes to
 * its blocks and items, runs its user-named triggers and calls `ROUTE_VIEW`, and `ROUTE_VIEW` (`forms/route_view_fmb.xml`),
 * which reads the global and calls `ROUTE_DESK` back.
 */
class FormsNamesPagesTest {
    @TempDir
    lateinit var temp: Path

    private val index by lazy {
        val root = temp.resolve("src").createDirectories()
        for (name in listOf("route_desk_fmb.xml", "route_view_fmb.xml")) {
            javaClass.getResourceAsStream("/forms/$name")!!.use { Files.copy(it, root.resolve(name)) }
        }
        Files.writeString(root.resolve("route_next.sql"), "CREATE OR REPLACE PROCEDURE route_next(p_city OUT VARCHAR2) IS\nBEGIN\n  p_city := 'X';\nEND;\n/\n")
        val db = temp.resolve("atlas.db")
        Indexer().index(IndexOptions(root, db, compilationSchema = "HR", dictionary = Fixture.dictionary, threads = 2))
        AtlasIndex(db, root, moduleTexts = { name, bytes -> FormsModuleReaders.BUILT_IN.text(name, bytes) })
    }

    /** Who uses [key], candidates included: each use's unit, line in the unit's own text, roles and status. */
    private fun usedBy(key: String) = index.occurrences(key).result!!.rows.map { "${it.unit!!.name} ${it.code!!.line} ${it.roles.joinToString(",")} ${it.status}" }

    @Test
    fun `a global's page lists the modules that set it and those that read it`() {
        val global = index.entity("forms::GLOBAL.ROUTE_USER").result!!
        assertThat(listOf(global.kind, global.name, global.display, global.forms)).containsExactly("FORMS_GLOBAL", "ROUTE_USER", ":GLOBAL.ROUTE_USER", null)
        assertThat(global.formsGlobal!!.map { "${it.module.key} ${it.path} ${it.writes} ${it.reads}" })
            .containsExactly("forms:ROUTE_DESK route_desk_fmb.xml 1 0", "forms:ROUTE_VIEW route_view_fmb.xml 0 1")
        assertThat(index.entity("forms:ROUTE_DESK/STOPS/CITY").result!!.formsGlobal).isNull()
    }

    @Test
    fun `an item, a block, a parameter, a trigger and a module are used by the code that names them`() {
        assertThat(usedBy("forms:ROUTE_DESK/STOPS/CITY")).containsExactly(
            "ROUTE_DESK/STOPS/STOP_ID/WHEN-VALIDATE-ITEM 2 WRITE RESOLVED",
            "ROUTE_DESK/STOPS/WHEN-NEW-RECORD-INSTANCE 1 READ RESOLVED",
            "ROUTE_DESK/WHEN-NEW-FORM-INSTANCE 6 READ AMBIGUOUS",
            "ROUTE_DESK/WHEN-NEW-FORM-INSTANCE 7 WRITE RESOLVED"
        )
        assertThat(usedBy("forms:ROUTE_DESK/LEGS")).containsExactly("ROUTE_DESK/WHEN-NEW-FORM-INSTANCE 12 CALL RESOLVED")
        assertThat(usedBy("forms:ROUTE_DESK/PARAMETER/P_CITY")).containsExactly("ROUTE_DESK/WHEN-NEW-FORM-INSTANCE 7 READ RESOLVED")
        assertThat(usedBy("forms:ROUTE_DESK/STOPS/REFRESH_STOP")).containsExactly("ROUTE_DESK/STOPS/STOP_ID/WHEN-VALIDATE-ITEM 4 CALL RESOLVED")
        // The module is called by the other one.
        assertThat(usedBy("forms:ROUTE_DESK")).containsExactly("ROUTE_VIEW/WHEN-NEW-FORM-INSTANCE 2 CALL RESOLVED")
        // The AMBIGUOUS use is one of each candidate's.
        assertThat(index.entity("forms:ROUTE_DESK/LEGS/CITY").result!!.usage.asCandidate).isEqualTo(1L)
    }

    @Test
    fun `a module's tree has its parameters, and its code page names its objects`() {
        val tree = index.formsTree("forms:ROUTE_DESK").result!!
        assertThat(tree.groups.map { it.kind }).containsExactly("TRIGGERS", "BLOCKS", "PARAMETERS")
        assertThat(tree.groups.last().nodes.map { it.entity.key }).containsExactly("forms:ROUTE_DESK/PARAMETER/P_CITY")
        val view = index.formsFile("route_desk_fmb.xml", "forms:ROUTE_DESK/STOPS/WHEN-NEW-RECORD-INSTANCE").result!!.code!!.view
        assertThat(view.occurrences.mapNotNull { o -> o.entity?.let { "${o.at} ${view.entities[it].key}" } }).contains(
            "[1, 0, 1, 5] forms:ROUTE_DESK/STOPS/NOTE", "[1, 9, 1, 14] forms:ROUTE_DESK/STOPS/CITY", "[1, 18, 1, 27] forms:ROUTE_DESK/LEGS/DISTANCE"
        )
    }

    @Test
    fun `the impact of a Forms object follows the code that names it, and the modules that call its module`() {
        // CHECK_STOP runs from WHEN-VALIDATE-ITEM of STOP_ID and from the module's WHEN-NEW-FORM-INSTANCE; ROUTE_DESK runs from ROUTE_VIEW.
        assertThat(index.impact("forms:ROUTE_DESK/STOPS/STOP_ID/CHECK_STOP").result!!.nodes.map { "${it.entity?.key} ${it.depth}" }).containsExactly(
            "forms:ROUTE_DESK/STOPS/STOP_ID/WHEN-VALIDATE-ITEM 1", "forms:ROUTE_DESK/WHEN-NEW-FORM-INSTANCE 1"
        )
        assertThat(index.impact("forms:ROUTE_DESK").result!!.nodes.map { "${it.entity?.key} ${it.depth}" })
            .containsExactly("forms:ROUTE_VIEW/WHEN-NEW-FORM-INSTANCE 1")
    }
}
