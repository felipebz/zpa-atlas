//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.DmlGrammar
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.api.PlSqlVisitorContext
import com.felipebz.zpa.api.dynamicsql.DynamicSqlText
import com.felipebz.zpa.api.project.ProjectParameterMode
import com.felipebz.zpa.api.semantics.NameBinding
import com.felipebz.zpa.api.semantics.NameDeclarationKind
import com.felipebz.zpa.api.semantics.ScalarFamily
import com.felipebz.zpa.api.semantics.ValueCategory

/**
 * The SQL text of a dynamic statement written as a plain variable (`EXECUTE IMMEDIATE v_sql`), read only when it can
 * be only one text: the variable is a local character variable of the file, and exactly one value is ever stored in it
 * (its default value, a constant, or one `:=` assignment). Anything else gives no text and a reason that says why.
 *
 * Writes are found by name, not by binding: every occurrence of the variable's name in the region that declares it
 * (the block or subprogram, nested blocks and subprograms included) is taken as the variable, even where an inner
 * declaration hides it. That counts too many writes, never too few: a missed text is better than a wrong one.
 */
internal class VariableSqlTexts(
    private val context: PlSqlVisitorContext,
    private val state: FileState,
    /** The text nodes of the file's dynamic statements: reads, even where they are a call's actual (`DBMS_SQL.PARSE`). */
    private val statementTexts: Set<AstNode>
) {

    /**
     * What became of a variable's text: the [reason] of the `DYNAMIC` occurrence, and, when it was parsed, the [text]
     * and the [value] it was read from (the same node for every statement that runs the variable).
     */
    class Outcome(val reason: String, val text: DynamicSqlText? = null, val value: AstNode? = null)

    /** The variable declarations of the file, by the position of their name. */
    private val declarations: Map<Pair<Int, Int>, AstNode> by lazy {
        context.rootTree()?.getDescendants(PlSqlGrammar.VARIABLE_DECLARATION).orEmpty()
            .associateBy { it.firstChild.token.line to it.firstChild.token.column }
    }

    /** The outcome for [name], the variable a dynamic statement runs. */
    fun outcome(name: AstNode): Outcome {
        val declaration = when (val binding = context.nameBinding(name)) {
            is NameBinding.Local -> binding.declaration
            is NameBinding.NothingLocal -> return Outcome(NOT_LOCAL)
            else -> return Outcome(UNRESOLVED)
        }
        if (declaration.kind != NameDeclarationKind.VARIABLE || declaration.fileId != null) return Outcome(NOT_LOCAL)
        val node = declarations[declaration.line to declaration.column] ?: return Outcome(UNRESOLVED)
        val region = node.parentOrNull?.takeIf { it.type == PlSqlGrammar.DECLARE_SECTION }?.parentOrNull
        if (region == null || region.type !in LOCAL_REGIONS) return Outcome(NOT_LOCAL)
        val category = context.valueCategory(name)
        if (category !is ValueCategory.Scalar || category.family != ScalarFamily.CHARACTER && category.family != ScalarFamily.CLOB) {
            return Outcome(NOT_CHARACTER)
        }
        val uncertainty = context.regionUncertainty(region)
        if (uncertainty == null || uncertainty.isNotEmpty()) return Outcome(UNSEEN_WRITE)

        val variable = normalizeIdentifier(node.firstChild.tokenOriginalValue)
        val writes = ArrayList<Write>()
        node.getFirstChildOrNull(PlSqlGrammar.DEFAULT_VALUE_ASSIGNMENT)?.let { writes += Write.Value(it.lastChild) }
        for (written in region.getDescendants(PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.IDENTIFIER_NAME)) {
            if (written === node.firstChild || normalizeIdentifier(written.tokenOriginalValue) != variable) continue
            write(written)?.let { writes += it }
        }
        return when {
            writes.any { it is Write.Unseen } -> Outcome(UNSEEN_WRITE)
            writes.size > 1 -> Outcome(SEVERAL_WRITES)
            writes.isEmpty() -> Outcome(NEVER_WRITTEN)
            writes.single() is Write.NotInCode -> Outcome(VALUE_NOT_IN_CODE)
            else -> parse((writes.single() as Write.Value).value, variable)
        }
    }

    private fun parse(value: AstNode, variable: String): Outcome {
        // `v := v || ' and x = 1'` builds on an earlier value: not one text.
        val reads = listOf(value) + value.getDescendants(PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.IDENTIFIER_NAME)
        if (reads.any { (it.type == PlSqlGrammar.VARIABLE_NAME || it.type == PlSqlGrammar.IDENTIFIER_NAME) && normalizeIdentifier(it.tokenOriginalValue) == variable }) {
            return Outcome(SEVERAL_WRITES)
        }
        val text = context.dynamicSql(parts(value))
        return when {
            text == null -> Outcome(if (hasStringLiteral(value)) NOT_PARSED else NO_LITERAL)
            text.holesDropped -> Outcome(PARSED_HOLES_DROPPED, text, value)
            text.holes.isNotEmpty() -> Outcome(PARSED_WITH_HOLES, text, value)
            else -> Outcome(PARSED, text, value)
        }
    }

    /** The operands of [value] as zpa takes those of a dynamic statement's text: a concatenation flattened, parentheses unwrapped. */
    private fun parts(value: AstNode): List<AstNode> = when {
        value.type == PlSqlGrammar.CONCATENATION_EXPRESSION ->
            value.children.filter { it.type != PlSqlGrammar.CONCATENATION_OPERATOR }.flatMap { parts(it) }
        value.type == PlSqlGrammar.BRACKED_EXPRESSION && value.numberOfChildren == 3 -> parts(value.children[1])
        else -> listOf(value)
    }

    private sealed interface Write {
        /** A value written in the code: the default value, or the right side of `:=`. */
        class Value(val value: AstNode) : Write

        /** A value that comes from a query or a call: an `INTO` target, an `OUT` or `IN OUT` actual. */
        object NotInCode : Write

        /** Maybe a write: an actual of a call whose formal's mode is unknown, or a part of the variable written. */
        object Unseen : Write
    }

    /** What the occurrence [name] of the variable's name does to it: null for a read. */
    private fun write(name: AstNode): Write? {
        var expression = name
        while (true) {
            val parent = expression.parentOrNull ?: return null
            val climbs = parent.firstChild === expression && parent.type in COMPOSITES ||
                parent.type == PlSqlGrammar.MEMBER_EXPRESSION && parent.lastChild === expression
            if (!climbs) break
            expression = parent
        }
        val parent = expression.parentOrNull ?: return null
        val plain = expression === name || expression.type == PlSqlGrammar.MEMBER_EXPRESSION && expression.lastChild === name ||
            expression.type == PlSqlGrammar.OBJECT_REFERENCE && expression.numberOfChildren == 1
        return when (parent.type) {
            PlSqlGrammar.ASSIGNMENT_STATEMENT -> {
                val assignment = parent.children.indexOfFirst { it.type == PlSqlPunctuator.ASSIGNMENT }
                when {
                    parent.children.indexOf(expression) > assignment -> null
                    plain -> Write.Value(parent.children[assignment + 1])
                    else -> Write.Unseen
                }
            }
            DmlGrammar.INTO_CLAUSE -> if (plain) Write.NotInCode else Write.Unseen
            PlSqlGrammar.UNNAMED_ACTUAL_PAMETER -> when {
                parent.lastChild !== expression -> null
                parent.hasDirectChildren(PlSqlKeyword.OUT) -> Write.NotInCode
                else -> null
            }
            PlSqlGrammar.ARGUMENT -> when {
                parent.lastChild !== expression || expression in statementTexts -> null
                else -> when (state.argumentModes[expression] ?: state.argumentModes[parent]) {
                    ProjectParameterMode.IN -> null
                    ProjectParameterMode.OUT, ProjectParameterMode.IN_OUT -> Write.NotInCode
                    null -> Write.Unseen
                }
            }
            else -> null
        }
    }

    companion object {
        /** The plain variable [sql] is, or null when it is another expression. */
        fun variable(sql: AstNode): AstNode? {
            var node = sql
            while (node.numberOfChildren == 1 && node.type != PlSqlGrammar.VARIABLE_NAME) node = node.firstChild
            return node.takeIf { it.type == PlSqlGrammar.VARIABLE_NAME }
        }

        /** The regions whose declarations are local: a block or a subprogram, standalone, nested or a method. */
        private val LOCAL_REGIONS = setOf(
            PlSqlGrammar.BLOCK_STATEMENT, PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION,
            PlSqlGrammar.CREATE_PROCEDURE, PlSqlGrammar.CREATE_FUNCTION, PlSqlGrammar.TIMING_POINT_SECTION
        )

        /** The nodes a name is the first part of, as the target of a write: `v(i)`, `v.f`, an `INTO` item. */
        private val COMPOSITES = setOf(PlSqlGrammar.MEMBER_EXPRESSION, PlSqlGrammar.METHOD_CALL, PlSqlGrammar.OBJECT_REFERENCE)

        const val PARSED = "VARIABLE_PARSED"
        const val PARSED_WITH_HOLES = "VARIABLE_PARSED_WITH_HOLES"
        const val PARSED_HOLES_DROPPED = "VARIABLE_PARSED_HOLES_DROPPED"
        const val NOT_PARSED = "VARIABLE_NOT_PARSED"
        const val NO_LITERAL = "VARIABLE_NO_LITERAL"
        const val SEVERAL_WRITES = "VARIABLE_SEVERAL_WRITES"
        const val VALUE_NOT_IN_CODE = "VARIABLE_VALUE_NOT_IN_CODE"
        const val NEVER_WRITTEN = "VARIABLE_NEVER_WRITTEN"
        const val UNSEEN_WRITE = "VARIABLE_UNSEEN_WRITE"
        const val NOT_CHARACTER = "VARIABLE_NOT_CHARACTER"
        const val NOT_LOCAL = "VARIABLE_NOT_LOCAL"
        const val UNRESOLVED = "VARIABLE_UNRESOLVED"
    }
}

/**
 * Whether a part of [sql] is a string literal, its operands taken as zpa takes them (a concatenation flattened,
 * parentheses unwrapped): a text zpa could not parse is then `NOT_PARSED`, not an `EXPRESSION`.
 */
internal fun hasStringLiteral(sql: AstNode): Boolean = when {
    sql.type == PlSqlGrammar.CONCATENATION_EXPRESSION ->
        sql.children.any { it.type != PlSqlGrammar.CONCATENATION_OPERATOR && hasStringLiteral(it) }
    sql.type == PlSqlGrammar.BRACKED_EXPRESSION && sql.numberOfChildren == 3 -> hasStringLiteral(sql.children[1])
    sql.type == PlSqlGrammar.CHARACTER_LITERAL -> true
    else -> sql.type == PlSqlGrammar.LITERAL && sql.numberOfChildren == 1 && sql.firstChild.type == PlSqlGrammar.CHARACTER_LITERAL
}
