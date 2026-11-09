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
import com.felipebz.zpa.atlas.model.OccurrenceFact
import com.felipebz.zpa.atlas.model.Role
import com.felipebz.zpa.atlas.model.SourceSpan
import com.felipebz.zpa.atlas.model.Status
import java.util.*

/**
 * The uses of record fields and object attributes: each segment of a value's chain that zpa's [FieldBinding] names as
 * a field with an entity (a package spec's type, a standalone object type, a type of the file's package body) is a use
 * of that field, at the segment's own span, with the roles of the value the chain starts from ([NameRoles.valueRoles]):
 * a field of a field written by an assignment is written too. In a type body the first name, or a name written alone,
 * may itself be an attribute read through the implicit `SELF` (`items.extend`, `tag := 'x'`): zpa answers it as it
 * answers `self.items`. Each field the name of an anchor names (`inner` and `code` of `pkg.g_out.inner.code%TYPE`, `code`
 * of `l_rec.code%TYPE`) is a `TYPE_USE` of that field, at the segment's own span: the anchor takes that field's type.
 *
 * The chains and anchors are gathered during the walk ([chain], [anchor]) and asked in [record], once the file is walked.
 * A segment whose value zpa could not decide records nothing, and so does a field of a type without an entity (declared
 * in a subprogram). zpa leaves undecided a member after a value whose type is declared in another file when text this
 * file may hold differently (a `$IF` the settings do not decide, a substitution) could change how that value is looked up.
 *
 * zpa does not always see a Forms module's program units: when the database has an object of the name of one of them,
 * zpa answers the name with it, which the module's code never reaches, and it may answer nothing. So in a Forms module,
 * in a value or anchor that starts with a name of the module's own (`pks.g_rec.code`, `pks.g_tab(1).code`) or with a
 * declaration of the file whose type the module's text gives through a member of one of its packages (`r.code` with
 * `r pks.t_rec`), a field zpa binds that is declared in the database, or a segment zpa binds to no field, is the field
 * of the file the module's text reaches there ([TypeShapes.formsField]), when it reaches one; a field declared in the
 * module is zpa's own answer, kept.
 */
