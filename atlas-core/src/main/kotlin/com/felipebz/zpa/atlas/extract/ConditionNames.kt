//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

/** A dotted name of two or three segments written in a `$IF`/`$ELSIF` condition, at [start] until [end] of the text. */
data class ConditionName(val segments: List<String>, val start: Int, val end: Int)

/**
 * The names in the conditions Oracle evaluated, read from the text the lexer removed for one conditional compilation
 * run (a skipped-text trivia). The text is one of:
 * - the opening of a selection whose branch *k* is kept: `$IF`, conditions 1 to *k* and the skipped branches before
 *   *k*, ending where branch *k* starts, after its `$THEN` or `$ELSE` ([wholeSelection] false): the names of
 *   conditions 1 to *k*;
 * - a whole `$IF … $END` that keeps nothing ([wholeSelection] true): the names of every condition;
 * - the closing of a kept branch (`$ELSIF …`, `$ELSE …`, `$END`) or an `$ERROR … $END`: nothing, Oracle evaluated none
 *   of it.
 *
 * Only top-level conditions count: a `$IF` inside a skipped branch was never processed. Comments, string literals
 * (`'…'`, `n'…'`, `q'…'`), quoted identifiers, identifiers containing `$` or `#` and inquiry directives (`$$name`) are
 * skipped as the lexer skips them, so directive-looking text inside them never counts.
 */
object ConditionNames {

    /** The names, in text order; null when the text does not have the expected shape. */
    fun read(text: String, wholeSelection: Boolean): List<ConditionName>? {
        val elements = Lexer(text).elements() ?: return null
        val significant = elements.filter { it.kind != Kind.SPACE }
        val first = significant.firstOrNull() ?: return null
        if (first.kind != Kind.DIRECTIVE) return null
        if (first.word != "IF") return if (first.word in CLOSING) emptyList() else null

        val names = ArrayList<ConditionName>()
        var depth = 1
        var index = (condition(significant, 1, names) ?: return null) + 1
        // Whether the scan stands right after a top-level `$THEN` or `$ELSE`: where an opening text ends.
        var atBranch = true
        while (index < significant.size) {
            val element = significant[index]
            atBranch = false
            if (element.kind == Kind.DIRECTIVE) {
                when (element.word) {
                    "IF", "ERROR" -> depth++
                    "END" -> {
                        depth--
                        if (depth == 0) return names.takeIf { wholeSelection && index == significant.size - 1 }
                    }
                    "ELSIF" -> if (depth == 1) {
                        index = condition(significant, index + 1, names) ?: return null
                        atBranch = true
                    }
                    "ELSE" -> if (depth == 1) atBranch = true
                }
            }
            index++
        }
        return names.takeIf { !wholeSelection && atBranch }
    }

    /** Reads the condition starting at [start] up to its `$THEN`, adding its names; the index of the `$THEN`, or null. */
    private fun condition(elements: List<Element>, start: Int, names: MutableList<ConditionName>): Int? {
        var index = start
        while (index < elements.size) {
            val element = elements[index]
            when (element.kind) {
                Kind.DIRECTIVE -> return if (element.word == "THEN") index else null
                Kind.WORD -> {
                    // A dotted run of words: `pkg.c`, `schema.pkg.c`.
                    val run = arrayListOf(element)
                    var next = index + 1
                    while (next + 1 < elements.size && elements[next].kind == Kind.DOT && elements[next + 1].kind == Kind.WORD) {
                        run += elements[next + 1]
                        next += 2
                    }
                    if (run.size in 2..3 && run.none { it.special }) {
                        names += ConditionName(run.map { it.text }, run.first().start, run.last().end)
                    }
                    index = next
                    continue
                }
                else -> Unit
            }
            index++
        }
        return null
    }

    private val CLOSING = setOf("ELSIF", "ELSE", "END", "ERROR")

    private enum class Kind { SPACE, DIRECTIVE, WORD, DOT, OTHER }

    /** One lexical element; [special] marks a word containing `$` or `#`, which is never part of a name. */
    private class Element(val kind: Kind, val start: Int, val end: Int, val text: String, val word: String = "", val special: Boolean = false)

