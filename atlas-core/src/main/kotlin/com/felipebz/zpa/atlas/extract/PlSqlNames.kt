//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.flr.api.Token
import com.felipebz.zpa.api.DmlGrammar
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.api.PlSqlTokenType
import com.felipebz.zpa.api.project.ProjectAnalysis
import com.felipebz.zpa.api.project.ProjectCallKind
import com.felipebz.zpa.api.project.ProjectParameterMode
import com.felipebz.zpa.api.project.ProjectQualifiedName
import com.felipebz.zpa.api.project.SequenceReferenceResolution
import com.felipebz.zpa.api.squid.SemanticAstNode
import com.felipebz.zpa.api.symbols.Symbol
import com.felipebz.zpa.api.symbols.SymbolTable
import com.felipebz.zpa.atlas.model.*

/**
 * PL/SQL names: calls, value references (package values, sequences, functions called without parentheses), the names
 * of conditional compilation conditions, and the names of an enclosing unit or of the object itself that the project
 * can only guess.
 */
internal class PlSqlNames(
    private val state: FileState,
    private val scopes: SqlScopes,
    private val catalog: DeclarationCatalog,
    private val calls: CallResolver,
    private val values: PackageValues,
    private val synonyms: SynonymResolver,
    private val typeMembers: ObjectTypeMembers,
    private val runSchemas: Set<String>,
    private val jsonBuiltIn: Boolean,
    /** The legacy symbol table of the file. */
    private val symbolTable: SymbolTable,
    private val projectAnalysis: ProjectAnalysis
) {
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
        val kind = if (!isMethodCall || node.parent.type == PlSqlGrammar.CALL_STATEMENT) ProjectCallKind.PROCEDURE else ProjectCallKind.FUNCTION
        val actuals = if (isMethodCall) node.getFirstChildOrNull(PlSqlGrammar.ARGUMENTS)?.getChildren(PlSqlGrammar.ARGUMENT).orEmpty() else emptyList()
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
            when (ownName(callee, segments, context)) {
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
            valueOccurrence(spanOf(callee), writtenText(callee), context, statementId, outcome.status, outcome.entityKey, outcome.reason,
                outcome.candidateKeys, memberRoles(callee, segments, outcome.kind))
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
            CallArgumentFact(index + 1, label?.let { normalizeIdentifier(it) },
                if (outcome.status == Status.RESOLVED) outcome.bindings[index + 1] else null, spanOf(expression))
        }
        state.occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, spanOf(callee), writtenText(callee), outcome.entityKey,
            setOf(Role.CALL), status(outcome.status, context.partial), Origin.STATIC, outcome.reason, outcome.candidateKeys, argumentFacts)
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
        val attribute = name.type == PlSqlGrammar.MEMBER_EXPRESSION && name.hasDirectChildren(PlSqlPunctuator.MOD)
        val parts = if (attribute) name.children.takeWhile { it.type != PlSqlPunctuator.MOD } else listOf(name)
        val segments = (if (attribute) attributeName(name, parts) else nameSegments(name)) ?: return
        val span = spanOf(parts.first(), parts.last())
        val text = parts.joinToString("") { writtenText(it) }
        if (!attribute && name.type == PlSqlGrammar.MEMBER_EXPRESSION && segments.size in 2..3 && normalizeIdentifier(segments.last()) in PSEUDOCOLUMNS) {
            sequenceReference(name, segments, context, statementId)
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
        val value = segmentValue(segments, context, packageName, valueReadable, callable, callAllowed = !attribute)
        if (value is SegmentValue.Member && value.status == Status.UNRESOLVED && value.reason in GUESSED_REASONS) {
            when (ownName(name, segments, context)) {
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
            is SegmentValue.Member -> valueOccurrence(span, text, context, statementId, value.status, value.entityKey, value.reason, value.candidates,
                if (attribute || value.kind == null) setOf(Role.READ) else memberRoles(name, segments, value.kind))
            is SegmentValue.Call -> callOccurrence(name, context, statementId, value.outcome)
            null -> Unit
        }
    }

    /** What a name the project could only guess resolves to, when it names a declaration of an enclosing unit or of the object itself. */
    private enum class OwnName {
        /** A local or private value, a type attribute: nothing to index. */
        VALUE,

        /** A local subprogram or a method of the type: a `LOCAL_SUBPROGRAM` call. */
        SUBPROGRAM
    }

    /** Whether a call outcome is a guess: an external name or a missing member, not a declaration the project found. */
    private fun isExternalGuess(outcome: CallOutcome): Boolean = when (outcome) {
        is CallOutcome.Call -> outcome.status == Status.UNRESOLVED && outcome.reason in GUESSED_REASONS
        is CallOutcome.Value -> outcome.status == Status.UNRESOLVED && outcome.reason in GUESSED_REASONS
        is CallOutcome.ExternalMember -> true
        CallOutcome.NotACall -> false
    }

    /**
     * A name whose leading segments name enclosing units of the place it is written (optionally the schema, the package
     * body, enclosing subprograms: `valida.x`, `pk.valida.x`, `pk.x`) is the rest of the name declared in the innermost
     * unit named, as the unqualified name there. In a type body, `SELF.x` and a name whose first segment is an
     * attribute or method of the type are the object's own members. Null when the name is neither.
     */
    private fun ownName(name: AstNode, segments: List<String>, context: Context): OwnName? {
        val names = segments.map { normalizeIdentifier(it) }
        context.objectType?.let { type ->
            val members = typeMembers.of(type)
            val methods = typeMembers.methods(type)
            if (names[0] == "SELF") {
                val member = names.getOrNull(1) ?: return OwnName.VALUE
                return if (names.size == 2 && (member in methods || isUnitSubprogram(name, member))) OwnName.SUBPROGRAM else OwnName.VALUE
            }
            if (names[0] in members) return if (names.size == 1 && names[0] in methods) OwnName.SUBPROGRAM else OwnName.VALUE
        }
        if (names.size < 2) return null
        val units = enclosingUnits(name)
        if (units.isEmpty()) return null
        var index: Int
        var consumed: Int
        if (names.size > 2 && names[0] == (units[0].schema ?: context.schema) && names[1] == units[0].name) {
            index = 0
            consumed = 2
        } else {
            index = units.indexOfLast { it.name == names[0] }
            if (index < 0) return null
            consumed = 1
        }
        while (consumed < names.size - 1 && index + 1 < units.size && names[consumed] == units[index + 1].name) {
            index++
            consumed++
        }
        val declared = symbolTable.getScopeFor(units[index].node)?.symbols.orEmpty().firstOrNull { it.called(names[consumed]) } ?: return null
        return when (declared.kind) {
            Symbol.Kind.PROCEDURE, Symbol.Kind.FUNCTION -> if (consumed == names.size - 1) OwnName.SUBPROGRAM else OwnName.VALUE
            else -> OwnName.VALUE
        }
    }

    /** A subprogram declared in the type body around [node] (a method body). */
    private fun isUnitSubprogram(node: AstNode, name: String): Boolean {
        val body = node.getFirstAncestorOrNull(PlSqlGrammar.CREATE_TYPE_BODY) ?: return false
        return symbolTable.getScopeFor(body)?.symbols.orEmpty()
            .any { it.called(name) && (it.kind == Symbol.Kind.PROCEDURE || it.kind == Symbol.Kind.FUNCTION) }
    }

    /** A named unit enclosing a name: a package body, a standalone subprogram or a nested subprogram. */
    private class EnclosingUnit(val node: AstNode, val schema: String?, val name: String)

    /** The named units around [node], outermost first. */
    private fun enclosingUnits(node: AstNode): List<EnclosingUnit> {
        val result = ArrayList<EnclosingUnit>()
        var current = node.parentOrNull
        while (current != null) {
            when (current.type) {
                PlSqlGrammar.CREATE_PACKAGE_BODY, PlSqlGrammar.CREATE_PROCEDURE, PlSqlGrammar.CREATE_FUNCTION -> {
                    val written = names(current.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME))
                    if (written.isNotEmpty()) result += EnclosingUnit(current, written.getOrNull(written.size - 2), written.last())
                }
                PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION ->
                    current.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { result += EnclosingUnit(current, null, normalizeIdentifier(it.tokenOriginalValue)) }
            }
            current = current.parentOrNull
        }
        return result.asReversed()
    }

    /** What a dotted value name resolves to from its segments alone, for [segmentValue]. */
    private sealed interface SegmentValue {
        /** A package member or external member; [kind] null for a member whose kind is unknown (read as a value). */
        class Member(val status: Status, val entityKey: String?, val reason: String?, val candidates: List<String>, val kind: MemberKind?) : SegmentValue
        class Call(val outcome: CallOutcome.Call) : SegmentValue
    }

    /**
     * The part of [valueReference] that needs only the segments of a name: a package value, exception or cursor
     * ([valueReadable]); else a project function called without parentheses ([callable], a call only when
     * [callAllowed]), a member of a package or type a synonym names, or a missing member of a project package. Null
     * when the name is none of these.
     */
    private fun segmentValue(
        segments: List<String>, context: Context, packageName: ProjectQualifiedName?, valueReadable: Boolean, callable: Boolean, callAllowed: Boolean
    ): SegmentValue? {
        if (valueReadable) {
            values.packageMember(segments, context.schema, packageName)?.let {
                return SegmentValue.Member(it.status, it.entityKey, it.reason, it.candidateKeys, it.kind)
            }
        }
        if (callable) {
            when (val outcome = calls.resolve(segments, ProjectCallKind.FUNCTION, emptyList(), context.schema, context.enclosingPackage, withoutParentheses = true)) {
                is CallOutcome.Value -> return SegmentValue.Member(outcome.status, outcome.entityKey, outcome.reason, outcome.candidateKeys, outcome.kind)
                // Only a name the project knows is a call: anything else may be a value outside the project.
                is CallOutcome.Call -> if (callAllowed && outcome.reason != "NOT_IN_PROJECT" && !(outcome.reason in INCONCLUSIVE && outcome.entityKey == null)) {
                    return SegmentValue.Call(outcome)
                }
                // A member of a package or type a synonym names, kind unknown: a read of the owner.
                is CallOutcome.ExternalMember -> return SegmentValue.Member(Status.UNRESOLVED, outcome.entityKey, "NOT_IN_PROJECT", emptyList(), null)
                CallOutcome.NotACall -> Unit
            }
        }
        if (segments.size >= 2 && values.isPackageMember(segments, context.schema)) {
            return SegmentValue.Member(Status.UNRESOLVED, null, "UNKNOWN_MEMBER", emptyList(), null)
        }
        return null
    }

    /**
     * The names written in the conditional compilation conditions Oracle evaluated, from the skipped-text trivia of
     * [token] ([ConditionNames]): one `READ` per name, resolved by [segmentValue], at the name's own position, without
     * statement. A condition name is always a package constant, never a local value: one the project and the
     * synonyms do not know is a read of its external package ([externalOwner]). Closing texts and `$ERROR` give
     * nothing; a text that cannot be read gives a `CONDITION_UNREADABLE` note.
     */
    fun conditionNames(token: Token, context: Context) {
        for (trivia in token.trivia) {
            if (!trivia.isSkippedText) continue
            val skipped = trivia.token
            val whole = when (skipped.type) {
                PlSqlTokenType.CONDITIONAL_BRANCH_BOUNDARY -> false
                PlSqlTokenType.CONDITIONAL_DIRECTIVE -> true
                else -> continue
            }
            val text = skipped.originalValue
            val names = ConditionNames.read(text, whole) ?: run {
                state.coverage += CoverageFact("CONDITION_UNREADABLE", "Conditional compilation text at line ${skipped.line} could not be read: its condition names are not indexed")
                continue
            }
            val packageName = context.enclosingPackage?.let { qualifiedName(it) }
            for (name in names) {
                val (startLine, startColumn) = lineAt(skipped, text, name.start)
                val (endLine, endColumn) = lineAt(skipped, text, name.end)
                val span = SourceSpan(startLine, startColumn, endLine, endColumn)
                val written = text.substring(name.start, name.end)
                when (val value = segmentValue(name.segments, context, packageName, valueReadable = true, callable = true, callAllowed = true)) {
                    is SegmentValue.Member -> valueOccurrence(span, written, context, null, value.status, value.entityKey, value.reason, value.candidates, setOf(Role.READ))
                    is SegmentValue.Call -> valueOccurrence(span, written, context, null, value.outcome.status, value.outcome.entityKey, value.outcome.reason,
                        value.outcome.candidateKeys, setOf(Role.READ))
                    null -> externalOwner(name.segments, context)?.let {
                        valueOccurrence(span, written, context, null, Status.UNRESOLVED, it, "NOT_IN_PROJECT", emptyList(), setOf(Role.READ))
                    }
                }
            }
        }
    }

    /**
     * The external package that owns a condition name the project does not know: `db:S.PKG` for `S.PKG.C` when `S` is
     * a schema of the run, else `db:~.PKG` (as [ExternalNames] keys the owner of an unknown name). Null when that
     * package is a project declaration (the member is then simply missing).
     */
    private fun externalOwner(segments: List<String>, context: Context): String? {
        val names = segments.map { normalizeIdentifier(it) }
        val schemaQualified = names.size == 3 && names[0] in runSchemas
        val schema = if (schemaQualified) names[0] else null
        val name = if (schemaQualified) names[1] else names[0]
        if (catalog.isDeclared(dbKey(schema ?: context.schema ?: UNKNOWN_SCHEMA, name))) return null
        val key = dbKey(schema ?: UNKNOWN_SCHEMA, name)
        state.entities += EntityFact(key, EntityKind.PACKAGE, schema, name, null, key.removePrefix("db:"), external = true, precedence = Precedence.MENTIONED)
        return key
    }

    /** The line and column of [offset] inside the text of [token], with the lexer's line breaks (LF, or CR not followed by LF). */
    private fun lineAt(token: Token, text: String, offset: Int): Pair<Int, Int> {
        var line = token.line
        var column = token.column
        for (i in 0 until offset) {
            val c = text[i]
            if (c == '\n' || c == '\r' && text.getOrNull(i + 1) != '\n') {
                line++
                column = 0
            } else {
                column++
            }
        }
        return line to column
    }

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

    private fun valueOccurrence(span: SourceSpan, written: String, context: Context, statementId: Int?, status: Status, entityKey: String?,
                                reason: String?, candidates: List<String>, roles: Set<Role>) {
        state.occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, span, written, entityKey,
            roles, status(status, context.partial), Origin.STATIC, reason, candidates)
    }

    /** A function called without parentheses: a `CALL` occurrence without actuals. */
    fun callOccurrence(name: AstNode, context: Context, statementId: Int?, outcome: CallOutcome.Call) {
        state.occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, spanOf(name), writtenText(name), outcome.entityKey,
            setOf(Role.CALL), status(outcome.status, context.partial), Origin.STATIC, outcome.reason, outcome.candidateKeys)
    }

    /**
     * The roles of a package member: for an exception `RAISE` as the exception of a `RAISE` statement and `HANDLE`
     * as a name of an exception handler (the direct parent only), else `READ`; for a cursor `READ`; for a value
     * [valueRoles].
     */
    private fun memberRoles(name: AstNode, segments: List<String>, kind: MemberKind): Set<Role> = when (kind) {
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

    /**
     * `seq.nextval`, `s.seq.currval`: a project sequence, else an external `SEQUENCE` entity, `db:S.SEQ` when
     * `S` is a schema of the run and `db:~.…` otherwise (the dictionary does not know sequences). Not a sequence when the
     * first segment is captured lexically, is an SQL qualifier, or the legacy table binds the whole name.
     */
    private fun sequenceReference(name: AstNode, segments: List<String>, context: Context, statementId: Int?) {
        val symbol = (name.firstChild as? SemanticAstNode)?.symbol
        if (symbol != null && symbol.kind != Symbol.Kind.PACKAGE && isLocalDeclaration(symbol)) return
        if (statementId != null && scopes.isSqlQualified(name, segments)) return
        if (projectAnalysis.resolveSequenceReference(name) == SequenceReferenceResolution.RESOLVED_NON_SEQUENCE) return
        val written = segments.dropLast(1)
        val children = name.children
        val lastDot = children.indexOfLast { it.type == PlSqlPunctuator.DOT }
        val nameParts = children.subList(0, lastDot)
        val text = nameParts.joinToString("") { writtenText(it) }
        val span = spanOf(nameParts.first(), nameParts.last())
        // A sequence the project does not declare may be named by a synonym: `syn_seq.nextval` reads the target.
        val target = if (values.sequence(written, context.schema) == null && written.size == 1) {
            synonyms.follow(normalizeIdentifier(written[0]), context.schema, SynonymResolver.SEQUENCES)
        } else {
            null
        }
        val resolvedName = target?.let { SynonymResolver.written(it, emptyList()) } ?: written
        val declared = values.sequence(resolvedName, context.schema)
        val (key, status) = if (declared != null) {
            declared to Status.RESOLVED
        } else {
            val names = resolvedName.map { normalizeIdentifier(it) }
            val schemaQualified = names.size == 2 && (names[0] in runSchemas || target != null)
            val key = if (schemaQualified) dbKey(names[0], names[1]) else dbKey(UNKNOWN_SCHEMA, *names.toTypedArray())
            state.entities += EntityFact(key, EntityKind.SEQUENCE, if (schemaQualified) names[0] else null, names.last(), null, key.removePrefix("db:"),
                external = true, precedence = Precedence.MENTIONED)
            key to Status.UNVERIFIED
        }
        state.occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, span, text, key,
            setOf(Role.READ), status(status, context.partial), Origin.STATIC, null)
    }

    /**
     * A subprogram the legacy table declares outside a package scope and below the file scope: nested in a standalone
     * unit, a subprogram or a block. `Symbol.isGlobal` is not used: it is true for subprograms nested in standalone
     * units, because their scope's outer scope is the file scope. The unit itself (recursion) is declared in the file
     * scope and package members in the package spec or body scope; those are not local.
     */
    private fun isLocalSubprogram(symbol: Symbol): Boolean {
        val scope = symbol.scope
        if (scope.outer == null) return false
        return scope.type != PlSqlGrammar.CREATE_PACKAGE && scope.type != PlSqlGrammar.CREATE_PACKAGE_BODY
    }

    /**
     * A value or type the legacy table declares outside a package specification and below the file scope: in a
     * block, a subprogram or a package body. zpa-core indexes the package values and types of specifications only, so
     * these are never project declarations.
     */
    fun isLocalDeclaration(symbol: Symbol): Boolean {
        val scope = symbol.scope
        return scope.outer != null && scope.type != PlSqlGrammar.CREATE_PACKAGE
    }
}

/** Legacy symbol kinds whose names are indexed or called like subprograms but are values or types. */
private val NOT_CALLABLE = setOf(Symbol.Kind.VARIABLE, Symbol.Kind.PARAMETER, Symbol.Kind.CURSOR, Symbol.Kind.TYPE)

private val CURSOR_ATTRIBUTES = setOf("ISOPEN", "FOUND", "NOTFOUND", "ROWCOUNT", "BULK_ROWCOUNT", "BULK_EXCEPTIONS")

/** Collection methods that change the collection. */
private val MODIFYING_METHODS = setOf("DELETE", "EXTEND", "TRIM")

/** Call statuses that say nothing about a name when no declaration was selected. */
private val INCONCLUSIVE = setOf("INCOMPLETE_INDEX", "PARTIAL_DECLARATIONS")

/** The reasons of a name the project did not find: an external guess or a missing member of a project package. */
private val GUESSED_REASONS = setOf("NOT_IN_PROJECT", "UNKNOWN_MEMBER")
