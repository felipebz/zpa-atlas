//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.api.semantics.FieldBinding
import com.felipebz.zpa.api.semantics.NameBinding
import com.felipebz.zpa.atlas.model.Role
import com.felipebz.zpa.atlas.model.Status
import java.util.*

/**
 * The uses of record fields and object attributes: each segment of a value's chain that zpa's [FieldBinding] names as
 * a field with an entity (a package spec's type, a standalone object type, a type of the file's package body) is a use
 * of that field, at the segment's own span, with the roles of the value the chain starts from ([NameRoles.valueRoles]):
 * a field of a field written by an assignment is written too. In a type body the first name, or a name written alone,
 * may itself be an attribute read through the implicit `SELF` (`items.extend`, `tag := 'x'`): zpa answers it as it
 * answers `self.items`.
 *
 * zpa resolves the members after an element, a call result or a package variable of another file when the file is
 * left: the chains are gathered during the walk ([chain]) and asked in [record], from `leaveFile`. A segment whose value
 * zpa could not decide records nothing, and so does a field of a type without an entity (declared in a subprogram).
 */
internal class FieldUses(
    private val state: FileState,
    private val catalog: DeclarationCatalog,
    private val path: String,
    private val roles: NameRoles,
    private val fieldBinding: (AstNode) -> FieldBinding,
    private val nameBinding: (AstNode) -> NameBinding,
    /** The field entity of a type of the file's package body declared at a line and column of this file ([BodyGlobals.fieldAt]). */
    private val bodyFieldAt: (Int, Int) -> String?
) {
    private class Chain(val root: AstNode, val start: AstNode, val segments: List<String>, val context: Context, val statementId: Int?)

    private val chains = IdentityHashMap<AstNode, Chain>()

    /**
     * Gathers the chain around [start], the value name or callee the walk reads ([PlSqlNames]), with its [segments] as
     * written: the outermost expression it starts (`g_rows(1).items.delete` from `g_rows`), once.
     */
    fun chain(start: AstNode, segments: List<String>, context: Context, statementId: Int?) {
        if (context.dynamic != null) return
        var root = start
        root.parentOrNull?.takeIf { it.type == PlSqlGrammar.METHOD_CALL && it.firstChild === root }?.let { root = it }
        while (true) {
            val parent = root.parentOrNull ?: break
            if (parent.type != PlSqlGrammar.OBJECT_REFERENCE || parent.firstChild !== root) break
            root = parent
        }
        // A name alone is a chain of its own only when it starts an expression (a member after an element or a call
        // result is a segment of the chain around it); any other lone node waits for the name the walk reads in it.
        if (root === start && start.type != PlSqlGrammar.MEMBER_EXPRESSION && (start.type != PlSqlGrammar.VARIABLE_NAME ||
                start.parentOrNull?.type == PlSqlGrammar.OBJECT_REFERENCE || start.previousSiblingOrNull?.type == PlSqlPunctuator.DOT)) return
        chains.putIfAbsent(root, Chain(root, start, segments, context, statementId))
    }

    /** Records the field uses of every chain gathered: call once the file is left. */
    fun record() {
        // A chain that starts with a call works on the function's result, a copy: its fields are read, never written.
        val calls = state.occurrences.filter { Role.CALL in it.roles }.mapTo(HashSet()) { it.span }
        for (chain in chains.values) {
            // In a type body, a name written alone or first may be an attribute read through the implicit `SELF`.
            val first = when (chain.start.type) {
                PlSqlGrammar.MEMBER_EXPRESSION -> chain.start.firstChild
                PlSqlGrammar.VARIABLE_NAME -> chain.start
                else -> null
            }
            val segments = listOfNotNull(first) + segmentsOf(chain.root)
            var valueRoles: Set<Role>? = null
            var status: Status? = null
            for (segment in segments) {
                when (val binding = fieldBinding(segment)) {
                    is FieldBinding.Field -> {
                        val declaration = binding.declaration
                        val key = catalog.fieldAt(declaration.fileId ?: path, declaration.line, declaration.column)
                            ?: (if (declaration.fileId == null) bodyFieldAt(declaration.line, declaration.column) else null) ?: continue
                        val roles = valueRoles ?: (if (spanOf(chain.start) in calls) setOf(Role.READ) else roles.valueRoles(chain.start, chain.segments))
                            .also { valueRoles = it }
                        val decided = status ?: startStatus(chain).also { status = it }
                        state.valueOccurrence(spanOf(segment), writtenText(segment), chain.context, chain.statementId, decided, key,
                            if (decided == Status.UNVERIFIED) "COLUMN_NOT_EXCLUDED" else null, emptyList(), roles)
                    }
                    // Unknown also answers methods, sequences and package members after an undecided value: a count of
                    // them would not count fields, so no note.
                    is FieldBinding.Unknown, is FieldBinding.Unavailable -> Unit
                }
            }
        }
    }

    /** In SQL a relation's column may take the chain's first name: the field is as uncertain as that name. */
    private fun startStatus(chain: Chain): Status {
        val first = if (chain.start.type == PlSqlGrammar.MEMBER_EXPRESSION) chain.start.firstChild else chain.start
        return if (chain.statementId != null && nameBinding(first) is NameBinding.ColumnOrLocal) Status.UNVERIFIED else Status.RESOLVED
    }

    /**
     * The segments of [root] written after a value that may name a field: the `IDENTIFIER_NAME`s after the first name
     * of a qualified name, and the names of the members after an element or a call result; never inside arguments.
     */
    private fun segmentsOf(root: AstNode): List<AstNode> {
        val result = ArrayList<AstNode>()
        fun visit(node: AstNode, first: Boolean) {
            when (node.type) {
                PlSqlGrammar.ARGUMENTS -> return
                PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.IDENTIFIER_NAME -> {
                    if (!first) result += node
                    return
                }
            }
            var leading = first
            for (child in node.children) {
                if (child.type == PlSqlPunctuator.DOT) {
                    leading = false
                    continue
                }
                visit(child, leading)
            }
        }
        visit(root, true)
        return result
    }
}
