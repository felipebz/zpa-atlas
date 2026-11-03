//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.*
import com.felipebz.zpa.api.project.ProjectParameterMode
import com.felipebz.zpa.api.semantics.NameBinding
import com.felipebz.zpa.api.semantics.NameDeclarationKind
import com.felipebz.zpa.atlas.model.*

/**
 * The `INTO`s that read select items into PL/SQL targets ([IntoFact]): `SELECT … INTO`, `FETCH … INTO` of an explicit
 * cursor, `RETURNING … INTO`, with or without `BULK COLLECT`, and the assignments `target := r.f` of a field of a
 * record, which is a row record when it is a cursor `FOR` loop record or a `%ROWTYPE` variable (decided after the
 * scan). A select item is a column when it is the column itself, written bare or qualified (the column read static SQL
 * records at it), or a `*` over a relation whose columns the dictionary lists; an expression of one column is counted by
 * its shape. A target is the entity zpa binds its name to (the value use the walk records), else the declaration
 * without an entity zpa binds it to, with its own type.
 *
 * Other reads of a column into PL/SQL are counted, not recorded ([FileState.placesLeftOut]): dynamic `INTO`, a field
 * of a `%ROWTYPE` or cursor loop record passed to a routine, a column passed to a routine in static SQL, and a `FETCH`
 * from a cursor whose query is not known.
 */
