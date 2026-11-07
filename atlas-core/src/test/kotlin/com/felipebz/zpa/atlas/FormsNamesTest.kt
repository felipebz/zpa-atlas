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
 * The names a Forms module's code gives its own objects, on the invented module `ROUTE_DESK`, exported with `DUMP=ALL`
 * (`forms/route_desk_fmb.xml`) and without it (`forms/route_plain_fmb.xml`, indexed as `ROUTE_PLAIN`), and the module
 * `ROUTE_VIEW` (`forms/route_view_fmb.xml`), which reads the global `ROUTE_DESK` sets and calls it back. `ROUTE_DESK`
 * has the blocks `STOPS` (`STOP_ID`, `CITY`, `NOTE`) and `LEGS` (`LEG_ID`, `CITY`, `DISTANCE`), the parameter `P_CITY`,
 * and the user-named triggers `CHECK_STOP` on the item `STOP_ID`, `REFRESH_STOP` on the block `STOPS` and on the
 * module, and `LOG_ROUTE` on the module. The database procedure `ROUTE_NEXT` has an `OUT` parameter.
 */
class FormsNamesTest {
    @TempDir
    lateinit var temp: Path

    private val db: Path get() = temp.resolve("atlas.db")

    private val indexed by lazy {
        val root = temp.resolve("src").createDirectories()
        for (name in listOf("route_desk_fmb.xml", "route_plain_fmb.xml", "route_view_fmb.xml")) {
            javaClass.getResourceAsStream("/forms/$name")!!.use { Files.copy(it, root.resolve(name)) }
        }
        root.resolve("route_next.sql").writeText(ROUTE_NEXT)
        Indexer().index(IndexOptions(root, db, compilationSchema = "HR", dictionary = Fixture.dictionary, threads = 2))
    }

    private fun query(sql: String): List<String> {
        indexed
        return Fixture.query(db, sql)
    }

    /**
     * The uses of Forms names (a host variable, a built-in's literal) in the unit [unit] of the module text (its path), in
     * order: written, roles, entity, status, reason, candidates, and `SQL` when inside a statement.
     */
    private fun uses(unit: String) = query("""
        SELECT o.written || ' ' || o.roles || ' ' || coalesce(e.key, '-') || ' ' || o.status || ' ' || coalesce(o.reason, '-') ||
               coalesce(' [' || (SELECT group_concat(ce.key, ',') FROM (SELECT ce.key FROM occurrence_candidate c JOIN entity ce ON ce.id = c.entity_id
                                 WHERE c.occurrence_id = o.id ORDER BY ce.key) ce) || ']', '') ||
               CASE WHEN o.statement_id IS NULL THEN '' ELSE ' SQL' END
        FROM occurrence o JOIN unit u ON u.id = o.unit_id LEFT JOIN entity e ON e.id = o.entity_id
        WHERE u.name = '$unit' AND (o.written LIKE ':%' OR o.written LIKE '''%') ORDER BY o.id""")

    @Test
    fun `an item is read, written by an assignment, an INTO and an OUT argument, in PL SQL and in SQL`() {
        assertThat(uses("ROUTE_DESK/STOPS/STOP_ID/WHEN-VALIDATE-ITEM").take(3)).containsExactly(
            ":stops.note WRITE forms:ROUTE_DESK/STOPS/NOTE RESOLVED - SQL",
            ":stops.stop_id READ forms:ROUTE_DESK/STOPS/STOP_ID RESOLVED - SQL",
            // Passed to ROUTE_NEXT's OUT parameter.
            ":stops.city WRITE forms:ROUTE_DESK/STOPS/CITY RESOLVED -"
        )
    }

    @Test
    fun `an item without its block is the trigger's block's, else the one item of that name in the module`() {
        // In a trigger on STOPS, CITY is STOPS's, though LEGS has one too; DISTANCE, which STOPS has not, is LEGS's. A
        // block's ROWID is no item.
        assertThat(uses("ROUTE_DESK/STOPS/WHEN-NEW-RECORD-INSTANCE")).containsExactly(
            ":note WRITE forms:ROUTE_DESK/STOPS/NOTE RESOLVED -",
            ":city READ forms:ROUTE_DESK/STOPS/CITY RESOLVED -",
            ":distance READ forms:ROUTE_DESK/LEGS/DISTANCE RESOLVED -"
        )
        // In a module trigger: DISTANCE is only LEGS's; CITY is both blocks'. A missing block or item is unresolved.
        assertThat(uses("ROUTE_DESK/WHEN-NEW-FORM-INSTANCE").take(8)).containsExactly(
            ":global.route_user WRITE forms::GLOBAL.ROUTE_USER RESOLVED -",
            ":distance WRITE forms:ROUTE_DESK/LEGS/DISTANCE RESOLVED -",
            ":city READ - AMBIGUOUS AMBIGUOUS [forms:ROUTE_DESK/LEGS/CITY,forms:ROUTE_DESK/STOPS/CITY]",
            ":stops.city WRITE forms:ROUTE_DESK/STOPS/CITY RESOLVED -",
            ":parameter.p_city READ forms:ROUTE_DESK/PARAMETER/P_CITY RESOLVED -",
            ":parameter.p_none READ - UNRESOLVED NO_SUCH_PARAMETER",
            ":nowhere.city READ - UNRESOLVED NO_SUCH_BLOCK",
            ":stops.nothing READ - UNRESOLVED NO_SUCH_ITEM"
        )
    }

