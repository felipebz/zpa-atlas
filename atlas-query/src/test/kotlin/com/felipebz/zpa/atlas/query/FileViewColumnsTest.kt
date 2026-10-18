//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * The name span of every occurrence, cut from the lines `file()` returns with string indexes (UTF-16 units, as in the
 * browser), is the written name: columns agree with the decoded text whatever the tabs, the charset or a BOM.
 */
class FileViewColumnsTest {
    @TempDir
    lateinit var temp: Path

    private val encodingSources: Path = Path.of(javaClass.getResource("/encoding")!!.toURI())

    /** `path`, `written` and the name span of every single-line occurrence with an entity. */
    private fun names(db: Path) = Fixture.query(db, """
        SELECT f.path, o.written, coalesce(o.name_start_line, o.start_line), coalesce(o.name_start_col, o.start_col),
               coalesce(o.name_end_line, o.end_line), coalesce(o.name_end_col, o.end_col), f.encoding
        FROM occurrence o JOIN source_file f ON f.id = o.file_id WHERE o.entity_id IS NOT NULL ORDER BY o.id""")
        .map { it.split('\t') }

    private fun mismatches(db: Path, sources: Path): List<String> {
        val index = AtlasIndex(db, sources)
        val files = HashMap<String, FileView>()
        return names(db).mapNotNull { (path, written, startLine, startCol, endLine, endCol) ->
            if (startLine != endLine) return@mapNotNull "$path $startLine: $written spans lines"
            val lines = files.getOrPut(path) { index.file(path).result!! }.lines
            val text = lines[startLine.toInt() - 1].substring(startCol.toInt(), endCol.toInt())
            if (text == written) null else "$path $startLine:$startCol-$endCol: '$text' is not '$written'"
        }
    }

    private operator fun <T> List<T>.component6() = this[5]

    @Test
    fun `tabs, accented text in UTF-8 and windows-1252, a BOM and a surrogate pair keep columns`() {
        val db = temp.resolve("encoding.db")
        Indexer({ Instant.EPOCH }).index(IndexOptions(encodingSources, db, compilationSchema = "HR", threads = 1))
        assertThat(Fixture.query(db, "SELECT path, encoding FROM source_file ORDER BY path")).containsExactly(
            "enc_astral.sql\tUTF-8", "enc_bom.sql\tUTF-8", "enc_legacy.sql\twindows-1252", "enc_utf8.sql\tUTF-8"
        )
        // Every file has a name after a tab, an accent or the BOM on the same line.
        assertThat(names(db).map { "${it[0]} ${it[1]} ${it[2]}" }).contains(
            "enc_utf8.sql enc_tab 4", "enc_utf8.sql enc_t 5", "enc_utf8.sql c 5", "enc_legacy.sql enc_legacy 4", "enc_bom.sql enc_bom 1", "enc_astral.sql enc_astral 3"
        )
        assertThat(mismatches(db, encodingSources)).isEmpty()
    }

    @Test
    fun `every name span of the fixture is its written name`() {
        val db = Fixture.index(temp.resolve("fixture.db"))
        assertThat(mismatches(db, Fixture.sources)).isEmpty()
    }
}
