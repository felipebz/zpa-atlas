//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

class SourceTextIndexTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `the copied text index is the one inserting the lines with their containers through source_line builds, with its settings`() {
        val index = Fixture.index(temp.resolve("fixture.db"))
        // The rows as written, each line with its file, number and container, inserted as SourceLines inserted them
        // before the text index was built apart: every column through the FTS5 table, with the same settings, in
        // batches of the same size.
        val direct = temp.resolve("direct.db")
        DriverManager.getConnection("jdbc:sqlite:$direct").use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute(SOURCE_LINE_DDL)
                SOURCE_LINE_SETTINGS.forEach(statement::execute)
            }
            connection.createStatement().execute("ATTACH DATABASE '$index' AS written")
            val rows = connection.createStatement().executeQuery("SELECT rowid, text, file_id, line, entity_id FROM written.source_line ORDER BY rowid")
            connection.prepareStatement("INSERT INTO source_line(rowid, text, file_id, line, entity_id) VALUES (?,?,?,?,?)").use { insert ->
                var pending = 0
                while (rows.next()) {
                    for (column in 1..5) insert.setObject(column, rows.getObject(column))
                    insert.addBatch()
                    if (++pending >= 20_000) {
                        insert.executeBatch()
                        pending = 0
                    }
                }
                if (pending > 0) insert.executeBatch()
            }
            connection.commit()
        }

        for (table in listOf("data", "idx", "docsize", "config", "content")) {
            val sql = "SELECT * FROM source_line_$table ORDER BY 1, 2"
            assertThat(Fixture.query(index, sql)).describedAs(table).isNotEmpty().isEqualTo(Fixture.query(direct, sql))
        }
        assertThat(Fixture.query(index, "SELECT count(*) FROM source_line_content WHERE c3 IS NOT NULL").single().toInt()).isGreaterThan(0)
        // The settings are kept in the copied config table: an index whose copy lost them would not say so otherwise.
        assertThat(Fixture.query(index, "SELECT k, v FROM source_line_config ORDER BY k"))
            .containsExactly("automerge\t8", "hashsize\t${64 * 1024 * 1024}", "version\t4")
        // FTS5 throws on an index that does not match its content.
        DriverManager.getConnection("jdbc:sqlite:$index").use { it.createStatement().execute("INSERT INTO source_line(source_line) VALUES ('integrity-check')") }
        // No file is left beside the index.
        assertThat(temp.toFile().list()!!.filter { it.endsWith(".tmp") }).isEmpty()
    }
}