internal class FieldUses(
    private val state: FileState,
    private val catalog: DeclarationCatalog,
    private val path: String,
    private val roles: NameRoles,
    private val fieldBinding: (AstNode) -> FieldBinding,
    private val nameBinding: (AstNode) -> NameBinding,
    /** The field entity of a type of the file's package body declared at a line and column of this file ([BodyGlobals.fieldAt]). */
    private val bodyFieldAt: (Int, Int) -> String?,
    /** Whether the file is a Forms module, whose own program units zpa does not see. */
    private val formsModule: Boolean
) {
    private class Chain(val root: AstNode, val start: AstNode, val segments: List<String>, val context: Context, val statementId: Int?)

    private class Anchor(val name: AstNode, val context: Context, val statementId: Int?)

    private val chains = IdentityHashMap<AstNode, Chain>()
    private val anchors = ArrayList<Anchor>()

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

    /** Gathers [name], the name of an anchor (`pkg.g_rec.code` of `pkg.g_rec.code%TYPE`), whose fields [record] asks. */
    fun anchor(name: AstNode, context: Context, statementId: Int?) {
        if (name.type == PlSqlGrammar.MEMBER_EXPRESSION) anchors += Anchor(name, context, statementId)
    }

    /**
     * Records the field uses of every chain and anchor gathered: call once the file is left. [shapes] tells, in a Forms
     * module, the types the module's text gives its declarations.
     */
    fun record(shapes: TypeShapes) {
        // A chain that starts with a call works on the function's result, a copy: its fields are read, never written.
        val calls = state.occurrences.filter { Role.CALL in it.roles }.mapTo(HashSet()) { it.span }
        for (chain in chains.values) recordChain(chain, calls, shapes)
        for (anchor in anchors) recordAnchor(anchor, shapes)
    }

    /** Records a `TYPE_USE` of each field the name of [anchor] names. */
    private fun recordAnchor(anchor: Anchor, shapes: TypeShapes) {
        val first = anchor.name.firstChild
        val segments = listOf(first) + segmentsOf(anchor.name)
        val start = if (!formsModule) null else when {
            namesModule(anchor.name) -> FormsStart.Module(state.formsMembers[spanOf(anchor.name)], segments.size)
            else -> shapes.symbolDeclaration(anchor.name)?.let(FormsStart::Declared)
        }
        for (index in 1 until segments.size) {
            val segment = segments[index]
            val key = keyOf(start, fieldBinding(segment) as? FieldBinding.Field, segments, index, shapes) ?: continue
            state.occurrences += OccurrenceFact(anchor.context.unitIndex, anchor.context.container, anchor.statementId, spanOf(segment),
                writtenText(segment), key, setOf(Role.TYPE_USE), status(Status.RESOLVED, anchor.context.partial), anchor.context.origin, null)
        }
    }

    /** Records the field uses of [chain]: one value occurrence for each of its segments that names a known field. */
    private fun recordChain(chain: Chain, calls: Set<SourceSpan>, shapes: TypeShapes) {
        // In a type body, a name written alone or first may be an attribute read through the implicit `SELF`.
        val first = when (chain.start.type) {
            PlSqlGrammar.MEMBER_EXPRESSION -> chain.start.firstChild
            PlSqlGrammar.VARIABLE_NAME -> chain.start
            else -> null
        }
        val segments = listOfNotNull(first) + segmentsOf(chain.root)
        val start = if (formsModule) formsStart(chain, first, shapes) else null
        var valueRoles: Set<Role>? = null
        var status: Status? = null
        for ((index, segment) in segments.withIndex()) {
            // Unknown also answers methods, sequences and package members after an undecided value: a count of them
            // would not count fields, so no note; nor is one given when unavailable.
            val key = keyOf(start, fieldBinding(segment) as? FieldBinding.Field, segments, index, shapes) ?: continue
            val roles = valueRoles ?: chainRoles(chain, calls).also { valueRoles = it }
            val decided = status ?: startStatus(chain).also { status = it }
            state.valueOccurrence(spanOf(segment), writtenText(segment), chain.context, chain.statementId, decided, key,
                if (decided == Status.UNVERIFIED) "COLUMN_NOT_EXCLUDED" else null, emptyList(), roles)
        }
    }

    /** How a Forms module's code reaches the value or anchor whose fields zpa binds; its first name is the first segment. */
    private sealed interface FormsStart {
        /**
         * It starts with a name of the module's own, of [written] segments, naming [member] of one of its packages
         * (null for another name, a program unit, or a member whose segments are not told).
         */
        class Module(val member: FormsNames.Member?, val written: Int) : FormsStart

        /** It starts with [declaration], a declaration of the file, whose type the module's text gives. */
        class Declared(val declaration: AstNode) : FormsStart
    }

    /**
     * How [chain], in a Forms module, its first name [first], starts: with a declaration of the file zpa binds the first
     * name to, else with a name of the module's own; null for anything else.
     */
    private fun formsStart(chain: Chain, first: AstNode?, shapes: TypeShapes): FormsStart? {
        val local = when (val binding = first?.let(nameBinding)) {
            is NameBinding.Local -> binding.declaration
            is NameBinding.ColumnOrLocal -> binding.declaration
            else -> null
        }?.takeIf { it.fileId == null }
        local?.let { shapes.localDeclaration(it.line, it.column) }?.let { return FormsStart.Declared(it) }
        if (!namesModule(chain.start)) return null
        return FormsStart.Module(state.formsMembers[spanOf(chain.start)]?.takeIf { first != null }, chain.segments.size)
    }

    /** Whether the walk resolved [name] to the Forms module's own: a program unit or a member of one of its packages. */
    private fun namesModule(name: AstNode): Boolean = state.occurrencesAt(spanOf(name)).any { it.entityKey?.startsWith(FORMS_PREFIX) == true }

    /**
     * The field [segments]`[index]` names, zpa's [binding] (null when zpa binds none), in a value that starts as [start]
     * tells ([FieldUses]): the field of the file the module's text reaches past a member of one of its packages, in
     * place of one declared in the database or of none; none when that text reaches none.
     */
    private fun keyOf(start: FormsStart?, binding: FieldBinding.Field?, segments: List<AstNode>, index: Int, shapes: TypeShapes): String? {
        if (start == null || binding != null && binding.declaration.fileId == null) return binding?.let(::fieldKey)
        val reach = when (start) {
            is FormsStart.Module -> {
                val member = start.member ?: return null
                val from = start.written - member.fields.size
                if (index < from) return null
                shapes.formsField(member.declaration, names(segments, from, index), true)
            }
            is FormsStart.Declared -> if (index == 0) return binding?.let(::fieldKey) else shapes.formsField(start.declaration, names(segments, 1, index), false)
        }
        return when (reach) {
            is TypeShapes.FieldReach.Own -> reach.field.token.takeIf { binding != null || reach.throughMember }
                ?.let { catalog.fieldAt(path, it.line, it.column) ?: bodyFieldAt(it.line, it.column) }
            TypeShapes.FieldReach.Module -> null
            TypeShapes.FieldReach.Elsewhere -> binding?.takeIf { start is FormsStart.Declared }?.let(::fieldKey)
        }
    }

    /** The names written in [segments] from [from] to [to], both included. */
    private fun names(segments: List<AstNode>, from: Int, to: Int): List<String> = segments.subList(from, to + 1).map { normalizeIdentifier(it.tokenOriginalValue) }

    /** The key of the field [binding] declares, from the catalog or, declared in this file, from the body's own types. */
    private fun fieldKey(binding: FieldBinding.Field): String? {
        val declaration = binding.declaration
        return catalog.fieldAt(declaration.fileId ?: path, declaration.line, declaration.column)
            ?: (if (declaration.fileId == null) bodyFieldAt(declaration.line, declaration.column) else null)
    }

    /** The roles of the value [chain] names: only read when it starts with a call ([calls]). */
    private fun chainRoles(chain: Chain, calls: Set<SourceSpan>): Set<Role> =
        if (spanOf(chain.start) in calls) setOf(Role.READ) else roles.valueRoles(chain.start, chain.segments)

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