    @Test
    fun `a parameter is an entity of its module, a global one entity for every module, a system variable nothing`() {
        assertThat(query("""
            SELECT e.kind || ' ' || e.key || ' ' || e.display || ' ' || e.name || ' ' || coalesce(p.key, '-') FROM entity e LEFT JOIN entity p ON p.id = e.parent_id
            WHERE e.kind IN ('FORMS_PARAMETER', 'FORMS_GLOBAL') ORDER BY e.key""")).containsExactly(
            "FORMS_GLOBAL forms::GLOBAL.ROUTE_USER :GLOBAL.ROUTE_USER ROUTE_USER -",
            "FORMS_PARAMETER forms:ROUTE_DESK/PARAMETER/P_CITY ROUTE_DESK/PARAMETER/P_CITY P_CITY forms:ROUTE_DESK",
            "FORMS_PARAMETER forms:ROUTE_PLAIN/PARAMETER/P_CITY ROUTE_PLAIN/PARAMETER/P_CITY P_CITY forms:ROUTE_PLAIN"
        )
        // ROUTE_DESK (and its plain twin) sets the global, ROUTE_VIEW reads it.
        assertThat(query("""
            SELECT u.name || ' ' || o.roles FROM occurrence o JOIN unit u ON u.id = o.unit_id JOIN entity e ON e.id = o.entity_id
            WHERE e.key = 'forms::GLOBAL.ROUTE_USER' ORDER BY u.name""")).containsExactly(
            "ROUTE_DESK/WHEN-NEW-FORM-INSTANCE WRITE", "ROUTE_PLAIN/WHEN-NEW-FORM-INSTANCE WRITE", "ROUTE_VIEW/WHEN-NEW-FORM-INSTANCE READ"
        )
        assertThat(query("SELECT count(*) FROM occurrence WHERE lower(written) LIKE ':system.%' OR lower(written) LIKE '%.rowid'")).containsExactly("0")
    }

    @Test
    fun `a built-in's literal calls the block, the item, the trigger or the module it names`() {
        assertThat(uses("ROUTE_DESK/WHEN-NEW-FORM-INSTANCE").drop(8)).containsExactly(
            "'LEGS' CALL forms:ROUTE_DESK/LEGS RESOLVED -",
            "'LEGS.DISTANCE' CALL forms:ROUTE_DESK/LEGS/DISTANCE RESOLVED -",
            "'distance' CALL forms:ROUTE_DESK/LEGS/DISTANCE RESOLVED -",
            // From the module, with no module-level CHECK_STOP: the one trigger of that name, a guess.
            "'CHECK_STOP' CALL forms:ROUTE_DESK/STOPS/STOP_ID/CHECK_STOP UNVERIFIED TRIGGER_FOUND_BY_NAME",
            "'route_view' CALL forms:ROUTE_VIEW RESOLVED -",
            "'/forms/route_view.fmx' CALL forms:ROUTE_VIEW RESOLVED -",
            "'ROUTE_GONE' CALL - UNRESOLVED MODULE_NOT_INDEXED",
            // The module's REFRESH_STOP, unless the cursor is on STOPS, whose REFRESH_STOP runs instead; LOG_ROUTE is the module's alone.
            "'REFRESH_STOP' CALL forms:ROUTE_DESK/REFRESH_STOP UNVERIFIED TRIGGER_MAY_BE_OVERRIDDEN",
            "'LOG_ROUTE' CALL forms:ROUTE_DESK/LOG_ROUTE RESOLVED -"
        )
        // From the item STOP_ID: its own trigger, then its block's (not the module's REFRESH_STOP), then the module's.
        assertThat(uses("ROUTE_DESK/STOPS/STOP_ID/WHEN-VALIDATE-ITEM").drop(3)).containsExactly(
            "'check_stop' CALL forms:ROUTE_DESK/STOPS/STOP_ID/CHECK_STOP RESOLVED -",
            "'REFRESH_STOP' CALL forms:ROUTE_DESK/STOPS/REFRESH_STOP RESOLVED -",
            "'LOG_ROUTE' CALL forms:ROUTE_DESK/LOG_ROUTE RESOLVED -"
        )
        assertThat(uses("ROUTE_VIEW/WHEN-NEW-FORM-INSTANCE")).contains("'route_desk' CALL forms:ROUTE_DESK RESOLVED -")
        // A name in a variable: not followed, counted.
        assertThat(query("""
            SELECT n.kind || ': ' || n.message FROM coverage_note n JOIN source_file f ON f.id = n.file_id
            WHERE f.path = 'route_desk_fmb.xml' AND n.kind = 'FORMS_NAME_NOT_LITERAL'""")).containsExactly(
            "FORMS_NAME_NOT_LITERAL: 4 call(s) of a built-in that names a block, an item, a trigger or a module with an expression, not a literal: " +
                "what they name is not followed (CALL_FORM 1, EXECUTE_TRIGGER 1, GO_BLOCK 1, GO_ITEM 1)"
        )
    }

