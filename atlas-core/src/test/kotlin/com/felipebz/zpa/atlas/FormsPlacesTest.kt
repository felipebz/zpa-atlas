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

/**
 * The Forms items, parameters and globals a column's value is read into, on the invented module `SHOP_DESK`
 * (`forms/shop_desk_fmb.xml`, `DUMP=ALL`, and `forms/shop_plain_fmb.xml`, without it, indexed as `SHOP_PLAIN`) and the
 * table and procedure of `forms/shop_desk.sql` (`ORDERS.STATUS` is `VARCHAR2(10 BYTE)`), without a dictionary: its
 * code reads the column into items of every size, by every kind of statement, through its own function `READ_ONE`, and
 * into names that are not items; the block `ORDERS` queries the table, `ORDERS_ALT` too, but its `ON-SELECT` replaces
 * the query.
 *
 * The declarations of a module's package specification, on the invented module `PACK_DESK` (`forms/pack_desk_fmb.xml`
 * and `forms/pack_plain_fmb.xml`) beside the database package `PK` of `forms/pack_desk.sql`, which declares the same:
 * the procedure `USE_PKS` reads the column into variables of the package's types, anchored to its variables, into its
 * variables, into the fields of its record type, of an element of its collection and of a record inside its record, as
 * the database procedure `USE_PK` does with `PK`, then reads a field of an element of its collection and calls its
 * function. The database also has a package `PKS` that declares the same names with other sizes, which the module's
 * code never reaches.
 */
class FormsPlacesTest {
    @TempDir
    lateinit var temp: Path

    private val db: Path get() = temp.resolve("atlas.db")

    private val indexed by lazy {
        val root = temp.resolve("src").createDirectories()
        for (name in listOf("shop_desk_fmb.xml", "shop_plain_fmb.xml", "shop_desk.sql")) {
            javaClass.getResourceAsStream("/forms/$name")!!.use { Files.copy(it, root.resolve(name)) }
        }
        Indexer().index(IndexOptions(root, db, compilationSchema = "SHOP", threads = 2))
    }

    /** The index of the package module and `forms/pack_desk.sql`, with the database's package `PKS` of the module package's name. */
    private val packages: Path by lazy { packageIndex("packages") { it } }

    /** The same without the database's package `PKS`. */
    private val solo: Path by lazy { packageIndex("solo") { it.replace(DATABASE_PKS, "") } }

    /** The package module in both exports, with `forms/pack_desk.sql` as [sql] makes it, indexed into `[name].db`. */
    private fun packageIndex(name: String, sql: (String) -> String): Path {
        val root = temp.resolve(name).createDirectories()
        for (file in listOf("pack_desk_fmb.xml", "pack_plain_fmb.xml")) {
            javaClass.getResourceAsStream("/forms/$file")!!.use { Files.copy(it, root.resolve(file)) }
        }
        Files.writeString(root.resolve("pack_desk.sql"), sql(javaClass.getResource("/forms/pack_desk.sql")!!.readText()))
        val index = temp.resolve("$name.db")
        Indexer().index(IndexOptions(root, index, compilationSchema = "SHOP", threads = 2))
        return index
    }

    /** Line, source, target as written and its entity, class, declared type, size, the entity whose type gave it, reason and field, of the places in [path]. */
    private fun packagePlaces(path: String, index: Path = packages): List<String> = Fixture.query(index, """
        SELECT p.start_line || ' ' || p.source || ' ' || p.target_written || ' ' || coalesce(t.key, '-') || ' ' || p.class || ' ' ||
               coalesce(p.declared_type, '-') || ' ' || coalesce(p.length || ' ' || p.length_unit, '-') || ' ' || coalesce(s.key, '-') || ' ' ||
               coalesce(p.reason, '-') || coalesce(' field ' || p.field, '')
        FROM column_place p JOIN source_file f ON f.id = p.file_id LEFT JOIN entity t ON t.id = p.target_entity_id
        LEFT JOIN entity s ON s.id = p.type_source_id
        WHERE f.path = '$path' ORDER BY p.start_line, p.id""")

    /** Line, written segment, field, roles and status of the uses of record fields in [path]. */
    private fun fieldUses(path: String, index: Path): List<String> = Fixture.query(index, """
        SELECT o.start_line || ' ' || o.written || ' ' || e.key || ' ' || o.roles || ' ' || o.status
        FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id
        WHERE f.path = '$path' AND e.kind = 'RECORD_FIELD' ORDER BY o.start_line, o.start_col""")

