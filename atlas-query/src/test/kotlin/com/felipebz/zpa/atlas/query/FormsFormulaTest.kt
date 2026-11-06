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
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

/** The formulas of calculated items, indexed next to the fixture's HR sources: the invented module `calc_desk_fmb.xml`. */
class FormsFormulaTest {
    @TempDir
    lateinit var temp: Path

    private val index by lazy {
        val root = temp.resolve("src")
        val hr = Fixture.sources.resolve("hr")
        Files.walk(hr).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { it.copyTo(root.resolve("hr").resolve(hr.relativize(it).toString()).also { target -> target.parent.createDirectories() }) }
        }
        javaClass.getResourceAsStream("/forms/calc_desk_fmb.xml")!!.use { Files.copy(it, root.resolve("forms").createDirectories().resolve("calc_desk_fmb.xml")) }
        val db = temp.resolve("atlas.db")
        Indexer().index(IndexOptions(root, db, compilationSchema = "HR", dictionary = Fixture.dictionary, threads = 2))
        AtlasIndex(db, root, moduleTexts = { name, bytes -> FormsModuleReaders.BUILT_IN.text(name, bytes) })
    }

    @Test
    fun `a formula parses, and the routines it calls list it by its path`() {
        val file = index.file("forms/calc_desk_fmb.xml").result!!
        assertThat(file.parseStatus).isEqualTo("OK")
        assertThat(file.objects.map { "${it.line} ${it.unitKind} ${it.entity.key}" }).containsExactly(
            "1 FORMS_TRIGGER forms:CALC_DESK/LINES/TOTAL/FORMULA-CALCULATION", "7 FORMS_TRIGGER forms:CALC_DESK/LINES/TOTAL/POST-CHANGE",
            "11 FORMS_TRIGGER forms:CALC_DESK/LINES/RATE/FORMULA-CALCULATION", "20 FORMS_PROGRAM_UNIT forms:CALC_DESK/FN_RATE"
        )
        // A function of the database: its "Used by" lists the formula.
        assertThat(index.occurrences("db:HR.PACK_PROCESS.F_NEXT()").result!!.rows.filter { it.path.startsWith("forms/") }.map { "${it.unit!!.kind} ${it.unit!!.name} ${it.span!!.startLine}" })
            .containsExactly("FORMS_TRIGGER CALC_DESK/LINES/TOTAL/FORMULA-CALCULATION 3")
        // A program unit of the module: the call resolves to it.
        assertThat(index.occurrences("forms:CALC_DESK/FN_RATE").result!!.rows.map { "${it.unit!!.name} ${it.span!!.startLine}" })
            .containsExactly("CALC_DESK/LINES/RATE/FORMULA-CALCULATION 13")
    }
}
