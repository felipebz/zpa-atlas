//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.api.SingleRowSqlFunctionsGrammar
import com.felipebz.zpa.api.project.ProjectCallKind
import com.felipebz.zpa.api.squid.SemanticAstNode
import com.felipebz.zpa.api.symbols.Symbol
import com.felipebz.zpa.atlas.model.*

/** Type references (`TYPE_USE`) and the built-in SQL functions a type or a synonym may name. */
internal class TypeReferences(
    private val state: FileState,
    private val types: TypeReferenceResolver,
    private val resolver: RelationResolver,
    private val calls: CallResolver,
    private val plsql: PlSqlNames,
    private val jsonBuiltIn: Boolean
) {
    /**
     * One `TYPE_USE` occurrence for the name of an `ANCHORED_DATATYPE` (`name%TYPE`, `name%ROWTYPE`), a
     * `CUSTOM_DATATYPE` or a `REF_DATATYPE` (the name after `REF`, which the span leaves out). A one-segment name is a
     * `VARIABLE_NAME`, a longer one a `MEMBER_EXPRESSION`. A REF CURSOR type of an enclosing block is local only for a
     * `CUSTOM_DATATYPE`. A first segment
     * the legacy table binds to a local declaration is counted (`LOCAL_ANCHOR`), not indexed; built-ins give nothing.
     * A binding to the declaration the datatype belongs to is ignored: `comentario comentario.texto%type` names the
     * table, the item is not visible in its own declaration.
     */
    fun typeUse(node: AstNode, context: Context, statementId: Int?) {
        val name = if (node.type == PlSqlGrammar.REF_DATATYPE) node.lastChild else node.firstChild
        if (context.hasHoles(node)) return
        if (node.type == PlSqlGrammar.ANCHORED_DATATYPE && remoteAnchor(node, context, statementId)) return
        val segments = plsql.nameSegments(name) ?: run {
            state.coverage += CoverageFact("TYPE_NOT_A_NAME", "Datatype at line ${name.tokenLine} is not a plain name")
            return
        }
        val first = if (name.type == PlSqlGrammar.VARIABLE_NAME) name else name.firstChild
        val symbol = (first as? SemanticAstNode)?.symbol?.takeUnless { isOwnDeclaration(it, node) }
        if (symbol != null && plsql.isLocalDeclaration(symbol) ||
            symbol == null && node.type == PlSqlGrammar.CUSTOM_DATATYPE && segments.size == 1 && isLocalRefCursorType(node, normalizeIdentifier(segments[0]))) {
            state.localTypeReferences++
            return
        }
        val outcome = if (node.type == PlSqlGrammar.ANCHORED_DATATYPE) {
            val enclosingMember = symbol != null && symbol.scope.type == PlSqlGrammar.CREATE_PACKAGE
            types.anchored(segments, node.lastChild.type == PlSqlKeyword.ROWTYPE, context.schema, context.enclosingPackage, enclosingMember)
        } else {
            types.named(segments, context.schema, context.enclosingPackage) ?: return
        }
        state.occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, spanOf(name), writtenText(name), outcome.entityKey,
            setOf(Role.TYPE_USE), status(outcome.status, context.partial), context.origin, outcome.reason, outcome.candidateKeys)
    }

    /**
     * An anchor on an object behind a database link (`t@link%ROWTYPE`, `s.t.c@link%TYPE`): the relation the segments
     * before `@` name (without the column for `%TYPE`), resolved with its link as static SQL resolves a remote
     * relation, and its column for `%TYPE`. Never a local declaration or a package member. False when the anchor has
     * no link or is not a plain dotted name.
     */
    private fun remoteAnchor(node: AstNode, context: Context, statementId: Int?): Boolean {
        val name = node.firstChild
        val (segments, link) = remoteName(name) ?: return false
        val rowType = node.lastChild.type == PlSqlKeyword.ROWTYPE
        val relationNames = if (rowType) segments else segments.dropLast(1)
        if (relationNames.size !in 1..2) return false
        val relation = resolver.resolve(WrittenRelation(relationNames.getOrNull(relationNames.size - 2), relationNames.last(), link), context.schema)
        val (key, status, reason) = if (rowType) {
            Triple(relation.entityKey, relation.status, relation.reason)
        } else {
            resolver.resolveColumn(relation, segments.last()).let { Triple(it.entityKey, it.status, it.reason) }
        }
        state.occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, spanOf(name), writtenText(name), key,
            setOf(Role.TYPE_USE), status(status, context.partial), context.origin, reason)
        return true
    }

    /** The type after `AGGREGATE USING` of a user-defined aggregate function. */
    fun isAggregateImplementation(node: AstNode): Boolean =
        node.previousSiblingOrNull?.type == PlSqlKeyword.USING && node.parentOrNull?.hasDirectChildren(PlSqlKeyword.AGGREGATE) == true

    /** `AGGREGATE USING t`: a `TYPE_USE` of the implementation type. */
    fun aggregateType(name: AstNode, context: Context) {
        val segments = plsql.nameSegments(name) ?: return
        typeUseOf(name, segments, context)
    }

    /**
     * A bare `json` (no modifier, no `LIMIT`): the parser reads it as the built-in `JSON` type, but it may name a user
     * type, as PL/JSON's `JSON`. Below version 21, where the built-in type does not exist, or without a database version,
     * a type the project, the dictionary or a synonym finds gets a `TYPE_USE`; nothing found is the built-in type. From
     * 21 on Oracle rejects a type named `JSON` (ORA-02302), so it is always the built-in.
     */
    fun jsonTypeUse(node: AstNode, context: Context, statementId: Int? = null) {
        if (jsonBuiltIn || node.numberOfChildren != 1) return
        typeUseOf(node, listOf(node.tokenOriginalValue), context, parsedAsBuiltIn = true, statementId = statementId)
    }

    /** The supertype of `CREATE TYPE t UNDER s`. */
    fun isSupertype(node: AstNode): Boolean =
        node.previousSiblingOrNull?.type == PlSqlKeyword.UNDER && node.parentOrNull?.type == PlSqlGrammar.OBJECT_TYPE_DEFINITION

    /** `UNDER s`: a `TYPE_USE` of the supertype. */
    fun supertypeUse(name: AstNode, context: Context) {
        val segments = name.getChildren(PlSqlGrammar.IDENTIFIER_NAME).map { it.tokenOriginalValue }.ifEmpty { return }
        typeUseOf(name, segments, context)
    }

    private fun typeUseOf(name: AstNode, segments: List<String>, context: Context, parsedAsBuiltIn: Boolean = false, statementId: Int? = null) {
        if (context.hasHoles(name)) return
        val outcome = types.named(segments, context.schema, context.enclosingPackage, parsedAsBuiltIn) ?: return
        state.occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, spanOf(name), writtenText(name), outcome.entityKey,
            setOf(Role.TYPE_USE), status(outcome.status, context.partial), context.origin, outcome.reason, outcome.candidateKeys)
    }

    /**
     * A built-in SQL function the grammar parses as its own expression (`XMLAGG(… ORDER BY …)`, `XMLSEQUENCE(…)`,
     * `JSON(x)`): a `CALL` of its keyword only when something names it (Oracle records the public synonym, and the
     * target for an operator; `json(x)` is the constructor of a user type `JSON` or of the type a synonym `JSON` names,
     * below version 21); nothing otherwise, as for other built-ins.
     */
    fun sqlFunctionKeyword(keyword: AstNode, context: Context, statementId: Int?) {
        // From version 21 on `json(x)` is always Oracle's built-in function, as a bare `json` is the built-in type.
        if (jsonBuiltIn && keyword.parent.type == SingleRowSqlFunctionsGrammar.JSON_CONSTRUCTOR) return
        val outcome = calls.resolve(listOf(keyword.tokenOriginalValue), ProjectCallKind.FUNCTION, emptyList(), context.schema, context.enclosingPackage)
        if (outcome is CallOutcome.Call && outcome.entityKey != null) plsql.callOccurrence(keyword, context, statementId, outcome)
    }

    private fun isOwnDeclaration(symbol: Symbol, reference: AstNode): Boolean {
        val declaration = symbol.node?.parentOrNull ?: return false
        return generateSequence(reference) { it.parentOrNull }.any { it === declaration }
    }

    /**
     * A REF CURSOR type of an enclosing block, subprogram or package body: the legacy table does not register
     * `REF_CURSOR_DECLARATION`. Declarations of a package specification are project types, never local.
     */
    private fun isLocalRefCursorType(reference: AstNode, name: String): Boolean {
        var node = reference.parentOrNull
        while (node != null && node.type != PlSqlGrammar.CREATE_PACKAGE) {
            val sections = if (node.type == PlSqlGrammar.DECLARE_SECTION) listOf(node) else node.getChildren(PlSqlGrammar.DECLARE_SECTION)
            val declared = sections.any { section ->
                section.getChildren(PlSqlGrammar.REF_CURSOR_DECLARATION).any {
                    it.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { identifier -> normalizeIdentifier(identifier.tokenOriginalValue) } == name
                }
            }
            if (declared) return true
            node = node.parentOrNull
        }
        return false
    }
}