    /** Source, target as written and its entity, class, declared type, length, unit and its source, and reason, of the places in [path]. */
    private fun places(path: String): List<String> {
        indexed
        return Fixture.query(db, """
            SELECT p.source || ' ' || p.target_written || ' ' || coalesce(t.key, '-') || ' ' || p.class || ' ' || coalesce(p.declared_type, '-') || ' ' ||
                   coalesce(p.length || ' ' || p.length_unit || ' ' || p.unit_source, '-') || ' ' || coalesce(p.reason, '-') || coalesce(' via ' || p.functions, '') ||
                   coalesce(' line ' || p.start_line, '')
            FROM column_place p JOIN source_file f ON f.id = p.file_id LEFT JOIN entity t ON t.id = p.target_entity_id
            WHERE f.path = '$path' AND p.column_id = (SELECT id FROM entity WHERE key = 'db:SHOP.ORDERS.STATUS') ORDER BY p.id""")
    }

    @Test
    fun `a column read into a Forms item has the item's size, a block's query reads it into the item on it`() {
        assertThat(places("shop_desk_fmb.xml").map { it.substringBefore(" line ") }).containsExactly(
            // The block's query reads the column into ORDERS.STATUS, in no code; ORDERS_ALT's ON-SELECT replaces its query.
            "BLOCK_QUERY SHOP_DESK/ORDERS/STATUS forms:SHOP_DESK/ORDERS/STATUS FIXED_SIZE CHAR 4 BYTE OPTION -",
            // A Char item 5 long, its unit the session's: by SELECT INTO, FETCH, an assignment of a %ROWTYPE field, through NVL.
            "SELECT_INTO :ctrl.short_status forms:SHOP_DESK/CTRL/SHORT_STATUS FIXED_SIZE CHAR 5 BYTE OPTION -",
            "FETCH :ctrl.short_status forms:SHOP_DESK/CTRL/SHORT_STATUS FIXED_SIZE CHAR 5 BYTE OPTION -",
            "ASSIGNMENT :ctrl.short_status forms:SHOP_DESK/CTRL/SHORT_STATUS FIXED_SIZE CHAR 5 BYTE OPTION -",
            "ASSIGNMENT :ctrl.short_status forms:SHOP_DESK/CTRL/SHORT_STATUS FIXED_SIZE CHAR 5 BYTE OPTION - via NVL",
            // The Forms default length; a length in characters; a Number item; a list item; a Char item with no data type written.
            "SELECT_INTO :ctrl.default_status forms:SHOP_DESK/CTRL/DEFAULT_STATUS FIXED_SIZE CHAR 30 BYTE OPTION -",
            "SELECT_INTO :ctrl.wide_status forms:SHOP_DESK/CTRL/WIDE_STATUS FIXED_SIZE CHAR 10 CHAR WRITTEN -",
            "SELECT_INTO :ctrl.count_id forms:SHOP_DESK/CTRL/COUNT_ID NO_SIZE NUMBER - -",
            "SELECT_INTO :ctrl.status_list forms:SHOP_DESK/CTRL/STATUS_LIST UNKNOWN - - ITEM_HOLDS_ELEMENT",
            "SELECT_INTO :ctrl.bare_status forms:SHOP_DESK/CTRL/BARE_STATUS FIXED_SIZE CHAR 8 BYTE OPTION -",
            // The argument for READ_STATUS's OUT parameter, which selects the column into it.
            "OUT_ARGUMENT :ctrl.short_status forms:SHOP_DESK/CTRL/SHORT_STATUS FIXED_SIZE CHAR 5 BYTE OPTION -",
            // :ITEM with one candidate, with two; a block the module does not have.
            "SELECT_INTO :only_here forms:SHOP_DESK/CTRL/ONLY_HERE FIXED_SIZE CHAR 7 BYTE OPTION -",
            "SELECT_INTO :dup - UNKNOWN - - AMBIGUOUS",
            "SELECT_INTO :nope.z - UNKNOWN - - NO_SUCH_BLOCK",
            // frmf2xml does not give a parameter's length; a global has none; :SYSTEM is no object of the module.
            "SELECT_INTO :parameter.p_status forms:SHOP_DESK/PARAMETER/P_STATUS UNKNOWN - - PARAMETER_LENGTH_NOT_GIVEN",
            "SELECT_INTO :global.last_status forms::GLOBAL.LAST_STATUS UNKNOWN - - GLOBAL_LENGTH_NOT_GIVEN",
            "SELECT_INTO :system.message_level - UNKNOWN - - TARGET_NOT_A_NAME",
            // The program unit READ_ONE returns the column it selects: its calls in SHOW_ONE give it to a local and to an item.
            "SELECT_INTO l forms:SHOP_DESK/READ_ONE.L FIXED_SIZE varchar2(20) 20 BYTE OPTION -",
            "RETURNED l_status forms:SHOP_DESK/SHOW_ONE.L_STATUS FIXED_SIZE varchar2(20) 20 BYTE OPTION -",
            "RETURNED :ctrl.default_status forms:SHOP_DESK/CTRL/DEFAULT_STATUS FIXED_SIZE CHAR 30 BYTE OPTION -",
            // An image holds an image.
            "SELECT_INTO :ctrl.status_image forms:SHOP_DESK/CTRL/STATUS_IMAGE UNKNOWN - - ITEM_HOLDS_IMAGE"
        )
        // The block's query is in no code; every other place is on a line.
        assertThat(places("shop_desk_fmb.xml").map { " line " in it }).containsExactly(false, *Array(20) { true })
    }

