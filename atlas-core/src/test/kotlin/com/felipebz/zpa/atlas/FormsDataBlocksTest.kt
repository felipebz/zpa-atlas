//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

/**
 * What Forms does with data blocks without code, on the invented module `DATA_DESK` in English words, exported with
 * `DUMP=ALL` (`forms/data_desk_fmb.xml`) and without it (`forms/data_plain_fmb.xml`, indexed as `DATA_PLAIN`), against
 * the fixture's dictionary: a module-level `ON-INSERT` trigger, which replaces the insert of every block without its
 * own; a block whose DML target is another relation (`COMPANY`), one that does not allow insert and has an `ON-UPDATE`
 * trigger (`EVENTS`), one whose DML target type is `None` (`QUEUE`), a database block without a source (`CONTROL`), one
 * on a procedure (`ORDERS`), one of a type the reader does not know (`ODD`), one with its own `ON-INSERT` (`ORD`) and
 * one on `DUAL` (`D`); and the inline module `NAMES_DESK`, whose table names are a query text, a list of names, or a
 * schema and a name.
 */
class FormsDataBlocksTest {
    @TempDir
    lateinit var temp: Path

    private val db: Path get() = temp.resolve("atlas.db")

    private val indexed by lazy {
        val root = temp.resolve("src").createDirectories()
        for (name in listOf("data_desk_fmb.xml", "data_plain_fmb.xml")) {
            javaClass.getResourceAsStream("/forms/$name")!!.use { Files.copy(it, root.resolve(name)) }
        }
        root.resolve("names_desk_fmb.xml").writeText(NAMES_DESK)
        Indexer().index(IndexOptions(root, db, compilationSchema = "HR", dictionary = Fixture.dictionary, threads = 2))
    }

    private fun query(sql: String): List<String> {
        indexed
        return Fixture.query(db, sql)
    }

    private fun relations(module: String) = query("""
        SELECT f.key || ' ' || r.kind || ' ' || t.key FROM relation r JOIN entity f ON f.id = r.from_id JOIN entity t ON t.id = r.to_id
        WHERE r.kind IN ('FORMS_BLOCK_TABLE', 'FORMS_ITEM_COLUMN') AND f.key LIKE 'forms:$module/%' ORDER BY 1""")

    private fun uses(module: String) = query("""
        SELECT c.key || ' ' || o.roles || ' ' || coalesce(e.key, '-') || ' ' || o.written || ' ' || o.status || ' ' || coalesce(o.reason, '-')
        FROM occurrence o JOIN entity c ON c.id = o.container_entity_id LEFT JOIN entity e ON e.id = o.entity_id
        WHERE o.origin = 'FORMS_IMPLICIT' AND c.key LIKE 'forms:$module/%' ORDER BY o.id""")

    private fun blocks(module: String) = query("""
        SELECT e.name || ' ' || b.database || ' ' || b.source_type || ':' || b.source_name || ':' || coalesce(s.key, '-') || ' ' ||
               b.target_type || ':' || b.target_name || ':' || coalesce(t.key, '-') || ' ' ||
               b.query_allowed || b.insert_allowed || b.update_allowed || b.delete_allowed || ' ' || b.where_clause || '|' || b.order_by_clause
        FROM forms_block b JOIN entity e ON e.id = b.entity_id LEFT JOIN entity s ON s.id = b.source_entity_id
        LEFT JOIN entity t ON t.id = b.target_entity_id WHERE e.key LIKE 'forms:$module/%' ORDER BY e.key""")

    private fun items(module: String) = query("""
        SELECT e.key || ' ' || i.database || ' ' || i.kind || ' [' || i.column_name || '] ' || i.insert_allowed || i.update_allowed || ' ' ||
               coalesce(c.key, '-') || ' ' || coalesce(t.key, '-')
        FROM forms_item i JOIN entity e ON e.id = i.entity_id LEFT JOIN entity c ON c.id = i.column_id LEFT JOIN entity t ON t.id = i.target_column_id
        WHERE e.key LIKE 'forms:$module/%' ORDER BY e.key""")

    private fun notes(path: String) = query("""
        SELECT n.kind || ': ' || n.message FROM coverage_note n JOIN source_file f ON f.id = n.file_id WHERE f.path = '$path' ORDER BY n.kind""")

