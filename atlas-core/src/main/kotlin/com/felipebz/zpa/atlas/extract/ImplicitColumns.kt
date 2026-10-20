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
import com.felipebz.zpa.api.squid.SemanticAstNode
import com.felipebz.zpa.atlas.model.*

/** The columns read or written without being named, and the `REF`/`VALUE` correlation calls. */
internal class ImplicitColumns(
    private val sources: ColumnSources,
    private val scopes: SqlScopes,
    private val plsql: PlSqlNames
) {
    /**
     * `REF(x)` or `VALUE(x)` whose single argument is the alias of a source of an enclosing SQL scope (innermost first):
     * the function name and that source, or null. Oracle takes only an alias here, not a relation name.
     */
    fun correlationCall(statement: Statement, call: AstNode): Pair<String, ColumnSource>? {
        if (call.type != PlSqlGrammar.METHOD_CALL) return null
        val callee = call.firstChild
        if (callee.type != PlSqlGrammar.VARIABLE_NAME) return null
        val function = normalizeIdentifier(callee.tokenOriginalValue).takeIf { it in CORRELATION_FUNCTIONS } ?: return null
        val argument = singleArgument(call) ?: return null
        if (argument.type != PlSqlGrammar.VARIABLE_NAME || plsql.nameSegments(argument)?.size != 1) return null
        val alias = normalizeIdentifier(argument.tokenOriginalValue)
        var scope = scopes.sqlScope(call)
        while (scope != null) {
            sources.scopeSources(statement, scope, argument, qualified = true).firstOrNull { it.alias == alias }?.let { return function to it }
            scope = scopes.sqlScope(scope)
        }
        return null
    }

    /** The argument of a [correlationCall]: an alias, never a column or a PL/SQL name. */
    fun isCorrelationArgument(statement: Statement, name: AstNode): Boolean {
        val call = name.parentOrNull?.parentOrNull?.parentOrNull ?: return false
        return name.parentOrNull?.type == PlSqlGrammar.ARGUMENT && correlationCall(statement, call) != null
    }

    /** `VALUE(x)` outside the left of `SET`: every column of the relation [x] names, at the call. */
    fun correlationValue(statement: Statement, call: AstNode) {
        val (function, source) = correlationCall(statement, call) ?: return
        if (function != "VALUE") return
        if (call.parentOrNull?.type == DmlGrammar.UPDATE_COLUMN && call.parent.firstChild === call) return
        source.target?.let { implicitColumns(statement, it, COLUMN_IMPLICIT_READ, call) }
    }

    /**
     * Every column written or read without being named (`INSERT` without a column list, `SET ROW`, `*`): by dictionary
     * position, at [at] (the target's range for writes).
     */
    fun implicitColumns(statement: Statement, target: Target, roles: Set<Role>, at: AstNode = target.reference, written: WriteSource? = null) {
        val relation = target.resolved.relation
        if (relation != null && relation.isColumnsComplete) {
            // A write pairs its values with the columns in the relation's order.
            val values = written?.forColumns(relation.rowFields.size)
            relation.rowFields.forEachIndexed { position, field ->
                statement.occurrence(at, target.resolved.entityKey + "." + field.name.exact(), roles, Status.RESOLVED, null,
                    values = values?.let { statement.writtenValues(it, position) }.orEmpty())
            }
        } else {
            // No value without the relation's columns, unless the statement has none to give anyway (`SET ROW`, a record).
            if (written != null) statement.unvalued((written as? WriteSource.None)?.reason ?: WrittenValues.COLUMNS_INCOMPLETE)
            val status = when (target.resolved.status) {
                Status.EXTERNAL, Status.UNRESOLVED -> target.resolved.status
                else -> Status.UNVERIFIED
            }
            statement.occurrence(at, target.resolved.entityKey, roles, status, "COLUMNS_UNKNOWN")
        }
    }

    /**
     * `*` and `t.*` in a select list: every column of the sources they cover, implicit reads at the `*` for a complete
     * dictionary relation, one `COLUMNS_UNKNOWN` read of a named relation whose columns are unknown.
     */
    fun selectStar(statement: Statement, selectColumn: AstNode) {
        val covered = sources.starSources(statement, selectColumn) ?: return
        for (source in covered) source.target?.let { implicitColumns(statement, it, COLUMN_IMPLICIT_READ, selectColumn.firstChild) }
    }
}

private val COLUMN_IMPLICIT_READ = setOf(Role.READ, Role.IMPLICIT)

private val CORRELATION_FUNCTIONS = setOf("REF", "VALUE")

/** The single positional argument of a method call, or null. */
internal fun singleArgument(call: AstNode): AstNode? {
    val arguments = call.getChildren(PlSqlGrammar.ARGUMENTS).singleOrNull() ?: return null
    val argument = arguments.getChildren(PlSqlGrammar.ARGUMENT).singleOrNull() ?: return null
    return argument.children.singleOrNull()
}

/** The relation of `MAKE_REF(t, key…)`, a plain name of one or two segments, or null. */
internal fun makeRefRelation(call: AstNode): AstNode? {
    if (call.type != PlSqlGrammar.METHOD_CALL) return null
    val callee = call.firstChild
    if (callee.type != PlSqlGrammar.VARIABLE_NAME || normalizeIdentifier(callee.tokenOriginalValue) != "MAKE_REF") return null
    val first = call.getChildren(PlSqlGrammar.ARGUMENTS).singleOrNull()?.getFirstChildOrNull(PlSqlGrammar.ARGUMENT) ?: return null
    val name = first.children.singleOrNull() ?: return null
    if (name.type != PlSqlGrammar.VARIABLE_NAME && name.type != PlSqlGrammar.MEMBER_EXPRESSION) return null
    val parts = if (name.type == PlSqlGrammar.VARIABLE_NAME) listOf(name) else name.children.filter { it.type != PlSqlPunctuator.DOT }
    if (parts.size !in 1..2 || parts.any { it.type is PlSqlPunctuator || tokensOf(it).size != 1 }) return null
    return name
}