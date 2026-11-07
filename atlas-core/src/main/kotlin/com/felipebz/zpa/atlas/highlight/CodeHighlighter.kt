//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.highlight

import com.felipebz.flr.api.Token
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.api.PlSqlTokenType
import com.felipebz.zpa.lexer.PlSqlLexer
import com.felipebz.zpa.squid.ConditionalCompilationSettings
import com.felipebz.zpa.squid.PlSqlConfiguration
import java.nio.charset.StandardCharsets

/** What a span of code is, as zpa's highlighter colors it. */
enum class Highlight { KEYWORD, LITERAL, COMMENT, DOC_COMMENT }

/** One colored span: lines from 1, columns from 0, the end exclusive (the index's own convention). */
data class HighlightSpan(val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int, val highlight: Highlight)

/**
 * The colors of a file's text, by the rule of zpa's SonarQube highlighter (`PlSqlHighlighterVisitor`): a token of a
 * literal type (strings, numbers, dates, wrapped or embedded source) is a literal, a keyword token a keyword, a comment
 * a comment, and one that opens with a slash and two stars a documentation comment. The tokens are zpa's lexer's, read
 * with the conditional compilation of the run, so a keyword, a string's end and the kept branch are where the index saw
 * them.
 */
object CodeHighlighter {
    /** The kinds of skipped conditional-compilation text, decided by the settings or not: trivia, never a colored token. */
    private val SKIPPED = setOf(
        PlSqlTokenType.CONDITIONAL_BRANCH_BOUNDARY, PlSqlTokenType.CONDITIONAL_DIRECTIVE,
        PlSqlTokenType.CONDITIONAL_DECIDED_BOUNDARY, PlSqlTokenType.CONDITIONAL_DECIDED_DIRECTIVE
    )

    /**
     * The spans of [text] in source order, with the flags and database version as the run stores them (`run.ccflags`,
     * `run.database_version`); null when the lexer cannot read the text.
     */
    fun spans(text: String, ccflags: String? = null, databaseVersion: String? = null): List<HighlightSpan>? {
        val tokens = tokens(text, settings(ccflags, databaseVersion)) ?: return null
        val spans = ArrayList<HighlightSpan>()
        for (token in tokens) addSpans(token, spans)
        return spans
    }

    /** The conditional compilation of [ccflags] and [databaseVersion]; none when they cannot be read. */
    private fun settings(ccflags: String?, databaseVersion: String?) = try {
        ConditionalCompilationSettings.of(ccflags, databaseVersion)
    } catch (_: IllegalArgumentException) {
        ConditionalCompilationSettings.NONE
    }

    /** The tokens of [text] read with [settings]; null when the lexer cannot read it. */
    private fun tokens(text: String, settings: ConditionalCompilationSettings) = try {
        PlSqlLexer.create(PlSqlConfiguration(StandardCharsets.UTF_8, false, settings)).lex(text)
    } catch (_: Exception) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    /** Adds to [spans] the spans of [token]: its comments first, then the token itself. */
    private fun addSpans(token: Token, spans: MutableList<HighlightSpan>) {
        for (trivia in token.trivia) {
            if (trivia.isComment) {
                val comment = trivia.token
                spans += span(comment, if (comment.value.startsWith("/**")) Highlight.DOC_COMMENT else Highlight.COMMENT)
            }
        }
        val type = token.type
        if (type is PlSqlTokenType && type !in SKIPPED) spans += span(token, Highlight.LITERAL)
        if (type is PlSqlKeyword) spans += span(token, Highlight.KEYWORD)
    }

    private fun span(token: Token, highlight: Highlight) = HighlightSpan(token.line, token.column, token.endLine, token.endColumn, highlight)
}
