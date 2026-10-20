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
import com.felipebz.zpa.atlas.model.*

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
     * formal of a resolved call or a modifying collection method; `READ` and `WRITE` for an `IN OUT` formal; else `READ`.
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
        if (segments.size >= 2 && normalizeIdentifier(segments.last()) in MODIFYING_METHODS) return setOf(Role.WRITE)
        return setOf(Role.READ)
    }
}

/** Collection methods that change the collection. */
private val MODIFYING_METHODS = setOf("DELETE", "EXTEND", "TRIM")
