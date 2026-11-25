//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.extract.sha256
import com.felipebz.zpa.atlas.writer.SourceTextIndex
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * A kept text index is reused by every build of the atlas whose line rules have the same [SourceTextIndex.LINES_FORMAT]:
 * this checks that the rows the fixtures give are those recorded for that format. The fixtures are the HR sources, every
 * Forms module of the test resources, and a file of the line breaks and short lines the rules tell apart.
 */
class TextIndexFormatTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `the text index rows of the fixtures are the ones recorded for its line format`() {
        val digest = digest()
        val recorded = HistoryFile.read(HISTORY)["${SourceTextIndex.LINES_FORMAT}"]
        val next = SourceTextIndex.LINES_FORMAT + 1
        when {
            recorded == null -> fail<Unit>(
                "No digest is recorded for text index line format ${SourceTextIndex.LINES_FORMAT} (digest $digest): set SourceTextIndex.LINES_DIGEST to " +
                    "\"$digest\" and add the line \"${SourceTextIndex.LINES_FORMAT} $digest\" to $HISTORY."
            )
            recorded != digest -> fail<Unit>(
                "The rows of the fixtures' text index changed (digest $digest, recorded $recorded for line format ${SourceTextIndex.LINES_FORMAT}). " +
                    "Raise SourceTextIndex.LINES_FORMAT to $next, set SourceTextIndex.LINES_DIGEST to \"$digest\" and add the line \"$next $digest\" to $HISTORY."
            )
        }
        assertThat(SourceTextIndex.LINES_DIGEST)
            .describedAs("SourceTextIndex.LINES_DIGEST is the digest $HISTORY records for line format ${SourceTextIndex.LINES_FORMAT}")
            .isEqualTo(recorded)
    }

    private fun digest(): String {
        val sources = temp.resolve("src")
        FormatFixtures.copyHrAndForms(sources)
        Files.writeString(sources.resolve("breaks.sql"), BREAKS)
        val output = temp.resolve("atlas.db")
        val indexer = Indexer()
        indexer.readSources(sources, IndexOptions.DEFAULT_EXTENSIONS, SourceDecoding.AUTO, output).use { read ->
            read.textIndex.await()
            return sha256(Fixture.query(read.textIndex.file, "SELECT rowid, text, file_id, line FROM source_line ORDER BY rowid").joinToString("\n"))
        }
    }

    private companion object {
        const val HISTORY = "text-index-formats.txt"

        /** Line breaks of every kind, lines shorter than a trigram, blank and indented lines, text beyond ASCII. */
        const val BREAKS = "begin\r\n  null;\rx\n\nab\n\t-- ação é ok\r\n\r\nend;\n/"

    }
}
