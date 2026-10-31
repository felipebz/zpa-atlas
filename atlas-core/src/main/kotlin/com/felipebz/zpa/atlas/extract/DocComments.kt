//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.flr.api.GenericTokenType
import com.felipebz.flr.api.Token
import com.felipebz.flr.impl.Lexer
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.atlas.model.DocCommentFact
import com.felipebz.zpa.atlas.model.DocShape
import com.felipebz.zpa.atlas.model.SourceSpan
import com.felipebz.zpa.lexer.PlSqlLexer
import com.felipebz.zpa.squid.PlSqlConfiguration
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * The documentation comment of each declaration of one file, read from the comments zpa's lexer attaches to the tokens
 * of the syntax tree. The first of these is the declaration's documentation:
 *
 * - the block right above it ([DocShape.ABOVE]): comments with no blank line between them nor before the declaration,
 *   leaving out a comment that ends the previous line of code;
 * - for a package or a routine with a body, the first block after its `IS`/`AS` ([DocShape.AFTER_IS]), then for a
 *   routine the first block after its `BEGIN` ([DocShape.AFTER_BEGIN]), each only when a blank line follows it (a block
 *   glued to the next line comments that line; after a package's `IS`, it is the first member's block above);
 * - the comment ending the declaration's own line, after nothing but `,` `;` `)` ([DocShape.END_OF_LINE]).
 *
 * A block is never taken when it is only `--%` annotations or only separator lines, holds license words, comes before
 * the file's first token, or reads as code (half its lines or more, read by zpa's lexer, end with `;` or hold `:=`).
 */
internal class DocComments(root: AstNode) {
    private val tokens: List<Token> = root.tokens
    private val starts = HashMap<Long, Int>()
    private val ends = HashMap<Long, Int>()
    private val comments: List<List<Comment>>

    init {
        comments = tokens.mapIndexed { i, token ->
            starts.putIfAbsent(position(token.line, token.column), i)
            ends[position(token.endLine, token.endColumn)] = i
            token.trivia.filter { it.isComment }.map { Comment(it.token) }
        }
    }

    /**
     * The documentation of the declaration of [entityKey] at [span], [inBody] when it is in a package or type body;
     * [node] is the declaration's package or routine node (one of [NODE_TYPES]), null for other declarations.
     */
    fun of(entityKey: String, span: SourceSpan, inBody: Boolean, node: AstNode?): DocCommentFact? {
        val first = starts[position(span.startLine, span.startCol)] ?: return null
        val last = ends[position(span.endLine, span.endCol)] ?: return null
        val (shape, block) = above(first)?.let { DocShape.ABOVE to it }
            ?: node?.let(::afterIs)?.let { DocShape.AFTER_IS to it }
            ?: node?.takeIf { it.type in ROUTINE_TYPES }?.let(::afterBegin)?.let { DocShape.AFTER_BEGIN to it }
            ?: endOfLine(last)?.let { DocShape.END_OF_LINE to it }
            ?: return null
        val text = keptLines(block).joinToString("\n")
        val cut = text.codePointCount(0, text.length) > TEXT_LIMIT
        return DocCommentFact(entityKey, inBody, shape, block.first().line, if (cut) cutText(text) else text, cut)
    }

    /** The block right above token [first], when it touches it and may be taken. */
    private fun above(first: Int): List<Comment>? {
        val token = tokens[first]
        val previousLine = if (first == 0) -1 else tokens[first - 1].endLine
        val own = comments[first].filter { it.line != previousLine }
        if (own.isEmpty()) return null
        val block = blocks(own).last()
        val gap = if (block.last().endLine == token.line) -1 else token.line - block.last().endLine - 1
        return block.takeIf { gap <= 0 && taken(it, first) }
    }

    private fun afterIs(node: AstNode): List<Comment>? {
        val keyword = node.getFirstChildOrNull(PlSqlKeyword.IS, PlSqlKeyword.AS) ?: return null
        return firstBlockAfter(keyword.token)
    }

    private fun afterBegin(node: AstNode): List<Comment>? {
        val statements = node.getFirstChildOrNull(PlSqlGrammar.STATEMENTS_SECTION) ?: return null
        return firstBlockAfter(statements.token)
    }

    /** The first block after [keyword], when a blank line follows it (else it comments the next line) and it may be taken. */
    private fun firstBlockAfter(keyword: Token): List<Comment>? {
        val at = starts[position(keyword.line, keyword.column)] ?: return null
        if (at + 1 >= tokens.size) return null
        val after = comments[at + 1]
        if (after.isEmpty()) return null
        val blocks = blocks(after)
        val block = blocks.first()
        val glued = blocks.size == 1 && tokens[at + 1].line - block.last().endLine <= 1
        return block.takeIf { !glued && taken(it, at + 1) }
    }

    /** The comments starting on the declaration's last line after it, past only `,` `;` `)`. */
    private fun endOfLine(last: Int): List<Comment>? {
        val line = tokens[last].endLine
        val found = ArrayList<Comment>()
        for (t in last + 1 until tokens.size) {
            comments[t].filterTo(found) { it.line == line }
            val token = tokens[t]
            if (token.line != line || token.originalValue !in END_PUNCTUATION) break
        }
        return found.takeIf { it.isNotEmpty() && taken(it, -1) }
    }