    @Test
    fun `a block on a table relates to its source and its other DML target, an item on a column to its columns`() {
        assertThat(relations("DATA_DESK")).containsExactly(
            "forms:DATA_DESK/COMPANY FORMS_BLOCK_TABLE db:HR.COMPANY",
            "forms:DATA_DESK/COMPANY FORMS_BLOCK_TABLE db:HR.V_COMPANY",
            // ID has no column name: its own; NAME's column is COMPANY_NAME. Each reads the source's and writes the target's.
            "forms:DATA_DESK/COMPANY/ID FORMS_ITEM_COLUMN db:HR.COMPANY.ID",
            "forms:DATA_DESK/COMPANY/ID FORMS_ITEM_COLUMN db:HR.V_COMPANY.ID",
            "forms:DATA_DESK/COMPANY/NAME FORMS_ITEM_COLUMN db:HR.COMPANY.COMPANY_NAME",
            "forms:DATA_DESK/COMPANY/NAME FORMS_ITEM_COLUMN db:HR.V_COMPANY.COMPANY_NAME",
            "forms:DATA_DESK/EVENTS FORMS_BLOCK_TABLE db:HR.EVENT_LOG",
            "forms:DATA_DESK/EVENTS/ARCHIVED FORMS_ITEM_COLUMN db:HR.EVENT_LOG.ARCHIVED",
            "forms:DATA_DESK/EVENTS/ID FORMS_ITEM_COLUMN db:HR.EVENT_LOG.ID",
            "forms:DATA_DESK/EVENTS/MSG FORMS_ITEM_COLUMN db:HR.EVENT_LOG.MSG",
            "forms:DATA_DESK/ORD FORMS_BLOCK_TABLE db:HR.IT_ORDERS",
            "forms:DATA_DESK/ORD/ID FORMS_ITEM_COLUMN db:HR.IT_ORDERS.ID",
            "forms:DATA_DESK/ORD/STATUS FORMS_ITEM_COLUMN db:HR.IT_ORDERS.STATUS",
            // D is on DUAL: no table, as in SQL.
            "forms:DATA_DESK/QUEUE FORMS_BLOCK_TABLE db:HR.SEND_QUEUE",
            "forms:DATA_DESK/QUEUE/ID FORMS_ITEM_COLUMN db:HR.SEND_QUEUE.ID",
            "forms:DATA_DESK/QUEUE/PAYLOAD FORMS_ITEM_COLUMN db:HR.SEND_QUEUE.PAYLOAD"
        )
    }

