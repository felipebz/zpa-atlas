//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Locale
import kotlin.io.path.isRegularFile
import kotlin.random.Random

class IdentifierScanTest {
    /** The words a text may use names by: what the scanner must find, never fewer. */
    private val words = Regex("\"[^\"\\r\\n]+\"|[\\p{L}_\$#][\\p{L}\\p{N}_\$#]*")

    private fun expected(text: String): Set<String> = words.findAll(text).mapTo(HashSet()) { it.value.removeSurrounding("\"").uppercase(Locale.ROOT) }

    private fun assertFindsEvery(text: String) {
        for (word in expected(text)) {
            assertThat(IncrementalRule.writesAny(text, setOf(word), setOf(word.length))).describedAs("%s in %s", word, text).isTrue()
        }
    }

    @Test
    fun `every word of the fixture's sources is found`() {
        val files = Files.walk(Fixture.sources).use { paths -> paths.filter { it.isRegularFile() }.toList() }
        assertThat(files).isNotEmpty
        files.forEach { assertFindsEvery(Files.readString(it)) }
    }

    @Test
    fun `words whose upper case is longer, of other scripts and outside the basic plane are found`() {
        val samples = listOf(
            "v := f_straße(1);", "select \"Straße\" from t_maß;", "x := ﬁx_ﬂow;", "call pkg_ŉame;",
            "n := col_٣٤ + col_२ + col_²³ + col_Ⅻ;", "begin 𝐀𝐁_proc(1); \"𝓧 quoted\" end;", "v_ação := v_ÇÃO || αβγ_Σ;",
            "a\$b#c := 1; _x := 2;", "-- f_straße in a comment\n'f_straße' in a string"
        )
        samples.forEach { assertFindsEvery(it) }
    }

    @Test
    fun `generated texts of mixed characters`() {
        val pieces = listOf("a", "Z", "_", "$", "#", "ß", "ﬁ", "ŉ", "İ", "ı", "Σ", "ς", "٣", "२", "²", "Ⅻ", "𝐀", "😀", "\"", " ", ".", "(", "\n", "\r", "1", "é", "e\u0301")
        val random = Random(42)
        repeat(2_000) {
            assertFindsEvery(buildString { repeat(random.nextInt(1, 30)) { append(pieces[random.nextInt(pieces.size)]) } })
        }
    }
}