    @Test
    fun `a module exported without DUMP=ALL gives the same places`() {
        fun lines(rows: List<String>) = rows.map { it.replace("SHOP_PLAIN", "SHOP_DESK") }
        assertThat(lines(places("shop_plain_fmb.xml"))).isEqualTo(places("shop_desk_fmb.xml"))
    }

    @Test
    fun `an item shorter than the column is SMALLER, the block query's too`() {
        indexed
        val rows = Fixture.query(db, """
            SELECT p.target_written || ' ' || p.length FROM column_place p JOIN source_file f ON f.id = p.file_id
            WHERE f.path = 'shop_desk_fmb.xml' AND p.class = 'FIXED_SIZE' AND p.length < 10 ORDER BY p.id""")
        assertThat(rows.distinct()).containsExactly("SHOP_DESK/ORDERS/STATUS 4", ":ctrl.short_status 5", ":ctrl.bare_status 8", ":only_here 7")
    }

    @Test
    fun `the types, variables and record fields of a module's package give their declared sizes`() {
        assertThat(packagePlaces("pack_desk_fmb.xml")).containsExactly(
            // In the package's body, its own variable.
            "19 SELECT_INTO g_code forms:PACK_DESK/PKS.G_CODE FIXED_SIZE varchar2(6) 6 BYTE - -",
            // A variable of the package's subtype; anchored to the package's variable.
            "35 SELECT_INTO c forms:PACK_DESK/USE_PKS.C FIXED_SIZE pks.t_code 4 BYTE forms:PACK_DESK/PKS.T_CODE -",
            "36 SELECT_INTO a forms:PACK_DESK/USE_PKS.A FIXED_SIZE pks.g_code%TYPE 6 BYTE forms:PACK_DESK/PKS.G_CODE -",
            // The package's variable itself, by SELECT INTO and by an assignment of a loop record's field.
            "37 SELECT_INTO pks.g_code forms:PACK_DESK/PKS.G_CODE FIXED_SIZE varchar2(6) 6 BYTE - -",
            "39 ASSIGNMENT pks.g_code forms:PACK_DESK/PKS.G_CODE FIXED_SIZE varchar2(6) 6 BYTE - -",
            // A variable of the package's record type: one field, then the whole record field by field.
            "41 SELECT_INTO r.code forms:PACK_DESK/PKS.T_REC.CODE FIXED_SIZE varchar2(7) 7 BYTE - -",
            "42 SELECT_INTO r forms:PACK_DESK/USE_PKS.R FIXED_SIZE varchar2(30) 30 BYTE forms:PACK_DESK/PKS.T_REC.NOTE - field NOTE",
            "42 SELECT_INTO r forms:PACK_DESK/USE_PKS.R FIXED_SIZE varchar2(7) 7 BYTE forms:PACK_DESK/PKS.T_REC.CODE - field CODE",
            // Fields of the package's record variable as targets (the second not its first field), and one as an anchor.
            "43 SELECT_INTO pks.g_rec.code forms:PACK_DESK/PKS.T_REC.CODE FIXED_SIZE varchar2(7) 7 BYTE - -",
            "44 SELECT_INTO pks.g_rec.note forms:PACK_DESK/PKS.T_REC.NOTE FIXED_SIZE varchar2(30) 30 BYTE - -",
            "45 SELECT_INTO f forms:PACK_DESK/USE_PKS.F FIXED_SIZE pks.g_rec.code%TYPE 7 BYTE forms:PACK_DESK/PKS.T_REC.CODE -",
            // A field of an element of the package's collection; the whole collection, field by field; a field of a record in a record.
            "46 SELECT_INTO pks.g_tab(1).code forms:PACK_DESK/PKS.T_REC.CODE FIXED_SIZE varchar2(7) 7 BYTE - -",
            "47 SELECT_INTO pks.g_tab forms:PACK_DESK/PKS.G_TAB FIXED_SIZE varchar2(30) 30 BYTE forms:PACK_DESK/PKS.T_REC.NOTE - field NOTE",
            "47 SELECT_INTO pks.g_tab forms:PACK_DESK/PKS.G_TAB FIXED_SIZE varchar2(7) 7 BYTE forms:PACK_DESK/PKS.T_REC.CODE - field CODE",
            "48 SELECT_INTO pks.g_out.inner.code forms:PACK_DESK/PKS.T_REC.CODE FIXED_SIZE varchar2(7) 7 BYTE - -",
            // A type of no package of the module (an attached library's): not followed.
            "52 SELECT_INTO x forms:PACK_DESK/USE_PKS.X UNKNOWN pack_lib_pk.t_code - - TYPE_NOT_DECLARED"
        )
        // The uses of the package's members stay the package's, and none is of the database's package of the same name.
        assertThat(Fixture.query(packages, """
            SELECT o.written || ' ' || e.key FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id
            WHERE f.path = 'pack_desk_fmb.xml' AND o.written LIKE 'pks.%' ORDER BY o.start_line, o.start_col""").distinct())
            .containsExactly("pks.t_code forms:PACK_DESK/PKS", "pks.g_code forms:PACK_DESK/PKS", "pks.t_rec forms:PACK_DESK/PKS",
                "pks.g_rec.code forms:PACK_DESK/PKS", "pks.g_rec.note forms:PACK_DESK/PKS", "pks.g_tab forms:PACK_DESK/PKS",
                "pks.g_out.inner.code forms:PACK_DESK/PKS", "pks.f forms:PACK_DESK/PKS")
        assertThat(Fixture.query(packages, """
            SELECT o.written || ' ' || e.key FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id
            WHERE f.path IN ('pack_desk_fmb.xml', 'pack_plain_fmb.xml') AND e.key LIKE 'db:SHOP.PKS%'""")).isEmpty()
    }

