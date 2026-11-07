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
import com.felipebz.zpa.api.project.ProjectParameterMode
import com.felipebz.zpa.atlas.model.Role

/** The roles of a name from where it is written, and from the modes of the resolved calls it is an actual of. */
internal class NameRoles(private val state: FileState) {
    /**
     * The roles of a package member: for an exception `RAISE` as the exception of a `RAISE` statement and `HANDLE`
     * as a name of an exception handler (the direct parent only), else `READ`; for a cursor `READ`; for a value
     * [valueRoles].
     */
    fun memberRoles(name: AstNode, segments: List<String>, kind: MemberKind): Set<Role> = when (kind) {
        MemberKind.EXCEPTION -> when (name.parentOrNull?.type) {
            PlSqlGrammar.RAISE_STATEMENT -> setOf(Role.RAISE)
            PlSqlGrammar.EXCEPTION_HANDLER -> setOf(Role.HANDLE)
            else -> setOf(Role.READ)
        }
        MemberKind.CURSOR -> setOf(Role.READ)
        MemberKind.VALUE -> valueRoles(name, segments)
    }

    /**
     * The roles of a value: `WRITE` for an assignment target, an `INTO` target, an actual bound to an `OUT`
     * formal of a resolved call, an `OUT` bind of dynamic SQL or a modifying collection method ([endsInModifyingMethod]);
     * `READ` and `WRITE` for an `IN OUT` formal or bind; else `READ`.
     */
    fun valueRoles(name: AstNode, segments: List<String>): Set<Role> {
        val expression = writtenChain(name)
        expression.parentOrNull?.let { parent -> parentRoles(expression, parent)?.let { return it } }
        if (endsInModifyingMethod(expression, segments)) return setOf(Role.WRITE)
        return setOf(Role.READ)
    }

    /** The whole written chain [name] starts: its method call, then every object reference it is the first part of. */
    private fun writtenChain(name: AstNode): AstNode {
        var expression = name
        name.parentOrNull?.takeIf { it.type == PlSqlGrammar.METHOD_CALL && it.firstChild === name }?.let { expression = it }
        while (true) {
            val parent = expression.parentOrNull ?: break
            if (parent.type != PlSqlGrammar.OBJECT_REFERENCE || parent.firstChild !== expression) break
            expression = parent
        }
        return expression
    }

    /** The roles the [parent] of the chain [expression] gives it; null when it gives none of its own. */
    private fun parentRoles(expression: AstNode, parent: AstNode): Set<Role>? {
        if (parent.type == PlSqlGrammar.ASSIGNMENT_STATEMENT) {
            val assignment = parent.children.indexOfFirst { it.type == PlSqlPunctuator.ASSIGNMENT }
            if (parent.children.indexOf(expression) < assignment) return setOf(Role.WRITE)
        }
        if (parent.type == DmlGrammar.INTO_CLAUSE) return setOf(Role.WRITE)
        // A bind of `EXECUTE IMMEDIATE … USING` (or `OPEN … FOR … USING`), with its written mode, as an actual of a call.
        if (parent.type == PlSqlGrammar.UNNAMED_ACTUAL_PAMETER && parent.hasDirectChildren(PlSqlKeyword.OUT)) {
            return if (parent.hasDirectChildren(PlSqlKeyword.IN)) setOf(Role.READ, Role.WRITE) else setOf(Role.WRITE)
        }
        val mode = state.argumentModes[expression] ?: if (parent.type == PlSqlGrammar.ARGUMENT) state.argumentModes[parent] else null
        return when (mode) {
            ProjectParameterMode.OUT -> setOf(Role.WRITE)
            ProjectParameterMode.IN_OUT -> setOf(Role.READ, Role.WRITE)
            else -> null
        }
    }

    /**
     * Whether the chain [expression] (the value's whole written chain) ends in a method that changes a collection:
     * `v.delete`, `pkg.v.extend(2)`, and through elements and fields, `v(1).items.delete`, `v(i).items.trim`. Whatever
     * the chain passes through, the collection changed is part of the value it starts from. [segments] are the
     * value's own dotted segments, the whole chain when it is a plain dotted name.
     */
    private fun endsInModifyingMethod(expression: AstNode, segments: List<String>): Boolean {
        val method = if (expression.type == PlSqlGrammar.OBJECT_REFERENCE) {
            // The member after the last dot: `items.delete` or `items.delete(1)` (a member chain, maybe called).
            val last = expression.lastChild.let { if (it.type == PlSqlGrammar.METHOD_CALL) it.firstChild else it }
            (if (last.type == PlSqlGrammar.MEMBER_EXPRESSION) last.lastChild else last).tokenOriginalValue
        } else {
            segments.takeIf { it.size >= 2 }?.last() ?: return false
        }
        return normalizeIdentifier(method) in MODIFYING_METHODS
    }
}

/** Collection methods that change the collection. */
private val MODIFYING_METHODS = setOf("DELETE", "EXTEND", "TRIM")