    /** Whether [block], in the trivia of the token at [owner], may be documentation. */
    private fun taken(block: List<Comment>, owner: Int): Boolean {
        if (owner == 0) return false
        if (block.all { it.annotation }) return false
        val lines = keptLines(block, dropSeparators = false).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.all(::separatorLine)) return false
        val raw = block.joinToString("\n") { it.text }.lowercase(Locale.ROOT)
        if (LICENSE_WORDS.any { it in raw }) return false
        return !codeLike(lines.filterNot(::separatorLine))
    }

    private class Comment(token: Token) {
        val text: String = token.originalValue
        val line = token.line
        val endLine = token.endLine
        val lineComment = text.startsWith("--")
        val annotation = lineComment && text.substring(2).trimStart().startsWith("%")
    }

    companion object {
        /** The nodes of the declarations whose `IS`/`AS` (and for a routine, `BEGIN`) may be followed by their documentation. */
        val NODE_TYPES = setOf(
            PlSqlGrammar.CREATE_PACKAGE, PlSqlGrammar.CREATE_PACKAGE_BODY, PlSqlGrammar.PROCEDURE_DECLARATION,
            PlSqlGrammar.FUNCTION_DECLARATION, PlSqlGrammar.CREATE_PROCEDURE, PlSqlGrammar.CREATE_FUNCTION
        )

        private val ROUTINE_TYPES = NODE_TYPES - setOf(PlSqlGrammar.CREATE_PACKAGE, PlSqlGrammar.CREATE_PACKAGE_BODY)

        /**
         * The most characters a documentation keeps; a longer one is cut at a line break and marked. Above the longest
         * block of the open corpora measured, which are history tables of a few dozen lines, so only a pasted
         * document or a long changelog is cut.
         */
        const val TEXT_LIMIT = 4000

        private val END_PUNCTUATION = setOf(",", ";", ")")
        private val LICENSE_WORDS = listOf("copyright", "license", "licence", "(c)")

        /** One lexer per thread for the code check: the lexer keeps state while it reads. */
        private val lexer = ThreadLocal.withInitial<Lexer> { PlSqlLexer.create(PlSqlConfiguration(StandardCharsets.UTF_8)) }

        private fun position(line: Int, column: Int) = (line.toLong() shl 32) or column.toLong()

        /** Comments in runs with no blank line between two of them. */
        private fun blocks(comments: List<Comment>): List<List<Comment>> {
            val blocks = ArrayList<List<Comment>>()
            var current = ArrayList<Comment>()
            for (comment in comments) {
                if (current.isNotEmpty() && comment.line - current.last().endLine > 1) {
                    blocks += current
                    current = ArrayList()
                }
                current += comment
            }
            if (current.isNotEmpty()) blocks += current
            return blocks
        }

        private fun separatorLine(line: String) = line.isNotBlank() && line.codePoints().noneMatch(Character::isLetterOrDigit)

        /**
         * The text of [block]: annotation comments left out, each comment without its markers (the dashes, the opening
         * and closing of a block comment, a leading star), the lines dedented together keeping their relative
         * indentation; with [dropSeparators], without separator lines; no blank line at either end nor two in a row, no
         * trailing spaces.
         */
        private fun keptLines(block: List<Comment>, dropSeparators: Boolean = true): List<String> {
            // A block comment's first line follows its opening marker: it is never part of the common indentation.
            val lines = ArrayList<Pair<String, Boolean>>()
            for (comment in block) {
                if (comment.annotation) continue
                if (comment.lineComment) {
                    lines += comment.text.substring(2) to true
                    continue
                }
                val inner = comment.text.removePrefix("/*").removePrefix("*").removeSuffix("*/")
                for ((i, raw) in inner.split('\n').withIndex()) {
                    val line = raw.trimEnd('\r')
                    lines += if (i == 0) line.trimStart() to false else stripStar(line) to true
                }
            }
            // Separator lines often start at the marker: they never set the indentation of the words.
            val indent = lines.filter { (line, dedent) -> dedent && line.isNotBlank() && !separatorLine(line) }
                .minOfOrNull { (line) -> line.length - line.trimStart().length } ?: 0
            val result = ArrayList<String>()
            for ((line, dedent) in lines) {
                val text = (if (dedent) line.drop(minOf(indent, line.length - line.trimStart().length)) else line).trimEnd()
                if (dropSeparators && separatorLine(text)) continue
                if (text.isEmpty() && (result.isEmpty() || result.last().isEmpty())) continue
                result += text
            }
            while (result.isNotEmpty() && result.last().isEmpty()) result.removeAt(result.lastIndex)
            return result
        }

        /** A block comment's line without a leading `*` and the space after it, its indentation before the star kept. */
        private fun stripStar(line: String): String {
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("*")) return line
            val indent = line.length - trimmed.length
            val rest = trimmed.substring(1)
            return " ".repeat(indent) + (if (rest.startsWith(" ")) rest.substring(1) else rest)
        }

        /** Commented-out code: half the [lines] or more, read by zpa's lexer, end with `;` or hold `:=`. */
        private fun codeLike(lines: List<String>): Boolean {
            if (lines.isEmpty()) return false
            val code = lines.count { line ->
                val tokens = try {
                    lexer.get().lex(line).filter { it.type != GenericTokenType.EOF }
                } catch (_: Exception) {
                    emptyList()
                } catch (_: StackOverflowError) {
                    emptyList()
                }
                tokens.isNotEmpty() && (tokens.last().originalValue == ";" || tokens.any { it.originalValue == ":=" })
            }
            return code * 2 >= lines.size
        }

        /** The first [TEXT_LIMIT] characters of [text], back to the last line break when there is one, then the mark. */
        private fun cutText(text: String): String {
            val end = text.offsetByCodePoints(0, TEXT_LIMIT)
            val lineBreak = text.lastIndexOf('\n', end)
            return (if (lineBreak > 0) text.substring(0, lineBreak) else text.substring(0, end).trimEnd()) + "\n…"
        }
    }
}