    private class Lexer(private val text: String) {
        private fun char(position: Int): Int = if (position < text.length) text[position].code else -1

        private fun isWordPart(c: Int) = c > 0 && (Character.isLetterOrDigit(c) || c == '_'.code || c == '$'.code || c == '#'.code)

        /** The elements of the text; comments, literals, quoted identifiers and inquiry directives are [Kind.SPACE]. Null when one is unterminated. */
        fun elements(): List<Element>? {
            val result = ArrayList<Element>()
            var p = 0
            while (p < text.length) {
                val c = char(p)
                val next = char(p + 1)
                val end: Int
                val kind: Kind
                when {
                    Character.isWhitespace(c) -> { end = p + 1; kind = Kind.SPACE }
                    c == '-'.code && next == '-'.code -> { end = until(p + 2) { it == '\n'.code || it == '\r'.code }; kind = Kind.SPACE }
                    c == '/'.code && next == '*'.code -> {
                        val close = text.indexOf("*/", p + 2)
                        if (close < 0) return null
                        end = close + 2; kind = Kind.SPACE
                    }
                    c == '"'.code -> {
                        val close = text.indexOf('"', p + 1)
                        if (close < 0) return null
                        end = close + 1; kind = Kind.SPACE
                    }
                    c == '\''.code -> { end = stringEnd(p) ?: return null; kind = Kind.SPACE }
                    c == '$'.code && next == '$'.code -> { end = until(p + 2) { !isWordPart(it) }; kind = Kind.SPACE }
                    c == '$'.code && next > 0 && Character.isLetter(next) -> {
                        end = until(p + 1) { !isWordPart(it) }
                        result += Element(Kind.DIRECTIVE, p, end, text.substring(p, end), text.substring(p + 1, end).uppercase())
                        p = end
                        continue
                    }
                    Character.isDigit(c) -> { end = until(p) { !(it > 0 && (Character.isLetterOrDigit(it) || it == '_'.code)) }; kind = Kind.OTHER }
                    isWordPart(c) && c != '$'.code -> {
                        val literal = quotedLiteralAt(p)
                        if (literal != null) {
                            end = if (literal < 0) return null else literal
                            kind = Kind.SPACE
                        } else {
                            end = until(p) { !isWordPart(it) }
                            val word = text.substring(p, end)
                            result += Element(Kind.WORD, p, end, word, special = word.contains('$') || word.contains('#'))
                            p = end
                            continue
                        }
                    }
                    c == '.'.code -> { end = p + 1; kind = Kind.DOT }
                    else -> { end = p + 1; kind = Kind.OTHER }
                }
                result += Element(kind, p, end, text.substring(p, end))
                p = end
            }
            return result
        }

        private fun until(start: Int, stop: (Int) -> Boolean): Int {
            var p = start
            while (p < text.length && !stop(char(p))) p++
            return p
        }

        /** `'…'` with `''` escapes, starting at the quote; null when unterminated. */
        private fun stringEnd(quote: Int): Int? {
            var p = quote + 1
            while (p < text.length) {
                if (char(p) == '\''.code) {
                    if (char(p + 1) == '\''.code) p += 2 else return p + 1
                } else {
                    p++
                }
            }
            return null
        }

        /** `n'…'`, `q'X…X'` or `nq'X…X'` at [position]: its end, -1 when unterminated, null when the word is not such a prefix. */
        private fun quotedLiteralAt(position: Int): Int? {
            var p = position
            if (char(p) == 'n'.code || char(p) == 'N'.code) p++
            val q = char(p) == 'q'.code || char(p) == 'Q'.code
            if (q) p++
            if (char(p) != '\''.code || p == position) return null
            if (!q) return stringEnd(p) ?: -1
            val open = char(p + 1)
            if (open < 0) return -1
            val close = when (open) {
                '['.code -> ']'.code
                '('.code -> ')'.code
                '{'.code -> '}'.code
                '<'.code -> '>'.code
                else -> open
            }
            var e = p + 2
            while (e < text.length && !(char(e) == close && char(e + 1) == '\''.code)) e++
            return if (e >= text.length) -1 else e + 2
        }
    }
}
