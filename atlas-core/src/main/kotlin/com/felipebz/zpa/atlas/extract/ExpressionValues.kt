//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.atlas.model.ValueKind
import java.math.BigDecimal

/** What an expression passes: its [kind], and its [value] for a literal, a name or a bind. */
data class ExpressionValue(val kind: ValueKind, val value: String?)

/**
 * The value of an expression passed to a call or written to a column, by its shape alone (whatever the call or the
 * column resolved to): a literal with its decoded value, one plain name or a bind as written, the keyword `DEFAULT`,
 * else an expression. Parentheses around one expression are unwrapped. An expression with a placeholder of a dynamic
 * text ([hasHoles]) is an expression: its text is not known.
 */
object ExpressionValues {
    fun of(expression: AstNode, hasHoles: (AstNode) -> Boolean = { false }): ExpressionValue {
        val node = unwrapped(expression)
        if (hasHoles(node)) return EXPRESSION
        return when (node.type) {
            PlSqlKeyword.DEFAULT -> ExpressionValue(ValueKind.DEFAULT, null)
            PlSqlGrammar.LITERAL -> literal(node.firstChild)
            PlSqlGrammar.UNARY_EXPRESSION -> signed(node)
            PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.MEMBER_EXPRESSION -> name(node)
            else -> EXPRESSION
        }
    }

    private val EXPRESSION = ExpressionValue(ValueKind.EXPRESSION, null)

    private fun unwrapped(node: AstNode): AstNode {
        // A positional actual is the ARGUMENT node around its expression.
        var current = if (node.type == PlSqlGrammar.ARGUMENT && node.numberOfChildren == 1) node.firstChild else node
        while (current.type == PlSqlGrammar.BRACKED_EXPRESSION && current.numberOfChildren == 3) current = current.children[1]
        return current
    }

    private fun literal(node: AstNode): ExpressionValue = when (node.type) {
        // Oracle takes a character value of length zero as null: `''` passes what `NULL` passes.
        PlSqlGrammar.CHARACTER_LITERAL -> SqlLiterals.decodeString(node.tokenValue)
            ?.let { if (it.isEmpty()) ExpressionValue(ValueKind.NULL, null) else ExpressionValue(ValueKind.STRING, it) } ?: EXPRESSION
        PlSqlGrammar.NUMERIC_LITERAL -> SqlLiterals.canonicalNumber(node.tokenValue)?.let { ExpressionValue(ValueKind.NUMBER, it) } ?: EXPRESSION
        PlSqlGrammar.NULL_LITERAL -> ExpressionValue(ValueKind.NULL, null)
        PlSqlGrammar.BOOLEAN_LITERAL -> ExpressionValue(ValueKind.BOOLEAN, node.tokenValue.uppercase())
        else -> EXPRESSION
    }

    /** `-1`, `+1.5`: a sign on a number is part of the number; on anything else, an expression. */
    private fun signed(node: AstNode): ExpressionValue {
        val sign = node.firstChild.type
        if (node.numberOfChildren != 2 || sign != PlSqlPunctuator.MINUS && sign != PlSqlPunctuator.PLUS) return EXPRESSION
        val operand = of(node.children[1])
        if (operand.kind != ValueKind.NUMBER) return EXPRESSION
        // The operand's number is negated, not its text: `- -1` is 1.
        return if (sign == PlSqlPunctuator.PLUS) operand else ExpressionValue(ValueKind.NUMBER, SqlLiterals.canonicalNumber(BigDecimal(operand.value).negate().toPlainString()))
    }

    /**
     * One plain name, dotted or not, normalized (`PACK_X.C_ATIVO`, `:NEW.X`): a bind alone (`:b1`) is a `BIND`. A cursor
     * attribute (`c%rowcount`) or anything with a call is an expression.
     */
    private fun name(node: AstNode): ExpressionValue {
        val parts = if (node.type == PlSqlGrammar.MEMBER_EXPRESSION) node.children else listOf(node)
        if (parts.any { it.type != PlSqlGrammar.VARIABLE_NAME && it.type != PlSqlGrammar.IDENTIFIER_NAME && it.type != PlSqlPunctuator.DOT }) return EXPRESSION
        val bind = parts.first().hasDirectChildren(PlSqlGrammar.HOST_AND_INDICATOR_VARIABLE)
        val text = tokensOf(node).joinToString("") { if (it.type is PlSqlPunctuator) it.originalValue else normalizeIdentifier(it.originalValue) }
        return ExpressionValue(if (bind && parts.size == 1) ValueKind.BIND else ValueKind.NAME, text)
    }
}

/** The values of SQL literals, decoded the same way wherever the atlas reads them. */
object SqlLiterals {
    /**
     * The text of a character literal: quotes removed, `''` turned into `'`, the `q` delimiters (`[]`, `{}`, `<>`, `()`,
     * or the same character on both sides) and the `N` prefix removed, case kept. Null when [literal] is not one.
     */
    fun decodeString(literal: String): String? {
        var text = literal
        if (text.startsWith('n') || text.startsWith('N')) text = text.substring(1)
        if (text.startsWith('q') || text.startsWith('Q')) {
            if (text.length < 5 || text[1] != '\'' || !text.endsWith('\'')) return null
            val open = text[2]
            val close = when (open) {
                '[' -> ']'
                '{' -> '}'
                '<' -> '>'
                '(' -> ')'
                else -> open
            }
            if (text[text.length - 2] != close) return null
            return text.substring(3, text.length - 2)
        }
        if (text.length < 2 || !text.startsWith('\'') || !text.endsWith('\'')) return null
        return text.substring(1, text.length - 1).replace("''", "'")
    }

    /**
     * The canonical text of a numeric literal: the same value always gives the same text (`1.50` and `1.5` give `1.5`,
     * `1e3` gives `1000`, `-0` gives `0`). Null for a literal that cannot be read exactly (`1.5f`, `2d`).
     */
    fun canonicalNumber(literal: String): String? {
        val number = try {
            BigDecimal(literal)
        } catch (e: NumberFormatException) {
            return null
        }
        return if (number.signum() == 0) "0" else number.stripTrailingZeros().toPlainString()
    }
}
