//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.compare.DatabaseDependencies
import com.felipebz.zpa.atlas.compare.DbObject
import com.felipebz.zpa.atlas.compare.DbSourceLine
import com.felipebz.zpa.atlas.compare.DependencyComparison
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class SourceEncodingTest {
    private val temp: Path = Files.createTempDirectory("atlas-encoding")
    private val sources: Path = temp.resolve("sources").also { Files.createDirectories(it) }

    @AfterEach
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private fun unit(name: String) = """create or replace procedure $name(p_descrição varchar2) is
  -- comentário com acentuação: ação, coração
  v_descrição varchar2(30) := 'São Paulo';
begin
  null;
end;
/
"""

    private fun write() {
        Files.write(sources.resolve("p_utf8.prc"), unit("p_utf8").toByteArray(Charsets.UTF_8))
        Files.write(sources.resolve("p_latin1.prc"), unit("p_latin1").toByteArray(Charsets.ISO_8859_1))
    }

    private fun index(encoding: String): Path {
        val db = temp.resolve("index-$encoding.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "HR", threads = 1, encoding = encoding))
        return db
    }

    private fun rows(db: Path, sql: String): List<String> = DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }

    @Test
    fun `auto decodes each file with its own charset and records it`() {
        write()
        val db = index("auto")
        assertThat(rows(db, "SELECT path || ' ' || encoding FROM source_file ORDER BY path"))
            .containsExactly("p_latin1.prc windows-1252", "p_utf8.prc UTF-8")
        assertThat(rows(db, "SELECT source_encoding FROM run")).containsExactly("auto")
        val parameters = rows(db, "SELECT f.path || ' ' || o.written FROM occurrence o JOIN source_file f ON f.id = o.file_id " +
            "JOIN entity e ON e.id = o.entity_id WHERE e.kind = 'PARAMETER' ORDER BY f.path")
        assertThat(parameters).containsExactly("p_latin1.prc p_descrição", "p_utf8.prc p_descrição")
        assertThat(rows(db, "SELECT key FROM entity WHERE kind = 'PARAMETER' ORDER BY key")).allMatch { it.endsWith(".P_DESCRIÇÃO") }

        val index = AtlasIndex(db, sources)
        for (path in listOf("p_utf8.prc", "p_latin1.prc")) {
            val text = index.source(path, 2, 1).result!!.lines.joinToString("\n") { it.text }
            assertThat(text).contains("comentário com acentuação: ação, coração", "v_descrição").doesNotContain("\uFFFD")
        }
    }

    @Test
    fun `a forced charset applies to every file`() {
        write()
        val db = index("ISO-8859-1")
        assertThat(rows(db, "SELECT encoding FROM source_file ORDER BY path")).containsExactly("ISO-8859-1", "ISO-8859-1")
        assertThat(rows(db, "SELECT source_encoding FROM run")).containsExactly("ISO-8859-1")
        val written = rows(db, "SELECT f.path || ' ' || o.written FROM occurrence o JOIN source_file f ON f.id = o.file_id " +
            "JOIN entity e ON e.id = o.entity_id WHERE e.kind = 'PARAMETER' ORDER BY f.path")
        assertThat(written).contains("p_latin1.prc p_descrição")
        // The UTF-8 file read as ISO-8859-1 gets mojibake, as expected for a wrong forced charset ("§" even breaks the
        // identifier, so that file parses with errors and has no parameter occurrence).
        val utf8 = AtlasIndex(db, sources).source("p_utf8.prc", 3, 0).result!!.lines.single().text
        assertThat(utf8).contains("v_descriÃ§Ã£o")
    }

    @Test
    fun `compare-deps reads each unit with the recorded charset`() {
        write()
        val db = index("auto")
        fun stored(name: String) = unit(name).removePrefix("create or replace ").removeSuffix("/\n").lines()
            .mapIndexed { i, line -> DbSourceLine("HR", name.uppercase(), "PROCEDURE", i + 1, line + "\n") }
        val facts = DatabaseDependencies(
            emptyList(),
            listOf(DbObject("HR", "P_UTF8", "PROCEDURE", "VALID"), DbObject("HR", "P_LATIN1", "PROCEDURE", "VALID")),
            stored("p_utf8") + stored("p_latin1"),
            sourceReadable = true
        )
        val result = DependencyComparison(db, facts, sources).compare()
        assertThat(result.units.map { "${it.unit.name} ${it.sourceStatus}" }).containsExactly("P_LATIN1 SAME", "P_UTF8 SAME")
    }
}
