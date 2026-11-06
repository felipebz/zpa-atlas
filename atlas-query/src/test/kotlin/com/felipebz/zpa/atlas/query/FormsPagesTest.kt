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
 * What the pages of Forms objects read: the module of an object, the tree of a module, a block or an item, and the
 * code page of a module, with the own code of a trigger, a formula, a program unit and a record group query. On the Doag modules `demo0002_fmb.xml`,
 * `demo0004_fmb.xml` and `modernize_fmb.xml` and the invented `calc_desk_fmb.xml` and `order_read_fmb.xml` (whose
 * program unit P_READ reads the table ORDERS of `orders.sql`).
 */
class FormsPagesTest {
    @TempDir
    lateinit var temp: Path

    private val index by lazy {
        val root = temp.resolve("src").createDirectories()
        for (name in listOf("doag/demo0002_fmb.xml", "doag/demo0004_fmb.xml", "doag/modernize_fmb.xml", "calc_desk_fmb.xml")) {
            javaClass.getResourceAsStream("/forms/$name")!!.use { Files.copy(it, root.resolve(name.substringAfter('/'))) }
        }
        val db = temp.resolve("atlas.db")
        Indexer().index(IndexOptions(root, db, compilationSchema = "HR", dictionary = Fixture.dictionary, threads = 2))
        AtlasIndex(db, root, moduleTexts = { name, bytes -> FormsModuleReaders.BUILT_IN.text(name, bytes) })
    }

    /** `order_read_fmb.xml` and the table it reads, `orders.sql`, indexed alone and without a dictionary: the table is the sources'. */
    private val orders by lazy {
        val root = temp.resolve("orders").createDirectories()
        javaClass.getResourceAsStream("/forms/order_read_fmb.xml")!!.use { Files.copy(it, root.resolve("order_read_fmb.xml")) }
        Files.writeString(root.resolve("orders.sql"), "CREATE TABLE orders (id NUMBER, status VARCHAR2(10));\n")
        val db = temp.resolve("orders.db")
        Indexer().index(IndexOptions(root, db, compilationSchema = "HR", threads = 2))
        AtlasIndex(db, root, moduleTexts = { name, bytes -> FormsModuleReaders.BUILT_IN.text(name, bytes) })
    }

    /** [tree] as lines: each group, then its objects by name (a package's halves with their kind), indented by depth. */
    private fun lines(groups: List<FormsGroup>, depth: Int = 0): List<String> = groups.flatMap { group ->
        listOf("  ".repeat(depth) + group.kind) + group.nodes.flatMap { node ->
            listOf("  ".repeat(depth + 1) + node.entity.name + (node.unitKind?.let { " $it" } ?: "")) + lines(node.groups, depth + 2)
        }
    }

    @Test
    fun `a module's title is its internal name, and every object of it knows the module and its file`() {
        val module = index.entity("forms:DEMO0004").result!!
        assertThat(module.forms).isEqualTo(FormsInfo(module.forms!!.module, "RELEASE_LOCKS", "demo0004_fmb.xml"))
        assertThat(module.forms!!.module.key).isEqualTo("forms:DEMO0004")
        val item = index.entity("forms:DEMO0004/V\$LOCK/ID1").result!!
        assertThat(listOf(item.name, item.display, item.forms?.title, item.forms?.path))
            .containsExactly("ID1", "DEMO0004/V\$LOCK/ID1", "RELEASE_LOCKS", "demo0004_fmb.xml")
        // A module whose internal name is its file's: that name.
        assertThat(index.entity("forms:DEMO0002").result!!.forms?.title).isEqualTo("DEMO0002")
        assertThat(index.entity("db:HR.COMPANY").result!!.forms).isNull()
    }