    @Test
    fun `a module's package gives the sizes a database package declaring the same gives`() {
        // Each place's source, target as written, class, size, declared type and target past its package or procedure.
        fun sizes(container: String) = Fixture.query(packages, """
            SELECT p.source || ' ' || replace(p.target_written, 'pks.', 'pk.') || ' ' || p.class || ' ' || p.length || ' ' ||
                   lower(replace(coalesce(p.declared_type, '-'), 'pks.', 'pk.')) || ' ' || t.key
            FROM column_place p JOIN entity k ON k.id = p.container_entity_id LEFT JOIN entity t ON t.id = p.target_entity_id
            WHERE k.key = '$container' ORDER BY p.start_line, p.id""").map { row ->
            row.replace(Regex(" \\S*?(USE_PKS?(\\(\\))?|PKS?)\\.(\\S+)$"), " $3")
        }
        assertThat(sizes("forms:PACK_DESK/USE_PKS").dropLast(1)).isEqualTo(sizes("db:SHOP.USE_PK()")).hasSize(14)
    }

    @Test
    fun `a module with packages exported without DUMP=ALL gives the same places`() {
        assertThat(packagePlaces("pack_plain_fmb.xml").map { it.replace("PACK_PLAIN", "PACK_DESK") }).isEqualTo(packagePlaces("pack_desk_fmb.xml"))
    }