    @Test
    fun `the uses Forms makes without code are the block's and the item's, with the roles of the SQL it runs`() {
        assertThat(uses("DATA_DESK")).containsExactly(
            // The module's ON-INSERT replaces COMPANY's insert, and so its items' insert writes.
            "forms:DATA_DESK/COMPANY IMPLICIT,SQL_SELECT db:HR.V_COMPANY V_COMPANY RESOLVED -",
            "forms:DATA_DESK/COMPANY IMPLICIT,SQL_UPDATE db:HR.COMPANY COMPANY RESOLVED -",
            "forms:DATA_DESK/COMPANY IMPLICIT,SQL_DELETE db:HR.COMPANY COMPANY RESOLVED -",
            // ID does not allow update.
            "forms:DATA_DESK/COMPANY/ID IMPLICIT,READ db:HR.V_COMPANY.ID ID RESOLVED -",
            "forms:DATA_DESK/COMPANY/NAME IMPLICIT,READ db:HR.V_COMPANY.COMPANY_NAME COMPANY_NAME RESOLVED -",
            "forms:DATA_DESK/COMPANY/NAME IMPLICIT,SQL_UPDATE,WRITE db:HR.COMPANY.COMPANY_NAME COMPANY_NAME RESOLVED -",
            // No insert (not allowed), no update (ON-UPDATE replaces it); a column the table lacks is unresolved, as in SQL.
            "forms:DATA_DESK/EVENTS IMPLICIT,SQL_SELECT db:HR.EVENT_LOG EVENT_LOG RESOLVED -",
            "forms:DATA_DESK/EVENTS IMPLICIT,SQL_DELETE db:HR.EVENT_LOG EVENT_LOG RESOLVED -",
            "forms:DATA_DESK/EVENTS/ID IMPLICIT,READ db:HR.EVENT_LOG.ID ID RESOLVED -",
            "forms:DATA_DESK/EVENTS/MSG IMPLICIT,READ db:HR.EVENT_LOG.MSG MSG RESOLVED -",
            "forms:DATA_DESK/EVENTS/ARCHIVED IMPLICIT,READ db:HR.EVENT_LOG.ARCHIVED ARCHIVED UNRESOLVED NOT_IN_DICTIONARY",
            // A DML target of type None: the block only queries.
            "forms:DATA_DESK/QUEUE IMPLICIT,SQL_SELECT db:HR.SEND_QUEUE SEND_QUEUE RESOLVED -",
            "forms:DATA_DESK/QUEUE/ID IMPLICIT,READ db:HR.SEND_QUEUE.ID ID RESOLVED -",
            "forms:DATA_DESK/QUEUE/PAYLOAD IMPLICIT,READ db:HR.SEND_QUEUE.PAYLOAD PAYLOAD RESOLVED -",
            // ORD's own ON-INSERT replaces its insert.
            "forms:DATA_DESK/ORD IMPLICIT,SQL_SELECT db:HR.IT_ORDERS IT_ORDERS RESOLVED -",
            "forms:DATA_DESK/ORD IMPLICIT,SQL_UPDATE db:HR.IT_ORDERS IT_ORDERS RESOLVED -",
            "forms:DATA_DESK/ORD IMPLICIT,SQL_DELETE db:HR.IT_ORDERS IT_ORDERS RESOLVED -",
            "forms:DATA_DESK/ORD/ID IMPLICIT,READ db:HR.IT_ORDERS.ID ID RESOLVED -",
            "forms:DATA_DESK/ORD/ID IMPLICIT,SQL_UPDATE,WRITE db:HR.IT_ORDERS.ID ID RESOLVED -",
            "forms:DATA_DESK/ORD/STATUS IMPLICIT,READ db:HR.IT_ORDERS.STATUS STATUS RESOLVED -",
            "forms:DATA_DESK/ORD/STATUS IMPLICIT,SQL_UPDATE,WRITE db:HR.IT_ORDERS.STATUS STATUS RESOLVED -"
        )
        // A use without code has no unit, statement or line.
        assertThat(query("""
            SELECT count(*) FROM occurrence WHERE origin = 'FORMS_IMPLICIT'
            AND (unit_id IS NOT NULL OR statement_id IS NOT NULL OR start_line IS NOT NULL OR end_col IS NOT NULL)""")).containsExactly("0")
        // The ON-UPDATE trigger's own update is the trigger's code.
        assertThat(query("""
            SELECT c.key || ' ' || o.origin || ' ' || o.start_line FROM occurrence o JOIN entity e ON e.id = o.entity_id
            JOIN entity c ON c.id = o.container_entity_id WHERE e.key = 'db:HR.EVENT_LOG' AND o.roles = 'SQL_UPDATE' AND c.key LIKE 'forms:DATA_DESK/%'"""))
            .containsExactly("forms:DATA_DESK/EVENTS/ON-UPDATE STATIC 2")
        // ORD's ON-INSERT inserts in its code; the module's ON-INSERT calls a Forms built-in.
        assertThat(query("""
            SELECT c.key || ' ' || o.origin FROM occurrence o JOIN entity e ON e.id = o.entity_id
            JOIN entity c ON c.id = o.container_entity_id WHERE e.key = 'db:HR.IT_ORDERS' AND o.roles = 'SQL_INSERT' AND c.key LIKE 'forms:DATA_DESK/%'"""))
            .containsExactly("forms:DATA_DESK/ORD/ON-INSERT STATIC")
        // Nothing of DUAL, as in SQL.
        assertThat(query("SELECT count(*) FROM entity WHERE key LIKE 'db:HR.DUAL%'")).containsExactly("0")
    }