    @Test
    fun `a module's tree has its groups in the Object Navigator's order and its objects in the file's`() {
        val tree = index.formsTree("forms:DEMO0002").result!!
        assertThat(lines(tree.groups)).containsExactly(
            "TRIGGERS", "  ON-ERROR", "  WHEN-NEW-FORM-INSTANCE", "  WHEN-NEW-RECORD-INSTANCE", "  WHEN-NEW-ITEM-INSTANCE", "  WHEN-NEW-BLOCK-INSTANCE",
            "  WHEN-TIMER-EXPIRED", "LIBRARIES", "  FRW_LIB", "BLOCKS", "  FILTER", "    TRIGGERS",
            "      WHEN-VALIDATE-ITEM", "    ITEMS", "      TI_EMPLOYEE_ID", "      TI_FIRST_NAME", "      TI_LAST_NAME", "      TI_EMAIL",
            "      BT_QUERY", "        TRIGGERS", "          WHEN-BUTTON-PRESSED", "  EMPLOYEES", "    TRIGGERS", "      PRE-QUERY",
            "    ITEMS", "      EMPLOYEE_ID", "      FIRST_NAME", "      LAST_NAME", "      EMAIL", "      PHONE_NUMBER",
            "      JOB_ID", "      SALARY", "      MANAGER_ID", "      DEPARTMENT_ID", "      COMMISSION_PCT", "      HIRE_DATE",
            "  CONTROL", "    ITEMS", "      BT_WIKI", "        TRIGGERS", "          WHEN-BUTTON-PRESSED", "      BT_EXIT",
            "        TRIGGERS", "          WHEN-BUTTON-PRESSED", "PROGRAM_UNITS", "  VERSION_FRW_FORMS", "  CONST_LOCAL PACKAGE", "  WNF",
            "  WNR", "  WNI", "  WNB", "  BL_MAINBLOCK PACKAGE_BODY", "  BL_MAINBLOCK PACKAGE", "  BL_EMPLOYEES PACKAGE",
            "  BL_EMPLOYEES PACKAGE_BODY", "  WHEN_TIMER_EXPIRED"
        )
        // A record group with a query; a block's tree, and an item's.
        assertThat(lines(index.formsTree("forms:MODERNIZE").result!!.groups).filter { !it.startsWith(" ") }).containsExactly("TRIGGERS", "BLOCKS", "RECORD_GROUPS")
        assertThat(lines(index.formsTree("forms:DEMO0004/STEUERBLOCK/PB_EXIT").result!!.groups)).containsExactly("TRIGGERS", "  WHEN-BUTTON-PRESSED")
        assertThat(index.formsTree("forms:DEMO0004/V\$LOCK").result!!.groups.map { it.kind }).containsExactly("TRIGGERS", "ITEMS")
        assertThat(index.formsTree("forms:DEMO0004/V\$LOCK/POST-QUERY").result).isNull()
    }

    /** The code page of the module of [key], showing [key] ([unitKind] for a package's half). */
    private fun page(key: String, unitKind: String? = null) = index.formsFile(index.entity(key).result!!.forms!!.path, key, unitKind).result!!

    /** The own lines of the code [key]. */
    private fun lines(key: String, unitKind: String? = null) = page(key, unitKind).code!!.view.lines

    /** The line [CodeAt] names, from the code's own text. */
    private fun lineOf(code: CodeAt) = page(code.key, code.unitKind).code!!.let {
        assertThat(listOf(it.key, it.unitKind)).containsExactly(code.key, code.unitKind)
        it.view.lines[code.line - 1]
    }

    @Test
    fun `a module's code page has its tree and shows the code asked for, else the first in the tree`() {
        val asked = index.formsFile("demo0004_fmb.xml", "forms:DEMO0004/STEUERBLOCK/PB_EXIT/WHEN-BUTTON-PRESSED").result!!
        assertThat(asked.forms.title).isEqualTo("RELEASE_LOCKS")
        assertThat(asked.tree).isEqualTo(index.formsTree("forms:DEMO0004").result)
        assertThat(asked.code!!.key).isEqualTo("forms:DEMO0004/STEUERBLOCK/PB_EXIT/WHEN-BUTTON-PRESSED")
        // None asked for, or one of another module: the first code in the tree, the module's first trigger.
        val first = asked.tree.groups.first().nodes.first().entity.key
        assertThat(first).isEqualTo("forms:DEMO0004/WHEN-NEW-FORM-INSTANCE")
        assertThat(index.formsFile("demo0004_fmb.xml").result!!.code!!.key).isEqualTo(first)
        assertThat(index.formsFile("demo0004_fmb.xml", "forms:DEMO0002/WNF").result!!.code!!.key).isEqualTo(first)
        // A package's half, by its kind; without one, its specification.
        assertThat(page("forms:DEMO0002/BL_MAINBLOCK", "PACKAGE_BODY").code!!.unitKind).isEqualTo("PACKAGE_BODY")
        assertThat(page("forms:DEMO0002/BL_MAINBLOCK").code!!.unitKind).isEqualTo("PACKAGE")
        // A file that is not a Forms module has no code page.
        assertThat(index.formsFile("company.sql").result).isNull()
    }

