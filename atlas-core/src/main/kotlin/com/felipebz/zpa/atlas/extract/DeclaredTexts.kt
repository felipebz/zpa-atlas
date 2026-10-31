//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.flr.api.Token
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.atlas.model.DeclaredTextFact

/**
 * What a declaration says, read from the nodes of zpa's syntax tree: a function's or a cursor's return type, a
 * parameter's type and default, a package variable's, constant's or record field's type and initial value. The text is
 * the tokens as written, comments and line breaks left out and every run of spaces between two tokens made one: an
 * anchor (`%TYPE`) is not resolved and an expression is not evaluated. What conditional compilation left out of the
 * tokens is not there: the text is the branch the lexer kept.
 */
internal object DeclaredTexts {
    /**
     * The most characters a value keeps; a longer one is cut and marked ([DeclaredTextFact.valueCut]), the whole text
     * being in the file. Enough for a value to be read in a header on a phone (five or six lines), short enough that a
     * big literal does not grow the index.
     */
    const val VALUE_LIMIT = 200

    /** The declaration nodes that say something beside their name, as the declaration catalog spans them. */
    val NODE_TYPES = setOf(
        PlSqlGrammar.FUNCTION_DECLARATION, PlSqlGrammar.CREATE_FUNCTION, PlSqlGrammar.CURSOR_DECLARATION,
        PlSqlGrammar.PARAMETER_DECLARATION, PlSqlGrammar.CURSOR_PARAMETER_DECLARATION,
        PlSqlGrammar.VARIABLE_DECLARATION, PlSqlGrammar.RECORD_FIELD_DECLARATION
    )

    /** The fact of [entityKey] declared by [node] (one of [NODE_TYPES]); null when it says nothing beside its name. */
    fun of(entityKey: String, node: AstNode): DeclaredTextFact? {
        // A function's or cursor's DATATYPE child is its return type: its parameters' types are inside their own nodes.
        val datatype = node.getFirstChildOrNull(PlSqlGrammar.DATATYPE)
        val constraint = node.getFirstChildOrNull(PlSqlGrammar.DATATYPE_NULL_CONSTRAINT)
        val type = datatype?.let { text(tokensOf(it) + constraint?.let(::tokensOf).orEmpty()) }
        val value = node.getFirstChildOrNull(PlSqlGrammar.DEFAULT_VALUE_ASSIGNMENT)?.lastChild?.let { text(tokensOf(it)) }
        if (type == null && value == null) return null
        val cut = value != null && value.codePointCount(0, value.length) > VALUE_LIMIT
        return DeclaredTextFact(entityKey, type, if (cut) cutText(value!!) + "…" else value, cut)
    }

    /**
     * [tokens] as one line: each as written, a line break inside one (a long literal) made a space; one space between
     * two tokens where the source has anything between them (spaces, line breaks, comments), none where they touch.
     */
    fun text(tokens: List<Token>): String {
        val text = StringBuilder()
        var previous: Token? = null
        for (token in tokens) {
            if (previous != null && (previous.endLine != token.line || previous.endColumn != token.column)) text.append(' ')
            text.append(token.originalValue.replace(LINE_BREAK, " "))
            previous = token
        }
        return text.toString()
    }

    private val LINE_BREAK = Regex("[ \\t]*\\R[ \\t]*")

    /** The first [VALUE_LIMIT] characters of [text], never splitting a character. */
    private fun cutText(text: String) = text.substring(0, text.offsetByCodePoints(0, VALUE_LIMIT)).trimEnd()
}
