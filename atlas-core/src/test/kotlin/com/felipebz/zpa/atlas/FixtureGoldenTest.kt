//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FixtureGoldenTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy { Fixture.index(temp.resolve("fixture.db")) }

    private fun occurrences(database: Path = db) = Fixture.query(database, Fixture.OCCURRENCES)

    private fun dump(database: Path): String = buildString {
        for ((title, sql) in listOf(
            "occurrence" to Fixture.OCCURRENCES,
            "occurrence_name" to "SELECT f.path, o.start_line || ':' || o.start_col || '-' || o.end_line || ':' || o.end_col, o.written, o.name_start_line || ':' || o.name_start_col || '-' || o.name_end_line || ':' || o.name_end_col FROM occurrence o JOIN source_file f ON f.id = o.file_id WHERE o.name_start_line IS NOT NULL ORDER BY o.id",
            "occurrence_origin" to "SELECT f.path, o.start_line || ':' || o.start_col || '-' || o.end_line || ':' || o.end_col, o.written, o.origin FROM occurrence o JOIN source_file f ON f.id = o.file_id WHERE o.origin <> 'STATIC' ORDER BY o.id",
            "entity" to "SELECT key, kind, coalesce(schema_name, '-'), name, coalesce((SELECT p.key FROM entity p WHERE p.id = e.parent_id), '-'), display, external FROM entity e ORDER BY id",
            "relation" to "SELECT f.key, r.kind, t.key FROM relation r JOIN entity f ON f.id = r.from_id JOIN entity t ON t.id = r.to_id ORDER BY 1, 2, 3",
            "source_file" to "SELECT path, kind, size_bytes, coalesce(compilation_schema, '-'), parse_status, recovered_regions FROM source_file ORDER BY id",
            "unit" to "SELECT f.path, u.kind, u.name, coalesce(e.key, '-'), u.start_line || ':' || u.start_col || '-' || u.end_line || ':' || u.end_col FROM unit u JOIN source_file f ON f.id = u.file_id LEFT JOIN entity e ON e.id = u.entity_id ORDER BY u.id",
            "sql_statement" to "SELECT f.path, s.kind, coalesce(e.key, '-'), s.start_line || ':' || s.start_col || '-' || s.end_line || ':' || s.end_col FROM sql_statement s JOIN source_file f ON f.id = s.file_id LEFT JOIN entity e ON e.id = s.container_entity_id ORDER BY s.id",
            "occurrence_candidate" to "SELECT f.path, o.start_line || ':' || o.start_col, o.written, e.key FROM occurrence_candidate c JOIN occurrence o ON o.id = c.occurrence_id JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = c.entity_id ORDER BY c.occurrence_id, c.entity_id",
            "call_argument" to "SELECT f.path, o.start_line || ':' || o.start_col, o.written, a.ordinal, coalesce(a.label, '-'), coalesce(e.key, '-'), a.start_line || ':' || a.start_col || '-' || a.end_line || ':' || a.end_col, a.value_kind, coalesce(a.value, '-') FROM call_argument a JOIN occurrence o ON o.id = a.occurrence_id JOIN source_file f ON f.id = o.file_id LEFT JOIN entity e ON e.id = a.parameter_entity_id ORDER BY a.occurrence_id, a.ordinal",
            "written_value" to "SELECT f.path, o.start_line || ':' || o.start_col, o.written, coalesce(e.key, '-'), v.row_number, v.value_kind, coalesce(v.value, '-'), v.start_line || ':' || v.start_col || '-' || v.end_line || ':' || v.end_col FROM written_value v JOIN occurrence o ON o.id = v.occurrence_id JOIN source_file f ON f.id = o.file_id LEFT JOIN entity e ON e.id = o.entity_id ORDER BY v.occurrence_id, v.row_number",
            "coverage_note" to "SELECT coalesce(f.path, '-'), c.kind, c.message FROM coverage_note c LEFT JOIN source_file f ON f.id = c.file_id ORDER BY c.id",
            "trigger_event" to "SELECT t.key, e.timing, coalesce(e.level, '-'), e.event, coalesce(c.key, '-') FROM trigger_event e JOIN entity t ON t.id = e.trigger_id LEFT JOIN entity c ON c.id = e.column_id ORDER BY t.key, e.event, e.timing, c.key"
        )) {
            append("## ").append(title).append('\n')
            Fixture.query(database, sql).forEach { append(it).append('\n') }
        }
    }

    @Test
    fun `every table matches the golden rows`() {
        val actual = dump(db)
        if (System.getProperty("atlas.updateGolden") == "true") {
            Files.writeString(Path.of("src/test/resources/golden/fixture.txt"), actual)
            return
        }
        val expected = String(Files.readAllBytes(Path.of(javaClass.getResource("/golden/fixture.txt")!!.toURI())), Charsets.UTF_8)
        if (actual != expected) Files.writeString(Path.of("build", "fixture-actual.txt"), actual)
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun `a DDL role is the only role of its occurrence, on a DDL statement row`() {
        val ddl = "(',' || o.roles || ',') LIKE '%,DDL\\_%' ESCAPE '\\'"
        assertThat(Fixture.query(db, "SELECT count(*) FROM occurrence o WHERE $ddl").single().toInt()).isGreaterThan(0)
        assertThat(Fixture.query(db, "SELECT o.roles FROM occurrence o WHERE $ddl AND o.roles LIKE '%,%'")).isEmpty()
        assertThat(Fixture.query(db, "SELECT o.written FROM occurrence o LEFT JOIN sql_statement s ON s.id = o.statement_id WHERE $ddl AND coalesce(s.kind, '-') <> 'DDL'")).isEmpty()
    }

    @Test
    fun `no placeholder of a dynamic text becomes a name`() {
        assertThat(Fixture.query(db, "SELECT count(*) FROM occurrence WHERE origin = 'DYNAMIC_LITERAL'").single().toInt()).isGreaterThan(0)
        assertThat(Fixture.query(db, "SELECT written FROM occurrence WHERE written LIKE '%ZPA$%'")).isEmpty()
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE key LIKE '%ZPA$%'")).isEmpty()
    }

    @Test
    fun `a write inside a package body procedure is contained by the body entity of that procedure`() {
        assertThat(occurrences()).contains(
            "hr/pack_process.pkb\t10:23-10:32\tst_active\tdb:HR.COMPANY.ST_ACTIVE\tSQL_UPDATE,WRITE\tRESOLVED\t-\t" +
                "db:HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE)@BODY\tUPDATE",
            // A private body procedure is its own container too.
            "hr/pack_process.pkb\t4:16-4:25\tevent_log\tdb:HR.EVENT_LOG\tSQL_INSERT\tRESOLVED\t-\t" +
                "db:HR.PACK_PROCESS.WRITE_LOG(P_MSG IN VARCHAR2)@BODY\tINSERT"
        )
    }

    @Test
    fun `names defined by WITH, collection expressions and DUAL are not relations`() {
        val load = occurrences().filter { it.startsWith("scripts/load.sql\t4:") || it.startsWith("scripts/load.sql\t5:") }
        assertThat(load.filter { it.split('\t')[4].contains("SQL_") }.map { it.split('\t')[3] }).containsExactly("db:HR.COMPANY", "db:HR.V_COMPANY")
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE kind IN ('TABLE', 'VIEW', 'OBJECT_TABLE', 'UNKNOWN_RELATION', 'COLUMN') AND (key LIKE '%.W' OR key LIKE '%DUAL%' OR key LIKE '%ODCINUMBERLIST%')")).isEmpty()
    }

    @Test
    fun `the alias in REF and VALUE is never a column, VALUE reads and SET VALUE writes every column`() {
        fun objects(database: Path) = occurrences(database).filter { it.startsWith("hr/proc_objects.prc\t") }.map { it.split('\t') }
        for (database in listOf(db, Fixture.index(temp.resolve("objects-bare.db"), dictionary = AtlasDictionary.NONE))) {
            assertThat(objects(database).filter { it[4].contains("READ") }.map { it[3] }).noneMatch { it.endsWith(".X") || it.endsWith(".T") }
            assertThat(Fixture.query(database, "SELECT key FROM entity WHERE kind = 'COLUMN' AND key IN ('db:HR.SHAPE_TAB.X', 'db:HR.SHAPE_TAB.S', 'db:HR.SHAPE_HOLDER.H')")).isEmpty()
        }
        val rows = objects(db)
        // value(x) reads the row; ref(x) reads nothing; the correlated ref(x) and the join with SHAPE_HOLDER.X stay aliases.
        assertThat(rows.filter { it[4] == "IMPLICIT,READ" }.map { "${it[1]} ${it[2]} ${it[3]}" })
            .containsExactly("6:17-6:25 value(x) db:HR.SHAPE_TAB.ID", "7:48-7:56 value(s) db:HR.SHAPE_TAB.ID", "19:55-19:63 value(x) db:HR.SHAPE_TAB.ID")
        assertThat(rows.filter { it[4] == "IMPLICIT,SQL_UPDATE,WRITE" }.map { "${it[1]} ${it[3]}" }).containsExactly("13:9-13:18 db:HR.SHAPE_TAB.ID")
        assertThat(rows.filter { it[4] == "READ" && it[1].startsWith("12:") }.map { it[3] }).containsExactly("db:HR.SHAPE_HOLDER.S", "db:HR.SHAPE_HOLDER.ID")
        val notes = Fixture.query(db, "SELECT c.kind || ' ' || c.message FROM coverage_note c JOIN source_file f ON f.id = c.file_id WHERE f.path = 'hr/proc_objects.prc' ORDER BY 1")
        assertThat(notes).noneMatch { it.startsWith("COLUMN_EXPRESSION") }
        // Only the PL/SQL parameters inside TABLE(…) stay undecided, not the aliases of the table functions.
        assertThat(notes).contains("SQL_NAME_UNDECIDED 2 unqualified name(s) in static SQL may be a column of a relation whose columns are unknown or a PL/SQL name: not indexed")
    }

    @Test
    fun `the type of an object table or view is a type use, a subview names its superview, a view's MAKE_REF uses the row type`() {
        fun uses(database: Path, path: String) = occurrences(database).filter { it.startsWith("$path\t") }.map { it.split('\t') }
            .filter { it[4].endsWith("TYPE_USE") }.map { "${it[1]} ${it[2]} ${it[3]} ${it[4]}" }
        assertThat(uses(db, "hr/shape_tab.sql")).contains("1:26-1:34 ty_shape db:HR.TY_SHAPE TYPE_USE")
        assertThat(uses(db, "hr/object_views.sql")).contains(
            "1:38-1:46 ty_shape db:HR.TY_SHAPE TYPE_USE",
            "2:39-2:48 ty_circle db:HR.TY_CIRCLE TYPE_USE", "2:55-2:66 v_shape_obj db:HR.V_SHAPE_OBJ TYPE_USE",
            // In a view, MAKE_REF also uses the row type the dictionary gives the relation; in a procedure it does not.
            "3:53-3:64 v_shape_obj db:HR.V_SHAPE_OBJ TYPE_USE", "3:53-3:64 v_shape_obj db:HR.TY_SHAPE IMPLICIT,TYPE_USE"
        )
        assertThat(uses(db, "hr/object_views.sql")).noneMatch { it.endsWith("IMPLICIT,TYPE_USE") && !it.startsWith("3:") }
        assertThat(uses(db, "hr/proc_objects.prc")).contains(
            "20:55-20:64 shape_tab db:HR.SHAPE_TAB TYPE_USE", "20:55-20:64 shape_tab db:HR.TY_SHAPE IMPLICIT,TYPE_USE"
        )
        // Without the dictionary there is no row type: only the relation's use.
        val bare = Fixture.index(temp.resolve("objects-types-bare.db"), dictionary = AtlasDictionary.NONE)
        assertThat(uses(bare, "hr/proc_objects.prc").filter { it.startsWith("20:") }).containsExactly("20:55-20:64 shape_tab db:HR.SHAPE_TAB TYPE_USE")
    }

    @Test
    fun `MAKE_REF names its relation as a type use, and a view column has the lineage of VALUE but not of REF`() {
        val makeRefs = occurrences().filter { it.startsWith("hr/proc_objects.prc\t") }.map { it.split('\t') }
            .filter { it[4] == "TYPE_USE" && it[3].let { key -> key.endsWith(".SHAPE_TAB") || key.endsWith(".MISSING_OBJ_TAB") } }
        assertThat(makeRefs.map { "${it[2]} ${it[3]} ${it[5]}" }).containsExactly(
            "shape_tab db:HR.SHAPE_TAB RESOLVED", "hr.shape_tab db:HR.SHAPE_TAB RESOLVED", "missing_obj_tab db:HR.MISSING_OBJ_TAB UNRESOLVED",
            "shape_tab db:HR.SHAPE_TAB RESOLVED"
        )
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE kind = 'COLUMN' AND name IN ('SHAPE_TAB', 'MISSING_OBJ_TAB')")).isEmpty()
        assertThat(Fixture.query(db, """SELECT v.name || ' ' || b.key FROM relation r JOIN entity v ON v.id = r.from_id JOIN entity b ON b.id = r.to_id
            WHERE r.kind = 'VIEW_BASE' AND v.key LIKE 'db:HR.V_SHAPES.%' ORDER BY 1""")).containsExactly("ID db:HR.SHAPE_TAB.ID", "SVAL db:HR.SHAPE_TAB.ID")
    }

    @Test
    fun `the name after REF is a type use in every position, and no type name in TREAT or IS OF is a column`() {
        val refs = occurrences().filter { it.startsWith("hr/ty_refs.sql\t") }.map { it.split('\t') }
        val typeUses = refs.filter { it[4] == "TYPE_USE" }.map { "${it[1]} ${it[3]}" }
        assertThat(typeUses).contains(
            "1:59-1:67 db:HR.TY_SHAPE", "1:79-1:90 db:HR.TY_SHAPE",   // object type attributes, unqualified and qualified
            "3:53-3:61 db:HR.TY_SHAPE",                                // table of ref
            "15:12-15:20 db:HR.TY_SHAPE",                              // package variable
            "16:34-16:42 db:HR.TY_SHAPE", "17:30-17:38 db:HR.TY_SHAPE",  // record field, index by element
            "18:31-18:39 db:HR.TY_SHAPE", "18:52-18:60 db:HR.TY_SHAPE",  // parameter, return type
            "30:14-30:23 db:HR.TY_CIRCLE",                             // local variable
            "37:32-37:41 db:HR.TY_CIRCLE",                             // treat(p as ref t) in PL/SQL
            "41:30-41:39 db:HR.TY_CIRCLE"                              // treat(p as ref t) in static SQL
        )
        assertThat(refs.filter { it[4] == "READ" }.map { it[3] }).containsExactly("db:HR.COMPANY.ID")
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE kind = 'COLUMN' AND name IN ('TY_CIRCLE', 'TY_SHAPE')")).isEmpty()
        // `ref t@lnk` and `t@lnk` get the same coverage.
        assertThat(Fixture.query(db, "SELECT c.message FROM coverage_note c JOIN source_file f ON f.id = c.file_id WHERE f.path = 'hr/ty_refs.sql' ORDER BY 1"))
            .containsExactly("Datatype at line 31 is not a plain name", "Datatype at line 32 is not a plain name")
    }

    @Test
    fun `without a dictionary an insert without column list writes one occurrence with unknown columns`() {
        val bare = Fixture.index(temp.resolve("bare.db"), dictionary = AtlasDictionary.NONE)
        assertThat(occurrences(bare).filter { it.startsWith("scripts/load.sql\t1:") }).containsExactly(
            "scripts/load.sql\t1:12-1:22\thr.company\tdb:HR.COMPANY\tIMPLICIT,SQL_INSERT,WRITE\tUNVERIFIED\tCOLUMNS_UNKNOWN\t-\tINSERT",
            "scripts/load.sql\t1:12-1:22\thr.company\tdb:HR.COMPANY\tSQL_INSERT\tUNVERIFIED\t-\t-\tINSERT"
        )
        assertThat(Fixture.query(bare, "SELECT kind, external FROM entity WHERE key = 'db:HR.COMPANY'")).containsExactly("UNKNOWN_RELATION\t1")
        assertThat(Fixture.query(bare, "SELECT kind, external FROM entity WHERE key = 'db:HR.COMPANY.ST_ACTIVE'")).containsExactly("COLUMN\t1")
    }

    /** A name PL/SQL binds (a parameter, a variable, a record, `SYSDATE`) is never read as a column of an unknown relation. */
    @Test
    fun `without a dictionary names bound by PL-SQL in static SQL are not columns`() {
        val bare = Fixture.index(temp.resolve("bare-names.db"), dictionary = AtlasDictionary.NONE)
        assertThat(Fixture.query(bare, "SELECT key FROM entity WHERE kind = 'COLUMN' AND (key IN ('db:HR.COMPANY.MSG', 'db:HR.SEND_QUEUE.P_STATUS') OR name IN ('P_A', 'P_ID', 'V', 'R', 'SYSDATE')) ORDER BY key"))
            .isEmpty()
        assertThat(Fixture.query(bare, "SELECT key FROM entity WHERE key LIKE 'db:HR.MISSING_TABLE.%' ORDER BY key"))
            .containsExactly("db:HR.MISSING_TABLE.A", "db:HR.MISSING_TABLE.B", "db:HR.MISSING_TABLE.C", "db:HR.MISSING_TABLE.X", "db:HR.MISSING_TABLE.Y")
    }

    @Test
    fun `without any schema relation keys use the placeholder and declarations get a coverage note`() {
        val noSchema = Fixture.index(temp.resolve("noschema.db"), dictionary = AtlasDictionary.NONE, compilationSchema = null)
        assertThat(Fixture.query(noSchema, "SELECT key FROM entity WHERE key IN ('db:~.PACK_PROCESS', 'db:~.COMPANY', 'db:HR.COMPANY') ORDER BY key"))
            .containsExactly("db:HR.COMPANY", "db:~.COMPANY", "db:~.PACK_PROCESS")
        assertThat(Fixture.query(noSchema, "SELECT f.path FROM coverage_note c JOIN source_file f ON f.id = c.file_id WHERE c.kind = 'SCHEMA_UNKNOWN' ORDER BY 1"))
            .containsExactly("hr/conditional.sql", "hr/fn_company_name.fun", "hr/imp_amb.prc", "hr/imp_echo_a.prc", "hr/imp_echo_b.prc", "hr/imp_fn.fun", "hr/imp_ovl.sql", "hr/imp_read_view.prc", "hr/imp_rec.prc", "hr/it_audit.prc", "hr/it_tables.sql", "hr/it_triggers.trg", "hr/it_writers.sql", "hr/json_holder.typ", "hr/json_type.typ", "hr/mv_company.sql", "hr/nested_calls.sql", "hr/nested_scopes.sql", "hr/object_views.sql", "hr/pack_args.pkb", "hr/pack_args.pks", "hr/pack_calls.pkb", "hr/pack_calls.pks",
                "hr/pack_collection.pkb", "hr/pack_collection.pks", "hr/pack_ddl.pkb", "hr/pack_ddl.pks", "hr/pack_dynamic.pkb", "hr/pack_dynamic.pks", "hr/pack_imp_a.pkb", "hr/pack_imp_a.pks", "hr/pack_imp_b.pkb", "hr/pack_imp_b.pks", "hr/pack_inline.sql", "hr/pack_json.pkb", "hr/pack_json.pks", "hr/pack_methods.pkb", "hr/pack_methods.pks", "hr/pack_nested.pkb", "hr/pack_nested.pks", "hr/pack_process.pkb",
                "hr/pack_process.pks", "hr/pack_remote.pks", "hr/pack_scoped.pkb", "hr/pack_scoped.pks", "hr/pack_types.pkb", "hr/pack_types.pks", "hr/proc_column_reads.prc", "hr/proc_columns.prc", "hr/proc_mview.prc", "hr/proc_objects.prc", "hr/proc_own_objects.prc",
                "hr/proc_source_view.prc",
                "hr/proc_standalone.prc", "hr/proc_synonym_objects.prc", "hr/proc_synonyms.prc", "hr/proc_view_chain.prc", "hr/proc_view_writes.prc", "hr/proc_writes.prc", "hr/proc_xmlseq.prc", "hr/remote_calls.prc", "hr/remote_names.prc", "hr/trg_company_bu.trg", "hr/trg_company_values.trg", "hr/trg_dynamic.trg",
                "hr/trg_forms.trg", "hr/trg_imp.trg", "hr/trg_syn_company.trg", "hr/ty_bag.tpb", "hr/ty_bag.typ", "hr/ty_num_list.typ", "hr/ty_point.typ", "hr/ty_refs.sql", "hr/ty_shapes.typ", "hr/v_imp.sql", "hr/view_ioi.sql", "hr/views.sql", "hr/views_lineage.sql", "hr/views_remote.sql", "hr/views_star.sql",
                "scripts/broken.sql", "scripts/broken_call.sql")
    }

    @Test
    fun `a TYPE anchor names a column the dictionary verifies, and only the dictionary`() {
        fun typeUse(database: Path, written: String) = occurrences(database)
            .filter { it.split('\t').let { row -> row[0] == "hr/pack_types.pkb" && row[2] == written && row[4] == "TYPE_USE" } }
            .map { it.split('\t').let { row -> "${row[3]} ${row[5]} ${row[6]}" } }
        assertThat(typeUse(db, "hr.send_queue.payload")).containsExactly("db:HR.SEND_QUEUE.PAYLOAD RESOLVED -")
        // HR is complete in the fixture dictionary: a missing column is known to be missing.
        assertThat(typeUse(db, "company.no_such_column")).containsExactly("db:HR.COMPANY.NO_SUCH_COLUMN UNRESOLVED NOT_IN_DICTIONARY")
        val bare = Fixture.index(temp.resolve("bare-types.db"), dictionary = AtlasDictionary.NONE)
        assertThat(typeUse(bare, "company.no_such_column")).containsExactly("db:HR.COMPANY.NO_SUCH_COLUMN UNVERIFIED -")
        // A column of a relation the complete schema lacks is as unresolved as the relation; without dictionary, unverified.
        assertThat(typeUse(db, "hr.no_such_table.col")).containsExactly("db:HR.NO_SUCH_TABLE.COL UNRESOLVED RELATION_UNRESOLVED")
        assertThat(typeUse(bare, "hr.no_such_table.col")).containsExactly("db:HR.NO_SUCH_TABLE.COL UNVERIFIED -")
    }
}
