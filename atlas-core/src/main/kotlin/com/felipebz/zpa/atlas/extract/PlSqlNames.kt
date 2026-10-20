//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.flr.api.Token
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.api.project.ProjectAnalysis
import com.felipebz.zpa.api.project.ProjectCallKind
import com.felipebz.zpa.api.squid.SemanticAstNode
import com.felipebz.zpa.api.symbols.Symbol
import com.felipebz.zpa.atlas.model.*

/**
 * PL/SQL names: calls, value references (package values, sequences, functions called without parentheses), the names
 * of conditional compilation conditions, and the names of an enclosing unit or of the object itself that the project
 * can only guess.
 */
internal class PlSqlNames(
    private val state: FileState,
    private val scopes: SqlScopes,
    catalog: DeclarationCatalog,
    private val calls: CallResolver,
    values: PackageValues,
    synonyms: SynonymResolver,
    typeMembers: ObjectTypeMembers,
    runSchemas: Set<String>,
    private val jsonBuiltIn: Boolean,
    projectAnalysis: ProjectAnalysis
) {
    private val ownNames = OwnNames(typeMembers)
    private val segmentValues = SegmentValues(calls, values)
    private val roles = NameRoles(state)
    private val remoteNames = RemoteNames(state, scopes, roles)
    private val sequenceNames = SequenceNames(state, scopes, values, synonyms, runSchemas, projectAnalysis)
    private val conditionNameReads = ConditionNameReads(state, catalog, segmentValues, runSchemas)

    /** The segments of a plain dotted name as written, or null for anything else (a host variable, `f(x).g`, `c%rowcount`). */
    fun nameSegments(callee: AstNode): List<String>? {
        val parts = when (callee.type) {
            PlSqlGrammar.VARIABLE_NAME -> listOf(callee)
            PlSqlGrammar.MEMBER_EXPRESSION -> callee.children.filter { it.type != PlSqlPunctuator.DOT }
            else -> return null
        }
        if (parts.any { it.type is PlSqlPunctuator || tokensOf(it).size != 1 }) return null
        return parts.map { it.tokenOriginalValue }
    }

    /**
     * One `CALL` occurrence for a `METHOD_CALL` or a `CALL_STATEMENT` without arguments ([node] is then the name).
     * Names the legacy symbol table binds to a local value or type are not calls; a nested subprogram is
     * `LOCAL_SUBPROGRAM` (local subprograms are not entities).
     */
    fun callSite(node: AstNode, context: Context, statementId: Int?, methodColumn: ((AstNode, List<String>) -> Boolean)? = null) {
        val isMethodCall = node.type == PlSqlGrammar.METHOD_CALL
        // `v(1).l.exists(1)`, `t_val('x').path(null)`: a method of the value before the dot, not a schema-level name.
        // The calls in its arguments are separate METHOD_CALL nodes, visited on their own.
        if (isMethodCall && node.previousSiblingOrNull?.type == PlSqlPunctuator.DOT) return
        val callee = if (isMethodCall) node.firstChild else node
        if (context.hasHoles(callee)) return
        val kind = if (!isMethodCall || node.parent.type == PlSqlGrammar.CALL_STATEMENT) ProjectCallKind.PROCEDURE else ProjectCallKind.FUNCTION
        val actuals = if (isMethodCall) node.getFirstChildOrNull(PlSqlGrammar.ARGUMENTS)?.getChildren(PlSqlGrammar.ARGUMENT).orEmpty() else emptyList()
        remoteName(callee)?.takeIf { it.segments.size in 1..3 }?.let {
            remoteNames.remoteCall(callee, it, kind, actuals, context, statementId)
            return
        }
        val segments = nameSegments(callee) ?: run {
            state.coverage += CoverageFact("CALL_NOT_A_NAME", "Call at line ${callee.tokenLine} has a callee that is not a plain name")
            return
        }
        // From version 21 on a call written `json(...)` is Oracle's built-in JSON function, never a user type's constructor.
        if (jsonBuiltIn && segments.size == 1 && normalizeIdentifier(segments[0]) == "JSON") return
        val first = if (callee.type == PlSqlGrammar.VARIABLE_NAME) callee else callee.firstChild
        val symbol = (first as? SemanticAstNode)?.symbol
        val arguments = actuals.map { argument ->
            val children = argument.children
            val named = children.size >= 3 && children[1].tokenValue == "=>"
            Triple(if (named) children[0].tokenOriginalValue else null, if (named) children.last() else argument, argument)
        }
        // A name resolved below may add external entities; a method of an SQL value takes them back.
        val entityMark = state.entities.size
        val relationMark = state.relations.size
        val resolved = when {
            // A type of the package specification is a constructor; local values, cursors and types are not calls, a
            // variable or cursor of the package specification (in its own file) is a member.
            symbol != null && symbol.kind in NOT_CALLABLE && (isLocalDeclaration(symbol) || symbol.kind == Symbol.Kind.PARAMETER) -> return
            symbol != null && (symbol.kind == Symbol.Kind.PROCEDURE || symbol.kind == Symbol.Kind.FUNCTION) && isLocalSubprogram(symbol) ->
                CallOutcome.Call(Status.UNRESOLVED, null, "LOCAL_SUBPROGRAM")
            else -> calls.resolve(segments, kind, arguments.map { WrittenArgument(it.first) }, context.schema, context.enclosingPackage)
        }
        var outcome = resolved
        if (isExternalGuess(outcome)) {
            when (ownNames.ownName(callee, segments, context)) {
                OwnName.VALUE -> {
                    state.rollBack(entityMark, relationMark)
                    return
                }
                OwnName.SUBPROGRAM -> {
                    state.rollBack(entityMark, relationMark)
                    outcome = CallOutcome.Call(Status.UNRESOLVED, null, "LOCAL_SUBPROGRAM")
                }
                null -> Unit
            }
        }
        if (outcome is CallOutcome.Value) {
            // `pkg.g_tab(i)`, `pkg.g_tab.exists(i)`, `pkg.g_tab.delete`, `pkg.c_pending(1)`: a package member; in SQL,
            // not when the first segment is a qualifier of the statement.
            if (statementId != null && scopes.isSqlQualified(callee, segments)) return
            state.valueOccurrence(spanOf(callee), writtenText(callee), context, statementId, outcome.status, outcome.entityKey, outcome.reason,
                outcome.candidateKeys, roles.memberRoles(callee, segments, outcome.kind))
            return
        }
        if (outcome !is CallOutcome.Call || symbol?.kind == Symbol.Kind.VARIABLE || symbol?.kind == Symbol.Kind.CURSOR) return
        // In SQL, `alias.col.method()`, `alias.method()` or `schema.t.col.method()` whose qualifier names a source of the
        // statement is a method of a column or row value: no call, no entity, the column read. A call the project knows
        // still wins: Oracle runs `pk.f()` even with an alias `pk`.
        if (methodColumn != null && segments.size >= 2 && outcome.status == Status.UNRESOLVED) {
            val resolvedEntities = state.entities.size
            val resolvedRelations = state.relations.size
            if (methodColumn(callee, segments)) {
                // Only what the call resolution added; the column read's own entities stay.
                state.rollBack(entityMark, relationMark, resolvedEntities, resolvedRelations)
                return
            }
        }
        if (outcome.status == Status.RESOLVED) {
            arguments.forEachIndexed { index, (_, expression, _) -> outcome.modes[index + 1]?.let { state.argumentModes[expression] = it } }
        }
        val argumentFacts = arguments.mapIndexed { index, (label, expression, _) ->
            val value = ExpressionValues.of(expression, context::hasHoles)
            CallArgumentFact(index + 1, label?.let { normalizeIdentifier(it) },
                if (outcome.status == Status.RESOLVED) outcome.bindings[index + 1] else null, spanOf(expression), value.kind, value.value)
        }
        state.occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, spanOf(callee), writtenText(callee), outcome.entityKey,
            setOf(Role.CALL), status(outcome.status, context.partial), context.origin, outcome.reason, outcome.candidateKeys, argumentFacts)
    }

    /**
     * Whether [node] is the outermost plain name of an expression: not a segment of a longer name, not the callee of a
     * call (handled by [callSite]), not the member after a call (`pkg.g_tab(i).f`).
     */
    fun isValueName(node: AstNode): Boolean {
        val parent = node.parentOrNull ?: return false
        if (parent.type == PlSqlGrammar.MEMBER_EXPRESSION || parent.type == PlSqlGrammar.CALL_STATEMENT) return false
        if (parent.type == PlSqlGrammar.METHOD_CALL && parent.firstChild === node) return false
        return node.previousSiblingOrNull?.type != PlSqlPunctuator.DOT
    }

    /**
     * A value reference: a sequence pseudocolumn; else, unless the legacy table captures the first segment
     * lexically, a package value, exception or cursor, a project function called without parentheses, or a missing
     * member of a project package. A cursor attribute (`pkg.c%isopen`) is a reference to the name before `%`. In SQL
     * ([sql]) only qualified names whose first segment is not an SQL qualifier are read.
     */
    fun valueReference(name: AstNode, context: Context, statementId: Int?, sql: Boolean) {
        if (context.hasHoles(name)) return
        remoteName(name)?.let {
            remoteNames.remoteValue(name, it, context, statementId)
            return
        }
        val attribute = name.type == PlSqlGrammar.MEMBER_EXPRESSION && name.hasDirectChildren(PlSqlPunctuator.MOD)
        val parts = if (attribute) name.children.takeWhile { it.type != PlSqlPunctuator.MOD } else listOf(name)
        val segments = (if (attribute) attributeName(name, parts) else nameSegments(name)) ?: return
        val span = spanOf(parts.first(), parts.last())
        val text = parts.joinToString("") { writtenText(it) }
        if (!attribute && name.type == PlSqlGrammar.MEMBER_EXPRESSION && segments.size in 2..3 && normalizeIdentifier(segments.last()) in PSEUDOCOLUMNS) {
            sequenceNames.sequenceReference(name, segments, context, statementId)
            return
        }
        if (sql && (segments.size == 1 || scopes.isSqlQualified(name, segments))) return
        val first = if (name.type == PlSqlGrammar.VARIABLE_NAME) name else name.firstChild
        val symbol = (first as? SemanticAstNode)?.symbol
        if (symbol != null) {
            when (symbol.kind) {
                Symbol.Kind.PROCEDURE, Symbol.Kind.FUNCTION -> if (isLocalSubprogram(symbol)) {
                    if (segments.size == 1) callOccurrence(name, context, statementId, CallOutcome.Call(Status.UNRESOLVED, null, "LOCAL_SUBPROGRAM"))
                    return
                }
                Symbol.Kind.PACKAGE, Symbol.Kind.TRIGGER -> Unit
                else -> if (isLocalDeclaration(symbol)) return
            }
        }
        if (isOwnBodyMember(segments, context)) return
        val packageName = context.enclosingPackage?.let { qualifiedName(it) }
        val valueReadable = segments.size >= 2 || (packageName != null && (symbol == null || symbol.scope.type == PlSqlGrammar.CREATE_PACKAGE))
        val callable = segments.size >= 2 || symbol == null || symbol.kind == Symbol.Kind.FUNCTION || symbol.kind == Symbol.Kind.PROCEDURE
        val entityMark = state.entities.size
        val relationMark = state.relations.size
        // A name before `%` is never a call.
        val value = segmentValues.segmentValue(segments, context, packageName, valueReadable, callable, callAllowed = !attribute)
        if (value is SegmentValue.Member && value.status == Status.UNRESOLVED && value.reason in GUESSED_REASONS) {
            when (ownNames.ownName(name, segments, context)) {
                OwnName.VALUE -> {
                    state.rollBack(entityMark, relationMark)
                    return
                }
                OwnName.SUBPROGRAM -> {
                    state.rollBack(entityMark, relationMark)
                    callOccurrence(name, context, statementId, CallOutcome.Call(Status.UNRESOLVED, null, "LOCAL_SUBPROGRAM"))
                    return
                }
                null -> Unit
            }
        }
        when (value) {
            is SegmentValue.Member -> state.valueOccurrence(span, text, context, statementId, value.status, value.entityKey, value.reason, value.candidates,
                if (attribute || value.kind == null) setOf(Role.READ) else roles.memberRoles(name, segments, value.kind))
            is SegmentValue.Call -> callOccurrence(name, context, statementId, value.outcome)
            null -> Unit
        }
    }

    /** Whether a call outcome is a guess: an external name or a missing member, not a declaration the project found. */
    private fun isExternalGuess(outcome: CallOutcome): Boolean = when (outcome) {
        is CallOutcome.Call -> outcome.status == Status.UNRESOLVED && outcome.reason in GUESSED_REASONS
        is CallOutcome.Value -> outcome.status == Status.UNRESOLVED && outcome.reason in GUESSED_REASONS
        is CallOutcome.ExternalMember -> true
        CallOutcome.NotACall -> false
    }

    /**
     * The names written in the conditional compilation conditions Oracle evaluated, from the skipped-text trivia of
     * [token] ([ConditionNames]): one `READ` per name, resolved by [SegmentValues.segmentValue], at the name's own position, without
     * statement. A condition name is always a package constant, never a local value: one the project and the
     * synonyms do not know is a read of its external package. Closing texts and `$ERROR` give
     * nothing; a text that cannot be read gives a `CONDITION_UNREADABLE` note.
     */
    fun conditionNames(token: Token, context: Context) = conditionNameReads.conditionNames(token, context)

    /** The segments of the name before `%` of a cursor attribute (`c%isopen`, `pkg.c%rowcount`), or null. */
    private fun attributeName(name: AstNode, parts: List<AstNode>): List<String>? {
        val attribute = name.children.lastOrNull()?.takeIf { name.children.indexOf(it) == parts.size + 1 } ?: return null
        if (normalizeIdentifier(attribute.tokenOriginalValue) !in CURSOR_ATTRIBUTES) return null
        val segments = parts.filter { it.type != PlSqlPunctuator.DOT }
        if (segments.isEmpty() || segments.any { it.type is PlSqlPunctuator || tokensOf(it).size != 1 }) return null
        return segments.map { it.tokenOriginalValue }
    }

    /** `pkg.v` or `s.pkg.v` inside the body of `pkg`, where `v` is declared in the body: captured lexically. */
    private fun isOwnBodyMember(segments: List<String>, context: Context): Boolean {
        val enclosing = context.enclosingPackage ?: return false
        if (context.bodyNames.isEmpty() || segments.size < 2) return false
        val names = segments.map { normalizeIdentifier(it) }
        val name = normalizeIdentifier(enclosing.last())
        val member = when {
            names[0] == name -> 1
            names.size >= 3 && names[1] == name && names[0] == context.schema -> 2
            else -> return false
        }
        return names[member] in context.bodyNames
    }

    /** A function called without parentheses: a `CALL` occurrence without actuals. */
    fun callOccurrence(name: AstNode, context: Context, statementId: Int?, outcome: CallOutcome.Call) = state.callOccurrence(name, context, statementId, outcome)

    /**
     * The roles of a value: `WRITE` for an assignment target, an `INTO` target, an actual bound to an `OUT`
     * formal of a resolved call or a modifying collection method; `READ` and `WRITE` for an `IN OUT` formal; else `READ`.
     */
    fun valueRoles(name: AstNode, segments: List<String>): Set<Role> = roles.valueRoles(name, segments)

    /**
     * The sequence [written] names, as `seq.nextval` finds it: the project sequence (`RESOLVED`), else an external
     * `SEQUENCE` entity, `db:S.SEQ` when `S` is a schema of the run and `db:~.…` otherwise (`UNVERIFIED`). With
     * [followSynonym], a one-segment name the project does not declare may be a synonym of the sequence; a DDL
     * statement names the sequence itself.
     */
    fun sequenceEntity(written: List<String>, schema: String?, followSynonym: Boolean): Pair<String, Status> =
        sequenceNames.sequenceEntity(written, schema, followSynonym)

    /**
     * A value or type the legacy table declares outside a package specification and below the file scope: in a
     * block, a subprogram or a package body. zpa-core indexes the package values and types of specifications only, so
     * these are never project declarations.
     */
    fun isLocalDeclaration(symbol: Symbol): Boolean = com.felipebz.zpa.atlas.extract.isLocalDeclaration(symbol)
}

/** Legacy symbol kinds whose names are indexed or called like subprograms but are values or types. */
private val NOT_CALLABLE = setOf(Symbol.Kind.VARIABLE, Symbol.Kind.PARAMETER, Symbol.Kind.CURSOR, Symbol.Kind.TYPE)

private val CURSOR_ATTRIBUTES = setOf("ISOPEN", "FOUND", "NOTFOUND", "ROWCOUNT", "BULK_ROWCOUNT", "BULK_EXCEPTIONS")

/** The reasons of a name the project did not find: an external guess or a missing member of a project package. */
private val GUESSED_REASONS = setOf("NOT_IN_PROJECT", "UNKNOWN_MEMBER")
