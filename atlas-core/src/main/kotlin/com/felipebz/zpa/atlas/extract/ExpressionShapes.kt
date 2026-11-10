//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.*
import com.felipebz.zpa.api.semantics.NameBinding
import com.felipebz.zpa.api.semantics.NameDeclarationKind
import com.felipebz.zpa.atlas.model.*

/**
 * The shapes of the expressions a column's value, a record's field or a parameter is read within: the value functions
 * one value is read through ([valueChain]), where following it stops, and the family of an expression that is not
 * followed ([expressionShape], [valueShape], [argumentShape]).
 */
internal class ExpressionShapes(
    private val plsql: PlSqlNames,
    private val state: FileState,
    private val nameBinding: (AstNode) -> NameBinding
) {
    /**
     * The value functions [expression] reads one value through, outer first, down to the node [isValue] accepts; null
     * when it is anything else. A value function gives back one of its arguments, or that argument trimmed or in
     * another case: `NVL(a, b)` and `COALESCE(a, b, …)` (any argument), `NVL2(e1, e2, e3)` (`e2` or `e3`), `MAX(x)` and
     * `MIN(x)` (with `DISTINCT`, `ALL`, `KEEP (…)`, `OVER (…)`), `TRIM`, `LTRIM`, `RTRIM`, `UPPER`, `LOWER` (`x`). `SUBSTR`
     * and `SUBSTRB` give back a part of their first argument, with their start and length when literal ([Substrings]).
     * `REPLACE` and `REGEXP_REPLACE` give back their first argument with text taken out or put in, and put their
     * replacement text (the third) into other text, as a concatenation does. The search text or pattern, the position,
     * occurrence and match arguments of `REGEXP_REPLACE`, and the start and length of `SUBSTR`, never land in the value
     * ([inDroppedArgument]). Exactly one of the arguments that may be the value must lead to the node.
     */
    fun valueChain(expression: AstNode, isValue: (AstNode) -> Boolean): List<String>? {
        val node = unbracketed(expression)
        if (isValue(node)) return emptyList()
        return when (node.type) {
            PlSqlGrammar.POSTFIX_EXPRESSION -> aggregateCall(node)?.let { valueChain(it, isValue) }
            SingleRowSqlFunctionsGrammar.SINGLE_ROW_SQL_FUNCTION ->
                node.firstChild.takeIf { it.type == SingleRowSqlFunctionsGrammar.TRIM_EXPRESSION }?.let(::trimmed)
                    ?.let { chainThrough("TRIM", listOf(it), isValue) }
            // A concatenation: the value is one of its parts, with other text around it.
            PlSqlGrammar.CONCATENATION_EXPRESSION ->
                node.children.filter { it.type != PlSqlGrammar.CONCATENATION_OPERATOR }
                    .firstNotNullOfOrNull { valueChain(it, isValue) }
                    ?.let { listOf(CONCATENATED) + it }

            PlSqlGrammar.METHOD_CALL -> callChain(node, isValue)
            else -> null
        }
    }

    /** [node], its brackets taken off (`((x))` is `x`). */
    private fun unbracketed(node: AstNode): AstNode {
        var inner = node
        while (inner.type == PlSqlGrammar.BRACKED_EXPRESSION && inner.numberOfChildren == 3) inner = inner.children[1]
        return inner
    }

    /** The name [call] calls, upper-cased, when it is a single name; null for anything else. */
    private fun calledName(call: AstNode): String? = plsql.nameSegments(call.firstChild)?.singleOrNull()?.uppercase()

    /** The expressions of the arguments of [call], when it has a single argument list; null for anything else. */
    private fun argumentValues(call: AstNode): List<AstNode>? =
        call.getChildren(PlSqlGrammar.ARGUMENTS).singleOrNull()?.getChildren(PlSqlGrammar.ARGUMENT)
            ?.map { it.lastChild }

    /** The call of `MAX` or `MIN` the postfix expression [postfix] is, with only `KEEP (…)` and `OVER (…)` after it; else null. */
    private fun aggregateCall(postfix: AstNode): AstNode? = postfix.firstChild.takeIf { call ->
        call.type == PlSqlGrammar.METHOD_CALL && calledName(call) in MAX_MIN &&
            postfix.children.drop(1).all { it.type == DmlGrammar.KEEP_CLAUSE || it.type == DmlGrammar.ANALYTIC_CLAUSE }
    }

    /** The value the `TRIM` expression [trim] trims (`TRIM(x)`, `TRIM(… FROM x)`); null for anything else. */
    private fun trimmed(trim: AstNode): AstNode? {
        val parts =
            trim.children.filter { it.type != PlSqlPunctuator.LPARENTHESIS && it.type != PlSqlPunctuator.RPARENTHESIS }
                .drop(1)
        val from = parts.indexOfFirst { it.type == PlSqlKeyword.FROM }
        return if (from >= 0) parts.getOrNull(from + 1) else parts.singleOrNull()
    }

    /** [valueChain] of the call [call]: `CONCAT`, `REPLACE`, `REGEXP_REPLACE`, `SUBSTR` and `SUBSTRB`, or a value function of one of its arguments. */
    private fun callChain(call: AstNode, isValue: (AstNode) -> Boolean): List<String>? {
        val function = calledName(call) ?: return null
        val arguments = argumentValues(call) ?: return null
        if (function == "CONCAT") {
            return arguments.takeIf { it.size == 2 }?.firstNotNullOfOrNull { valueChain(it, isValue) }
                ?.let { listOf(CONCATENATED) + it }
        }
        if (function in REPLACES) return replaceChain(function, arguments, isValue)
        if (function in Substrings.NAMES) {
            if (arguments.size !in 2..3) return null
            return valueChain(arguments[0], isValue)?.let {
                listOf(
                    Substrings.entry(
                        function,
                        arguments.drop(1).map(::wholeNumber)
                    )
                ) + it
            }
        }
        val candidates = givenBack(function, arguments) ?: return null
        return chainThrough(function, candidates, isValue)
    }

    /**
     * The arguments of a call of the value function [function] that it may give back, trimmed or in another case, among
     * its [arguments]: any of `NVL` and `COALESCE`, the last two of `NVL2`, the only one of `MAX`, `MIN`, `UPPER`,
     * `LOWER`, `TRIM`, the first of `LTRIM` and `RTRIM`; null for another function or another number of arguments.
     */
    private fun givenBack(function: String, arguments: List<AstNode>): List<AstNode>? = when (function) {
        "NVL" -> arguments.takeIf { it.size == 2 }
        "COALESCE" -> arguments.takeIf { it.size >= 2 }
        "NVL2" -> arguments.takeIf { it.size == 3 }?.drop(1)
        "MAX", "MIN", "UPPER", "LOWER", "TRIM" -> arguments.takeIf { it.size == 1 }
        "LTRIM", "RTRIM" -> arguments.takeIf { it.size in 1..2 }?.take(1)
        else -> null
    }

    /**
     * Where following one value down from [expression] stops, the value being the node [isValue] accepts: through the
     * value functions [valueChain] follows, the first function or operator that is no such function, by its shape
     * ([stopShape]: `TO_CHAR`, `OTHER_DECODE`, `OTHER_CASE`, `OTHER_LPAD`); `CONDITION` when the value is only in
     * the first argument of `NVL2`, `OTHER_ARGUMENT` when it is only in another argument a value function does not give
     * back, `SEVERAL_ARGUMENTS` when it is in more than one argument it may give back. [reached] names the stop when the
     * value itself is reached.
     */
    private fun stopOf(expression: AstNode, isValue: (AstNode) -> Boolean, reached: () -> String): String {
        var node = expression
        while (true) {
            node = unbracketed(node)
            if (isValue(node)) return reached()
            val (function, candidates) = valueArguments(node) ?: return stopShape(node)
            val leading = candidates.filter { candidate -> subtree(candidate).any(isValue) }
            node = when (leading.size) {
                1 -> leading.single()
                0 -> return if (function == "NVL2") "CONDITION" else "OTHER_ARGUMENT"
                else -> return "SEVERAL_ARGUMENTS"
            }
        }
    }

    /**
     * The shape of [node], where following a value stops: an operator by what it is, not by the first word written in
     * it (`nvl(max(n), 0) + 1` starts with `NVL`, `max(n) + 1` with `MAX`): `OTHER_ARITHMETIC`, `OTHER_COMPARISON` for
     * a condition (`nvl(x, 0) = 0`, `IN`, `IS NULL`, `LIKE`, `AND`); a member of a call or element (`t(i).code`,
     * `f(x).m(y)`) by that call or element ([otherShape]: `OTHER_ELEMENT`, `OTHER_ROUTINE`); else as [expressionShape] says it.
     */
    private fun stopShape(node: AstNode): String {
        if (node.type in ARITHMETIC) return "OTHER_ARITHMETIC"
        if (node.type is ConditionsGrammar || node.type in CONDITIONS) return "OTHER_COMPARISON"
        if (node.type in MEMBER_TYPES || node.type == PlSqlGrammar.METHOD_CALL && plsql.nameSegments(node.firstChild) == null) {
            generateSequence(node.firstChild) { it.children.firstOrNull() }
                .firstOrNull { it.type == PlSqlGrammar.METHOD_CALL && plsql.nameSegments(it.firstChild) != null }
                ?.let { return "OTHER_" + otherShape(it) }
        }
        return expressionShape(node).let { if (it in PlaceShapes.SPLIT_SHAPES) "OTHER_" + otherShape(node) else it }
    }

    /** The value function [node] is, by name, with the arguments it may give back ([valueChain]); null for anything else. */
    private fun valueArguments(node: AstNode): Pair<String, List<AstNode>>? = when (node.type) {
        PlSqlGrammar.POSTFIX_EXPRESSION -> aggregateCall(node)?.let { "MAX" to listOf(it) }
        SingleRowSqlFunctionsGrammar.SINGLE_ROW_SQL_FUNCTION ->
            node.firstChild.takeIf { it.type == SingleRowSqlFunctionsGrammar.TRIM_EXPRESSION }?.let(::trimmed)
                ?.let { "TRIM" to listOf(it) }

        PlSqlGrammar.CONCATENATION_EXPRESSION -> "CONCATENATION" to node.children.filter { it.type != PlSqlGrammar.CONCATENATION_OPERATOR }
        PlSqlGrammar.METHOD_CALL -> valueCallArguments(node)
        else -> null
    }

    /** [valueArguments] of the call [call]. */
    private fun valueCallArguments(call: AstNode): Pair<String, List<AstNode>>? {
        val function = calledName(call) ?: return null
        val arguments = argumentValues(call) ?: return null
        val candidates = when {
            function == "CONCAT" -> arguments.takeIf { it.size == 2 }
            function in REPLACES -> arguments.takeIf { it.size in REPLACES.getValue(function) }
                ?.let { listOfNotNull(it[0], it.getOrNull(2)) }

            function in Substrings.NAMES -> arguments.takeIf { it.size in 2..3 }?.take(1)
            else -> givenBack(function, arguments)
        } ?: return null
        return function to candidates
    }

    /**
     * The shape of [expression], an expression of one value [isValue] accepts, as [expressionShape] says it; for `NVL`,
     * `NVL2` and `COALESCE`, which the index follows, followed by where following the value stops ([stopOf]):
     * `NVL_TO_CHAR`, `OTHER_COALESCE_OTHER_DECODE`. [reached] names the stop when the value itself is reached.
     */
    fun valueShape(expression: AstNode, isValue: (AstNode) -> Boolean, reached: () -> String = { "OTHER" }): String {
        val shape = expressionShape(expression)
        return if (shape in PlaceShapes.SPLIT_SHAPES) shape + "_" + stopOf(expression, isValue, reached) else shape
    }

    /**
     * [valueChain] of `REPLACE` or `REGEXP_REPLACE` ([function]) of [arguments]: through its first argument, or through
     * the replacement text it puts into other text; null for another number of arguments, or when both lead to the value.
     */
    private fun replaceChain(function: String, arguments: List<AstNode>, isValue: (AstNode) -> Boolean): List<String>? {
        if (arguments.size !in REPLACES.getValue(function)) return null
        val value = valueChain(arguments[0], isValue)?.let { listOf(function) + it }
        val put = arguments.getOrNull(2)?.let { valueChain(it, isValue) }?.let { listOf(function, CONCATENATED) + it }
        return listOfNotNull(value, put).singleOrNull()
    }

    /** [function], then the chain of the one of [candidates] that leads to the value; null unless exactly one does. */
    private fun chainThrough(
        function: String,
        candidates: List<AstNode>,
        isValue: (AstNode) -> Boolean
    ): List<String>? {
        val chains = candidates.mapNotNull { valueChain(it, isValue) }
        return chains.singleOrNull()?.let { listOf(function) + it }
    }

    /** The value of [expression] when it is a whole-number literal (`5`, `-3`); null for anything else (`p_len`, `2.5`, a constant). */
    private fun wholeNumber(expression: AstNode): Int? {
        val value = ExpressionValues.of(expression)
        return if (value.kind == ValueKind.NUMBER) value.value?.toIntOrNull() else null
    }

    /**
     * Whether [node], inside [expression], is in an argument whose value never lands in the result: the search text or
     * pattern of `REPLACE` or `REGEXP_REPLACE`, the position, occurrence and match arguments of `REGEXP_REPLACE`, or the
     * start and length of `SUBSTR` and `SUBSTRB`.
     */
    fun inDroppedArgument(expression: AstNode, node: AstNode): Boolean {
        for (argument in generateSequence(node) { it.parentOrNull }.takeWhile { it !== expression }) {
            if (argument.type != PlSqlGrammar.ARGUMENT) continue
            val call = argument.parentOrNull?.parentOrNull?.takeIf { it.type == PlSqlGrammar.METHOD_CALL } ?: continue
            val function = calledName(call)
            if (function !in REPLACES && function !in Substrings.NAMES) continue
            val index = argument.parent.getChildren(PlSqlGrammar.ARGUMENT).indexOfFirst { it === argument }
            if ((function in Substrings.NAMES && index >= 1) || (function in REPLACES && (index == 1 || index > 2))) return true
        }
        return false
    }

    /** [node] and every node under it. */
    fun subtree(node: AstNode): Sequence<AstNode> = sequenceOf(node) + node.children.asSequence().flatMap(::subtree)

    /**
     * The shape of an expression a parameter or a function's result ([value]) is written within: as [valueShape] says it, apart from a `CASE`
     * (which may give the value back as is) and `LENGTH` and its kin (which give a number, not the value).
     */
    fun argumentShape(expression: AstNode, value: AstNode): String {
        val node = unbracketed(expression)
        if (node.type == PlSqlGrammar.CASE_EXPRESSION) return "CASE"
        if (node.type == PlSqlGrammar.METHOD_CALL && calledName(node) in LENGTH_FUNCTIONS) return "LENGTH"
        return valueShape(expression, { it === value })
    }

    /**
     * The outer function or operator of an expression item: `NVL`, `NVL2`, `UPPER`, `TRIM`, `SUBSTR`, `MAX_MIN`,
     * `TO_CHAR`, `CONCATENATION`, `OTHER`.
     */
    fun expressionShape(expression: AstNode): String {
        val node = unbracketed(expression)
        if (node.type == PlSqlGrammar.CONCATENATION_EXPRESSION) return "CONCATENATION"
        val function = if (node.type == PlSqlGrammar.METHOD_CALL) plsql.nameSegments(node.firstChild)?.last()
            ?.uppercase() else node.tokenValue.uppercase()
        return when (function) {
            "NVL" -> "NVL"
            "NVL2" -> "NVL2"
            "UPPER" -> "UPPER"
            "TRIM", "LTRIM", "RTRIM" -> "TRIM"
            "SUBSTR", "SUBSTRB" -> "SUBSTR"
            "MAX", "MIN" -> "MAX_MIN"
            "TO_CHAR" -> "TO_CHAR"
            else -> "OTHER_" + otherShape(node)
        }
    }

    /**
     * What an expression of no family of its own is, by its outer function or operator: `CASE`, `ARITHMETIC` (`+`, `-`,
     * `*`, `/`, `**`), the name of an Oracle built-in ([OracleBuiltins]: `DECODE`, `TO_NUMBER`, `LOWER`, `CAST`), `ELEMENT`
     * (an element of a collection variable or parameter, `v(i)`), `ROUTINE` (any other call: a function of the code or of
     * another schema, resolved or not), or `OTHER`.
     */
    private fun otherShape(expression: AstNode): String {
        var node = expression
        while (node.type == PlSqlGrammar.POSTFIX_EXPRESSION || node.numberOfChildren == 1) node = node.firstChild
        return when {
            node.type == PlSqlGrammar.CASE_EXPRESSION -> "CASE"
            node.type in ARITHMETIC -> "ARITHMETIC"
            node.type == PlSqlGrammar.METHOD_CALL -> {
                val segments = plsql.nameSegments(node.firstChild) ?: return "OTHER"
                val called = segments.singleOrNull()?.let { normalizeIdentifier(it) }
                when {
                    called == null || isRoutineCall(node.firstChild) -> "ROUTINE"
                    // A collection's element (`v(i)`), a variable or parameter zpa binds the name to.
                    (nameBinding(node.firstChild) as? NameBinding.Local)?.declaration?.kind.let {
                        it == NameDeclarationKind.VARIABLE || it == NameDeclarationKind.PARAMETER
                    } -> "ELEMENT"

                    OracleBuiltins.isBuiltin(called) && WORD.matches(called) -> called
                    else -> "ROUTINE"
                }
            }
            // A built-in written with its own syntax: `CAST(… AS …)`, `EXTRACT(… FROM …)`.
            node.numberOfChildren > 1 && node.children[1].tokenValue == "(" -> node.firstChild.tokenValue.uppercase()
                .takeIf { WORD.matches(it) } ?: "OTHER"

            else -> "OTHER"
        }
    }

    /** Whether the called name [name] is a routine the index resolves (a built-in is not one). */
    private fun isRoutineCall(name: AstNode): Boolean {
        return state.occurrencesAt(spanOf(name))
            .any { Role.CALL in it.roles && it.entityKey != null && it.status != Status.UNRESOLVED }
    }

    companion object {
        private val LENGTH_FUNCTIONS = setOf("LENGTH", "LENGTHB", "LENGTHC", "LENGTH2", "LENGTH4")

        /** The aggregates a value function may be: `MAX` and `MIN` give back one of the values they are given. */
        private val MAX_MIN = setOf("MAX", "MIN")

        /** The functions that give back their first argument with text taken out or put in, by the number of arguments they take. */
        private val REPLACES = mapOf("REPLACE" to 2..3, "REGEXP_REPLACE" to 2..6)

        /** The grammar's conditions that are not of [ConditionsGrammar]: comparisons, `IN`, `NOT`, `AND`, `OR`. */
        private val CONDITIONS = setOf(
            PlSqlGrammar.COMPARISON_EXPRESSION,
            PlSqlGrammar.IN_EXPRESSION,
            PlSqlGrammar.NOT_EXPRESSION,
            PlSqlGrammar.AND_EXPRESSION,
            PlSqlGrammar.OR_EXPRESSION
        )

        /** A member of what comes before it: `r.f`, `t(i).f`, `f(x).m`. */
        private val MEMBER_TYPES = setOf(PlSqlGrammar.MEMBER_EXPRESSION, PlSqlGrammar.OBJECT_REFERENCE)
        private val ARITHMETIC = setOf(
            PlSqlGrammar.ADDITIVE_EXPRESSION,
            PlSqlGrammar.MULTIPLICATIVE_EXPRESSION,
            PlSqlGrammar.UNARY_EXPRESSION,
            PlSqlGrammar.EXPONENTIATION_EXPRESSION
        )
        private val WORD = Regex("[A-Z][A-Z0-9_]*")
    }
}
