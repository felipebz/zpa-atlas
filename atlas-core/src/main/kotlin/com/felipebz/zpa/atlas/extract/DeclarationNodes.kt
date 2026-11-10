//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.atlas.model.SourceSpan

/**
 * The nodes of the tree under [root] with the span of one of [declarations], found in one walk over the file, a node
 * checked only when it starts where a declaration starts: the outermost one ([outermostAt]), the one of a
 * [DeclaredTexts.NODE_TYPES] type ([declaringAt]), which is the declaration itself under its wrappers, and a package or
 * routine ([documentedAt]); and the name of each declaration ([names]).
 */
internal class DeclarationNodes(private val root: AstNode, private val declarations: List<DeclarationOccurrence>) {
    private val spans = declarations.mapTo(HashSet()) { it.span }
    private val starts = declarations.mapTo(HashSet()) { position(it.span.startLine, it.span.startCol) }
    private val outermost = HashMap<SourceSpan, AstNode>()
    private val declaring = HashMap<SourceSpan, AstNode>()
    private val documented = HashMap<SourceSpan, AstNode>()

    init {
        if (declarations.isNotEmpty()) collect(root)
    }

    /** The outermost node with [span]; null when none has it (a declaration of a region the parser recovered). */
    fun outermostAt(span: SourceSpan): AstNode? = outermost[span]

    /** The node with [span] of a [DeclaredTexts.NODE_TYPES] type: the declaration itself under its wrappers. */
    fun declaringAt(span: SourceSpan): AstNode? = declaring[span]

    /** The package or routine ([DocComments.NODE_TYPES]) with [span]. */
    fun documentedAt(span: SourceSpan): AstNode? = documented[span]

    /**
     * Puts [node] and the nodes under it, in source order, that start at one of [starts] and have one of [spans], each
     * kept only as the first of its span: in [outermost], and by type in [declaring] and [documented].
     */
    private fun collect(node: AstNode) {
        if (node.hasToken() && position(node.token.line, node.token.column) in starts) {
            val span = spanOf(node)
            if (span in spans) {
                outermost.putIfAbsent(span, node)
                if (node.type in DeclaredTexts.NODE_TYPES) declaring.putIfAbsent(span, node)
                if (node.type in DocComments.NODE_TYPES) documented.putIfAbsent(span, node)
            }
        }
        for (child in node.children) collect(child)
    }

    /**
     * The name spans of the declarations, found in the AST: the outermost node with the declaration's span, then its
     * first `UNIT_NAME` or `IDENTIFIER_NAME` in source order (for a `UNIT_NAME`, the whole name or its last segment),
     * kept only when its text is the written name.
     */
    fun names(): Map<DeclarationOccurrence, SourceSpan> {
        if (declarations.isEmpty()) return emptyMap()
        val bySpan = declarations.groupBy { it.span }
        val result = HashMap<DeclarationOccurrence, SourceSpan>()
        for ((span, node) in outermost) {
            val name = firstName(node) ?: continue
            for (declaration in bySpan.getValue(span)) {
                matching(name, declaration.written)?.let { result[declaration] = spanOf(it) }
            }
        }
        // A declaration of a region the parser recovered has no node of its span: its first name inside the span.
        for (declaration in declarations) {
            if (declaration in result) continue
            nameWithin(root, declaration.span, declaration.written)?.let { result[declaration] = spanOf(it) }
        }
        return result
    }

    private fun isName(node: AstNode) = node.type == PlSqlGrammar.UNIT_NAME || node.type == PlSqlGrammar.IDENTIFIER_NAME

    private fun firstName(node: AstNode): AstNode? {
        if (isName(node)) return node
        for (child in node.children) firstName(child)?.let { return it }
        return null
    }

    /** [name] or, for a `UNIT_NAME`, one of its segments (the last first), when its text is [written]. */
    private fun matching(name: AstNode, written: String): AstNode? {
        val candidates =
            if (name.type == PlSqlGrammar.UNIT_NAME) listOf(name) + name.getChildren(PlSqlGrammar.IDENTIFIER_NAME)
                .asReversed() else listOf(name)
        return candidates.firstOrNull { writtenText(it) == written }
    }

    /** The first name node inside [span] that is [written], skipping subtrees outside it. */
    private fun nameWithin(node: AstNode, span: SourceSpan, written: String): AstNode? {
        if (!node.hasToken()) return null
        val own = spanOf(node)
        if (own.endLine < span.startLine || own.endLine == span.startLine && own.endCol <= span.startCol) return null
        if (own.startLine > span.endLine || own.startLine == span.endLine && own.startCol >= span.endCol) return null
        if (isName(node) && span.contains(own.startLine, own.startCol)) matching(node, written)?.let { return it }
        for (child in node.children) nameWithin(child, span, written)?.let { return it }
        return null
    }

    private fun position(line: Int, column: Int): Long = line.toLong() shl 32 or column.toLong()
}