    @Test
    fun `a trigger a program unit finds by name only, or the module's when a block has one of that name too, is not certain`() {
        val root = temp.resolve("nam").createDirectories()
        root.resolve("nam_fmb.xml").writeText(NAM)
        val nam = temp.resolve("nam.db")
        Indexer().index(IndexOptions(root, nam, compilationSchema = "HR", threads = 1))
        assertThat(Fixture.query(nam, """
            SELECT u.name || ' ' || o.written || ' ' || coalesce(e.key, '-') || ' ' || o.status || ' ' || coalesce(o.reason, '-')
            FROM occurrence o JOIN unit u ON u.id = o.unit_id LEFT JOIN entity e ON e.id = o.entity_id
            WHERE o.written LIKE '''%' ORDER BY o.id""")).containsExactly(
            // The item's code looks on X, on A, on the module: B's trigger is not there.
            "NAM/A/X/WHEN-VALIDATE-ITEM 'ONLY_B' - UNRESOLVED NO_SUCH_TRIGGER",
            // A program unit does not know the cursor's item: B's trigger, found by its name alone.
            "NAM/P 'ONLY_B' forms:NAM/B/ONLY_B UNVERIFIED TRIGGER_FOUND_BY_NAME",
            // The module's own trigger is found wherever the cursor is.
            "NAM/P 'ON_MODULE' forms:NAM/ON_MODULE RESOLVED -",
            // The module's SHARED, but B's runs instead when the cursor is on B.
            "NAM/P 'SHARED' forms:NAM/SHARED UNVERIFIED TRIGGER_MAY_BE_OVERRIDDEN"
        )
    }

    @Test
    fun `a module exported without DUMP=ALL gives the same uses`() {
        for (unit in listOf("STOPS/STOP_ID/WHEN-VALIDATE-ITEM", "STOPS/WHEN-NEW-RECORD-INSTANCE", "WHEN-NEW-FORM-INSTANCE")) {
            assertThat(uses("ROUTE_PLAIN/$unit").map { it.replace("ROUTE_PLAIN", "ROUTE_DESK") }).isEqualTo(uses("ROUTE_DESK/$unit"))
        }
    }

    @Test
    fun `a call of a module follows the modules of each run, the caller not walked again`() {
        val root = temp.resolve("alone").createDirectories()
        javaClass.getResourceAsStream("/forms/route_desk_fmb.xml")!!.use { Files.copy(it, root.resolve("route_desk_fmb.xml")) }
        val alone = temp.resolve("alone.db")
        fun index() = Indexer().index(IndexOptions(root, alone, compilationSchema = "HR", threads = 2), incremental = true)
        fun calls() = Fixture.query(alone, """
            SELECT o.written || ' ' || coalesce(e.key, '-') || ' ' || o.status || ' ' || coalesce(o.reason, '-') FROM occurrence o
            LEFT JOIN entity e ON e.id = o.entity_id WHERE lower(o.written) LIKE '%route_view%' ORDER BY o.id""")
        index()
        assertThat(calls()).containsExactly(
            "'route_view' - UNRESOLVED MODULE_NOT_INDEXED", "'/forms/route_view.fmx' - UNRESOLVED MODULE_NOT_INDEXED"
        )
        javaClass.getResourceAsStream("/forms/route_view_fmb.xml")!!.use { Files.copy(it, root.resolve("route_view_fmb.xml")) }
        val report = index().incremental!!
        assertThat(listOf(report.walked, report.changed, report.new)).containsExactly(1, 0, 1)
        assertThat(calls()).containsExactly("'route_view' forms:ROUTE_VIEW RESOLVED -", "'/forms/route_view.fmx' forms:ROUTE_VIEW RESOLVED -")
    }

