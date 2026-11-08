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
 * variables and into the fields of its record type, as the database procedure `USE_PK` does with `PK`.
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

    private val packages: Path get() = temp.resolve("packages.db")

    private val packagesIndexed by lazy {
        val root = temp.resolve("packages").createDirectories()
        for (name in listOf("pack_desk_fmb.xml", "pack_plain_fmb.xml", "pack_desk.sql")) {
            javaClass.getResourceAsStream("/forms/$name")!!.use { Files.copy(it, root.resolve(name)) }
        }
        Indexer().index(IndexOptions(root, packages, compilationSchema = "SHOP", threads = 2))
    }

    /** Line, source, target as written and its entity, class, declared type, size, the entity whose type gave it, reason and field, of the places in [path]. */
    private fun packagePlaces(path: String): List<String> {
        packagesIndexed
        return Fixture.query(packages, """
            SELECT p.start_line || ' ' || p.source || ' ' || p.target_written || ' ' || coalesce(t.key, '-') || ' ' || p.class || ' ' ||
                   coalesce(p.declared_type, '-') || ' ' || coalesce(p.length || ' ' || p.length_unit, '-') || ' ' || coalesce(s.key, '-') || ' ' ||
                   coalesce(p.reason, '-') || coalesce(' field ' || p.field, '')
            FROM column_place p JOIN source_file f ON f.id = p.file_id LEFT JOIN entity t ON t.id = p.target_entity_id
            LEFT JOIN entity s ON s.id = p.type_source_id
            WHERE f.path = '$path' ORDER BY p.start_line, p.id""")
    }

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
            "14 SELECT_INTO g_code forms:PACK_DESK/PKS.G_CODE FIXED_SIZE varchar2(6) 6 BYTE - -",
            // A variable of the package's subtype; anchored to the package's variable.
            "26 SELECT_INTO c forms:PACK_DESK/USE_PKS.C FIXED_SIZE pks.t_code 4 BYTE forms:PACK_DESK/PKS.T_CODE -",
            "27 SELECT_INTO a forms:PACK_DESK/USE_PKS.A FIXED_SIZE pks.g_code%TYPE 6 BYTE forms:PACK_DESK/PKS.G_CODE -",
            // The package's variable itself, by SELECT INTO and by an assignment of a loop record's field.
            "28 SELECT_INTO pks.g_code forms:PACK_DESK/PKS.G_CODE FIXED_SIZE varchar2(6) 6 BYTE - -",
            "30 ASSIGNMENT pks.g_code forms:PACK_DESK/PKS.G_CODE FIXED_SIZE varchar2(6) 6 BYTE - -",
            // A variable of the package's record type: one field, then the whole record field by field.
            "32 SELECT_INTO r.code forms:PACK_DESK/PKS.T_REC.CODE FIXED_SIZE varchar2(7) 7 BYTE - -",
            "33 SELECT_INTO r forms:PACK_DESK/USE_PKS.R FIXED_SIZE varchar2(30) 30 BYTE forms:PACK_DESK/PKS.T_REC.NOTE - field NOTE",
            "33 SELECT_INTO r forms:PACK_DESK/USE_PKS.R FIXED_SIZE varchar2(7) 7 BYTE forms:PACK_DESK/PKS.T_REC.CODE - field CODE",
            // Fields of the package's record variable as targets (the second not its first field), and one as an anchor.
            "34 SELECT_INTO pks.g_rec.code forms:PACK_DESK/PKS.T_REC.CODE FIXED_SIZE varchar2(7) 7 BYTE - -",
            "35 SELECT_INTO pks.g_rec.note forms:PACK_DESK/PKS.T_REC.NOTE FIXED_SIZE varchar2(30) 30 BYTE - -",
            "36 SELECT_INTO f forms:PACK_DESK/USE_PKS.F FIXED_SIZE pks.g_rec.code%TYPE 7 BYTE forms:PACK_DESK/PKS.T_REC.CODE -",
            // A type of no package of the module (an attached library's): not followed.
            "37 SELECT_INTO x forms:PACK_DESK/USE_PKS.X UNKNOWN pack_lib_pk.t_code - - TYPE_NOT_DECLARED"
        )
        // The uses of the package's members stay the package's.
        assertThat(Fixture.query(packages, """
            SELECT o.written || ' ' || e.key FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id
            WHERE f.path = 'pack_desk_fmb.xml' AND o.written LIKE 'pks.%' ORDER BY o.start_line, o.start_col""").distinct())
            .containsExactly("pks.t_code forms:PACK_DESK/PKS", "pks.g_code forms:PACK_DESK/PKS", "pks.t_rec forms:PACK_DESK/PKS",
                "pks.g_rec.code forms:PACK_DESK/PKS", "pks.g_rec.note forms:PACK_DESK/PKS")
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
                // The database names the anchor of a record variable's field (`pk.g_rec.code%type`) by the field's declaration.
                .replace(Regex("^(SELECT_INTO f FIXED_SIZE 7) \\S+ "), "$1 ")
        }
        packagesIndexed
        assertThat(sizes("forms:PACK_DESK/USE_PKS").dropLast(1)).isEqualTo(sizes("db:SHOP.USE_PK()")).hasSize(10)
    }

    @Test
    fun `a module with packages exported without DUMP=ALL gives the same places`() {
        assertThat(packagePlaces("pack_plain_fmb.xml").map { it.replace("PACK_PLAIN", "PACK_DESK") }).isEqualTo(packagePlaces("pack_desk_fmb.xml"))
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
    }
}
