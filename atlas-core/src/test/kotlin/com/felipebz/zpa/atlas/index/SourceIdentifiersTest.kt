//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import kotlin.io.path.isRegularFile
import kotlin.random.Random

class SourceIdentifiersTest {
    /** The scan as it was written first, a regex over the Latin-1 text: the set the scanner must give, exactly. */
    private val word = Regex("\"([^\"\\r\\n]+)\"|([A-Za-z][A-Za-z0-9_$#]*)")

    private fun reference(files: List<ByteArray>): Set<String> = files.flatMapTo(HashSet()) { bytes ->
        word.findAll(String(bytes, StandardCharsets.ISO_8859_1)).map { it.groups[1]?.value ?: it.value.uppercase(Locale.ROOT) }
    }

    private fun assertSameAsReference(vararg texts: ByteArray) {
        val files = texts.toList()
        assertThat(SourceIdentifiers.scan(files)).describedAs(files.joinToString(" | ") { String(it, StandardCharsets.ISO_8859_1) })
            .isEqualTo(reference(files))
    }

    private fun latin1(text: String) = text.toByteArray(StandardCharsets.ISO_8859_1)

    @Test
    fun `quotes that open no word, words next to digits and symbols, other bytes and line ends give the regex's names`() {
        val samples = listOf(
            "x := \"unterminated", "x := \"unterminated\nnext_line\"", "\"\"", "a\"\"b", "\"\"\"x\"", "\"a\"\"b\"", "\"across\r\nlines\"",
            "\"across\rlines\"", "\"ok\"\"", "v\$x#1 := w_2\$ || \$q || #r || _s", "1abc 2_x 3\$ x1y2", "\"Mixed Case\" mixed case",
            "a\r\nb\r\n\"c d\"\r\n", "\"\"\r\n\"", "\"", "z\""
        )
        samples.forEach { assertSameAsReference(latin1(it)) }
        // Non-ASCII inside, before and after words, as Latin-1 and as UTF-8: the bytes above 127 end a word and start none.
        val latin = "ação v_çx éabc abcé \"ação\" \"é\" ab\u00a0cd \u0085x\"y\u0085z\" straße f_ß"
        assertSameAsReference(latin1(latin))
        assertSameAsReference(latin.toByteArray(StandardCharsets.UTF_8))
        assertSameAsReference("\"😀 x\" 😀abc abc😀 a\u2028b \"c\u2028d\"".toByteArray(StandardCharsets.UTF_8))
        // One file's unterminated quote ends with the file.
        assertSameAsReference(latin1("a := \"open"), latin1("close\" b"))
    }

    @Test
    fun `generated texts give the regex's names`() {
        val pieces = listOf("a", "Z", "q", "_", "$", "#", "1", "9", "\"", "\"\"", " ", ".", "\n", "\r", "\r\n", "é", "ß", "\u0085", "\u00ff", "😀")
        val random = Random(42)
        repeat(5_000) {
            val text = buildString { repeat(random.nextInt(1, 40)) { append(pieces[random.nextInt(pieces.size)]) } }
            assertSameAsReference(text.toByteArray(StandardCharsets.UTF_8))
            assertSameAsReference(text.toByteArray(StandardCharsets.ISO_8859_1))
        }
    }

    @Test
    fun `a name longer than the scanner's first buffer is kept whole`() {
        assertSameAsReference(latin1("x".repeat(1_000) + " \"" + "y".repeat(700) + "\" " + "z".repeat(300)))
    }

    @Test
    fun `the fixture's sources give the regex's names`() {
        val files = Files.walk(Fixture.sources).use { paths -> paths.filter { it.isRegularFile() }.sorted().toList() }.map { Files.readAllBytes(it) }
        assertThat(files).isNotEmpty
        val expected = reference(files)
        assertThat(expected).hasSizeGreaterThan(100)
        assertThat(SourceIdentifiers.scan(files)).isEqualTo(expected)
        // Files split across threads: each thread's names, joined, are the same set.
        assertThat(SourceIdentifiers.scan(files, threads = 3)).isEqualTo(expected)
    }
}