    @Test
    fun `a block and an item keep what they say of their data`() {
        assertThat(blocks("DATA_DESK")).containsExactly(
            "COMPANY 1 TABLE:V_COMPANY:db:HR.V_COMPANY TABLE:COMPANY:db:HR.COMPANY 1111 st_active = 'Y'|company_name",
            "CONTROL 1 TABLE::- TABLE::- 1111 |",
            // DUAL is no table: no relation for its source and target.
            "D 1 TABLE:DUAL:- TABLE:DUAL:- 1111 |",
            // A blank DML target name is the source's.
            "EVENTS 1 TABLE:EVENT_LOG:db:HR.EVENT_LOG TABLE:EVENT_LOG:db:HR.EVENT_LOG 1011 |",
            "ODD 1 UNKNOWN:IT_ORDERS:- TABLE:IT_ORDERS:- 1111 |",
            "ORD 1 TABLE:IT_ORDERS:db:HR.IT_ORDERS TABLE:IT_ORDERS:db:HR.IT_ORDERS 1111 |",
            "ORDERS 1 PROCEDURE:order_api.query_orders:- TRANSACTIONAL_TRIGGERS:order_api.query_orders:- 1111 |",
            "QUEUE 1 TABLE:SEND_QUEUE:db:HR.SEND_QUEUE NONE:SEND_QUEUE:- 1111 |"
        )
        assertThat(items("DATA_DESK").filter { it.startsWith("forms:DATA_DESK/COMPANY/") }).containsExactly(
            "forms:DATA_DESK/COMPANY/ID 1 TEXT_ITEM [] 10 db:HR.V_COMPANY.ID db:HR.COMPANY.ID",
            "forms:DATA_DESK/COMPANY/LABEL 0 DISPLAY_ITEM [] 11 - -",
            "forms:DATA_DESK/COMPANY/LEVEL 1 UNKNOWN [] 11 - -",
            "forms:DATA_DESK/COMPANY/NAME 1 TEXT_ITEM [COMPANY_NAME] 11 db:HR.V_COMPANY.COMPANY_NAME db:HR.COMPANY.COMPANY_NAME",
            // A push button holds no value: no column, even as a database item.
            "forms:DATA_DESK/COMPANY/SAVE 1 PUSH_BUTTON [] 11 - -"
        )
    }

    @Test
    fun `the notes count the blocks not followed and the words not known`() {
        val expected = listOf(
            "FORMS_BLOCK_SOURCE_NOT_FOLLOWED: 2 database block(s) whose data source or DML target is not a table: what Forms does with it, " +
                "and with its items' columns, is not followed (1 a procedure, a FROM clause query or transactional triggers; 1 of a type not known)",
            "FORMS_VALUE_UNKNOWN: 2 value(s) not known: ItemType \"Slider\" (1), QueryDataSourceType \"Stored Thing\" (1)"
        )
        assertThat(notes("data_desk_fmb.xml")).containsExactlyElementsOf(expected)
        assertThat(notes("data_plain_fmb.xml")).containsExactlyElementsOf(expected)
    }

    @Test
    fun `a module exported without DUMP=ALL gives the same rows, its absent properties the Forms defaults`() {
        fun desk(rows: List<String>) = rows.map { it.replace("DATA_PLAIN", "DATA_DESK") }
        assertThat(desk(relations("DATA_PLAIN"))).isEqualTo(relations("DATA_DESK"))
        assertThat(desk(uses("DATA_PLAIN"))).isEqualTo(uses("DATA_DESK"))
        assertThat(desk(blocks("DATA_PLAIN"))).isEqualTo(blocks("DATA_DESK"))
        assertThat(desk(items("DATA_PLAIN"))).isEqualTo(items("DATA_DESK"))
    }

