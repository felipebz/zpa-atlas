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
 * What the pages and the usage answers read of what Forms does with data blocks without code, on the invented module
 * `DATA_DESK` (`forms/data_desk_fmb.xml`) against the fixture's dictionary, with two triggers of the database on
 * `COMPANY`, one before an update of `COMPANY_NAME` and one before an update of `ID`: a module-level `ON-INSERT`, which
 * replaces the insert of every block without its own; a block whose DML target is another relation (`COMPANY`), one
 * that does not allow insert and has an `ON-UPDATE` trigger (`EVENTS`), one whose DML target type is `None` (`QUEUE`), a
 * database block without a source (`CONTROL`), one on a procedure (`ORDERS`), one of a type the reader does not know
 * (`ODD`), one with its own `ON-INSERT` (`ORD`) and one on `DUAL` (`D`).
 */
class FormsDataPagesTest {
    @TempDir
    lateinit var temp: Path

    private val index by lazy { index("src", desk()) }

    /** The text of `data_desk_fmb.xml`. */
    private fun desk(): String = javaClass.getResourceAsStream("/forms/data_desk_fmb.xml")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

    private fun index(folder: String, module: String): AtlasIndex {
        val root = temp.resolve(folder).createDirectories()
        Files.writeString(root.resolve("data_desk_fmb.xml"), module)
        Files.writeString(root.resolve("trg_company_update.trg"), """
            CREATE OR REPLACE TRIGGER trg_company_name BEFORE UPDATE OF company_name ON company FOR EACH ROW
            BEGIN
              NULL;
            END;
            /
            CREATE OR REPLACE TRIGGER trg_company_id BEFORE UPDATE OF id ON company FOR EACH ROW
            BEGIN
              NULL;
            END;
            /
            """.trimIndent())
        val db = temp.resolve("$folder.db")
        Indexer().index(IndexOptions(root, db, compilationSchema = "HR", dictionary = Fixture.dictionary, threads = 2))
        return AtlasIndex(db, root, moduleTexts = { name, bytes -> FormsModuleReaders.BUILT_IN.text(name, bytes) })
    }

    private fun block(name: String) = index.entity("forms:DATA_DESK/$name").result!!.formsBlock!!

    /** Each operation of [block] as `OPERATION STATE`, with the replacing trigger's key. */
    private fun operations(block: FormsBlockData) = block.operations.map { "${it.operation} ${it.state}" + (it.trigger?.let { t -> " ${t.key}" } ?: "") }

    private fun source(source: FormsDataSource) = "${source.type} ${source.name} ${source.entity?.key}"

    /** The `FORMS_IMPLICIT` rows of [rows] as `container roles entity path`, checking they have no line. */
    private fun implicit(rows: List<OccurrenceRow>) = rows.filter { it.origin == "FORMS_IMPLICIT" }.map {
        assertThat(listOf(it.span, it.unit, it.code, it.statementKind)).containsOnlyNulls()
        "${it.container!!.key} ${it.roles.joinToString(",")} ${it.entity?.key} ${it.path}"
    }

    @Test
    fun `a block on a table names what it queries and writes, and Forms does each operation it allows`() {
        val company = block("COMPANY")
        assertThat(source(company.source)).isEqualTo("TABLE V_COMPANY db:HR.V_COMPANY")
        assertThat(source(company.target)).isEqualTo("TABLE COMPANY db:HR.COMPANY")
        // The module's ON-INSERT replaces the insert of a block without its own.
        assertThat(operations(company)).containsExactly("QUERY DONE", "INSERT REPLACED forms:DATA_DESK/ON-INSERT", "UPDATE DONE", "DELETE DONE")
        assertThat(company.where to company.orderBy).isEqualTo("st_active = 'Y'" to "company_name")
        // Not allowed comes first; a block trigger replaces an operation it allows.
        val events = block("EVENTS")
        assertThat(source(events.target)).isEqualTo("TABLE EVENT_LOG db:HR.EVENT_LOG")
        assertThat(operations(events)).containsExactly("QUERY DONE", "INSERT NOT_ALLOWED", "UPDATE REPLACED forms:DATA_DESK/EVENTS/ON-UPDATE", "DELETE DONE")
        // A block's own trigger comes before the module's.
        assertThat(operations(block("ORD")))
            .containsExactly("QUERY DONE", "INSERT REPLACED forms:DATA_DESK/ORD/ON-INSERT", "UPDATE DONE", "DELETE DONE")
        assertThat(events.where to events.orderBy).isEqualTo("" to "")
        // A DML target of type None: the block only queries; what it allows beside is not followed.
        val queue = block("QUEUE")
        assertThat(source(queue.target)).isEqualTo("NONE SEND_QUEUE null")
        assertThat(operations(queue))
            .containsExactly("QUERY DONE", "INSERT REPLACED forms:DATA_DESK/ON-INSERT", "UPDATE NOT_FOLLOWED", "DELETE NOT_FOLLOWED")
        // Database blocks on no table (DUAL is none, as in SQL): nothing is followed, but the module's ON-INSERT still runs.
        for (name in listOf("CONTROL", "ORDERS", "ODD", "D")) {
            assertThat(operations(block(name))).describedAs(name)
                .containsExactly("QUERY NOT_FOLLOWED", "INSERT REPLACED forms:DATA_DESK/ON-INSERT", "UPDATE NOT_FOLLOWED", "DELETE NOT_FOLLOWED")
        }
        assertThat(source(block("ORDERS").source)).isEqualTo("PROCEDURE order_api.query_orders null")
        assertThat(source(block("ODD").source)).isEqualTo("UNKNOWN IT_ORDERS null")
        assertThat(source(block("D").source)).isEqualTo("TABLE DUAL null")
        assertThat(index.entity("forms:DATA_DESK/COMPANY/ID").result!!.formsBlock).isNull()
        assertThat(index.entity("db:HR.COMPANY").result!!.let { it.formsBlock to it.formsItem }).isEqualTo(null to null)
    }

