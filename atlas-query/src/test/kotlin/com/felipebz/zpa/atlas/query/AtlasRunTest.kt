//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.writer.AtlasWriter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager

/** The run an index file holds: its schema version, its counts, and the SQLite indexes queries rely on. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AtlasRunTest {
    private val temp: Path = Files.createTempDirectory("atlas-test")
    private val db: Path = SharedFixtureIndex.db
    private val index = AtlasIndex(db, Fixture.sources)

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun `the supported schema version is the one the writer produces`() {
        assertThat(AtlasIndex.SCHEMA_VERSION).isEqualTo(AtlasWriter.SCHEMA_VERSION)
    }

    @Test
    fun `every answer carries the run it was read from`() {
        val answer = index.run()
        assertThat(answer.run.compilationSchema).isEqualTo("HR")
        assertThat(answer.run.metadataOrigin).isEqualTo("fixture")
        assertThat(answer.result.filesByParseStatus).contains(Count("RECOVERED", 2))
    }

    @Test
    fun `the run's counts are read again once another run replaces the index file`() {
        val served = temp.resolve("replaced.db")
        Files.copy(db, served)
        val replaced = AtlasIndex(served, null)
        val first = replaced.run()
        assertThat(first.result.filesByParseStatus).contains(Count("RECOVERED", 2))
        // Another run, as index writes it: a new file with its own stamp moved over the one served.
        val next = temp.resolve("next.db")
        Files.copy(db, next)
        DriverManager.getConnection("jdbc:sqlite:$next").use { connection ->
            connection.createStatement().use {
                it.executeUpdate("UPDATE source_file SET parse_status = 'OK' WHERE parse_status = 'RECOVERED'")
                it.executeUpdate("UPDATE run SET finished_at = '2099-01-01T00:00:00Z'")
            }
        }
        Files.move(next, served, StandardCopyOption.REPLACE_EXISTING)
        val second = replaced.run()
        assertThat(second.run.finishedAt).isEqualTo("2099-01-01T00:00:00Z")
        assertThat(second.result.filesByParseStatus).noneMatch { it.value == "RECOVERED" }
        assertThat(second.result.filesByParseStatus.sumOf { it.count }).isEqualTo(first.result.filesByParseStatus.sumOf { it.count })
    }

    @Test
    fun `a file with another schema version is refused`() {
        val other = temp.resolve("other.db")
        Files.copy(db, other)
        DriverManager.getConnection("jdbc:sqlite:$other").use { it.createStatement().execute("UPDATE run SET schema_version = 2") }
        assertThatThrownBy { AtlasIndex(other, null).run() }
            .isInstanceOf(SchemaMismatchException::class.java)
            .hasMessageContaining("schema version 2").hasMessageContaining("version ${AtlasIndex.SCHEMA_VERSION}")
    }

    @Test
    fun `the name prefix search and the candidate lookup use their indexes`() {
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            fun plan(sql: String) = connection.createStatement().executeQuery("EXPLAIN QUERY PLAN $sql").use { rs ->
                buildList { while (rs.next()) add(rs.getString("detail")) }
            }
            assertThat(plan("SELECT id FROM entity e WHERE e.name LIKE 'exec%' ESCAPE '\\'"))
                .anyMatch { it.contains("entity_name_nocase") }
            assertThat(plan("SELECT occurrence_id FROM occurrence_candidate WHERE entity_id = 1"))
                .anyMatch { it.contains("occurrence_candidate_entity") }
        }
    }
}