    /**
     * The whole Doag corpus of `frmf2xml` exports, when the build is given it (`-PdoagForms=<folder with frm2xml/>`): its
     * uses of Forms names by the kind of their entity (`-` for none), status, reason and roles.
     */
    @Test
    fun `the Doag modules give their uses of Forms names`() {
        val folder = System.getProperty("doagForms")?.let { Path.of(it).resolve("frm2xml") }
        assumeTrue(folder != null && folder.isDirectory(), "-PdoagForms not given")
        val root = temp.resolve("doag").createDirectories()
        for (file in folder!!.listDirectoryEntries("*_fmb.xml")) file.copyTo(root.resolve(file.fileName.toString()))
        val doag = temp.resolve("doag.db")
        Indexer().index(IndexOptions(root, doag, compilationSchema = "DEMO", threads = 2))
        assertThat(Fixture.query(doag, "SELECT count(*) FROM entity WHERE kind = 'FORMS_PARAMETER'")).containsExactly("83")
        assertThat(Fixture.query(doag, DOAG_USES)).containsExactly(*DOAG.toTypedArray())
    }

    private companion object {
        const val ROUTE_NEXT = "CREATE OR REPLACE PROCEDURE route_next(p_city OUT VARCHAR2) IS\nBEGIN\n  p_city := 'X';\nEND;\n/\n"

        /**
         * Blocks `A` (item `X`) and `B`; only `B` has the user-named trigger `ONLY_B`, the module has `ON_MODULE`, and both
         * have `SHARED`.
         */
        const val NAM = """<Module version="122010300" xmlns="http://xmlns.oracle.com/Forms"><FormModule Name="NAM">
<Trigger Name="ON_MODULE" TriggerText="null;"/>
<Trigger Name="SHARED" TriggerText="null;"/>
<Block Name="A"><Item Name="X"><Trigger Name="WHEN-VALIDATE-ITEM" TriggerText="execute_trigger('ONLY_B');"/></Item></Block>
<Block Name="B"><Trigger Name="ONLY_B" TriggerText="null;"/><Trigger Name="SHARED" TriggerText="null;"/></Block>
<ProgramUnit Name="P" ProgramUnitText="PROCEDURE p IS&amp;#10;BEGIN&amp;#10;  execute_trigger('ONLY_B');&amp;#10;  execute_trigger('ON_MODULE');&amp;#10;  execute_trigger('SHARED');&amp;#10;END;"/>
</FormModule></Module>"""

        /** The uses of Forms names of an index, counted by entity kind, status, reason and roles. */
        const val DOAG_USES = """
            SELECT coalesce(e.kind, '-') || ' ' || o.status || ' ' || coalesce(o.reason, '-') || ' ' || o.roles || ' ' || count(*)
            FROM occurrence o JOIN source_file f ON f.id = o.file_id LEFT JOIN entity e ON e.id = o.entity_id
            WHERE f.kind = 'FORMS_MODULE' AND o.origin = 'STATIC' AND (o.written LIKE ':%' OR o.written LIKE '''%')
              AND (e.kind LIKE 'FORMS_%' OR o.reason IN ('AMBIGUOUS', 'NO_SUCH_BLOCK', 'NO_SUCH_ITEM', 'NO_SUCH_PARAMETER', 'NO_SUCH_TRIGGER', 'MODULE_NOT_INDEXED'))
            GROUP BY coalesce(e.kind, '-'), o.status, o.reason, o.roles ORDER BY 1"""

        /**
         * What the Doag modules give: two `EXECUTE_TRIGGER`s of a program unit find the trigger on several items, three
         * `CALL_FORM`s call a module the corpus does not have, and two `GO_ITEM('builtins.filename')` name an item the
         * block does not have (its item is `FILE_NAME`). One `EXECUTE_TRIGGER` of a program unit finds its trigger on one
         * item only: by its name alone.
         */
        val DOAG = listOf(
            "- AMBIGUOUS AMBIGUOUS CALL 2",
            "- UNRESOLVED MODULE_NOT_INDEXED CALL 3",
            "- UNRESOLVED NO_SUCH_ITEM CALL 2",
            "FORMS_BLOCK RESOLVED - CALL 36",
            "FORMS_GLOBAL RESOLVED - READ 85",
            "FORMS_GLOBAL RESOLVED - WRITE 36",
            "FORMS_ITEM RESOLVED - CALL 73",
            "FORMS_ITEM RESOLVED - READ 486",
            "FORMS_ITEM RESOLVED - WRITE 376",
            "FORMS_MODULE RESOLVED - CALL 11",
            "FORMS_PARAMETER RESOLVED - READ 708",
            "FORMS_PARAMETER RESOLVED - WRITE 94",
            "FORMS_TRIGGER RESOLVED - CALL 1",
            "FORMS_TRIGGER UNVERIFIED TRIGGER_FOUND_BY_NAME CALL 1"
        )
    }
}
