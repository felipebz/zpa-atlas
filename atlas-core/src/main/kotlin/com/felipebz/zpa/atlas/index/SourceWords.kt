//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.UnquotedIdentifier

/**
 * The identifier-like words of a source text, by a lexical pass over all of it, comments and string literals
 * included: never fewer than the identifiers zpa's lexer gives for the text.
 * - **An unquoted word** starts where [UnquotedIdentifier.isStart] and goes on while [UnquotedIdentifier.isPart].
 * - **A quoted word** is what the lexer takes after a `"`: one or more characters other than a line terminator
 *   (`\n`, `\r`, `\u0085`, `\u2028`, `\u2029`), the first of which may be a `"`, up to the next `"`. The scan goes on
 *   from the character after the opening `"`: that `"` may be in a string literal or a comment, where the lexer reads
 *   the words that follow it.
 * - **A word that follows a digit** (directly, or after a point) may begin with the end of a number literal, as the
 *   lexer reads it: an exponent (`e` and digits) and an `f` or `d` (`1e5abc` is `1e5` and `abc`, `1d2dabc` is `1d`,
 *   `2d` and `abc`). The identifier that may start after them is found too.
 */
internal object SourceWords {
    /**
     * Calls [unquoted] with the range of each unquoted word of [text], and [quoted] with the range of each quoted word
     * without its quotes, in order, until one returns true; returns whether one did.
     */
    inline fun find(text: String, unquoted: (from: Int, to: Int) -> Boolean, quoted: (from: Int, to: Int) -> Boolean): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    val close = quotedClose(text, i)
                    if (close >= 0 && quoted(i + 1, close)) return true
                    i++
                }
                // A start is a letter of the Basic Multilingual Plane: one char.
                UnquotedIdentifier.isStart(c.code) -> {
                    val to = wordEnd(text, i + 1)
                    if (unquoted(i, to)) return true
                    val inner = identifierAfterNumber(text, i, to)
                    if (inner >= 0 && unquoted(inner, to)) return true
                    i = to
                }
                else -> i++
            }
        }
        return false
    }

    /** Where the word whose part starts at [from] ends. */
    @PublishedApi
    internal fun wordEnd(text: String, from: Int): Int {
        var j = from
        val end = text.length
        while (j < end) {
            val c = text[j]
            if (c.code < 0x80) {
                if (!UnquotedIdentifier.isPart(c.code)) break
                j++
            } else {
                val codePoint = text.codePointAt(j)
                if (!UnquotedIdentifier.isPart(codePoint)) break
                j += Character.charCount(codePoint)
            }
        }
        return j
    }

    /** The index of the `"` that closes a quoted word opened at [open], or -1 when it opens none. */
    @PublishedApi
    internal fun quotedClose(text: String, open: Int): Int {
        val end = text.length
        var j = open + 1
        if (j >= end || isLineTerminator(text[j])) return -1
        j++
        while (j < end) {
            val c = text[j]
            if (c == '"') return j
            if (isLineTerminator(c)) return -1
            j++
        }
        return -1
    }

    /**
     * Where the lexer starts an identifier inside the word [from, to) when it follows a digit and the number literal
     * before it goes on into it, or -1: past the literal's end, and past any other literal or character that starts no
     * identifier.
     */
    @PublishedApi
    internal fun identifierAfterNumber(text: String, from: Int, to: Int): Int {
        if (!followsDigit(text, from)) return -1
        var p = numberEnd(text, from, to)
        if (p == from) return -1
        while (p < to) {
            val c = text[p]
            when {
                UnquotedIdentifier.isStart(c.code) -> return p
                isAsciiDigit(c) -> {
                    var q = p + 1
                    while (q < to && isAsciiDigit(text[q])) q++
                    p = numberEnd(text, q, to)
                }
                else -> p++
            }
        }
        return -1
    }

    /** Where a number literal whose digits end at [from] ends: past an exponent (`e` and digits) and an `f` or `d`. */
    private fun numberEnd(text: String, from: Int, to: Int): Int {
        var p = from
        if (p + 1 < to && (text[p] == 'e' || text[p] == 'E') && isAsciiDigit(text[p + 1])) {
            p += 2
            while (p < to && isAsciiDigit(text[p])) p++
        }
        if (p < to && text[p].let { it == 'f' || it == 'F' || it == 'd' || it == 'D' }) p++
        return p
    }

    /** Whether the word starting at [at] follows an ASCII digit, directly or after a point: a number may end in it. */
    private fun followsDigit(text: String, at: Int): Boolean =
        at > 0 && (isAsciiDigit(text[at - 1]) || text[at - 1] == '.' && at > 1 && isAsciiDigit(text[at - 2]))

    private fun isAsciiDigit(c: Char) = c in '0'..'9'

    /** What `.` does not match in a Java regex: the line terminators the lexer's quoted identifier stops at. */
    private fun isLineTerminator(c: Char) = c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029'
}