    @Test
    fun `each piece of code shows only its own lines, numbered from 1`() {
        assertThat(lines("forms:DEMO0004/STEUERBLOCK/PB_EXIT/WHEN-BUTTON-PRESSED")).containsExactly("Do_Key('Exit_Form');")
        assertThat(lines("forms:CALC_DESK/LINES/RATE/FORMULA-CALCULATION")).containsExactly("fn_rate(", "  :lines.qty)", "  / 100")
        val group = lines(index.formsTree("forms:MODERNIZE").result!!.groups.last().nodes.single().entity.key)
        assertThat(group.first()).startsWithIgnoringCase("select")
        assertThat(group.last()).doesNotEndWith(";").isNotEqualTo(";")
        // A package: its specification and its body, each from line 1, even when the file has the body first.
        for (key in listOf("forms:DEMO0002/BL_EMPLOYEES", "forms:DEMO0002/BL_MAINBLOCK")) {
            assertThat(listOf("PACKAGE", "PACKAGE_BODY").map { lines(key, it).first().trim().uppercase().substringBefore(" IS") })
                .containsExactly("PACKAGE " + key.substringAfter('/'), "PACKAGE BODY " + key.substringAfter('/'))
        }
        // Occurrences are on the code's own numbers.
        val body = page("forms:DEMO0002/BL_EMPLOYEES", "PACKAGE_BODY").code!!.view
        assertThat(body.occurrences).isNotEmpty.allSatisfy { assertThat(it.at[0]).isBetween(1, body.lines.size) }
    }

    @Test
    fun `an occurrence in a piece of code gives that code and its line there`() {
        // The formula calls the module's FN_RATE on its first line, the RATE item's formula's line 1.
        val call = index.occurrences("forms:CALC_DESK/FN_RATE").result!!.rows.single()
        assertThat(call.code).isEqualTo(CodeAt("forms:CALC_DESK/LINES/RATE/FORMULA-CALCULATION", 1))
        // Each use of a program unit of DEMO0002, in triggers and in program units: the line its code gives has the name.
        val rows = index.occurrences("forms:DEMO0002/CONST_LOCAL").result!!.rows
        assertThat(rows.map { it.code!!.key.substringAfter('/') }.distinct())
            .containsExactlyInAnyOrder("FILTER/BT_QUERY/WHEN-BUTTON-PRESSED", "FILTER/WHEN-VALIDATE-ITEM", "WHEN_TIMER_EXPIRED", "WNF")
        for (row in rows) assertThat(lineOf(row.code!!)).describedAs("${row.unit!!.name} ${row.span!!.startLine}").containsIgnoringCase(row.written)
        // A package's halves: its definitions, one in each half, at line 1.
        val definitions = index.entity("forms:DEMO0002/BL_EMPLOYEES").result!!.definitions.map { it.code!! }
        assertThat(definitions).containsExactlyInAnyOrder(
            CodeAt("forms:DEMO0002/BL_EMPLOYEES", 1, "PACKAGE"), CodeAt("forms:DEMO0002/BL_EMPLOYEES", 1, "PACKAGE_BODY")
        )
        // The text search says it too.
        val hit = index.searchText("BL_EMPLOYEES", path = "demo0002_fmb.xml").result.rows.first { it.code?.key == "forms:DEMO0002/BL_EMPLOYEES" }
        assertThat(lineOf(hit.code!!)).containsIgnoringCase("BL_EMPLOYEES")
    }

    @Test
    fun `a comment, a column place, an impact node and a source line in Forms code give that code and its line there`() {
        val unit = "forms:ORDER_READ/P_READ"
        fun lineOf(code: CodeAt) = orders.formsFile("order_read_fmb.xml", code.key, code.unitKind).result!!.code!!.view.lines[code.line - 1]
        // The comment above P_READ is the unit's first line.
        val doc = orders.entity(unit).result!!.documentation.single()
        assertThat(doc.code).isEqualTo(CodeAt(unit, 1))
        assertThat(lineOf(doc.code!!)).contains("Reads the status of an order.")
        // P_READ reads ORDERS.STATUS into v on its line 5: the row's line, and the line it shows.
        val place = orders.columnPlaces("db:HR.ORDERS.STATUS").result!!.rows.single()
        assertThat(listOf(place.source, place.code, place.shown?.code)).containsExactly("SELECT_INTO", CodeAt(unit, 5), CodeAt(unit, 5))
        assertThat(lineOf(place.code!!)).contains("SELECT status INTO v")
        // Its impact: P_READ at that line, then the trigger calling it, on the trigger's line 1.
        val trigger = "forms:ORDER_READ/WHEN-NEW-FORM-INSTANCE"
        val nodes = orders.impact("db:HR.ORDERS.STATUS").result!!.nodes.associate { it.entity!!.key to it.via.code }
        assertThat(nodes).containsExactlyInAnyOrderEntriesOf(mapOf(unit to CodeAt(unit, 5), trigger to CodeAt(trigger, 1)))
        assertThat(orders.impactView("db:HR.ORDERS.STATUS").result!!.nodes.map { it.via.code }).containsExactlyInAnyOrder(CodeAt(unit, 5), CodeAt(trigger, 1))
        // The lines get_source reads; a database file's have none.
        assertThat(orders.source("order_read_fmb.xml", place.line, context = 0).result!!.lines.single().code).isEqualTo(CodeAt(unit, 5))
        assertThat(orders.source("orders.sql", 1).result!!.lines.map { it.code }).containsOnlyNulls()
    }
}
