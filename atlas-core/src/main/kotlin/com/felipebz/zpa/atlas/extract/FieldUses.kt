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
 * The uses of record fields: each segment of a value's chain that zpa's [FieldBinding] names as a field of a record type
 * with an entity (a package spec's type) is a use of that field, at the segment's own span, with the roles of the value
 * the chain starts from ([NameRoles.valueRoles]): a field of a field written by an assignment is written too.
 *
 * zpa resolves the members after an element, a call result or a package variable of another file when the file is
 * left: the chains are gathered during the walk ([chain]) and asked in [record], from `leaveFile`. A segment whose value
 * zpa could not decide records nothing, and so does a field of a type without an entity (declared in a body or a
 * subprogram, an object attribute).
 */
internal class FieldUses(
    private val state: FileState,
    private val catalog: DeclarationCatalog,
    private val path: String,
    private val roles: NameRoles,
    private val fieldBinding: (AstNode) -> FieldBinding,
    private val nameBinding: (AstNode) -> NameBinding
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
        if (root === start && start.type != PlSqlGrammar.MEMBER_EXPRESSION) return
        chains.putIfAbsent(root, Chain(root, start, segments, context, statementId))
    }

    /** Records the field uses of every chain gathered: call once the file is left. */
    fun record() {
        // A chain that starts with a call works on the function's result, a copy: its fields are read, never written.
        val calls = state.occurrences.filter { Role.CALL in it.roles }.mapTo(HashSet()) { it.span }
        for (chain in chains.values) {
            val segments = segmentsOf(chain.root)
            if (segments.isEmpty()) continue
            var valueRoles: Set<Role>? = null
            var status: Status? = null
            for (segment in segments) {
                when (val binding = fieldBinding(segment)) {
                    is FieldBinding.Field -> {
                        val declaration = binding.declaration
                        val key = catalog.fieldAt(declaration.fileId ?: path, declaration.line, declaration.column) ?: continue
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
                PlSqlGrammar.VARIABLE_NAME -> {
                    if (!first) result += node
                    return
                }
                PlSqlGrammar.IDENTIFIER_NAME -> {
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
