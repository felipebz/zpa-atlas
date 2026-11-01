//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.DmlGrammar
import com.felipebz.zpa.api.PlSqlGrammar
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
     * formal of a resolved call or a modifying collection method ([endsInModifyingMethod]); `READ` and `WRITE` for an
     * `IN OUT` formal; else `READ`.
     */
    fun valueRoles(name: AstNode, segments: List<String>): Set<Role> {
        var expression = name
        name.parentOrNull?.takeIf { it.type == PlSqlGrammar.METHOD_CALL && it.firstChild === name }?.let { expression = it }
        while (true) {
            val parent = expression.parentOrNull ?: break
            if (parent.type != PlSqlGrammar.OBJECT_REFERENCE || parent.firstChild !== expression) break
            expression = parent
        }
        val parent = expression.parentOrNull
        if (parent != null) {
            if (parent.type == PlSqlGrammar.ASSIGNMENT_STATEMENT) {
                val assignment = parent.children.indexOfFirst { it.type == PlSqlPunctuator.ASSIGNMENT }
                if (parent.children.indexOf(expression) < assignment) return setOf(Role.WRITE)
            }
            if (parent.type == DmlGrammar.INTO_CLAUSE) return setOf(Role.WRITE)
            val mode = state.argumentModes[expression] ?: if (parent.type == PlSqlGrammar.ARGUMENT) state.argumentModes[parent] else null
            when (mode) {
                ProjectParameterMode.OUT -> return setOf(Role.WRITE)
                ProjectParameterMode.IN_OUT -> return setOf(Role.READ, Role.WRITE)
                else -> Unit
            }
        }
        if (endsInModifyingMethod(expression, segments)) return setOf(Role.WRITE)
        return setOf(Role.READ)
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