    @Test
    fun `an operation is done exactly where the block has its implicit use`() {
        val roles = mapOf("SQL_SELECT" to "QUERY", "SQL_INSERT" to "INSERT", "SQL_UPDATE" to "UPDATE", "SQL_DELETE" to "DELETE")
        for (name in listOf("COMPANY", "EVENTS", "QUEUE", "CONTROL", "ORDERS", "ODD", "ORD", "D")) {
            val key = "forms:DATA_DESK/$name"
            val uses = index.queryOccurrences(OccurrenceQuery(container = key, containerInside = false, origins = setOf("FORMS_IMPLICIT"))).result.rows
            val done = block(name).operations.filter { it.state == "DONE" }.map { it.operation }
            assertThat(uses.flatMap { row -> row.roles.mapNotNull { roles[it] } }).describedAs(name).containsExactlyInAnyOrderElementsOf(done)
        }
    }

    @Test
    fun `a non-database block has no data section`() {
        val free = index("free", desk().replace(
            "Name=\"CONTROL\" DATA_DESK_default:DatabaseBlock=\"true\"", "Name=\"CONTROL\" DATA_DESK_overridden:DatabaseBlock=\"false\""))
        val control = free.entity("forms:DATA_DESK/CONTROL").result!!
        assertThat(control.kind).isEqualTo("FORMS_BLOCK")
        assertThat(control.formsBlock).isNull()
        assertThat(free.entity("forms:DATA_DESK/COMPANY").result!!.formsBlock).isNotNull()
    }

    @Test
    fun `an item names its column and says what Forms does with it`() {
        fun item(name: String) = index.entity("forms:DATA_DESK/$name").result!!.formsItem!!
        fun summary(item: FormsItemData) = "${item.database} ${item.kind} [${item.columnName}] ${item.column?.key} ${item.targetColumn?.key} " +
            "${item.read} ${item.insertWrite} ${item.updateWrite}"
        // ID does not allow update; NAME stands for COMPANY_NAME; the module's ON-INSERT replaces their insert.
        assertThat(summary(item("COMPANY/ID"))).isEqualTo("true TEXT_ITEM [] db:HR.V_COMPANY.ID db:HR.COMPANY.ID true false false")
        assertThat(summary(item("COMPANY/NAME")))
            .isEqualTo("true TEXT_ITEM [COMPANY_NAME] db:HR.V_COMPANY.COMPANY_NAME db:HR.COMPANY.COMPANY_NAME true false true")
        assertThat(summary(item("COMPANY/LABEL"))).isEqualTo("false DISPLAY_ITEM [] null null false false false")
        assertThat(summary(item("COMPANY/SAVE"))).isEqualTo("true PUSH_BUTTON [] null null false false false")
        assertThat(summary(item("COMPANY/LEVEL"))).isEqualTo("true UNKNOWN [] null null false false false")
        // EVENTS does not insert and its update is replaced: its items are only read; the target is the source.
        assertThat(summary(item("EVENTS/ARCHIVED"))).isEqualTo("true CHECK_BOX [ARCHIVED] db:HR.EVENT_LOG.ARCHIVED null true false false")
        assertThat(summary(item("ORDERS/ID"))).isEqualTo("true TEXT_ITEM [] null null false false false")
        assertThat(summary(item("ORD/STATUS"))).isEqualTo("true TEXT_ITEM [] db:HR.IT_ORDERS.STATUS null true false true")
        assertThat(summary(item("D/DUMMY"))).isEqualTo("true TEXT_ITEM [] null null false false false")
        assertThat(index.entity("forms:DATA_DESK/COMPANY").result!!.formsItem).isNull()
    }

