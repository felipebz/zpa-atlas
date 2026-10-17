//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.compare

import java.util.Locale

/**
 * A small PL/SQL token reader for the comparison, independent of zpa-core: it only needs to tell names from comments,
 * string literals (including `q'[...]'`) and quoted identifiers, which the token stream of the ZPA lexer also does.
 */
object PlSqlText {
    enum class Kind { WORD, QUOTED, SYMBOL }

    data class Token(val kind: Kind, val text: String, val start: Int, val end: Int) {
        /** Oracle identity of a name: unquoted upper-cased, quoted kept. */
        val name: String get() = if (kind == Kind.QUOTED) text.substring(1, text.length - 1) else text.uppercase(Locale.ROOT)
    }

    fun tokens(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && i + 1 < n && text[i + 1] == '-' -> { while (i < n && text[i] != '\n') i++ }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val close = text.indexOf("*/", i + 2)
                    i = if (close < 0) n else close + 2
                }
                (c == 'q' || c == 'Q' || ((c == 'n' || c == 'N') && i + 1 < n && (text[i + 1] == 'q' || text[i + 1] == 'Q'))) &&
                    quoteStart(text, i) != null -> {
                    i = skipQQuote(text, quoteStart(text, i)!!)
                }
                c == '\'' -> i = skipString(text, i)
                c == '"' -> {
                    val close = text.indexOf('"', i + 1)
                    val end = if (close < 0) n else close + 1
                    tokens += Token(Kind.QUOTED, text.substring(i, end), i, end)
                    i = end
                }
                c.isLetter() -> {
                    val start = i
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$' || text[i] == '#')) i++
                    // N'text' is a string literal, not the word N.
                    if (i - start == 1 && (c == 'n' || c == 'N') && i < n && text[i] == '\'') {
                        i = skipString(text, i)
                    } else {
                        tokens += Token(Kind.WORD, text.substring(start, i), start, i)
                    }
                }
                c.isDigit() -> { while (i < n && (text[i].isLetterOrDigit() || text[i] == '.')) i++ }
                else -> { tokens += Token(Kind.SYMBOL, c.toString(), i, i + 1); i++ }
            }
        }
        return tokens
    }

    /** The index of the opening quote of `q'x...x'` / `nq'x...x'` at [i], or null. */
    private fun quoteStart(text: String, i: Int): Int? {
        var j = i
        if (text[j] == 'n' || text[j] == 'N') j++
        if (j >= text.length || (text[j] != 'q' && text[j] != 'Q')) return null
        if (j > 0 && j == i && i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '_')) return null
        return if (j + 2 < text.length && text[j + 1] == '\'') j + 1 else null
    }

    private fun skipQQuote(text: String, quote: Int): Int {
        val open = text[quote + 1]
        val close = when (open) { '[' -> ']'; '{' -> '}'; '(' -> ')'; '<' -> '>'; else -> open }
        val end = text.indexOf("$close'", quote + 2)
        return if (end < 0) text.length else end + 2
    }

    private fun skipString(text: String, quote: Int): Int {
        var i = quote + 1
        while (i < text.length) {
            if (text[i] == '\'') {
                if (i + 1 < text.length && text[i + 1] == '\'') i += 2 else return i + 1
            } else {
                i++
            }
        }
        return i
    }

    /** Every name (word or quoted identifier) outside comments and string literals, as Oracle identities. */
    fun names(text: String): Set<String> = tokens(text).filter { it.kind != Kind.SYMBOL }.mapTo(HashSet()) { it.name }

    private val HEADER_WORDS = setOf(
        "CREATE", "OR", "REPLACE", "EDITIONABLE", "NONEDITIONABLE", "EDITIONING", "FORCE", "NO", "NOFORCE",
        "PACKAGE", "BODY", "PROCEDURE", "FUNCTION", "TRIGGER", "TYPE", "VIEW", "MATERIALIZED", "IF", "NOT", "EXISTS"
    )

    /**
     * The text after the unit name of a CREATE (file) or of the first `ALL_SOURCE` line, whose header Oracle stores as
     * `package` + blanks in place of `owner.` + the name as written. Null when no name is found.
     */
    fun afterHeader(text: String): String? {
        val tokens = tokens(text)
        var i = 0
        while (i < tokens.size && tokens[i].kind == Kind.WORD && tokens[i].name in HEADER_WORDS) i++
        if (i >= tokens.size || tokens[i].kind == Kind.SYMBOL) return null
        var name = tokens[i]
        if (i + 2 < tokens.size && tokens[i + 1].text == "." && tokens[i + 2].kind != Kind.SYMBOL) name = tokens[i + 2]
        return text.substring(name.end)
    }

    /** Whether the header declares wrapped source (`... name wrapped`). */
    fun isWrapped(text: String): Boolean {
        val rest = afterHeader(text) ?: return false
        return tokens(rest).firstOrNull()?.let { it.kind == Kind.WORD && it.name == "WRAPPED" } == true
    }

    /** Line ends as `\n`, trailing blanks of every line, blank last lines and a final `/` line removed. */
    fun normalize(text: String): List<String> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trimEnd() }.toMutableList()
        while (lines.isNotEmpty() && (lines.last().isEmpty() || lines.last() == "/")) lines.removeAt(lines.size - 1)
        return lines
    }

    /**
     * Compares a unit's file text with its `ALL_SOURCE` text after their headers; returns null when they are equal,
     * else the 1-based line of the stored source where they first differ.
     */
    fun firstDifference(fileText: String, storedText: String): Int? {
        val file = normalize(afterHeader(fileText) ?: fileText)
        val stored = normalize(afterHeader(storedText) ?: storedText)
        val max = maxOf(file.size, stored.size)
        for (i in 0 until max) {
            if (file.getOrNull(i) != stored.getOrNull(i)) return i + 1
        }
        return null
    }
}
