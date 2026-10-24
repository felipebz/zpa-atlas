//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** A symbolic link in the sources folder never makes the index hold, or the server read, a file outside it. */
class SourcesRootLinkTest {
    private val temp: Path = Files.createTempDirectory("atlas-links")
    private val sources: Path = Files.createDirectories(temp.resolve("sources/pkg")).parent
    private val outside: Path = Files.createDirectories(temp.resolve("outside"))
    private val db: Path = temp.resolve("atlas.db")

    @AfterEach
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private val secret = "SECRET_MARKER=not-a-source\n"

    private fun tree() {
        Files.writeString(sources.resolve("pkg/ok.sql"), "select 1 from dual;\n")
        Files.writeString(outside.resolve("app.env"), secret)
        Files.writeString(outside.resolve("ok.sql"), secret)
    }

    private fun index() = Indexer().index(IndexOptions(sources, db, compilationSchema = "HR", threads = 1))

    private fun paths(): List<String> = DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
        c.createStatement().use { s -> s.executeQuery("SELECT path FROM source_file ORDER BY path").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
    }

    private fun assertRefused(index: AtlasIndex, path: String) {
        assertThatThrownBy { index.file(path) }.isInstanceOf(InvalidQueryException::class.java).hasMessage("Path leaves the sources root: $path")
        assertThatThrownBy { index.source(path, 1, 1) }.isInstanceOf(InvalidQueryException::class.java).hasMessage("Path leaves the sources root: $path")
    }

    @Test
    fun `a link present when indexing that leads outside is left out, one that stays inside is indexed`() {
        tree()
        Files.createSymbolicLink(sources.resolve("pkg/leak.sql"), Path.of("../../outside/app.env"))
        Files.createSymbolicLink(sources.resolve("pkg/abs_leak.sql"), outside.resolve("app.env"))
        Files.createSymbolicLink(sources.resolve("pkg/inside.sql"), Path.of("ok.sql"))
        val summary = index()
        assertThat(summary.outsideRoot).containsExactly("pkg/abs_leak.sql", "pkg/leak.sql")
        assertThat(paths()).containsExactly("pkg/inside.sql", "pkg/ok.sql")
        val index = AtlasIndex(db, sources)
        assertThat(index.searchText("SECRET_MARKER").result.total).isZero()
        assertThatThrownBy { index.file("pkg/leak.sql") }.hasMessage("Not a file of the index: pkg/leak.sql")
        assertThat(index.file("pkg/inside.sql").result!!.lines.first()).isEqualTo("select 1 from dual;")
    }

    @Test
    fun `a file replaced after indexing by a link that leads outside is refused, and a search still answers`() {
        tree()
        index()
        Files.delete(sources.resolve("pkg/ok.sql"))
        Files.createSymbolicLink(sources.resolve("pkg/ok.sql"), outside.resolve("app.env"))
        val index = AtlasIndex(db, sources)
        assertRefused(index, "pkg/ok.sql")
        val page = index.searchText("select").result
        assertThat(page.rows.map { it.path to it.stale }).containsExactly("pkg/ok.sql" to null)
    }

    @Test
    fun `a folder replaced after indexing by a link that leads outside is refused`() {
        tree()
        index()
        sources.resolve("pkg/ok.sql").toFile().delete()
        Files.delete(sources.resolve("pkg"))
        Files.createSymbolicLink(sources.resolve("pkg"), outside)
        assertRefused(AtlasIndex(db, sources), "pkg/ok.sql")
    }

    @Test
    fun `a server given the sources folder through a link reads its files`() {
        tree()
        index()
        val linked = Files.createSymbolicLink(temp.resolve("linked-sources"), sources)
        assertThat(AtlasIndex(db, linked).file("pkg/ok.sql").result!!.stale).isFalse()
    }
}
