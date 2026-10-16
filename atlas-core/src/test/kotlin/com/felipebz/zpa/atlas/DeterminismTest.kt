package com.felipebz.zpa.atlas

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DeterminismTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `indexing twice produces identical rows and ids apart from the run timestamps`() {
        val first = Fixture.index(temp.resolve("first.db"), threads = 1)
        val second = Fixture.index(temp.resolve("second.db"), threads = 8)
        val tables = Fixture.query(first, "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
        assertThat(tables).contains("entity", "occurrence", "relation", "run", "source_file", "sql_statement", "unit", "coverage_note")
        for (table in tables) {
            val sql = if (table == "run") "SELECT id, schema_version, atlas_version, zpa_version, sources_root, metadata_origin, compilation_schema FROM run"
            else "SELECT * FROM $table ORDER BY rowid"
            assertThat(Fixture.query(second, sql)).describedAs(table).isEqualTo(Fixture.query(first, sql))
        }
        assertThat(Fixture.query(first, "SELECT count(*) FROM occurrence").single().toInt()).isGreaterThan(100)
    }
}
