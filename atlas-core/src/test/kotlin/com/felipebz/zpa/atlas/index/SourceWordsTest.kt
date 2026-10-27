//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.flr.api.GenericTokenType
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.lexer.PlSqlLexer
import com.felipebz.zpa.project.OracleIdentifier
import com.felipebz.zpa.squid.PlSqlConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import kotlin.io.path.isRegularFile
import kotlin.random.Random

/** Both scans of the written words find every identifier zpa's own lexer gives, normalized as `OracleIdentifier`. */
class SourceWordsTest {
    private val lexer = PlSqlLexer.create(PlSqlConfiguration(StandardCharsets.UTF_8))

    /** The lookup names of the identifier and keyword tokens the lexer gives for [text]. */
    private fun lexed(text: String): Set<String> = lexer.lex(text)
        .filter { it.type == GenericTokenType.IDENTIFIER || it.type is PlSqlKeyword }
        .mapTo(HashSet()) { OracleIdentifier.fromSource(it.originalValue).lookupName }

    private fun assertFindsEveryIdentifier(texts: List<String>) {
        val scanned = SourceIdentifiers.scan(texts)
        for (text in texts) {
            for (name in lexed(text)) {
                assertThat(scanned).describedAs("%s in %s", name, text).contains(name)
                // The names of the incremental rule are upper-cased, quoted ones included.
                val upper = name.uppercase(Locale.ROOT)
                assertThat(IncrementalRule.writesAny(text, setOf(upper), setOf(upper.length))).describedAs("%s in %s", name, text).isTrue()
            }
        }
    }

    @Test
    fun `the fixture's sources`() {
        val texts = Files.walk(Fixture.sources).use { paths -> paths.filter { it.isRegularFile() }.sorted().toList() }
            .map { SourceDecoding.decode(Files.readAllBytes(it), SourceDecoding.AUTO).text }
        assertThat(texts).isNotEmpty
        assertFindsEveryIdentifier(texts)
        // Files split across threads: each thread's names, joined, are the same set.
        assertThat(SourceIdentifiers.scan(texts, threads = 3)).isEqualTo(SourceIdentifiers.scan(texts))
    }

    @Test
    fun `words of other scripts, after numbers and next to quotes`() {
        listOf(
            "v_descrição := f_straße(1); \"Ação\" x", "x := ﬁx_ﬂow; call pkg_ŉame; a\uD835\uDC00b \uD835\uDC00abc",
            "1d2dabc 1e5abc 1.5fabc 1.dabc 3.e2x .5dabc 12abc 1e5_x 1E5D\$y 1d\uD835\uDC00z 2nd", "x := '\"' || col_a || '\"'; \"\"y\" \"a\" b \"c\"",
            "\"across\nlines\" \"across\u2028lines\" \"ok\u0085\" z", "-- f_ção in a comment\n'f_ção' in a string", "a\$b#c := 1; _x := 2; a١b"
        ).forEach { assertFindsEveryIdentifier(listOf(it)) }
    }

    @Test
    fun `generated texts`() {
        val pieces = listOf(
            "a", "Z", "d", "e", "f", "D", "E", "F", "é", "Ç", "ß", "ﬁ", "İ", "ı", "Σ", "\uD835\uDC00", "\uD83D\uDE00", "1", "9", "٣", "_", "$", "#", "\"", "'", " ", ".",
            "\n", "\r", "\r\n", "\u0085", "\u2028"
        )
        val random = Random(42)
        repeat(5_000) {
            assertFindsEveryIdentifier(listOf(buildString { repeat(random.nextInt(1, 40)) { append(pieces[random.nextInt(pieces.size)]) } }))
        }
    }

    @Test
    fun `an accented word is one name, upper-cased with the root locale, and a quoted word keeps its case`() {
        assertThat(SourceIdentifiers.scan(listOf("syn_ação \"Mixed Ção\" f_straße"))).contains("SYN_AÇÃO", "Mixed Ção", "F_STRASSE")
    }

    @Test
    fun `a name longer than the scanner's first buffer is kept whole`() {
        assertThat(SourceIdentifiers.scan(listOf("x".repeat(1_000) + " \"" + "y".repeat(700) + "\""))).contains("X".repeat(1_000), "y".repeat(700))
    }
}