    @Test
    fun `the uses of a table and of a column list the implicit uses of the block and the item, without a line`() {
        val company = index.occurrences("db:HR.COMPANY").result!!
        assertThat(implicit(company.rows)).containsExactly(
            "forms:DATA_DESK/COMPANY IMPLICIT,SQL_UPDATE db:HR.COMPANY data_desk_fmb.xml",
            "forms:DATA_DESK/COMPANY IMPLICIT,SQL_DELETE db:HR.COMPANY data_desk_fmb.xml"
        )
        assertThat(company.facets.byRole.map { it.value }).contains("IMPLICIT", "SQL_UPDATE", "SQL_DELETE")
        assertThat(implicit(index.occurrences("db:HR.V_COMPANY.ID").result!!.rows))
            .containsExactly("forms:DATA_DESK/COMPANY/ID IMPLICIT,READ db:HR.V_COMPANY.ID data_desk_fmb.xml")
        // With the columns, and by side: a use in no unit is outside the table.
        val withColumns = index.occurrences("db:HR.COMPANY", UsageFilter(scope = setOf(Scope.SELF, Scope.CHILDREN)), sides = true).result!!
        assertThat(implicit(withColumns.rows)).contains("forms:DATA_DESK/COMPANY/NAME IMPLICIT,SQL_UPDATE,WRITE db:HR.COMPANY.COMPANY_NAME data_desk_fmb.xml")
        assertThat(implicit(withColumns.rows)).noneMatch { "SQL_INSERT" in it }
        assertThat(index.occurrences("db:HR.COMPANY", side = UsageSide.OUTSIDE).result!!.total).isEqualTo(company.total)
        // The occurrence query and the groups count them too, in no unit.
        val query = OccurrenceQuery(origins = setOf("FORMS_IMPLICIT"), path = "data_desk_fmb.xml")
        val found = index.queryOccurrences(query, limit = 500).result
        assertThat(found.total).isEqualTo(found.rows.size.toLong()).isEqualTo(21)
        assertThat(found.facets.byOrigin).containsExactly(Count("FORMS_IMPLICIT", 21))
        assertThat(found.facets.byUnitKind).isEmpty()
        val byUnit = index.groupOccurrences(query, listOf(GroupDimension.UNIT, GroupDimension.CONTAINER)).result
        assertThat(byUnit.groups.map { it.values.first() }.distinct()).containsExactly(GroupValue())
        assertThat(byUnit.groups.map { it.values.last().entity!!.key }).contains("forms:DATA_DESK/COMPANY", "forms:DATA_DESK/COMPANY/NAME")
        val grouped = index.groupUsages("db:HR.COMPANY", listOf(GroupDimension.UNIT, GroupDimension.ORIGIN)).result!!
        assertThat(grouped.groups.filter { it.values.last().text == "FORMS_IMPLICIT" }.map { it.values.first() to it.count }).containsExactly(GroupValue() to 2L)
        // A use without a line has no line to show, and is on none of the file's.
        assertThat(index.occurrenceLines(found.rows.map { it.id }).result).isEmpty()
        assertThat(index.file("data_desk_fmb.xml").result!!.occurrences).allSatisfy { assertThat(it.at).hasSize(4) }
    }

    @Test
    fun `the impact of a table reaches the block through its implicit use, and its triggers through the block's update`() {
        val graph = index.impact("db:HR.COMPANY", ImpactOptions(followTriggers = true)).result!!
        val node = graph.nodes.single { it.entity?.key == "forms:DATA_DESK/COMPANY" }
        assertThat(listOf(node.kind, node.depth, node.via.uses, node.via.path, node.via.line, node.via.origin, node.via.code, node.certain))
            .containsExactly("FORMS_BLOCK", 1, "db:HR.COMPANY", "data_desk_fmb.xml", null, "FORMS_IMPLICIT", null, true)
        // The view of the whole graph says the same.
        val view = index.impactView("db:HR.COMPANY").result!!
        val viewed = view.nodes.single { it.entity != null && view.entities[it.entity].key == "forms:DATA_DESK/COMPANY" }
        assertThat(listOf(viewed.via.line, viewed.via.origin, view.paths[viewed.via.path])).containsExactly(null, "FORMS_IMPLICIT", "data_desk_fmb.xml")
        // The block's update sets COMPANY_NAME (ID does not allow update): it fires UPDATE OF COMPANY_NAME, not UPDATE OF ID.
        val name = index.impact("db:HR.TRG_COMPANY_NAME").result!!
        assertThat(name.nodes.map { "${it.entity?.key} ${it.via.fires} ${it.via.line} ${it.via.origin} ${it.certain}" })
            .containsExactly("forms:DATA_DESK/COMPANY UPDATE null FORMS_IMPLICIT true")
        assertThat(index.impact("db:HR.TRG_COMPANY_ID").result!!.nodes).isEmpty()
    }
}
