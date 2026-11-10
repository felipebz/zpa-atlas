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
import com.felipebz.zpa.api.semantics.NameBinding
import com.felipebz.zpa.api.semantics.NameDeclarationKind
import com.felipebz.zpa.atlas.model.*

/**
 * The PL/SQL targets of the `INTO`s, assignments and arguments of one file ([target]), and the records whose fields
 * they read ([record]).
 */
internal class IntoTargets(
    private val state: FileState,
    private val plsql: PlSqlNames,
    private val nameBinding: (AstNode) -> NameBinding
) {
    /**
     * A target as zpa binds it: a field with an entity at its last segment, else the entity of the whole name (the
     * segments after the one it names are fields), else a declaration without an entity, with its own type. A field
     * reached through a collection element (`a(i).f`, `a(i).r.f`, and `a(i).f(j)` for an element of it) is the field
     * zpa binds its last segment to, as the index records its use; when that field has no entity (a field of an element
     * that is a `%ROWTYPE`), the collection itself, its element and fields as steps of the path ([IntoTarget.ELEMENT]).
     * An element of an element (`a(i)(j)`, `a(i).f(j)(k)`) is a shape the index does not follow
     * (`TARGET_SHAPE_NOT_FOLLOWED`). A member of a Forms module's package (`pks.g_code`), whose use is the package's,
     * is the member ([FileState.formsMembers]).
     */
    fun target(node: AstNode, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): IntoTarget {
        val span = spanOf(node)
        val written = writtenText(node)
        if (node.type == PlSqlGrammar.OBJECT_REFERENCE) {
            val head = node.firstChild
            val (field, element) = fieldThroughElement(node)?.takeUnless {
                head.type == PlSqlGrammar.METHOD_CALL && isElementOfElement(
                    head
                )
            }
                ?: return IntoTarget.of(span, written, null, reason = TARGET_SHAPE_NOT_FOLLOWED)
            bySpan[spanOf(field)]?.firstOrNull()?.entityKey?.let { return IntoTarget.of(span, written, it, element) }
            return throughElements(node, bySpan, shapes)?.copy(span = span, written = written)
                ?: IntoTarget.of(span, written, null, element, "FIELD_UNDECIDED")
        }
        val element = node.type == PlSqlGrammar.METHOD_CALL
        if (element && isElementOfElement(node)) return IntoTarget.of(
            span,
            written,
            null,
            true,
            TARGET_SHAPE_NOT_FOLLOWED
        )
        val name = if (element) node.firstChild else node
        val segments = plsql.nameSegments(name)?.map { normalizeIdentifier(it) }
            ?: return formsTarget(span, written, name, element) ?: IntoTarget.of(
                span,
                written,
                null,
                element,
                "TARGET_NOT_A_NAME"
            )
        if (segments.size > 1) {
            val last =
                name.children.last { it.type == PlSqlGrammar.IDENTIFIER_NAME || it.type == PlSqlGrammar.VARIABLE_NAME }
            bySpan[spanOf(last)]?.firstOrNull()?.entityKey?.let { return IntoTarget.of(span, written, it, element) }
        }
        state.formsMembers[spanOf(name)]?.let {
            return IntoTarget(
                span,
                written,
                it.key,
                it.fields,
                element,
                null,
                null,
                null
            )
        }
        bySpan[spanOf(name)]?.firstOrNull()?.entityKey?.let { key ->
            val own = key.substringAfterLast('.').substringBefore('@').substringBefore('#')
            val at = segments.indexOfLast { it == own }
            return IntoTarget(
                span,
                written,
                key,
                if (at >= 0) segments.drop(at + 1) else segments.drop(1),
                element,
                null,
                null,
                null
            )
        }
        val declaration = local(name, shapes)
            ?: return IntoTarget.of(span, written, null, element, "TARGET_UNDECIDED")
        val shape = shapes.of(declaration, null) ?: TypeShape.Unknown("DECLARATION_UNKNOWN")
        // A nested subprogram's parameter, whose calls this file binds; not an object type method's, whose calls the
        // index does not record.
        val localKey =
            declaration.takeIf { it.type == PlSqlGrammar.PARAMETER_DECLARATION && !it.hasAncestor(PlSqlGrammar.CREATE_TYPE_BODY) }
                ?.let(::localKey)
        return IntoTarget(
            span,
            written,
            null,
            segments.drop(1),
            element,
            shape,
            DeclaredTexts.of("", declaration)?.datatype,
            null,
            localKey
        )
    }

    /**
     * A Forms host name (`:BLOCK.ITEM`, `:ITEM`, `:PARAMETER.NAME`, `:GLOBAL.NAME`) as the module's names resolve its use
     * ([FormsReferences]): the object it names, else, ambiguous or missing, the use's reason; null for any other target
     * (`:SYSTEM.NAME` among them).
     */
    private fun formsTarget(span: SourceSpan, written: String, name: AstNode, element: Boolean): IntoTarget? {
        val use = state.occurrencesAt(spanOf(name)).firstOrNull { use ->
            use.entityKey?.startsWith(FORMS_PREFIX) == true || use.reason in FORMS_MISSING ||
                use.status == Status.AMBIGUOUS && use.candidateKeys.isNotEmpty() && use.candidateKeys.all {
                it.startsWith(
                    FORMS_PREFIX
                )
            }
        } ?: return null
        return IntoTarget.of(span, written, use.entityKey, element, use.reason)
    }

    /**
     * `a(i).f`, `a(i).r.f`, `a(i).f(j)` followed from the collection `a`: its target, then an element step and the
     * written fields and elements after it; null when `a` is not a target the index knows.
     */
    private fun throughElements(
        reference: AstNode,
        bySpan: Map<SourceSpan, List<OccurrenceFact>>,
        shapes: TypeShapes
    ): IntoTarget? {
        val head = reference.firstChild.takeIf { it.type == PlSqlGrammar.METHOD_CALL } ?: return null
        val collection =
            target(head.firstChild, bySpan, shapes).takeIf { it.reason == null && !it.element } ?: return null
        val steps = ArrayList(collection.path)
        steps += IntoTarget.ELEMENT
        fun visit(node: AstNode): Boolean = when (node.type) {
            PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.IDENTIFIER_NAME -> steps.add(normalizeIdentifier(node.tokenOriginalValue))
            PlSqlGrammar.OBJECT_REFERENCE, PlSqlGrammar.MEMBER_EXPRESSION -> node.children.filter { it.type != PlSqlPunctuator.DOT }
                .all(::visit)

            PlSqlGrammar.METHOD_CALL -> visit(node.firstChild) && steps.add(IntoTarget.ELEMENT)
            else -> false
        }
        if (!reference.children.drop(1).filter { it.type != PlSqlPunctuator.DOT }.all(::visit)) return null
        return collection.copy(path = steps)
    }

    /**
     * The last name of a target that goes through a collection element (`a(i).f`, `a(i).r.f`: zpa parses it as an
     * object reference, the element then the rest), and whether the target is an element of what it names (`a(i).f(j)`);
     * null for any other shape, an element of an element among them.
     */
    private fun fieldThroughElement(reference: AstNode): Pair<AstNode, Boolean>? {
        var tail = reference.lastChild
        var element = false
        while (true) {
            tail = when (tail.type) {
                PlSqlGrammar.OBJECT_REFERENCE, PlSqlGrammar.MEMBER_EXPRESSION -> tail.lastChild
                PlSqlGrammar.METHOD_CALL -> {
                    if (isElementOfElement(tail)) return null
                    element = true
                    tail.firstChild
                }

                PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.IDENTIFIER_NAME -> return tail to element
                else -> return null
            }
        }
    }

    /** Whether the call [call] is an element of an element (`a(i)(j)`): two argument lists, or a call of a call. */
    private fun isElementOfElement(call: AstNode) =
        call.getChildren(PlSqlGrammar.ARGUMENTS).size > 1 || call.firstChild.type == PlSqlGrammar.METHOD_CALL

    /**
     * The record of the field `r.f`: a cursor `FOR` loop record, its row the loop's query or cursor, or the variable or
     * parameter zpa binds `r` to; null when `r` names no record (a package, or a record type's field entity).
     */
    fun record(field: AstNode, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): IntoTarget? {
        val name = field.firstChild
        val declaration = (nameBinding(name) as? NameBinding.Local)?.declaration
        if (declaration?.kind == NameDeclarationKind.LOOP_INDEX) {
            val iterator =
                declaration.takeIf { it.fileId == null }?.let { shapes.iterand(it.line, it.column) }?.parentOrNull
                    ?: return null
            return IntoTarget(
                spanOf(name),
                writtenText(name),
                null,
                emptyList(),
                false,
                loopRow(iterator, bySpan, shapes),
                null,
                null
            )
        }
        val whole = target(field, bySpan, shapes)
        if (whole.entityKey == null && whole.local == null) return null
        // The field itself has an entity (a field of a record type): not a row record.
        if (whole.path.size != 1) return null
        return whole.copy(span = spanOf(name), written = writtenText(name), path = emptyList())
    }

    /** The row of a cursor `FOR` loop's record: its query's items, or the cursor it opens (`for r in c`, `for r in c(1)`). */
    private fun loopRow(
        iterator: AstNode,
        bySpan: Map<SourceSpan, List<OccurrenceFact>>,
        shapes: TypeShapes
    ): TypeShape {
        val unknown = TypeShape.Anchored(true, null, null, emptyList(), "CURSOR_QUERY_UNKNOWN")
        iterator.getFirstDescendantOrNull(DmlGrammar.SELECT_EXPRESSION)?.let { query ->
            return state.cursorItems[query]?.let { TypeShape.CursorRow(it) } ?: unknown
        }
        var cursor = iterator.children.dropWhile { it.type != PlSqlKeyword.IN }.drop(1).firstOrNull() ?: return unknown
        while (cursor.type != PlSqlGrammar.VARIABLE_NAME && cursor.type != PlSqlGrammar.MEMBER_EXPRESSION) cursor =
            cursor.firstChildOrNull ?: return unknown
        (local(cursor, shapes)?.takeIf { it.type == PlSqlGrammar.CURSOR_DECLARATION } ?: enclosingCursor(
            iterator,
            cursor
        ))
            ?.let { shapes.of(it, null) }?.let { return it }
        return bySpan[spanOf(cursor)]?.firstOrNull()?.entityKey?.let {
            TypeShape.Anchored(
                true,
                it,
                null,
                emptyList(),
                null
            )
        } ?: unknown
    }

    /**
     * The cursor a loop names (`for r in c`), which zpa does not bind: the declaration of that name in the nearest
     * declarations around the loop (a block's, a routine's, the package body's), or null.
     */
    private fun enclosingCursor(from: AstNode, name: AstNode): AstNode? {
        if (name.type != PlSqlGrammar.VARIABLE_NAME) return null
        val wanted = normalizeIdentifier(name.tokenOriginalValue)
        var scope = from.parentOrNull
        while (scope != null) {
            scope.children.asSequence()
                .flatMap { if (it.type == PlSqlGrammar.DECLARE_SECTION) it.children.asSequence() else sequenceOf(it) }
                .firstOrNull {
                    it.type == PlSqlGrammar.CURSOR_DECLARATION && normalizeIdentifier(
                        it.getFirstChild(
                            PlSqlGrammar.IDENTIFIER_NAME
                        ).tokenOriginalValue
                    ) == wanted
                }
                ?.let { return it }
            scope = scope.parentOrNull
        }
        return null
    }

    /** The declaration of this file without an entity that zpa binds [name] (its first segment) to, or null. */
    fun local(name: AstNode, shapes: TypeShapes): AstNode? {
        val first = if (name.type == PlSqlGrammar.MEMBER_EXPRESSION) name.firstChild else name
        val declaration = when (val binding = nameBinding(first)) {
            is NameBinding.Local -> binding.declaration
            is NameBinding.ColumnOrLocal -> binding.declaration
            else -> return null
        }
        if (declaration.fileId != null) return null
        return shapes.localDeclaration(declaration.line, declaration.column)
    }

    /** Where a nested subprogram's parameter is named in the file: `local:line:column`. */
    fun localKey(parameter: AstNode, name: Boolean = false): String =
        (if (name) parameter else parameter.firstChild).token.let { "${PlaceShapes.LOCAL_PREFIX}${it.line}:${it.column}" }

    companion object {
        /** A target whose shape the index does not follow (an element of an element). */
        private const val TARGET_SHAPE_NOT_FOLLOWED = "TARGET_SHAPE_NOT_FOLLOWED"

        /** The reasons of a Forms host name whose block, item or parameter the module does not have ([formsTarget]). */
        private val FORMS_MISSING =
            setOf(Reason.NO_SUCH_BLOCK.name, Reason.NO_SUCH_ITEM.name, Reason.NO_SUCH_PARAMETER.name)
    }
}