    @Test
    fun `a table source or DML target name that is not a table name is not followed, and the note names its block`() {
        assertThat(relations("NAMES_DESK")).containsExactly(
            "forms:NAMES_DESK/OWNED FORMS_BLOCK_TABLE db:HR.COMPANY",
            "forms:NAMES_DESK/OWNED/ID FORMS_ITEM_COLUMN db:HR.COMPANY.ID",
            "forms:NAMES_DESK/WRITES_LIST FORMS_BLOCK_TABLE db:HR.COMPANY",
            "forms:NAMES_DESK/WRITES_LIST/ID FORMS_ITEM_COLUMN db:HR.COMPANY.ID"
        )
        // WRITES_LIST queries its source; its DML target is two names, so it writes nothing.
        assertThat(uses("NAMES_DESK")).containsExactly(
            "forms:NAMES_DESK/WRITES_LIST IMPLICIT,SQL_SELECT db:HR.COMPANY COMPANY RESOLVED -",
            "forms:NAMES_DESK/WRITES_LIST/ID IMPLICIT,READ db:HR.COMPANY.ID ID RESOLVED -",
            "forms:NAMES_DESK/OWNED IMPLICIT,SQL_SELECT db:HR.COMPANY HR.COMPANY RESOLVED -",
            "forms:NAMES_DESK/OWNED IMPLICIT,SQL_INSERT db:HR.COMPANY HR.COMPANY RESOLVED -",
            "forms:NAMES_DESK/OWNED IMPLICIT,SQL_UPDATE db:HR.COMPANY HR.COMPANY RESOLVED -",
            "forms:NAMES_DESK/OWNED IMPLICIT,SQL_DELETE db:HR.COMPANY HR.COMPANY RESOLVED -",
            "forms:NAMES_DESK/OWNED/ID IMPLICIT,READ db:HR.COMPANY.ID ID RESOLVED -",
            "forms:NAMES_DESK/OWNED/ID IMPLICIT,SQL_INSERT,WRITE db:HR.COMPANY.ID ID RESOLVED -",
            "forms:NAMES_DESK/OWNED/ID IMPLICIT,SQL_UPDATE,WRITE db:HR.COMPANY.ID ID RESOLVED -"
        )
        assertThat(notes("names_desk_fmb.xml")).containsExactly(
            "FORMS_BLOCK_SOURCE_NOT_FOLLOWED: 3 database block(s) whose data source or DML target is not a table: what Forms does with it, " +
                "and with its items' columns, is not followed (3 whose table name is not a name: NAMES_DESK/QUERIED, NAMES_DESK/LISTED, " +
                "NAMES_DESK/WRITES_LIST (DML target))"
        )
    }

    /**
     * The whole Doag corpus of `frmf2xml` exports, when the build is given it (`-PdoagForms=<folder with frm2xml/>`):
     * its blocks on a table, what they do, and their items on a column.
     */
    @Test
    fun `the Doag modules give the uses Forms makes of their tables`() {
        val folder = System.getProperty("doagForms")?.let { Path.of(it).resolve("frm2xml") }
        assumeTrue(folder != null && folder.isDirectory(), "-PdoagForms not given")
        val root = temp.resolve("doag").createDirectories()
        for (file in folder!!.listDirectoryEntries("*_fmb.xml")) file.copyTo(root.resolve(file.fileName.toString()))
        val doag = temp.resolve("doag.db")
        Indexer().index(IndexOptions(root, doag, compilationSchema = "DEMO", threads = 2))
        fun count(sql: String) = Fixture.query(doag, sql).single().toInt()
        fun uses(role: String, container: String) = count("""
            SELECT count(*) FROM occurrence o JOIN entity c ON c.id = o.container_entity_id
            WHERE o.origin = 'FORMS_IMPLICIT' AND c.kind = '$container' AND (',' || o.roles || ',') LIKE '%,$role,%'""")
        assertThat(count("SELECT count(*) FROM source_file WHERE kind = 'FORMS_MODULE'")).isEqualTo(37)
        // The block D of qa_fmb.xml is on DUAL, as are its 100 items: no table (its four operations are replaced anyway).
        assertThat(count("SELECT count(DISTINCT from_id) FROM relation WHERE kind = 'FORMS_BLOCK_TABLE'")).isEqualTo(11)
        assertThat(listOf("SQL_SELECT", "SQL_INSERT", "SQL_UPDATE", "SQL_DELETE").map { uses(it, "FORMS_BLOCK") }).containsExactly(11, 5, 8, 5)
        assertThat(count("SELECT count(*) FROM forms_item WHERE column_id IS NOT NULL")).isEqualTo(82)
        assertThat(listOf("READ", "SQL_INSERT", "SQL_UPDATE").map { uses(it, "FORMS_ITEM") }).containsExactly(82, 48, 59)
    }

    private companion object {
        /** A module without DUMP=ALL whose blocks name their tables by a query text, a list, a list as DML target, and a schema and a name. */
        const val NAMES_DESK = """<Module version="122010300" xmlns="http://xmlns.oracle.com/Forms"><FormModule Name="NAMES_DESK">
<Block Name="QUERIED" QueryDataSourceName="select id from company where st_active = 'Y'"><Item Name="ID"/></Block>
<Block Name="LISTED" QueryDataSourceName="COMPANY,EVENT_LOG"><Item Name="ID"/></Block>
<Block Name="WRITES_LIST" QueryDataSourceName="COMPANY" DMLDataName="COMPANY c, EVENT_LOG e"><Item Name="ID"/></Block>
<Block Name="OWNED" QueryDataSourceName="HR.COMPANY"><Item Name="ID"/></Block>
</FormModule></Module>"""
    }
}