internal class IntoClauses(
    private val state: FileState,
    private val plsql: PlSqlNames,
    private val columnReads: ColumnReads,
    private val nameBinding: (AstNode) -> NameBinding
) {
    private class Pending(
        val source: PlaceSource, val bulk: Boolean, val context: Context, val statementId: Int?, val branches: List<List<QueryItem>>,
        val cursor: AstNode?, val targets: List<AstNode>, val fetch: AstNode? = null
    )

    private val pending = ArrayList<Pending>()

    /** The select items of [statement]'s cursor query, its `INTO` or `RETURNING INTO`, and the columns it passes to routines. */
    fun statement(statement: Statement) {
        val root = statement.root
        if (statement.context.dynamic != null) return
        if (root.parentOrNull?.type == PlSqlGrammar.CURSOR_DECLARATION || isLoopQuery(root)) {
            branches(root).firstOrNull()?.let { state.cursorItems[root] = selectItems(statement, it) }
        }
        when (root.type) {
            DmlGrammar.SELECT_EXPRESSION -> root.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK)?.getFirstChildOrNull(DmlGrammar.INTO_CLAUSE)?.let { into ->
                pending += Pending(PlaceSource.SELECT_INTO, isBulk(into), statement.context, statement.id,
                    branches(root).map { selectItems(statement, it) }, null, targetsOf(into))
                for (block in branches(root)) {
                    selectedParameters(statement, block.getChildren(DmlGrammar.SELECT_COLUMN)
                        .map { it.firstChild }, into)
                }
            }
            DmlGrammar.INSERT_EXPRESSION, DmlGrammar.UPDATE_EXPRESSION, DmlGrammar.DELETE_EXPRESSION ->
                root.getFirstDescendantOrNull(DmlGrammar.RETURNING_INTO_CLAUSE)?.let { returning ->
                    val into = returning.getFirstChild(DmlGrammar.INTO_CLAUSE)
                    val items = returning.children.filter { it.type != PlSqlKeyword.RETURNING && it.type != PlSqlKeyword.RETURN && it.type != PlSqlPunctuator.COMMA && it !== into }
                        .map { item(statement, it, null) }
                    pending += Pending(PlaceSource.RETURNING, isBulk(into), statement.context, statement.id, listOf(items), null, targetsOf(into))
                    selectedParameters(statement, returning.children.filter { it.type != PlSqlKeyword.RETURNING && it.type != PlSqlKeyword.RETURN &&
                        it.type != PlSqlPunctuator.COMMA && it !== into }, into)
                }
        }
        if (root.parentOrNull?.type == PlSqlGrammar.CURSOR_DECLARATION) {
            // A cursor that selects a parameter: what a FETCH reads of it is not followed.
            for (block in branches(root)) for (column in block.getChildren(DmlGrammar.SELECT_COLUMN)) {
                for (name in parameterNames(column.firstChild)) {
                    if (columnOf(statement, name) == null) flows += Flow(name, spanOf(column), statement.context, shape = ARGUMENT_FETCHED)
                }
            }
        }
        passedColumns(statement)
    }

    /**
     * The routine's `IN` parameters a `SELECT … INTO` or `RETURNING … INTO` reads ([values], the items in order), as is or
     * through value functions: each is written as a column in the same position would be, into its own target, or into
     * the single target's field or element ([intos] keeps the position). A parameter in an item that is another
     * expression, or in an `INTO` whose targets do not match the items, is written in a way not followed.
     */
    private fun selectedParameters(statement: Statement, values: List<AstNode>, into: AstNode) {
        val targets = targetsOf(into)
        val single = targets.size == 1
        for ((index, value) in values.withIndex()) {
            for ((name, functions) in parameterChains(value, parameterNames(value).filter { columnOf(statement, it) == null })) {
                flows += when {
                    functions == null -> Flow(name, spanOf(value), statement.context, shape = ARGUMENT_EXPRESSION + argumentShape(value))
                    !single && targets.size != values.size -> Flow(name, spanOf(value), statement.context, shape = ARGUMENT_INTO_NOT_FOLLOWED)
                    else -> Flow(name, spanOf(value), statement.context, target = targets[if (single) 0 else index], functions = functions,
                        bulk = isBulk(into), item = if (single) index to values.size else null)
                }
            }
        }
    }

    /**
     * A routine whose body is not PL/SQL the index reads: a call spec (`LANGUAGE JAVA`, `LANGUAGE C`, `EXTERNAL`), an
     * aggregate (`AGGREGATE USING`) or a polymorphic table function (`PIPELINED … USING`). Its parameters are marked so;
     * what its callers give them is counted at each call, as for a routine outside the index.
     */
    fun routine(node: AstNode, context: Context) {
        val outside = node.hasDirectChildren(PlSqlGrammar.CALL_SPECIFICATION) || node.hasDirectChildren(PlSqlKeyword.AGGREGATE) ||
            node.children.any { it.hasDirectChildren(PlSqlKeyword.PIPELINED) && it.hasDirectChildren(PlSqlKeyword.USING) } ||
            node.hasDirectChildren(PlSqlKeyword.PIPELINED) && node.hasDirectChildren(PlSqlKeyword.USING)
        if (!outside) return
        // The routine's parameters, by the routine's key (the context is the routine's).
        val routine = context.container ?: return
        for (parameter in node.getFirstChildOrNull(PlSqlGrammar.PARAMETER_DECLARATIONS)?.getChildren(PlSqlGrammar.PARAMETER_DECLARATION).orEmpty()) {
            val name = parameter.firstChild
            flows += Flow(name, spanOf(name), context, shape = BODY_NOT_PLSQL, fromKey = "$routine.${normalizeIdentifier(name.tokenOriginalValue)}")
        }
    }

    /** `FETCH c INTO …`: the cursor's query decides the items (its own file's, for a cursor with an entity). */
    fun fetch(node: AstNode, context: Context) {
        if (context.dynamic != null) return
        val into = node.getFirstChildOrNull(DmlGrammar.INTO_CLAUSE) ?: return
        val cursor = node.children.firstOrNull { it.type == PlSqlGrammar.MEMBER_EXPRESSION || it.type == PlSqlGrammar.VARIABLE_NAME } ?: return
        pending += Pending(PlaceSource.FETCH, isBulk(into), context, null, emptyList(), cursor, targetsOf(into), node)
    }

    /**
     * What a `FETCH` ([fetch]) reads from the cursor variable [cursor], and the entity of its type when it names one.
     * zpa binds no name of a `FETCH` or `OPEN`: the variable is the declaration of that name nearest the `FETCH`. The
     * kind: `STRONG` for a `REF CURSOR` type of this file with `RETURN`, `OPENED_STATIC` when the routine opens it `FOR`
     * static queries only, `OPENED_DYNAMIC` when it opens it `FOR` a string, `PARAMETER` for a parameter it does not
     * open (the routine or block that declares the variable); null for anything else.
     */
    private fun cursorKind(fetch: AstNode, cursor: AstNode, shapes: TypeShapes): Pair<String?, String?> {
        val declaration = cursorDeclaration(cursor) ?: return null to null
        var shape = shapes.of(declaration, null)
        if (shape is TypeShape.OutParameter) shape = shape.type
        val type = (shape as? TypeShape.Named)?.typeKey
        if (shape?.let(::isStrongCursor) == true) return "STRONG" to type
        // The routine or block that declares the variable (its parameters, or its declare section).
        val scope = declaration.parentOrNull?.parentOrNull
        val opens = scope?.getDescendants(PlSqlGrammar.OPEN_FOR_STATEMENT).orEmpty().filter { open ->
            open.children.firstOrNull { it.type == PlSqlGrammar.MEMBER_EXPRESSION || it.type == PlSqlGrammar.VARIABLE_NAME }
                ?.let(::cursorDeclaration) === declaration
        }
        return when {
            opens.isEmpty() -> if (declaration.type == PlSqlGrammar.PARAMETER_DECLARATION) "PARAMETER" else null
            opens.all { it.hasDirectChildren(DmlGrammar.SELECT_EXPRESSION) } -> "OPENED_STATIC"
            else -> "OPENED_DYNAMIC"
        } to type
    }

    /** The variable or parameter declaration of the simple name [cursor] nearest it, in the blocks and routines around it. */
    private fun cursorDeclaration(cursor: AstNode): AstNode? {
        if (cursor.type != PlSqlGrammar.VARIABLE_NAME) return null
        val name = normalizeIdentifier(cursor.tokenOriginalValue)
        for (scope in generateSequence(cursor.parentOrNull) { it.parentOrNull }) {
            val declarations = scope.getChildren(PlSqlGrammar.DECLARE_SECTION).flatMap { it.getChildren(PlSqlGrammar.VARIABLE_DECLARATION) } +
                scope.getChildren(PlSqlGrammar.PARAMETER_DECLARATIONS).flatMap { it.getChildren(PlSqlGrammar.PARAMETER_DECLARATION) }
            declarations.firstOrNull { normalizeIdentifier(it.firstChild.tokenOriginalValue) == name }?.let { return it }
        }
        return null
    }

    /** `EXECUTE IMMEDIATE … INTO` or `RETURNING INTO`: counted, one per target. */
    fun dynamic(node: AstNode) {
        for (into in node.getDescendants(DmlGrammar.INTO_CLAUSE)) state.leftOut(DYNAMIC_INTO, targetsOf(into).size)
    }

    /**
     * `target := r.f`, or an expression of one such field (`upper(r.f)`): kept to be decided once the file is left,
     * when what `r` is is known ([intos]). Through value functions only ([valueChain]), the field is read as is.
     */
    fun assignment(node: AstNode, context: Context) {
        val at = node.children.indexOfFirst { it.type == PlSqlPunctuator.ASSIGNMENT }
        if (at <= 0) return
        val value = node.children.getOrNull(at + 1) ?: return
        valueWrites += ValueWrite(value, spanOf(node.children[at]), node.firstChild, context)
        val fields = if (isFieldOfName(value)) listOf(value) else value.getDescendants(PlSqlGrammar.MEMBER_EXPRESSION).filter(::isFieldOfName)
        val field = fields.singleOrNull()
        if (field == null) {
            // Several fields: followed when each is a part of a concatenation (`r.code || r.note`), a place for each.
            val chains = fields.map { f -> valueChain(value) { it === f } }
            if (fields.isEmpty() || chains.any { it == null || CONCATENATED !in it }) return
            for ((f, chain) in fields.zip(chains)) assignments += Assignment(node.firstChild, spanOf(node.children[at]), f, null, chain!!, context)
            return
        }
        val functions = valueChain(value) { it === field }
        assignments += Assignment(node.firstChild, spanOf(node.children[at]), field, if (functions == null) value else null,
            functions.orEmpty(), context)
    }

    private class Assignment(
        val target: AstNode, val site: SourceSpan, val field: AstNode, val expression: AstNode?, val functions: List<String>, val context: Context
    )

    private val assignments = ArrayList<Assignment>()

    /** `RETURN expr`: its value leaves the routine, which the index does not follow ([intos]). */
    fun returnStatement(node: AstNode, context: Context) {
        val value = node.children.firstOrNull { it.type != PlSqlGrammar.LABELS && it.type != PlSqlKeyword.RETURN && it.type != PlSqlPunctuator.SEMICOLON }
            ?: return
        valueWrites += ValueWrite(value, spanOf(node), null, context)
    }

    /**
     * A value written in PL/SQL: assigned to [target] (`target := value`, at the `:=`), or returned (null [target]). The
     * routine's `IN` parameters it writes are told once the file is left, when its calls are known ([parameterFlows]).
     */
    private class ValueWrite(val value: AstNode, val site: SourceSpan, val target: AstNode?, val context: Context)

    private val valueWrites = ArrayList<ValueWrite>()

    /**
     * A column, or a record's field, passed to a routine's `IN` parameter ([parameterKey], null when the call does not
     * tie the argument to one), through [functions]; [reason] when the call is not followed.
     */
    private class Pass(
        val argument: AstNode, val column: String?, val field: AstNode?, val functions: List<String>, val parameterKey: String?,
        val reason: String?, val context: Context, val statementId: Int?
    )

    private val passes = ArrayList<Pass>()

    /**
     * A routine's `IN` parameter ([parameter], its name in the code) written somewhere: into [target], passed on to
     * [passedTo], or written in a way the index does not follow ([shape]), through [functions].
     */
    private class Flow(
        val parameter: AstNode, val site: SourceSpan, val context: Context, val target: AstNode? = null, val passedTo: String? = null,
        val shape: String? = null, val functions: List<String> = emptyList(), val fromKey: String? = null, val bulk: Boolean = false,
        /** For the single target of an `INTO`: the parameter's item and the number of items. */
        val item: Pair<Int, Int>? = null
    )

    private val flows = ArrayList<Flow>()

    /**
     * Whether [name] is a whole name zpa binds to a parameter (not the record of `p.f`, not a called routine). In SQL
     * the name may still be a column of the statement's tables ([NameBinding.ColumnOrLocal]): [intos] decides by them.
     */
    private fun isParameterName(name: AstNode) =
        name.type == PlSqlGrammar.VARIABLE_NAME && name.parentOrNull?.type != PlSqlGrammar.MEMBER_EXPRESSION &&
            parameterDeclaration(name)?.kind == NameDeclarationKind.PARAMETER

    /** The declaration zpa binds [name] to, in PL/SQL or in SQL where a column may take the name instead. */
    private fun parameterDeclaration(name: AstNode) = when (val binding = nameBinding(name)) {
        is NameBinding.Local -> binding.declaration
        is NameBinding.ColumnOrLocal -> binding.declaration
        else -> null
    }

    /** The names of parameters in [value], [value] itself included. */
    private fun parameterNames(value: AstNode) = (sequenceOf(value) + value.getDescendants(PlSqlGrammar.VARIABLE_NAME).asSequence()).filter(::isParameterName).toList()

    /** Whether [query] is the query of a cursor `FOR` loop (`for r in (select …)`). */
    private fun isLoopQuery(query: AstNode): Boolean {
        var node = query.parentOrNull
        repeat(6) {
            if (node == null) return false
            if (node!!.type == PlSqlGrammar.ITERATOR) return true
            node = node!!.parentOrNull
        }
        return false
    }

    /**
     * A PL/SQL call resolved to a routine: a field of a `%ROWTYPE` or cursor loop record it is passed is counted per
     * argument; an argument for an `OUT` or `IN OUT` parameter is kept, its target decided once the file is left.
     */
    fun call(node: AstNode, before: Int, context: Context) {
        if (node.type != PlSqlGrammar.METHOD_CALL) return
        val calls = state.occurrences.subList(before, state.occurrences.size).filter { Role.CALL in it.roles }
        // A subprogram nested in the routine has no entity: its call is bound here, in the file that declares both.
        if (calls.none { it.entityKey != null } && calls.any { it.reason == LOCAL_SUBPROGRAM }) {
            localCall(node, context)
            return
        }
        val resolved = calls.lastOrNull { it.entityKey != null && it.status in CALLED_STATUSES }
        if (resolved == null) {
            // A call the index does not decide: what it is passed is not followed (a built-in function is no routine).
            val reason = calls.firstNotNullOfOrNull(::unresolvedCall)
            if (reason != null) for (argument in arguments(node)) passedValue(argument, null, reason, context)
            return
        }
        for (argument in arguments(node)) {
            // The walk keeps the mode at the argument's expression node, which may wrap or be wrapped by this one.
            val span = spanOf(argument)
            val mode = (generateSequence(argument) { it.parentOrNull }.take(3) + generateSequence(argument) { it.children.firstOrNull() }.take(4))
                .filter { it.hasToken() && spanOf(it) == span }.firstNotNullOfOrNull { state.argumentModes[it] }
            val parameter = resolved.arguments.firstOrNull { it.span == span }?.parameterKey
            if (mode == ProjectParameterMode.OUT || mode == ProjectParameterMode.IN_OUT) {
                if (parameter != null) outArguments += OutArgument(argument, parameter, context)
                continue
            }
            passedValue(argument, parameter, if (parameter == null) notTied(resolved) else null, context)
        }
    }

    /**
     * An argument of a PL/SQL call for an `IN` parameter ([parameter]): a record's field, read as is or through value
     * functions, is a column's value passed to the routine (decided after the scan); one of the routine's own `IN`
     * parameters is passed on.
     */
    private fun passedValue(argument: AstNode, parameter: String?, reason: String?, context: Context) {
        val field = if (isFieldOfName(argument)) argument else argument.getDescendants(PlSqlGrammar.MEMBER_EXPRESSION).singleOrNull(::isFieldOfName)
        if (field != null) {
            valueChain(argument) { it === field }?.let { passes += Pass(argument, null, field, it, parameter, reason, context, null) }
            return
        }
        for (name in parameterNames(argument)) {
            val functions = valueChain(argument) { it === name } ?: continue
            flows += if (reason == null && parameter != null) Flow(name, spanOf(argument), context, passedTo = parameter, functions = functions)
                else Flow(name, spanOf(argument), context, shape = reason ?: ARGUMENT_NOT_TIED)
        }
    }

    /**
     * A call of a subprogram nested in a routine: the subprogram of that name in the nearest declarations around the
     * call; its arguments bound to its parameters by position, then by name. When several subprograms of that name are
     * declared there (overloads), the call is kept for each of their `OUT` parameters as not decided, counted
     * (`OUT_CALL_NOT_DECIDED`) when a column's value reaches one of them.
     */
    private fun localCall(node: AstNode, context: Context) {
        val callee = node.firstChild.takeIf { it.type == PlSqlGrammar.VARIABLE_NAME } ?: return
        val wanted = normalizeIdentifier(callee.tokenOriginalValue)
        var scope = node.parentOrNull
        var subprograms = emptyList<AstNode>()
        while (scope != null && subprograms.isEmpty()) {
            subprograms = scope.children.asSequence()
                .flatMap { if (it.type == PlSqlGrammar.DECLARE_SECTION) it.children.asSequence() else sequenceOf(it) }
                .filter { (it.type == PlSqlGrammar.PROCEDURE_DECLARATION || it.type == PlSqlGrammar.FUNCTION_DECLARATION) &&
                    it.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { name -> normalizeIdentifier(name.tokenOriginalValue) } == wanted }
                .toList()
            scope = scope.parentOrNull
        }
        fun parametersOf(subprogram: AstNode) =
            subprogram.getFirstChildOrNull(PlSqlGrammar.PARAMETER_DECLARATIONS)?.getChildren(PlSqlGrammar.PARAMETER_DECLARATION).orEmpty()
        fun isOut(parameter: AstNode) = parameter.hasDirectChildren(PlSqlKeyword.OUT)
        val subprogram = subprograms.singleOrNull()
        if (subprogram == null) {
            for (parameter in subprograms.flatMap(::parametersOf).filter(::isOut)) undecidedCalls += spanOf(node) to localKey(parameter)
            if (subprograms.isNotEmpty()) for (argument in arguments(node)) passedValue(argument, null, ARGUMENT_LOCAL_SUBPROGRAM, context)
            return
        }
        val parameters = parametersOf(subprogram)
        val written = node.getFirstChildOrNull(PlSqlGrammar.ARGUMENTS)?.getChildren(PlSqlGrammar.ARGUMENT).orEmpty()
        for ((index, argument) in written.withIndex()) {
            val label = argument.takeIf { it.hasDirectChildren(PlSqlPunctuator.ASSOCIATION) }?.firstChild?.let { normalizeIdentifier(it.tokenOriginalValue) }
            val parameter = if (label == null) parameters.getOrNull(index)
                else parameters.firstOrNull { normalizeIdentifier(it.firstChild.tokenOriginalValue) == label }
            if (parameter != null && isOut(parameter)) outArguments += OutArgument(argument.lastChild, localKey(parameter), context)
            // A nested subprogram's IN parameter: followed by where it is declared in the file.
            else passedValue(argument.lastChild, parameter?.let(::localKey), if (parameter == null) ARGUMENT_NOT_TIED else null, context)
        }
    }

    /** The key of the nested subprogram's parameter zpa binds [name] to, which has no entity ([localKey]); null for any other. */
    private fun localParameterKey(name: AstNode, shapes: TypeShapes): String? {
        val declaration = parameterDeclaration(name)?.takeIf { it.fileId == null } ?: return null
        return shapes.localDeclaration(declaration.line, declaration.column)?.takeIf { it.type == PlSqlGrammar.PARAMETER_DECLARATION }?.let(::localKey)
    }

    /** Where a nested subprogram's parameter is named in the file: `local:line:column`. */
    private fun localKey(parameter: AstNode): String = parameter.firstChild.token.let { "$LOCAL_PREFIX${it.line}:${it.column}" }

    private class OutArgument(val value: AstNode, val parameterKey: String, val context: Context)

    /** The calls of overloaded nested subprograms, with each `OUT` parameter they may be for. */
    private val undecidedCalls = ArrayList<Pair<SourceSpan, String>>()

    private val outArguments = ArrayList<OutArgument>()


    private fun arguments(call: AstNode): List<AstNode> =
        call.getFirstChildOrNull(PlSqlGrammar.ARGUMENTS)?.getChildren(PlSqlGrammar.ARGUMENT).orEmpty().map { it.lastChild }

    private fun isFieldOfName(value: AstNode) =
        value.type == PlSqlGrammar.MEMBER_EXPRESSION && value.numberOfChildren == 3 && value.firstChild.type == PlSqlGrammar.VARIABLE_NAME

    /**
     * Why a call the index has no routine for is not followed: among candidates (`ARGUMENT_ROUTINE_UNDECIDED`), or
     * qualified by a package or schema the sources do not have (`ARGUMENT_ROUTINE_OUTSIDE`); null for an unqualified
     * name, which is a built-in function (`upper`, `count`) as often as a routine outside the index.
     */
    private fun unresolvedCall(call: OccurrenceFact): String? = when {
        call.status == Status.AMBIGUOUS -> ARGUMENT_ROUTINE_UNDECIDED
        call.status == Status.EXTERNAL -> ARGUMENT_ROUTINE_OUTSIDE
        '.' in call.written -> ARGUMENT_ROUTINE_OUTSIDE
        else -> null
    }

    /**
     * Why a resolved call does not tie an argument to a parameter: an object type's constructor (its key names the type,
     * with no parameter list), whose arguments go to attributes (`ARGUMENT_CONSTRUCTOR`); else `ARGUMENT_NOT_TIED`.
     */
    private fun notTied(call: OccurrenceFact) =
        if (call.entityKey?.let(ROUTINE_KEY::containsMatchIn) == true) ARGUMENT_NOT_TIED else ARGUMENT_CONSTRUCTOR

    /** The column [node] reads in [statement], when it is a column read that names one. */
    private fun columnOf(statement: Statement, node: AstNode) =
        statement.columnReads[node].orEmpty().singleOrNull()?.takeIf { it.entityKey != null && it.status in COLUMN_STATUSES }?.entityKey

    /**
     * In static SQL, a column read passed to a routine (not a built-in SQL function), as is or through value functions:
     * kept with the `IN` parameter the call ties it to, or with why it is not followed (the routine is not decided, it
     * is outside the index, or the call does not tie the argument to a parameter).
     */
    private fun passedColumns(statement: Statement) {
        val calls = state.occurrences.filter { it.statementLocalId == statement.id && Role.CALL in it.roles }.associateBy { it.span }
        if (calls.isEmpty()) return
        for (call in statement.root.getDescendants(PlSqlGrammar.METHOD_CALL)) {
            val occurrence = calls[spanOf(call.firstChild)] ?: continue
            for (argument in arguments(call)) {
                val item = item(statement, argument, null)
                for (part in listOf(item) + item.parts) {
                val column = part.columnKey ?: continue
                val functions = part.functions
                val parameter = occurrence.arguments.firstOrNull { it.span == spanOf(argument) }?.parameterKey
                val reason = when {
                    occurrence.entityKey == null || occurrence.status !in CALLED_STATUSES -> unresolvedCall(occurrence) ?: continue
                    parameter == null -> notTied(occurrence)
                    else -> null
                }
                passes += Pass(argument, column, null, functions, parameter, reason, statement.context, statement.id)
                }
            }
        }
    }

    /**
     * The parameters among [names] in [value], once each, with the value functions [value] reads it through (null when
     * it is another expression): a parameter named twice (`upper(p) || nvl(p, '?')`) is told by its first name the
     * value is read through.
     */
    private fun parameterChains(value: AstNode, names: List<AstNode>): List<Pair<AstNode, List<String>?>> =
        names.groupBy { parameterDeclaration(it) }.values.map { same ->
            same.firstNotNullOfOrNull { name -> valueChain(value) { it === name }?.let { name to it } } ?: (same.first() to null)
        }

    /** The query blocks of a compound query, in order, parenthesized branches flattened. */
    private fun branches(query: AstNode): List<AstNode> = query.getChildren(DmlGrammar.QUERY_BLOCK).flatMap { block ->
        if (block.firstChild.type == PlSqlPunctuator.LPARENTHESIS) block.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)?.let { branches(it) }.orEmpty()
        else listOf(block)
    }

    private fun isBulk(into: AstNode) = into.hasDirectChildren(PlSqlKeyword.BULK)

    private fun targetsOf(into: AstNode): List<AstNode> = into.children
        .filter { it.type != PlSqlKeyword.BULK && it.type != PlSqlKeyword.COLLECT && it.type != PlSqlKeyword.INTO && it.type != PlSqlPunctuator.COMMA }
        .map { if (it.type == PlSqlGrammar.OBJECT_REFERENCE && it.numberOfChildren == 1) it.firstChild else it }

    /** The select list of [block], a `*` expanded to the columns of a relation the dictionary lists completely. */
    private fun selectItems(statement: Statement, block: AstNode): List<QueryItem> {
        val result = ArrayList<QueryItem>()
        for (column in block.getChildren(DmlGrammar.SELECT_COLUMN)) {
            val sources = columnReads.starSources(statement, column)
            if (sources == null) {
                result += item(statement, column.firstChild, column.children.drop(1).lastOrNull { it.type == PlSqlGrammar.IDENTIFIER_NAME }, block)
                continue
            }
            for (source in sources) {
                val relation = source.target?.resolved?.relation?.takeIf { it.isColumnsComplete }
                if (relation == null) {
                    result += QueryItem(null, null, null, opaque = true)
                    continue
                }
                for (field in relation.rowFields.sortedBy { it.position.asInt }) {
                    result += QueryItem(field.name.exact(), source.target.resolved.entityKey + "." + field.name.exact(), null)
                }
            }
        }
        return result
    }

    /**
     * One select or `RETURNING` item: the column it is, read as is or through value functions ([valueChain]), the shape
     * of another expression of one column, or neither.
     */
    private fun item(statement: Statement, expression: AstNode, alias: AstNode?, block: AstNode? = null): QueryItem {
        val segments = plsql.nameSegments(expression)
        val name = alias?.let { normalizeIdentifier(it.tokenOriginalValue) } ?: segments?.last()?.let { normalizeIdentifier(it) }
        fun column(node: AstNode) = columnOf(statement, node)
        if (segments != null) return column(expression)?.let { QueryItem(name, it, null) } ?: QueryItem(name, null, null, notColumn = notColumn(statement, expression, block))
        val columns = HashSet<String>()
        fun visit(node: AstNode) {
            statement.columnReads[node]?.let { reads -> reads.mapNotNullTo(columns) { it.entityKey } }
            node.children.forEach(::visit)
        }
        visit(expression)
        if (columns.size > 1) {
            // A concatenation of several columns, each a part of it: the item is each of them.
            val chains = columns.sorted().map { key -> key to valueChain(expression) { node -> plsql.nameSegments(node) != null && column(node) == key } }
            if (chains.all { it.second != null && CONCATENATED in it.second!! }) {
                val (first, chain) = chains.first()
                return QueryItem(name, first, null, functions = chain!!, parts = chains.drop(1).map { (key, f) -> QueryItem(name, key, null, functions = f!!) })
            }
            return QueryItem(name, null, null, combined = expressionShape(expression))
        }
        if (columns.isEmpty()) return QueryItem(name, null, null, notColumn = notColumn(statement, expression, block))
        var leaf: String? = null
        val functions = valueChain(expression) { node -> plsql.nameSegments(node) != null && column(node)?.also { leaf = it } != null }
        return if (functions != null && leaf != null) QueryItem(name, leaf, null, functions = functions)
            else QueryItem(name, null, expressionShape(expression))
    }

    /**
     * The value functions [expression] reads one value through, outer first, down to the node [isValue] accepts; null
     * when it is anything else. A value function gives back one of its arguments, or that argument trimmed or in
     * another case: `NVL(a, b)` and `COALESCE(a, b, …)` (any argument), `NVL2(e1, e2, e3)` (`e2` or `e3`), `MAX(x)` and
     * `MIN(x)` (with `DISTINCT`, `ALL`, `KEEP (…)`, `OVER (…)`), `TRIM`, `LTRIM`, `RTRIM`, `UPPER`, `LOWER` (`x`). Exactly
     * one of the arguments that may be the value must lead to the node.
     */
    private fun valueChain(expression: AstNode, isValue: (AstNode) -> Boolean): List<String>? {
        var node = expression
        while (node.type == PlSqlGrammar.BRACKED_EXPRESSION && node.numberOfChildren == 3) node = node.children[1]
        if (isValue(node)) return emptyList()
        fun through(function: String, candidates: List<AstNode>): List<String>? {
            val chains = candidates.mapNotNull { valueChain(it, isValue) }
            return chains.singleOrNull()?.let { listOf(function) + it }
        }
        return when (node.type) {
            PlSqlGrammar.POSTFIX_EXPRESSION -> node.firstChild.takeIf { call ->
                call.type == PlSqlGrammar.METHOD_CALL && plsql.nameSegments(call.firstChild)?.singleOrNull()?.uppercase() in setOf("MAX", "MIN") &&
                    node.children.drop(1).all { it.type == DmlGrammar.KEEP_CLAUSE || it.type == DmlGrammar.ANALYTIC_CLAUSE }
            }?.let { valueChain(it, isValue) }
            SingleRowSqlFunctionsGrammar.SINGLE_ROW_SQL_FUNCTION -> node.firstChild.takeIf { it.type == SingleRowSqlFunctionsGrammar.TRIM_EXPRESSION }?.let { trim ->
                val parts = trim.children.filter { it.type != PlSqlPunctuator.LPARENTHESIS && it.type != PlSqlPunctuator.RPARENTHESIS }.drop(1)
                val from = parts.indexOfFirst { it.type == PlSqlKeyword.FROM }
                val value = if (from >= 0) parts.getOrNull(from + 1) else parts.singleOrNull()
                value?.let { through("TRIM", listOf(it)) }
            }
            // A concatenation: the value is one of its parts, with other text around it.
            PlSqlGrammar.CONCATENATION_EXPRESSION ->
                node.children.filter { it.type != PlSqlGrammar.CONCATENATION_OPERATOR }.firstNotNullOfOrNull { valueChain(it, isValue) }
                    ?.let { listOf(CONCATENATED) + it }
            PlSqlGrammar.METHOD_CALL -> {
                val function = plsql.nameSegments(node.firstChild)?.singleOrNull()?.uppercase() ?: return null
                if (function == "CONCAT") {
                    val arguments = node.getChildren(PlSqlGrammar.ARGUMENTS).singleOrNull()?.getChildren(PlSqlGrammar.ARGUMENT)?.map { it.lastChild }
                        ?.takeIf { it.size == 2 } ?: return null
                    return arguments.firstNotNullOfOrNull { valueChain(it, isValue) }?.let { listOf(CONCATENATED) + it }
                }
                val arguments = node.getChildren(PlSqlGrammar.ARGUMENTS).singleOrNull()?.getChildren(PlSqlGrammar.ARGUMENT)?.map { it.lastChild }
                    ?: return null
                val candidates = when (function) {
                    "NVL" -> arguments.takeIf { it.size == 2 }
                    "COALESCE" -> arguments.takeIf { it.size >= 2 }
                    "NVL2" -> arguments.takeIf { it.size == 3 }?.drop(1)
                    "MAX", "MIN", "UPPER", "LOWER", "TRIM" -> arguments.takeIf { it.size == 1 }
                    "LTRIM", "RTRIM" -> arguments.takeIf { it.size in 1..2 }?.take(1)
                    else -> null
                } ?: return null
                through(function, candidates)
            }
            else -> null
        }
    }

    /**
     * Why the select item [expression] of the query block [block] reads no column the index knows ([QueryItem.notColumn]):
     * `INLINE_VIEW` when it names a column of an inline view (`v.code` from `(select code from orders) v`),
     * `COLUMN_UNRESOLVED` when it reads columns the index does not resolve, else `NO_COLUMN` (a literal, a PL/SQL value,
     * `ROWNUM`, `LEVEL`, a function of those, `extractValue(value(t), …)` over `table(…)`).
     */
    private fun notColumn(statement: Statement, expression: AstNode, block: AstNode?): String {
        if (block != null && readsInlineView(expression, block)) return "INLINE_VIEW"
        return if (subtree(expression).any { statement.columnReads[it].orEmpty().isNotEmpty() }) "COLUMN_UNRESOLVED" else "NO_COLUMN"
    }

    /** [node] and every node under it. */
    private fun subtree(node: AstNode): Sequence<AstNode> = sequenceOf(node) + node.children.asSequence().flatMap(::subtree)

    /**
     * Whether [expression] names a column of an inline view of [block]'s `FROM`: by its alias (`v.code`), or by a name
     * alone when every relation of the `FROM` is an inline view.
     */
    private fun readsInlineView(expression: AstNode, block: AstNode): Boolean {
        val from = block.getFirstChildOrNull(DmlGrammar.FROM_CLAUSE) ?: return false
        val items = from.getDescendants(DmlGrammar.DML_TABLE_EXPRESSION_CLAUSE).filter { item ->
            generateSequence(item.parentOrNull) { it.parentOrNull }.takeWhile { it !== from }.none { it.type == DmlGrammar.SELECT_EXPRESSION }
        }
        val views = items.filter { it.hasDirectChildren(DmlGrammar.SELECT_EXPRESSION) }
        if (views.isEmpty()) return false
        val aliases = views.mapNotNull { view -> view.getFirstChildOrNull(DmlGrammar.ALIAS)?.let { normalizeIdentifier(it.lastChild.tokenOriginalValue) } }.toSet()
        val names = subtree(expression).filter { it.type == PlSqlGrammar.VARIABLE_NAME || it.type == PlSqlGrammar.MEMBER_EXPRESSION }
        return names.any { name ->
            val segments = plsql.nameSegments(name) ?: return@any false
            when {
                segments.size >= 2 -> name.type == PlSqlGrammar.MEMBER_EXPRESSION && normalizeIdentifier(segments.first()) in aliases
                else -> name.parentOrNull?.type != PlSqlGrammar.MEMBER_EXPRESSION && views.size == items.size &&
                    normalizeIdentifier(segments.single()) !in PSEUDO_COLUMNS && nameBinding(name) !is NameBinding.Local
            }
        }
    }

    /**
     * The shape of an expression a parameter is written within: as [expressionShape] says it, apart from a `CASE`
     * (which may give the value back as is) and `LENGTH` and its kin (which give a number, not the value).
     */
    private fun argumentShape(expression: AstNode): String {
        var node = expression
        while (node.type == PlSqlGrammar.BRACKED_EXPRESSION && node.numberOfChildren == 3) node = node.children[1]
        if (node.type == PlSqlGrammar.CASE_EXPRESSION) return "CASE"
        if (node.type == PlSqlGrammar.METHOD_CALL && plsql.nameSegments(node.firstChild)?.singleOrNull()?.uppercase() in LENGTH_FUNCTIONS) return "LENGTH"
        return expressionShape(expression)
    }

    /**
     * The outer function or operator of an expression item: `NVL`, `NVL2`, `UPPER`, `TRIM`, `SUBSTR`, `MAX_MIN`,
     * `TO_CHAR`, `CONCATENATION`, `OTHER`.
     */
    private fun expressionShape(expression: AstNode): String {
        var node = expression
        while (node.type == PlSqlGrammar.BRACKED_EXPRESSION && node.numberOfChildren == 3) node = node.children[1]
        if (node.type == PlSqlGrammar.CONCATENATION_EXPRESSION) return "CONCATENATION"
        val function = if (node.type == PlSqlGrammar.METHOD_CALL) plsql.nameSegments(node.firstChild)?.last()?.uppercase() else node.tokenValue.uppercase()
        return when (function) {
            "NVL" -> "NVL"
            "NVL2" -> "NVL2"
            "UPPER" -> "UPPER"
            "TRIM", "LTRIM", "RTRIM" -> "TRIM"
            "SUBSTR", "SUBSTRB" -> "SUBSTR"
            "MAX", "MIN" -> "MAX_MIN"
            "TO_CHAR" -> "TO_CHAR"
            else -> "OTHER_" + otherShape(node)
        }
    }

    /**
     * What an expression of no family of its own is, by its outer function or operator: `CASE`, `ARITHMETIC` (`+`, `-`,
     * `*`, `/`, `**`), `ROUTINE` (a call of a package or schema function), the name of a SQL built-in (`DECODE`,
     * `TO_NUMBER`, `LOWER`, `CAST`), or `OTHER`.
     */
    private fun otherShape(expression: AstNode): String {
        var node = expression
        while (node.type == PlSqlGrammar.POSTFIX_EXPRESSION || node.numberOfChildren == 1) node = node.firstChild
        return when {
            node.type == PlSqlGrammar.CASE_EXPRESSION -> "CASE"
            node.type in ARITHMETIC -> "ARITHMETIC"
            node.type == PlSqlGrammar.METHOD_CALL -> {
                val segments = plsql.nameSegments(node.firstChild) ?: return "OTHER"
                if (segments.size > 1 || isRoutineCall(node.firstChild)) "ROUTINE" else segments.single().uppercase().takeIf { WORD.matches(it) } ?: "OTHER"
            }
            // A built-in written with its own syntax: `CAST(… AS …)`, `EXTRACT(… FROM …)`.
            node.numberOfChildren > 1 && node.children[1].tokenValue == "(" -> node.firstChild.tokenValue.uppercase().takeIf { WORD.matches(it) } ?: "OTHER"
            else -> "OTHER"
        }
    }

    /** Whether the called name [name] is a routine the index resolves (a built-in is not one). */
    private fun isRoutineCall(name: AstNode): Boolean {
        val span = spanOf(name)
        return state.occurrences.any { it.span == span && Role.CALL in it.roles && it.entityKey != null && it.status != Status.UNRESOLVED }
    }

    /** Whether [shape], followed through type names of this file, is a `REF CURSOR` type with `RETURN`. */
    private fun isStrongCursor(shape: TypeShape): Boolean = when (shape) {
        is TypeShape.Unknown -> shape.strongCursor
        is TypeShape.Named -> shape.local?.let(::isStrongCursor) == true
        else -> false
    }

    /**
     * The `INTO`s of the file and its assignments of a record's field, their targets and records decided, and the row
     * record fields passed counted: call once the file is left, when the field uses are recorded. The expression items an
     * `INTO` reads are counted after the scan, with the cursors of other files.
     */
    fun intos(shapes: TypeShapes): List<IntoFact> {
        if (pending.isEmpty() && assignments.isEmpty() && outArguments.isEmpty() && undecidedCalls.isEmpty() && passes.isEmpty() &&
            valueWrites.isEmpty() && flows.isEmpty()) return emptyList()
        val bySpan = state.occurrences.filter { it.entityKey != null }.groupBy { it.span }
        val intos = pending.mapNotNullTo(ArrayList()) { into ->
            val targets = into.targets.map { target(it, bySpan, shapes) }
            var cursorKey: String? = null
            var branches = into.branches
            if (into.cursor != null) {
                val cursor = into.cursor
                cursorKey = bySpan[spanOf(cursor)]?.firstOrNull()?.entityKey
                if (cursorKey == null) {
                    val declaration = local(cursor, shapes)
                    val query = declaration?.takeIf { it.type == PlSqlGrammar.CURSOR_DECLARATION }?.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)
                    val items = query?.let { state.cursorItems[it] }
                    // Not known: counted once the types of other files are known ([ColumnPlaces]), by the cursor it is.
                    if (items == null) {
                        val (kind, type) = cursorKind(into.fetch!!, cursor, shapes)
                        return@mapNotNullTo IntoFact(into.source, into.bulk, into.context.unitIndex, into.context.container, null, emptyList(), null,
                            targets, cursorKind = kind, cursorType = type)
                    }
                    branches = listOf(items)
                }
            }
            val (kind, type) = if (into.fetch != null && cursorKey != null && into.cursor != null) cursorKind(into.fetch, into.cursor, shapes) else null to null
            IntoFact(into.source, into.bulk, into.context.unitIndex, into.context.container, into.statementId, branches, cursorKey, targets,
                cursorKind = kind, cursorType = type)
        }
        for (assignment in assignments) {
            val row = record(assignment.field, bySpan, shapes) ?: continue
            val target = target(assignment.target, bySpan, shapes).copy(span = assignment.site)
            intos += IntoFact(PlaceSource.ASSIGNMENT, false, assignment.context.unitIndex, assignment.context.container, null, emptyList(), null,
                listOf(target), row, normalizeIdentifier(assignment.field.lastChild.tokenOriginalValue), assignment.expression?.let(::expressionShape), assignment.functions)
        }
        // The argument passed for an OUT or IN OUT parameter, as an INTO target: it takes what the parameter is given.
        for (argument in outArguments) {
            intos += IntoFact(PlaceSource.OUT_ARGUMENT, false, argument.context.unitIndex, argument.context.container, null, emptyList(), null,
                listOf(target(argument.value, bySpan, shapes)), parameterKey = argument.parameterKey)
        }
        // A column, or a record's field, passed to a routine's IN parameter.
        for (pass in passes) {
            val argument = IntoTarget(spanOf(pass.argument), writtenText(pass.argument), null, emptyList(), false, null, null, pass.reason)
            if (pass.field != null) {
                val row = record(pass.field, bySpan, shapes) ?: continue
                intos += IntoFact(PlaceSource.IN_ARGUMENT, false, pass.context.unitIndex, pass.context.container, pass.statementId, emptyList(), null,
                    listOf(argument), row, normalizeIdentifier(pass.field.lastChild.tokenOriginalValue), null, pass.functions, pass.parameterKey)
            } else {
                intos += IntoFact(PlaceSource.IN_ARGUMENT, false, pass.context.unitIndex, pass.context.container, pass.statementId,
                    listOf(listOf(QueryItem(null, pass.column, null, functions = pass.functions))), null, listOf(argument),
                    functions = pass.functions, parameterKey = pass.parameterKey)
            }
        }
        // What a routine writes its IN parameters into, passes them on to, or writes them in a way not followed.
        val routineCalls = state.occurrences.filter { Role.CALL in it.roles && it.entityKey != null }.mapTo(HashSet()) { it.span }
        for (write in valueWrites) {
            // A parameter passed to a routine inside the value is that call's argument ([call]).
            val names = parameterNames(write.value).filter { name ->
                generateSequence(name.parentOrNull) { it.parentOrNull }.takeWhile { it !== write.value.parentOrNull }
                    .none { it.type == PlSqlGrammar.METHOD_CALL && spanOf(it.firstChild) in routineCalls }
            }
            for ((name, functions) in parameterChains(write.value, names)) {
                flows += when {
                    write.target == null -> Flow(name, write.site, write.context, shape = ARGUMENT_RETURNED)
                    functions == null -> Flow(name, write.site, write.context, shape = ARGUMENT_EXPRESSION + argumentShape(write.value))
                    else -> Flow(name, write.site, write.context, target = write.target, functions = functions)
                }
            }
        }
        // A name in SQL a column may take instead of the parameter: the parameter only when the index resolves the name,
        // the statement's tables' columns known and none of them so named.
        val statusBySpan = state.occurrences.groupBy { it.span }
        for (flow in flows) {
            val from = flow.fromKey ?: bySpan[spanOf(flow.parameter)]?.firstOrNull()?.entityKey ?: localParameterKey(flow.parameter, shapes) ?: continue
            val columnPossible = flow.fromKey == null && flow.shape == null && nameBinding(flow.parameter) is NameBinding.ColumnOrLocal &&
                statusBySpan[spanOf(flow.parameter)].orEmpty().none { it.status == Status.RESOLVED }
            if (columnPossible) {
                intos += IntoFact(PlaceSource.IN_ARGUMENT, false, flow.context.unitIndex, flow.context.container, null, emptyList(), null,
                    listOf(IntoTarget(flow.site, "", null, emptyList(), false, null, null, null)), expression = ARGUMENT_COLUMN_POSSIBLE,
                    fromParameter = from)
                continue
            }
            val targets = flow.target?.let { listOf(target(it, bySpan, shapes).copy(span = flow.site)) }
                ?: listOf(IntoTarget(flow.site, "", null, emptyList(), false, null, null, null))
            val branches = flow.item?.let { (index, size) ->
                listOf(List(size) { if (it == index) QueryItem(null, from, null, functions = flow.functions) else QueryItem(null, null, null) })
            }.orEmpty()
            intos += IntoFact(PlaceSource.IN_ARGUMENT, flow.bulk, flow.context.unitIndex, flow.context.container, null, branches, null, targets,
                expression = flow.shape, functions = flow.functions, parameterKey = flow.passedTo, fromParameter = from)
        }
        // A call of an overloaded nested subprogram: no target, only the parameters it may be for.
        for ((span, parameter) in undecidedCalls) {
            intos += IntoFact(PlaceSource.OUT_ARGUMENT, false, 0, null, null, emptyList(), null,
                listOf(IntoTarget(span, "", null, emptyList(), false, null, null, OUT_CALL_NOT_DECIDED)), parameterKey = parameter)
        }
        return intos
    }

    /**
     * The record of the field `r.f`: a cursor `FOR` loop record, its row the loop's query or cursor, or the variable or
     * parameter zpa binds `r` to; null when `r` names no record (a package, or a record type's field entity).
     */
    private fun record(field: AstNode, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): IntoTarget? {
        val name = field.firstChild
        val declaration = (nameBinding(name) as? NameBinding.Local)?.declaration
        if (declaration?.kind == NameDeclarationKind.LOOP_INDEX) {
            val iterator = declaration.takeIf { it.fileId == null }?.let { shapes.iterand(it.line, it.column) }?.parentOrNull ?: return null
            return IntoTarget(spanOf(name), writtenText(name), null, emptyList(), false, loopRow(iterator, bySpan, shapes), null, null)
        }
        val whole = target(field, bySpan, shapes)
        if (whole.entityKey == null && whole.local == null) return null
        // The field itself has an entity (a field of a record type): not a row record.
        if (whole.path.size != 1) return null
        return whole.copy(span = spanOf(name), written = writtenText(name), path = emptyList())
    }

    /** The row of a cursor `FOR` loop's record: its query's items, or the cursor it opens (`for r in c`, `for r in c(1)`). */
    private fun loopRow(iterator: AstNode, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): TypeShape {
        val unknown = TypeShape.Anchored(true, null, null, emptyList(), "CURSOR_QUERY_UNKNOWN")
        iterator.getFirstDescendantOrNull(DmlGrammar.SELECT_EXPRESSION)?.let { query ->
            return state.cursorItems[query]?.let { TypeShape.CursorRow(it) } ?: unknown
        }
        var cursor = iterator.children.dropWhile { it.type != PlSqlKeyword.IN }.drop(1).firstOrNull() ?: return unknown
        while (cursor.type != PlSqlGrammar.VARIABLE_NAME && cursor.type != PlSqlGrammar.MEMBER_EXPRESSION) cursor = cursor.firstChild ?: return unknown
        (local(cursor, shapes)?.takeIf { it.type == PlSqlGrammar.CURSOR_DECLARATION } ?: enclosingCursor(iterator, cursor))
            ?.let { shapes.of(it, null) }?.let { return it }
        return bySpan[spanOf(cursor)]?.firstOrNull()?.entityKey?.let { TypeShape.Anchored(true, it, null, emptyList(), null) } ?: unknown
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
                .firstOrNull { it.type == PlSqlGrammar.CURSOR_DECLARATION && normalizeIdentifier(it.getFirstChild(PlSqlGrammar.IDENTIFIER_NAME).tokenOriginalValue) == wanted }
                ?.let { return it }
            scope = scope.parentOrNull
        }
        return null
    }

    /** The declaration of this file without an entity that zpa binds [name] (its first segment) to, or null. */
    private fun local(name: AstNode, shapes: TypeShapes): AstNode? {
        val first = if (name.type == PlSqlGrammar.MEMBER_EXPRESSION) name.firstChild else name
        val declaration = when (val binding = nameBinding(first)) {
            is NameBinding.Local -> binding.declaration
            is NameBinding.ColumnOrLocal -> binding.declaration
            else -> return null
        }
        if (declaration.fileId != null) return null
        return shapes.localDeclaration(declaration.line, declaration.column)
    }

    /**
     * A target as zpa binds it: a field with an entity at its last segment, else the entity of the whole name (the
     * segments after the one it names are fields), else a declaration without an entity, with its own type. A field
     * reached through a collection element (`a(i).f`, `a(i).r.f`, and `a(i).f(j)` for an element of it) is the field
     * zpa binds its last segment to, as the index records its use; when that field has no entity (a field of an element
     * that is a `%ROWTYPE`), the collection itself, its element and fields as steps of the path ([IntoTarget.ELEMENT]).
     * An element of an element (`a(i)(j)`, `a(i).f(j)(k)`) is a shape the index does not follow
     * (`TARGET_SHAPE_NOT_FOLLOWED`).
     */
    private fun target(node: AstNode, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): IntoTarget {
        val span = spanOf(node)
        val written = writtenText(node)
        if (node.type == PlSqlGrammar.OBJECT_REFERENCE) {
            val head = node.firstChild
            val (field, element) = fieldThroughElement(node)?.takeUnless { head.type == PlSqlGrammar.METHOD_CALL && isElementOfElement(head) }
                ?: return IntoTarget(span, written, null, emptyList(), false, null, null, TARGET_SHAPE_NOT_FOLLOWED)
            bySpan[spanOf(field)]?.firstOrNull()?.entityKey?.let { return IntoTarget(span, written, it, emptyList(), element, null, null, null) }
            return throughElements(node, bySpan, shapes)?.copy(span = span, written = written)
                ?: IntoTarget(span, written, null, emptyList(), element, null, null, "FIELD_UNDECIDED")
        }
        val element = node.type == PlSqlGrammar.METHOD_CALL
        if (element && isElementOfElement(node)) return IntoTarget(span, written, null, emptyList(), true, null, null, TARGET_SHAPE_NOT_FOLLOWED)
        val name = if (element) node.firstChild else node
        val segments = plsql.nameSegments(name)?.map { normalizeIdentifier(it) }
            ?: return IntoTarget(span, written, null, emptyList(), element, null, null, "TARGET_NOT_A_NAME")
        if (segments.size > 1) {
            val last = name.children.last { it.type == PlSqlGrammar.IDENTIFIER_NAME || it.type == PlSqlGrammar.VARIABLE_NAME }
            bySpan[spanOf(last)]?.firstOrNull()?.entityKey?.let { return IntoTarget(span, written, it, emptyList(), element, null, null, null) }
        }
        bySpan[spanOf(name)]?.firstOrNull()?.entityKey?.let { key ->
            val own = key.substringAfterLast('.').substringBefore('@').substringBefore('#')
            val at = segments.indexOfLast { it == own }
            return IntoTarget(span, written, key, if (at >= 0) segments.drop(at + 1) else segments.drop(1), element, null, null, null)
        }
        val declaration = local(name, shapes)
            ?: return IntoTarget(span, written, null, emptyList(), element, null, null, "TARGET_UNDECIDED")
        val shape = shapes.of(declaration, null) ?: TypeShape.Unknown("DECLARATION_UNKNOWN")
        // A nested subprogram's parameter, whose calls this file binds; not an object type method's, whose calls the
        // index does not record.
        val localKey = declaration.takeIf { it.type == PlSqlGrammar.PARAMETER_DECLARATION && !it.hasAncestor(PlSqlGrammar.CREATE_TYPE_BODY) }
            ?.let(::localKey)
        return IntoTarget(span, written, null, segments.drop(1), element, shape, DeclaredTexts.of("", declaration)?.datatype, null, localKey)
    }

    /**
     * `a(i).f`, `a(i).r.f`, `a(i).f(j)` followed from the collection `a`: its target, then an element step and the
     * written fields and elements after it; null when `a` is not a target the index knows.
     */
    private fun throughElements(reference: AstNode, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): IntoTarget? {
        val head = reference.firstChild.takeIf { it.type == PlSqlGrammar.METHOD_CALL } ?: return null
        val collection = target(head.firstChild, bySpan, shapes).takeIf { it.reason == null && !it.element } ?: return null
        val steps = ArrayList(collection.path)
        steps += IntoTarget.ELEMENT
        fun visit(node: AstNode): Boolean = when (node.type) {
            PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.IDENTIFIER_NAME -> steps.add(normalizeIdentifier(node.tokenOriginalValue))
            PlSqlGrammar.OBJECT_REFERENCE, PlSqlGrammar.MEMBER_EXPRESSION -> node.children.filter { it.type != PlSqlPunctuator.DOT }.all(::visit)
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

    companion object {
        const val DYNAMIC_INTO = "DYNAMIC_INTO"
        const val RECORD_ASSIGNMENT = "RECORD_ASSIGNMENT"
        const val ARGUMENT_ROUTINE_UNDECIDED = "ARGUMENT_ROUTINE_UNDECIDED"
        const val ARGUMENT_ROUTINE_OUTSIDE = "ARGUMENT_ROUTINE_OUTSIDE"
        const val ARGUMENT_NOT_TIED = "ARGUMENT_NOT_TIED"
        const val ARGUMENT_RETURNED = "ARGUMENT_RETURNED"
        /** The prefix of the shape of an expression a parameter is written within ([argumentShape]). */
        const val ARGUMENT_EXPRESSION = "ARGUMENT_EXPRESSION_"
        private val LENGTH_FUNCTIONS = setOf("LENGTH", "LENGTHB", "LENGTHC", "LENGTH2", "LENGTH4")
        const val ARGUMENT_LOCAL_SUBPROGRAM = "ARGUMENT_LOCAL_SUBPROGRAM"
        const val ARGUMENT_CONSTRUCTOR = "ARGUMENT_CONSTRUCTOR"
        const val ARGUMENT_FETCHED = "ARGUMENT_FETCHED"
        const val ARGUMENT_COLUMN_POSSIBLE = "ARGUMENT_COLUMN_POSSIBLE"
        /** Marks a parameter of a routine whose body is not PL/SQL ([routine]); never counted itself. */
        const val BODY_NOT_PLSQL = "BODY_NOT_PLSQL"
        const val ARGUMENT_INTO_NOT_FOLLOWED = "ARGUMENT_INTO_NOT_FOLLOWED"
        /** The prefix of the shape of a `FETCH` from a cursor whose query is not known, by the cursor it is. */
        const val FETCH_CURSOR_NOT_KNOWN = "FETCH_CURSOR_NOT_KNOWN_"
        private val ARITHMETIC = setOf(
            PlSqlGrammar.ADDITIVE_EXPRESSION, PlSqlGrammar.MULTIPLICATIVE_EXPRESSION, PlSqlGrammar.UNARY_EXPRESSION, PlSqlGrammar.EXPONENTIATION_EXPRESSION
        )
        private val WORD = Regex("[A-Z][A-Z0-9_]*")
        private val PSEUDO_COLUMNS = setOf("ROWNUM", "LEVEL", "ROWID", "SYSDATE", "SYSTIMESTAMP", "USER", "CURRENT_DATE", "CURRENT_TIMESTAMP", "UID")
        const val OUT_CALL_NOT_DECIDED = "OUT_CALL_NOT_DECIDED"

        /** The prefix of the key of a nested subprogram's parameter ([IntoTarget.localKey]). */
        const val LOCAL_PREFIX = "local:"

        /** The reason of a call of a subprogram nested in a routine, which has no entity. */
        private const val LOCAL_SUBPROGRAM = "LOCAL_SUBPROGRAM"

        /** A target whose shape the index does not follow (an element of an element). */
        private const val TARGET_SHAPE_NOT_FOLLOWED = "TARGET_SHAPE_NOT_FOLLOWED"

        /** The statuses of a column read that names the column: an unresolved or ambiguous one names none. */
        /** A routine's key ends with its parameter list. */
        private val ROUTINE_KEY = Regex("""\)(@BODY)?(#\d+)?$""")

        /** The statuses of a call that ties its arguments to the routine's parameters. */
        private val CALLED_STATUSES = setOf(Status.RESOLVED, Status.PARTIAL_SOURCE)

        private val COLUMN_STATUSES = setOf(Status.RESOLVED, Status.UNVERIFIED, Status.EXTERNAL, Status.PARTIAL_SOURCE)
    }
}
