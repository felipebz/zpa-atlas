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
    enum class Kind { WORD, QUOTED, SYMBOL, LITERAL }

    data class Token(val kind: Kind, val text: String, val start: Int, val end: Int) {
        /** Oracle identity of a name: unquoted upper-cased, quoted kept. */
        val name: String get() = if (kind == Kind.QUOTED) text.substring(1, text.length - 1) else text.uppercase(Locale.ROOT)
    }

    /** Names and symbols, without comments, string literals and numbers. */
    fun tokens(text: String): List<Token> = tokens(text, literals = false)

    /** With [literals], string literals (`'…'`, `n'…'`, `q'[…]'`) and numbers are [Kind.LITERAL] tokens instead of skipped. */
    private fun tokens(text: String, literals: Boolean): List<Token> {
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
                    val start = i
                    i = skipQQuote(text, quoteStart(text, i)!!)
                    if (literals) tokens += Token(Kind.LITERAL, text.substring(start, i), start, i)
                }
                c == '\'' -> {
                    val start = i
                    i = skipString(text, i)
                    if (literals) tokens += Token(Kind.LITERAL, text.substring(start, i), start, i)
                }
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
                        if (literals) tokens += Token(Kind.LITERAL, text.substring(start, i), start, i)
                    } else {
                        tokens += Token(Kind.WORD, text.substring(start, i), start, i)
                    }
                }
                c.isDigit() -> {
                    val start = i
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '.')) i++
                    if (literals) tokens += Token(Kind.LITERAL, text.substring(start, i), start, i)
                }
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

    /**
     * Compares the query of a view's file text (a `CREATE VIEW`) with its `ALL_VIEWS.TEXT`: null when they are the
     * same, else the 1-based line of the stored text where the first differing token is.
     *
     * The file query is the text after the `AS` that follows the view name and its column list, without the final `;`
     * or `/`, and without the name of a `WITH CHECK OPTION CONSTRAINT x` (Oracle stores the clause without it). Tokens
     * are compared ignoring whitespace and comments: names as Oracle identities, literals and symbols exactly.
     *
     * Oracle stores a select-list `*` expanded: `*` as `"A","B"` and `q.*` as `q."A",q."B"` (quoted names, commas
     * without blanks). A file star therefore consumes, on the stored side, the run of items of that form: quoted names
     * for `*`, names qualified by the same qualifier for `q.*`. Oracle expands only the stars of the view's own select
     * list: a star of a subquery (an inline view, `EXISTS`, `IN`) and the star of a view that does not compile stay
     * as written, so a stored `*`, or a stored `q.*` with the same qualifier, matches the file star itself. A quoted column written right after a star (`*, "X"`) is consumed by the star too, so such a view compares
     * as different: an accepted false difference.
     */
    fun viewDifference(fileText: String, storedText: String): Int? {
        val file = viewQuery(tokens(fileText, literals = true))
        val stored = tokens(storedText, literals = true)
        fun lineOf(index: Int): Int {
            val offset = stored.getOrNull(index)?.start ?: storedText.trimEnd().length
            return storedText.substring(0, offset).count { it == '\n' } + 1
        }
        var f = 0
        var s = 0
        while (f < file.size && s < stored.size) {
            val star = selectStar(file, f)
            if (star != null && !storedStar(stored, s, star.second)) {
                val end = expansionEnd(stored, s, star.second) ?: return lineOf(s)
                f = star.first
                s = end
                continue
            }
            if (!same(file[f], stored[s])) return lineOf(s)
            f++
            s++
        }
        return if (f == file.size && s == stored.size) null else lineOf(s)
    }

    /** Whether the stored text keeps the star at [i] as written: `*`, or `q.*` with [qualifier]. */
    private fun storedStar(tokens: List<Token>, i: Int, qualifier: String?): Boolean {
        val t = tokens[i]
        if (qualifier == null) return t.kind == Kind.SYMBOL && t.text == "*"
        return t.kind != Kind.SYMBOL && t.kind != Kind.LITERAL && t.name == qualifier &&
            tokens.getOrNull(i + 1)?.text == "." && tokens.getOrNull(i + 2)?.let { it.kind == Kind.SYMBOL && it.text == "*" } == true
    }

    private fun same(a: Token, b: Token): Boolean = when {
        a.kind == Kind.LITERAL || b.kind == Kind.LITERAL || a.kind == Kind.SYMBOL || b.kind == Kind.SYMBOL -> a.kind == b.kind && a.text == b.text
        else -> a.name == b.name
    }

    /** The tokens of the query of a `CREATE VIEW`: after the `AS` that follows the name and the column list. */
    private fun viewQuery(tokens: List<Token>): List<Token> {
        var i = 0
        while (i < tokens.size && tokens[i].kind == Kind.WORD && tokens[i].name in HEADER_WORDS) i++
        var depth = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.kind == Kind.SYMBOL && t.text == "(") depth++
            if (t.kind == Kind.SYMBOL && t.text == ")") depth--
            i++
            if (depth == 0 && t.kind == Kind.WORD && t.name == "AS") break
        }
        var query = tokens.subList(minOf(i, tokens.size), tokens.size)
        while (query.isNotEmpty() && query.last().kind == Kind.SYMBOL && (query.last().text == ";" || query.last().text == "/")) query = query.dropLast(1)
        val n = query.size
        if (n >= 5 && query[n - 2].kind == Kind.WORD && query[n - 2].name == "CONSTRAINT" && query[n - 3].name == "OPTION" && query[n - 4].name == "CHECK") {
            query = query.dropLast(2)
        }
        return query
    }

    /**
     * A select-list star of the file at [i]: `*` or `q.*` after `SELECT`, `DISTINCT`, `UNIQUE`, `ALL` or a comma, and
     * before a comma or `FROM`. Returns the index after it and its qualifier (null for `*`), or null.
     */
    private fun selectStar(tokens: List<Token>, i: Int): Pair<Int, String?>? {
        fun isStar(t: Token?) = t != null && t.kind == Kind.SYMBOL && t.text == "*"
        fun opensItem(t: Token?) = t != null && (t.kind == Kind.SYMBOL && t.text == "," || t.kind == Kind.WORD && t.name in SELECT_OPENERS)
        fun closesItem(t: Token?) = t != null && (t.kind == Kind.SYMBOL && t.text == "," || t.kind == Kind.WORD && t.name == "FROM")
        val t = tokens[i]
        if (isStar(t) && opensItem(tokens.getOrNull(i - 1)) && closesItem(tokens.getOrNull(i + 1))) return i + 1 to null
        if (t.kind != Kind.SYMBOL && tokens.getOrNull(i + 1)?.text == "." && isStar(tokens.getOrNull(i + 2)) &&
            opensItem(tokens.getOrNull(i - 1)) && closesItem(tokens.getOrNull(i + 3))) return i + 3 to t.name
        return null
    }

    /** The end of a stored star expansion starting at [i]: `"A","B"`, or `q."A",q."B"` for [qualifier]; null when there is none. */
    private fun expansionEnd(tokens: List<Token>, i: Int, qualifier: String?): Int? {
        fun item(at: Int): Int? = if (qualifier == null) {
            at.takeIf { tokens.getOrNull(it)?.kind == Kind.QUOTED && tokens.getOrNull(it + 1)?.text != "." }?.plus(1)
        } else {
            val q = tokens.getOrNull(at)
            at.takeIf { q != null && q.kind != Kind.SYMBOL && q.kind != Kind.LITERAL && q.name == qualifier &&
                tokens.getOrNull(at + 1)?.text == "." && tokens.getOrNull(at + 2)?.kind == Kind.QUOTED }?.plus(3)
        }
        var end = item(i) ?: return null
        while (tokens.getOrNull(end)?.text == ",") end = item(end + 1) ?: break
        return end
    }

    private val SELECT_OPENERS = setOf("SELECT", "DISTINCT", "UNIQUE", "ALL")
}