    @Test
    fun `the fields after a module package's names are the module's own, whatever package of that name the database has`() {
        val uses = listOf(
            // The fields' declarations in the package's specification.
            "5 code forms:PACK_DESK/PKS.T_REC.CODE DEFINITION RESOLVED",
            "5 note forms:PACK_DESK/PKS.T_REC.NOTE DEFINITION RESOLVED",
            "9 inner forms:PACK_DESK/PKS.T_OUT.INNER DEFINITION RESOLVED",
            // The anchor `pks.g_rec.code%TYPE`; a field of a variable of the package's record type, written.
            "32 code forms:PACK_DESK/PKS.T_REC.CODE TYPE_USE RESOLVED",
            "41 code forms:PACK_DESK/PKS.T_REC.CODE WRITE RESOLVED",
            // Fields of the package's record variable, of an element of its collection, of a record in its record, written.
            "43 code forms:PACK_DESK/PKS.T_REC.CODE WRITE RESOLVED",
            "44 note forms:PACK_DESK/PKS.T_REC.NOTE WRITE RESOLVED",
            "46 code forms:PACK_DESK/PKS.T_REC.CODE WRITE RESOLVED",
            "48 inner forms:PACK_DESK/PKS.T_OUT.INNER WRITE RESOLVED",
            "48 code forms:PACK_DESK/PKS.T_REC.CODE WRITE RESOLVED",
            // The field of an element of its collection, read.
            "49 code forms:PACK_DESK/PKS.T_REC.CODE READ RESOLVED"
        )
        // The element of the package's collection is a use of the package, written or read as the collection; its function is called.
        val members = listOf("46 pks.g_tab WRITE", "47 pks.g_tab WRITE", "49 pks.g_tab READ", "49 pks.f CALL")
        fun memberUses(index: Path) = Fixture.query(index, """
            SELECT o.start_line || ' ' || o.written || ' ' || o.roles FROM occurrence o JOIN source_file f ON f.id = o.file_id
            WHERE f.path = 'pack_desk_fmb.xml' AND o.written IN ('pks.g_tab', 'pks.f') ORDER BY o.start_line, o.start_col""")
        assertThat(fieldUses("pack_desk_fmb.xml", packages)).containsExactlyElementsOf(uses)
        assertThat(memberUses(packages)).containsExactlyElementsOf(members)
        // Without the database's package of the same name: the same uses, the same places.
        assertThat(Fixture.query(solo, "SELECT key FROM entity WHERE key LIKE 'db:SHOP.PKS%'")).isEmpty()
        assertThat(fieldUses("pack_desk_fmb.xml", solo)).containsExactlyElementsOf(uses)
        assertThat(memberUses(solo)).containsExactlyElementsOf(members)
        assertThat(packagePlaces("pack_desk_fmb.xml", solo)).isEqualTo(packagePlaces("pack_desk_fmb.xml"))
        // The module exported without DUMP=ALL.
        assertThat(fieldUses("pack_plain_fmb.xml", packages).map { it.replace("PACK_PLAIN", "PACK_DESK") }).isEqualTo(uses)
    }

    /**
     * The places of the Doag corpus (`-PdoagForms=<folder with frm2xml/>`), by source, class, reason and what the target
     * is: its items read the columns of the tables their blocks query, and its code reads columns into items.
     */
    @Test
    fun `the Doag modules give the places of their items`() {
        val folder = System.getProperty("doagForms")?.let { Path.of(it).resolve("frm2xml") }
        assumeTrue(folder != null && folder.isDirectory(), "-PdoagForms not given")
        val root = temp.resolve("doag").createDirectories()
        for (file in folder!!.listDirectoryEntries("*_fmb.xml")) file.copyTo(root.resolve(file.fileName.toString()))
        val doag = temp.resolve("doag.db")
        Indexer().index(IndexOptions(root, doag, compilationSchema = "DEMO", threads = 2))
        assertThat(Fixture.query(doag, """
            SELECT p.source || ' ' || p.class || ' ' || coalesce(p.reason, '-') || ' ' || coalesce(t.kind, '-') || ' ' || count(*) FROM column_place p
            JOIN source_file f ON f.id = p.file_id LEFT JOIN entity t ON t.id = p.target_entity_id WHERE f.kind = 'FORMS_MODULE'
            GROUP BY p.source, p.class, p.reason, t.kind ORDER BY 1"""))
            .containsExactly(*DOAG_PLACES)
    }

    private companion object {
        /** The Doag places in its modules, by source, class, reason and target kind, with their counts. */
        val DOAG_PLACES = arrayOf(
            "ASSIGNMENT FIXED_SIZE - LOCAL_VARIABLE 22",
            "BLOCK_QUERY FIXED_SIZE - FORMS_ITEM 46",
            "BLOCK_QUERY NO_SIZE - FORMS_ITEM 35",
            "BLOCK_QUERY UNKNOWN ITEM_HOLDS_ELEMENT FORMS_ITEM 1",
            "SELECT_INTO FIXED_SIZE - FORMS_ITEM 4"
        )

        /** The database's package `PKS` of `forms/pack_desk.sql`, of the module package's name. */
        val DATABASE_PKS = Regex("create or replace package pks is\\n.*?\\nend;\\n/\\n\\n", RegexOption.DOT_MATCHES_ALL)
    }
}
