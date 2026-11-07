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

/** The answers about Forms code, from the fixture's HR sources and the invented modules `order_desk_fmb.xml` and `order_lite_fmb.xml`. */
class FormsAnswersTest {
    @TempDir
    lateinit var temp: Path

    private val texts = ModuleTexts { name, bytes -> FormsModuleReaders.BUILT_IN.text(name, bytes) }

    private val sources: Path by lazy {
        val root = temp.resolve("src")
        val hr = Fixture.sources.resolve("hr")
        Files.walk(hr).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { it.copyTo(root.resolve("hr").resolve(hr.relativize(it).toString()).also { target -> target.parent.createDirectories() }) }
        }
        for (name in listOf("order_desk_fmb.xml", "order_lite_fmb.xml")) {
            javaClass.getResourceAsStream("/forms/$name")!!.use { Files.copy(it, root.resolve("forms").createDirectories().resolve(name)) }
        }
        // Another form whose Name is ORDER_DESK: known as FRONT_DESK.
        javaClass.getResourceAsStream("/forms/order_desk_fmb.xml")!!.use { Files.copy(it, root.resolve("forms/front_desk_fmb.xml")) }
        root
    }

    private val index by lazy {
        val db = temp.resolve("atlas.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "HR", dictionary = Fixture.dictionary, threads = 2))
        AtlasIndex(db, sources, moduleTexts = texts)
    }

    /** The units of the uses of [key] (and with [scope], of its members) in the Forms modules, as `kind path`; a use without code has none. */
    private fun formsUnits(key: String, scope: Set<Scope> = setOf(Scope.SELF)) =
        index.occurrences(key, UsageFilter(scope = scope)).result!!.rows.filter { it.path.startsWith("forms/order_") }.mapNotNull { it.unit }.map { "${it.kind} ${it.name}" }.distinct()

    @Test
    fun `the uses of a table, a package and a procedure of the database list the Forms units by path`() {
        assertThat(formsUnits("db:HR.COMPANY")).containsExactly(
            "FORMS_TRIGGER ORDER_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM", "FORMS_PROGRAM_UNIT ORDER_DESK/PROC_STANDALONE", "FORMS_PROGRAM_UNIT ORDER_DESK/FN_LABEL",
            "SQL_STATEMENT ORDER_DESK/COMPANY_LOV"
        )
        assertThat(formsUnits("db:HR.PACK_PROCESS", setOf(Scope.SELF, Scope.CHILDREN))).containsExactly("FORMS_PROGRAM_UNIT ORDER_DESK/ORDER_UTIL")
        // ORDER_DESK calls its own PROC_STANDALONE: only ORDER_LITE calls HR's.
        assertThat(formsUnits("db:HR.PROC_STANDALONE(P_ID IN NUMBER)")).containsExactly("FORMS_TRIGGER ORDER_LITE/WHEN-NEW-FORM-INSTANCE")
        assertThat(index.occurrences("forms:ORDER_DESK/PROC_STANDALONE").result!!.rows.map { "${it.unit!!.name} ${it.span!!.startLine}" })
            .containsExactly("ORDER_DESK/WHEN-NEW-FORM-INSTANCE 26")
    }

    @Test
    fun `a Forms entity is found, has a page, and its module lists its objects`() {
        assertThat(index.searchEntities("ORDER_UTIL").result.map { "${it.kind} ${it.key}" })
            .containsExactlyInAnyOrder("FORMS_PROGRAM_UNIT forms:ORDER_DESK/ORDER_UTIL", "FORMS_PROGRAM_UNIT forms:FRONT_DESK/ORDER_UTIL")
        val entity = index.entity("forms:ORDER_DESK/ORDER_UTIL").result!!
        assertThat(entity.ancestors.map { it.key }).containsExactly("forms:ORDER_DESK")
        assertThat(entity.children.map { it.entity.key }).contains("forms:ORDER_DESK/ORDER_UTIL.G_REFRESHED")
        assertThat(entity.definitions.map { "${it.path} ${it.span.startLine}" }).containsExactly("forms/order_desk_fmb.xml 46", "forms/order_desk_fmb.xml 52")
        // A module is known by its file's name; its page shows its Name when that is another.
        assertThat(index.entity("forms:FRONT_DESK").result!!.declared?.value).isEqualTo("ORDER_DESK")
        assertThat(index.file("forms/front_desk_fmb.xml").result!!.lines.first()).isEqualTo("BEGIN -- FRONT_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM")
        val module = index.entity("forms:ORDER_DESK").result!!
        assertThat(module.children.map { it.entity.key }).containsExactly(
            "forms:ORDER_DESK/COMPANY", "forms:ORDER_DESK/FN_LABEL", "forms:ORDER_DESK/ORDER_UTIL", "forms:ORDER_DESK/PROC_STANDALONE",
            "forms:ORDER_DESK/COMPANY_LOV", "forms:ORDER_DESK/WHEN-NEW-FORM-INSTANCE"
        )
        assertThat(module.relations.map { "${it.kind} ${it.entity.key}" }).contains("FORMS_ATTACHED_LIBRARY forms:ORDER_DESK/ORDER_LIB")
    }

    @Test
    fun `the server serves the module text the index read, with its objects and occurrence lines`() {
        val path = "forms/order_desk_fmb.xml"
        val file = index.file(path).result!!
        assertThat(file.stale).isFalse()
        assertThat(file.parseStatus).isEqualTo("OK")
        // Every line the index stored is the served line of the same number.
        val stored = Fixture.query(index.database, "SELECT l.line, l.text FROM source_line l JOIN source_file f ON f.id = l.file_id WHERE f.path = '$path'")
            .map { it.split('\t', limit = 2) }
        assertThat(stored).hasSize(56)
        assertThat(stored.filter { (line, text) -> file.lines[line.toInt() - 1] != text }).isEmpty()
        assertThat(file.objects.map { "${it.line} ${it.unitKind} ${it.entity.key}" }).containsExactly(
            "1 FORMS_TRIGGER forms:ORDER_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM", "10 FORMS_TRIGGER forms:ORDER_DESK/COMPANY/POST-QUERY",
            "20 FORMS_TRIGGER forms:ORDER_DESK/COMPANY/KEY-EXIT", "24 FORMS_TRIGGER forms:ORDER_DESK/WHEN-NEW-FORM-INSTANCE",
            "31 FORMS_PROGRAM_UNIT forms:ORDER_DESK/PROC_STANDALONE", "38 FORMS_PROGRAM_UNIT forms:ORDER_DESK/FN_LABEL",
            "46 FORMS_PROGRAM_UNIT forms:ORDER_DESK/ORDER_UTIL", "52 FORMS_PROGRAM_UNIT forms:ORDER_DESK/ORDER_UTIL",
            "63 SQL_STATEMENT forms:ORDER_DESK/COMPANY_LOV"
        )
        val call = index.occurrences("forms:ORDER_DESK/FN_LABEL").result!!.rows.single()
        assertThat(index.occurrenceLines(listOf(call.id)).result.single().text).isEqualTo("\tv_label := fn_label(:company.id);")
    }
}
