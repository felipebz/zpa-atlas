//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.atlas.model.ValueKind
import java.math.BigDecimal

/** What an argument expression passes: its [kind], and its [value] for a literal, a name or a bind. */
data class ArgumentValue(val kind: ValueKind, val value: String?)

/**
 * The value of an argument expression, by its shape alone (whatever the call resolved to): a literal with its decoded
 * value, one plain name or a bind as written, else an expression. Parentheses around one expression are unwrapped.
 */
object ArgumentValues {
    fun of(expression: AstNode): ArgumentValue {
        val node = unwrapped(expression)
        return when (node.type) {
            PlSqlGrammar.LITERAL -> literal(node.firstChild)
            PlSqlGrammar.UNARY_EXPRESSION -> signed(node)
            PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.MEMBER_EXPRESSION -> name(node)
            else -> EXPRESSION
        }
    }

    private val EXPRESSION = ArgumentValue(ValueKind.EXPRESSION, null)

    private fun unwrapped(node: AstNode): AstNode {
        // A positional actual is the ARGUMENT node around its expression.
        var current = if (node.type == PlSqlGrammar.ARGUMENT && node.numberOfChildren == 1) node.firstChild else node
        while (current.type == PlSqlGrammar.BRACKED_EXPRESSION && current.numberOfChildren == 3) current = current.children[1]
        return current
    }

    private fun literal(node: AstNode): ArgumentValue = when (node.type) {
        // Oracle takes a character value of length zero as null: `''` passes what `NULL` passes.
        PlSqlGrammar.CHARACTER_LITERAL -> SqlLiterals.decodeString(node.tokenValue)
            ?.let { if (it.isEmpty()) ArgumentValue(ValueKind.NULL, null) else ArgumentValue(ValueKind.STRING, it) } ?: EXPRESSION
        PlSqlGrammar.NUMERIC_LITERAL -> SqlLiterals.canonicalNumber(node.tokenValue)?.let { ArgumentValue(ValueKind.NUMBER, it) } ?: EXPRESSION
        PlSqlGrammar.NULL_LITERAL -> ArgumentValue(ValueKind.NULL, null)
        PlSqlGrammar.BOOLEAN_LITERAL -> ArgumentValue(ValueKind.BOOLEAN, node.tokenValue.uppercase())
        else -> EXPRESSION
    }

    /** `-1`, `+1.5`: a sign on a number is part of the number; on anything else, an expression. */
    private fun signed(node: AstNode): ArgumentValue {
        val sign = node.firstChild.type
        if (node.numberOfChildren != 2 || sign != PlSqlPunctuator.MINUS && sign != PlSqlPunctuator.PLUS) return EXPRESSION
        val operand = of(node.children[1])
        if (operand.kind != ValueKind.NUMBER) return EXPRESSION
        // The operand's number is negated, not its text: `- -1` is 1.
        return if (sign == PlSqlPunctuator.PLUS) operand else ArgumentValue(ValueKind.NUMBER, SqlLiterals.canonicalNumber(BigDecimal(operand.value).negate().toPlainString()))
    }

    /**
     * One plain name, dotted or not, normalized (`PACK_X.C_ATIVO`, `:NEW.X`): a bind alone (`:b1`) is a `BIND`. A cursor
     * attribute (`c%rowcount`) or anything with a call is an expression.
     */
    private fun name(node: AstNode): ArgumentValue {
        val parts = if (node.type == PlSqlGrammar.MEMBER_EXPRESSION) node.children else listOf(node)
        if (parts.any { it.type != PlSqlGrammar.VARIABLE_NAME && it.type != PlSqlGrammar.IDENTIFIER_NAME && it.type != PlSqlPunctuator.DOT }) return EXPRESSION
        val bind = parts.first().hasDirectChildren(PlSqlGrammar.HOST_AND_INDICATOR_VARIABLE)
        val text = tokensOf(node).joinToString("") { if (it.type is PlSqlPunctuator) it.originalValue else normalizeIdentifier(it.originalValue) }
        return ArgumentValue(if (bind && parts.size == 1) ValueKind.BIND else ValueKind.NAME, text)
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
