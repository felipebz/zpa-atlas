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
            "entity" to "SELECT key, kind, coalesce(schema_name, '-'), name, coalesce((SELECT p.key FROM entity p WHERE p.id = e.parent_id), '-'), display, external FROM entity e ORDER BY id",
            "relation" to "SELECT f.key, r.kind, t.key FROM relation r JOIN entity f ON f.id = r.from_id JOIN entity t ON t.id = r.to_id ORDER BY 1, 2, 3",
            "source_file" to "SELECT path, kind, size_bytes, coalesce(compilation_schema, '-'), parse_status, recovered_regions FROM source_file ORDER BY id",
            "unit" to "SELECT f.path, u.kind, u.name, coalesce(e.key, '-'), u.start_line || ':' || u.start_col || '-' || u.end_line || ':' || u.end_col FROM unit u JOIN source_file f ON f.id = u.file_id LEFT JOIN entity e ON e.id = u.entity_id ORDER BY u.id",
            "sql_statement" to "SELECT f.path, s.kind, coalesce(e.key, '-'), s.start_line || ':' || s.start_col || '-' || s.end_line || ':' || s.end_col FROM sql_statement s JOIN source_file f ON f.id = s.file_id LEFT JOIN entity e ON e.id = s.container_entity_id ORDER BY s.id",
            "occurrence_candidate" to "SELECT f.path, o.start_line || ':' || o.start_col, o.written, e.key FROM occurrence_candidate c JOIN occurrence o ON o.id = c.occurrence_id JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = c.entity_id ORDER BY c.occurrence_id, c.entity_id",
            "call_argument" to "SELECT f.path, o.start_line || ':' || o.start_col, o.written, a.ordinal, coalesce(a.label, '-'), coalesce(e.key, '-'), a.start_line || ':' || a.start_col || '-' || a.end_line || ':' || a.end_col FROM call_argument a JOIN occurrence o ON o.id = a.occurrence_id JOIN source_file f ON f.id = o.file_id LEFT JOIN entity e ON e.id = a.parameter_entity_id ORDER BY a.occurrence_id, a.ordinal",
            "coverage_note" to "SELECT coalesce(f.path, '-'), c.kind, c.message FROM coverage_note c LEFT JOIN source_file f ON f.id = c.file_id ORDER BY c.id"
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
        assertThat(load.filter { it.split('\t')[4] != "CALL" }.map { it.split('\t')[3] }).containsExactly("db:HR.COMPANY", "db:HR.V_COMPANY")
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE kind IN ('TABLE', 'VIEW', 'OBJECT_TABLE', 'UNKNOWN_RELATION', 'COLUMN') AND (key LIKE '%.W' OR key LIKE '%DUAL%' OR key LIKE '%ODCINUMBERLIST%')")).isEmpty()
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

    @Test
    fun `without any schema relation keys use the placeholder and declarations get a coverage note`() {
        val noSchema = Fixture.index(temp.resolve("noschema.db"), dictionary = AtlasDictionary.NONE, compilationSchema = null)
        assertThat(Fixture.query(noSchema, "SELECT key FROM entity WHERE key IN ('db:~.PACK_PROCESS', 'db:~.COMPANY', 'db:HR.COMPANY') ORDER BY key"))
            .containsExactly("db:HR.COMPANY", "db:~.COMPANY", "db:~.PACK_PROCESS")
        assertThat(Fixture.query(noSchema, "SELECT f.path FROM coverage_note c JOIN source_file f ON f.id = c.file_id WHERE c.kind = 'SCHEMA_UNKNOWN' ORDER BY 1"))
            .containsExactly("hr/nested_calls.sql", "hr/nested_scopes.sql", "hr/pack_calls.pkb", "hr/pack_calls.pks", "hr/pack_collection.pkb", "hr/pack_collection.pks",
                "hr/pack_nested.pkb", "hr/pack_nested.pks", "hr/pack_process.pkb", "hr/pack_process.pks", "hr/proc_standalone.prc",
                "hr/trg_company_bu.trg", "hr/ty_point.typ", "scripts/broken.sql", "scripts/broken_call.sql")
    }
}
