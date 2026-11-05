//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.IndexSummary
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Forms modules indexed next to the fixture's HR sources: `order_desk_fmb.xml` (exported with `DUMP=ALL`), its copy
 * under another file name and under the same name in another folder, `order_lite_fmb.xml` (without `DUMP=ALL`),
 * `pkg_check_fmb.xml` (without `DUMP=ALL`, inheriting nothing) and an object library.
 */
class FormsIndexTest {
    @TempDir
    lateinit var temp: Path

    private val sources: Path by lazy {
        val root = temp.resolve("src")
        Files.walk(Fixture.sources.resolve("hr")).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { it.copyTo(root.resolve("hr").resolve(Fixture.sources.resolve("hr").relativize(it).toString()).also { target -> target.parent.createDirectories() }) }
        }
        for (name in listOf("order_desk_fmb.xml", "order_lite_fmb.xml")) copy(name, root.resolve("forms").createDirectories().resolve(name))
        // Another form with the same Name: a module of its own. The same file name in another folder: the same module.
        copy("order_desk_fmb.xml", root.resolve("forms/zz_copy_fmb.xml"))
        copy("order_desk_fmb.xml", root.resolve("forms/zz").createDirectories().resolve("order_desk_fmb.xml"))
        root.resolve("forms/pkg_check_fmb.xml").writeText(PKG_CHECK)
        root.resolve("forms/shared_olb.xml").writeText("<Module/>")
        root
    }

    /** Copies the module [name] of the test fixtures to [target]. */
    private fun copy(name: String, target: Path) = javaClass.getResourceAsStream("/forms/$name")!!.use { Files.copy(it, target) }

    private val db: Path get() = temp.resolve("atlas.db")

    private fun index(incremental: Boolean): IndexSummary =
        Indexer().index(IndexOptions(sources, db, compilationSchema = "HR", dictionary = Fixture.dictionary, threads = 2), incremental = incremental)

    /** A full run, which writes the facts cache an incremental run reads. */
    private val indexed by lazy { index(incremental = true) }

    private fun query(sql: String): List<String> {
        indexed
        return Fixture.query(db, sql)
    }

    @Test
    fun `a module gives its units, entities and relations, the inherited trigger included`() {
        assertThat(query("SELECT kind || ' ' || parse_status FROM source_file WHERE path = 'forms/order_desk_fmb.xml'")).containsExactly("FORMS_MODULE OK")
        assertThat(query("""
            SELECT u.kind || ' ' || u.name || ' ' || u.start_line || '-' || u.end_line || ' ' || coalesce(e.key, '-')
            FROM unit u JOIN source_file f ON f.id = u.file_id LEFT JOIN entity e ON e.id = u.entity_id
            WHERE f.path = 'forms/order_desk_fmb.xml' ORDER BY u.id""")).containsExactly(
            "FORMS_TRIGGER ORDER_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM 1-8 forms:ORDER_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM",
            "FORMS_TRIGGER ORDER_DESK/COMPANY/POST-QUERY 10-18 forms:ORDER_DESK/COMPANY/POST-QUERY",
            "FORMS_TRIGGER ORDER_DESK/COMPANY/KEY-EXIT 20-22 forms:ORDER_DESK/COMPANY/KEY-EXIT",
            "FORMS_TRIGGER ORDER_DESK/WHEN-NEW-FORM-INSTANCE 24-28 forms:ORDER_DESK/WHEN-NEW-FORM-INSTANCE",
            "FORMS_PROGRAM_UNIT ORDER_DESK/PROC_STANDALONE 31-35 forms:ORDER_DESK/PROC_STANDALONE",
            "FORMS_PROGRAM_UNIT ORDER_DESK/FN_LABEL 38-43 forms:ORDER_DESK/FN_LABEL",
            "FORMS_PROGRAM_UNIT ORDER_DESK/ORDER_UTIL 46-49 forms:ORDER_DESK/ORDER_UTIL",
            "FORMS_PROGRAM_UNIT ORDER_DESK/ORDER_UTIL 52-60 forms:ORDER_DESK/ORDER_UTIL",
            "SQL_STATEMENT ORDER_DESK/COMPANY_LOV 63-66 forms:ORDER_DESK/COMPANY_LOV"
        )
        assertThat(query("""
            SELECT e.kind || ' ' || e.key || ' ' || e.display || ' ' || coalesce(p.key, '-') FROM entity e LEFT JOIN entity p ON p.id = e.parent_id
            WHERE e.key LIKE 'forms:ORDER_DESK%' AND e.kind LIKE 'FORMS%' ORDER BY e.key""")).containsExactly(
            "FORMS_MODULE forms:ORDER_DESK ORDER_DESK -",
            "FORMS_BLOCK forms:ORDER_DESK/COMPANY ORDER_DESK/COMPANY forms:ORDER_DESK",
            "FORMS_ITEM forms:ORDER_DESK/COMPANY/COMPANY_NAME ORDER_DESK/COMPANY/COMPANY_NAME forms:ORDER_DESK/COMPANY",
            "FORMS_ITEM forms:ORDER_DESK/COMPANY/ID ORDER_DESK/COMPANY/ID forms:ORDER_DESK/COMPANY",
            "FORMS_TRIGGER forms:ORDER_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM ORDER_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM forms:ORDER_DESK/COMPANY/ID",
            "FORMS_TRIGGER forms:ORDER_DESK/COMPANY/KEY-EXIT ORDER_DESK/COMPANY/KEY-EXIT forms:ORDER_DESK/COMPANY",
            "FORMS_ITEM forms:ORDER_DESK/COMPANY/LABEL ORDER_DESK/COMPANY/LABEL forms:ORDER_DESK/COMPANY",
            "FORMS_TRIGGER forms:ORDER_DESK/COMPANY/POST-QUERY ORDER_DESK/COMPANY/POST-QUERY forms:ORDER_DESK/COMPANY",
            "FORMS_RECORD_GROUP forms:ORDER_DESK/COMPANY_LOV ORDER_DESK/COMPANY_LOV forms:ORDER_DESK",
            "FORMS_PROGRAM_UNIT forms:ORDER_DESK/FN_LABEL ORDER_DESK/FN_LABEL forms:ORDER_DESK",
            "FORMS_LIBRARY forms:ORDER_DESK/ORDER_LIB ORDER_DESK/ORDER_LIB forms:ORDER_DESK",
            "FORMS_PROGRAM_UNIT forms:ORDER_DESK/ORDER_UTIL ORDER_DESK/ORDER_UTIL forms:ORDER_DESK",
            "FORMS_PROGRAM_UNIT forms:ORDER_DESK/PROC_STANDALONE ORDER_DESK/PROC_STANDALONE forms:ORDER_DESK",
            "FORMS_TRIGGER forms:ORDER_DESK/WHEN-NEW-FORM-INSTANCE ORDER_DESK/WHEN-NEW-FORM-INSTANCE forms:ORDER_DESK"
        )
        assertThat(query("""
            SELECT f.key || ' ' || r.kind || ' ' || t.key FROM relation r JOIN entity f ON f.id = r.from_id JOIN entity t ON t.id = r.to_id
            WHERE f.key LIKE 'forms:ORDER_DESK%' AND f.kind LIKE 'FORMS%' AND t.kind LIKE 'FORMS%' ORDER BY 1""")).containsExactly(
            "forms:ORDER_DESK CONTAINS forms:ORDER_DESK/COMPANY",
            "forms:ORDER_DESK CONTAINS forms:ORDER_DESK/COMPANY_LOV",
            "forms:ORDER_DESK CONTAINS forms:ORDER_DESK/FN_LABEL",
            "forms:ORDER_DESK CONTAINS forms:ORDER_DESK/ORDER_UTIL",
            "forms:ORDER_DESK CONTAINS forms:ORDER_DESK/PROC_STANDALONE",
            "forms:ORDER_DESK CONTAINS forms:ORDER_DESK/WHEN-NEW-FORM-INSTANCE",
            "forms:ORDER_DESK FORMS_ATTACHED_LIBRARY forms:ORDER_DESK/ORDER_LIB",
            "forms:ORDER_DESK/COMPANY CONTAINS forms:ORDER_DESK/COMPANY/COMPANY_NAME",
            "forms:ORDER_DESK/COMPANY CONTAINS forms:ORDER_DESK/COMPANY/ID",
            "forms:ORDER_DESK/COMPANY CONTAINS forms:ORDER_DESK/COMPANY/KEY-EXIT",
            "forms:ORDER_DESK/COMPANY CONTAINS forms:ORDER_DESK/COMPANY/LABEL",
            "forms:ORDER_DESK/COMPANY CONTAINS forms:ORDER_DESK/COMPANY/POST-QUERY",
            "forms:ORDER_DESK/COMPANY/ID CONTAINS forms:ORDER_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM"
        )
        // Parameters and locals hang from their program unit or trigger; a package's from the package.
        assertThat(query("""
            SELECT e.kind || ' ' || e.key || ' ' || p.key FROM entity e JOIN entity p ON p.id = e.parent_id
            WHERE e.key LIKE 'forms:ORDER_DESK%' AND e.kind NOT LIKE 'FORMS%' ORDER BY e.key""")).containsExactly(
            "LOCAL_VARIABLE forms:ORDER_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM.V_COUNT forms:ORDER_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM",
            "LOCAL_VARIABLE forms:ORDER_DESK/COMPANY/POST-QUERY.V_LABEL forms:ORDER_DESK/COMPANY/POST-QUERY",
            "PARAMETER forms:ORDER_DESK/FN_LABEL.P_ID forms:ORDER_DESK/FN_LABEL",
            "LOCAL_VARIABLE forms:ORDER_DESK/FN_LABEL.V_LABEL forms:ORDER_DESK/FN_LABEL",
            "PACKAGE_VARIABLE forms:ORDER_DESK/ORDER_UTIL.G_REFRESHED forms:ORDER_DESK/ORDER_UTIL",
            "PARAMETER forms:ORDER_DESK/ORDER_UTIL.REFRESH(P_ID IN NUMBER).P_ID forms:ORDER_DESK/ORDER_UTIL",
            "LOCAL_VARIABLE forms:ORDER_DESK/ORDER_UTIL.REFRESH(P_ID IN NUMBER).V_NEXT forms:ORDER_DESK/ORDER_UTIL",
            "PARAMETER forms:ORDER_DESK/PROC_STANDALONE.P_ID forms:ORDER_DESK/PROC_STANDALONE"
        )
    }

    /** The calls of [path] as `line written key status`. */
    private fun calls(path: String) = query("""
        SELECT o.start_line || ' ' || o.written || ' ' || coalesce(e.key, '-') || ' ' || o.status FROM occurrence o
        JOIN source_file f ON f.id = o.file_id LEFT JOIN entity e ON e.id = o.entity_id
        WHERE f.path = '$path' AND (',' || o.roles || ',') LIKE '%,CALL,%' ORDER BY o.id""")

    @Test
    fun `a name of the module's own code wins over the database, and a Forms built-in gives nothing`() {
        assertThat(calls("forms/order_desk_fmb.xml")).containsExactly(
            // count is Oracle's own: recorded as any built-in function the index does not know.
            "5 count - UNRESOLVED",
            // A routine of the module's package is the package.
            "7 order_util.refresh forms:ORDER_DESK/ORDER_UTIL RESOLVED",
            "14 fn_label forms:ORDER_DESK/FN_LABEL RESOLVED",
            // The module's PROC_STANDALONE, not HR's; go_block and execute_query are Forms built-ins.
            "26 proc_standalone forms:ORDER_DESK/PROC_STANDALONE RESOLVED",
            "56 pack_process.f_next db:HR.PACK_PROCESS.F_NEXT() RESOLVED",
            "57 pack_process.run_job - AMBIGUOUS"
        )
        // ORDER_LITE has no PROC_STANDALONE of its own: HR's.
        assertThat(calls("forms/order_lite_fmb.xml")).containsExactly("3 proc_standalone db:HR.PROC_STANDALONE(P_ID IN NUMBER) RESOLVED")
        assertThat(query("SELECT written FROM occurrence WHERE lower(written) IN ('go_block', 'execute_query', 'message', 'exit_form')")).isEmpty()
    }

    @Test
    fun `the coverage notes count what was left out`() {
        assertThat(query("""
            SELECT coalesce(f.path, '-') || ' ' || n.kind || ': ' || n.message FROM coverage_note n LEFT JOIN source_file f ON f.id = n.file_id
            WHERE n.kind LIKE 'FORMS%' ORDER BY 1""")).containsExactly(
            "- FORMS_FILE_SKIPPED: 1 object library file(s) not indexed: forms/shared_olb.xml",
            "- FORMS_MODULE_DUPLICATED: 1 module file(s) not indexed: a file of the same name in another folder, first by path, is: " +
                "forms/zz/order_desk_fmb.xml (indexed from forms/order_desk_fmb.xml)",
            // pkg_check_fmb.xml, without DUMP=ALL too, inherits nothing: no note.
            "forms/order_lite_fmb.xml FORMS_NOT_FULL_DUMP: Exported without DUMP=ALL: only the properties the module sets itself are in the file, " +
                "so the code of the 1 object(s) it takes from other modules (ParentModule) may be missing. Export with frmf2xml DUMP=ALL."
        )
    }

    @Test
    fun `a module is known by its file's name, and its Name is its internal name`() {
        assertThat(query("""
            SELECT e.key || ' ' || e.name || ' ' || coalesce(d.value, '-') FROM entity e LEFT JOIN declared_text d ON d.entity_id = e.id
            WHERE e.kind = 'FORMS_MODULE' ORDER BY e.key""")).containsExactly(
            "forms:ORDER_DESK ORDER_DESK -", "forms:ORDER_LITE ORDER_LITE -", "forms:PKG_CHECK PKG_CHECK -", "forms:ZZ_COPY ZZ_COPY ORDER_DESK"
        )
        assertThat(query("SELECT u.name FROM unit u JOIN source_file f ON f.id = u.file_id WHERE f.path = 'forms/zz_copy_fmb.xml' ORDER BY u.id LIMIT 2"))
            .containsExactly("ZZ_COPY/COMPANY/ID/WHEN-VALIDATE-ITEM", "ZZ_COPY/COMPANY/POST-QUERY")
        assertThat(query("SELECT path FROM source_file WHERE kind = 'FORMS_MODULE' ORDER BY path"))
            .containsExactly("forms/order_desk_fmb.xml", "forms/order_lite_fmb.xml", "forms/pkg_check_fmb.xml", "forms/zz_copy_fmb.xml")
    }

    @Test
    fun `a member a module's package does not declare is unknown`() {
        assertThat(query("""
            SELECT o.written || ' ' || coalesce(e.key, '-') || ' ' || o.status || ' ' || coalesce(o.reason, '-') FROM occurrence o
            JOIN source_file f ON f.id = o.file_id LEFT JOIN entity e ON e.id = o.entity_id
            WHERE f.path = 'forms/pkg_check_fmb.xml' AND o.roles = 'CALL' ORDER BY o.id""")).containsExactly(
            "pkg.m forms:PKG_CHECK/PKG RESOLVED -", "pkg.nope - UNRESOLVED UNKNOWN_MEMBER"
        )
    }

    @Test
    fun `an incremental run walks only the module whose file changed`() {
        indexed
        val lite = sources.resolve("forms/order_lite_fmb.xml")
        lite.writeText(lite.readText().replace("proc_standalone(2);", "proc_standalone(2);&amp;#10;proc_standalone(3);"))
        val report = index(incremental = true).incremental!!
        assertThat(report.fullReason).isNull()
        assertThat(listOf(report.walked, report.changed, report.new, report.affected)).containsExactly(1, 1, 0, 0)
        assertThat(calls("forms/order_lite_fmb.xml")).containsExactly(
            "3 proc_standalone db:HR.PROC_STANDALONE(P_ID IN NUMBER) RESOLVED", "4 proc_standalone db:HR.PROC_STANDALONE(P_ID IN NUMBER) RESOLVED"
        )
    }

    private companion object {
        /** A module without DUMP=ALL that inherits nothing, with a package and calls of a member it declares and one it does not. */
        const val PKG_CHECK = """<Module version="122010300" xmlns="http://xmlns.oracle.com/Forms"><FormModule Name="PKG_CHECK">
<Trigger Name="WHEN-NEW-FORM-INSTANCE" TriggerText="pkg.m();&amp;#10;pkg.nope();"/>
<ProgramUnit Name="PKG" ProgramUnitText="PACKAGE pkg IS&amp;#10;  PROCEDURE m;&amp;#10;END;"/>
<ProgramUnit Name="PKG" ProgramUnitText="PACKAGE BODY pkg IS&amp;#10;  PROCEDURE m IS BEGIN NULL; END;&amp;#10;END;"/>
</FormModule></Module>"""
    }
}
