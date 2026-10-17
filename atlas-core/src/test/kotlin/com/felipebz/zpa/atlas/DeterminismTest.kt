//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DeterminismTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `indexing twice produces identical rows and ids apart from the run timestamps`() {
        assertSameRows(Fixture.index(temp.resolve("first.db"), threads = 1), Fixture.index(temp.resolve("second.db"), threads = 8))
    }

    /** A dictionary that cannot list its relations: the ones code resolves are emitted once per run, after the scan. */
    @Test
    fun `resolved relations of a lookup-only dictionary are entities with all their columns, whatever the thread order`() {
        val dictionary = AtlasDictionary.lookupOnly(Fixture.dictionary.metadata, setOf("HR"))
        val first = Fixture.index(temp.resolve("lookup1.db"), dictionary = dictionary, threads = 1)
        assertSameRows(first, Fixture.index(temp.resolve("lookup8.db"), dictionary = dictionary, threads = 8))
        assertThat(Fixture.query(first,
            """SELECT c.key FROM relation r JOIN entity p ON p.id = r.from_id JOIN entity c ON c.id = r.to_id
               WHERE p.key = 'db:HR.COMPANY' AND r.kind = 'CONTAINS' AND c.external = 0 ORDER BY c.key"""))
            .containsExactly("db:HR.COMPANY.COMPANY_NAME", "db:HR.COMPANY.DT_CHANGED", "db:HR.COMPANY.ID", "db:HR.COMPANY.ST_ACTIVE")
        assertThat(Fixture.query(first, "SELECT count(*) FROM entity WHERE key LIKE 'db:HR.COMPANY%' AND external = 0").single()).isEqualTo("5")
    }

    private fun assertSameRows(first: Path, second: Path) {
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
