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
    /** The two kinds of skipped conditional-compilation text: trivia for the highlighter, never a colored token. */
    private val SKIPPED = setOf(PlSqlTokenType.CONDITIONAL_BRANCH_BOUNDARY, PlSqlTokenType.CONDITIONAL_DIRECTIVE)

    /**
     * The spans of [text] in source order, with the flags and database version as the run stores them (`run.ccflags`,
     * `run.database_version`); null when the lexer cannot read the text.
     */
    fun spans(text: String, ccflags: String? = null, databaseVersion: String? = null): List<HighlightSpan>? {
        val settings = try {
            ConditionalCompilationSettings.of(ccflags, databaseVersion)
        } catch (_: IllegalArgumentException) {
            ConditionalCompilationSettings.NONE
        }
        val tokens = try {
            PlSqlLexer.create(PlSqlConfiguration(StandardCharsets.UTF_8, false, settings)).lex(text)
        } catch (_: Exception) {
            return null
        } catch (_: StackOverflowError) {
            return null
        }
        val spans = ArrayList<HighlightSpan>()
        for (token in tokens) {
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
        return spans
    }

    private fun span(token: Token, highlight: Highlight) = HighlightSpan(token.line, token.column, token.endLine, token.endColumn, highlight)
}
