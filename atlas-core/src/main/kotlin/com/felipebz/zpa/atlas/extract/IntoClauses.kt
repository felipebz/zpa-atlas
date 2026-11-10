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
import com.felipebz.zpa.api.dynamicsql.DynamicSqlText
import com.felipebz.zpa.api.project.ProjectParameterMode
import com.felipebz.zpa.api.semantics.NameBinding
import com.felipebz.zpa.api.semantics.NameDeclarationKind
import com.felipebz.zpa.atlas.model.*
import java.util.*

/**
 * The `INTO`s that read select items into PL/SQL targets ([IntoFact]): `SELECT … INTO`, `FETCH … INTO` of an explicit
 * cursor, `RETURNING … INTO`, with or without `BULK COLLECT`, and the assignments `target := r.f` of a field of a
 * record, which is a row record when it is a cursor `FOR` loop record or a `%ROWTYPE` variable (decided after the
 * scan). A select item is a column when it is the column itself, written bare or qualified (the column read static SQL
 * records at it), or a `*` over a relation whose columns the dictionary lists; an expression of one column is counted by
 * its shape. A target is the entity zpa binds its name to (the value use the walk records), else the declaration
 * without an entity zpa binds it to, with its own type; a Forms host name (`:BLOCK.ITEM`) is the module's object its
 * use names.
 *
 * Other reads of a column into PL/SQL are counted, not recorded ([FileState.placesLeftOut]): dynamic `INTO`, a field
 * of a `%ROWTYPE` or cursor loop record passed to a routine, a column passed to a routine in static SQL, and a `FETCH`
 * from a cursor whose query is not known.
 */
internal class IntoClauses(
    private val state: FileState,
    private val plsql: PlSqlNames,
    private val columnReads: ColumnReads,
    private val lineage: ViewLineage,
    private val nameBinding: (AstNode) -> NameBinding
) {
    private val expressions = ExpressionShapes(plsql, state, nameBinding)
    private val queryItems = SelectItems(plsql, columnReads, lineage, nameBinding, expressions)
    private val intoTargets = IntoTargets(state, plsql, nameBinding)
    private val cursors = CursorOpens(state)

    private val pending = ArrayList<Pending>()
    private val assignments = ArrayList<Assignment>()
    private val valueWrites = ArrayList<ValueWrite>()
    private val passes = ArrayList<Pass>()
    private val flows = ArrayList<Flow>()
    private val callArguments = ArrayList<CallArgument>()
    private val outArguments = ArrayList<OutArgument>()

    /** The calls of overloaded nested subprograms, with each `OUT` parameter they may be for. */
    private val undecidedCalls = ArrayList<Pair<SourceSpan, String>>()

    /** The functions of the file, by their declaration: the key of what each returns ([returnKey]). */
    private val functions = IdentityHashMap<AstNode, String>()

    /** What the function program units of the Forms module the file is return ([returnKey]): their calls are followed as a routine's. */
    private val moduleFunctions = HashSet<String>()

    /** The nested functions the calls of the file name, by the call: the key of what each returns. */
    private val localFunctions = IdentityHashMap<AstNode, String>()

    private class Pending(
        val source: PlaceSource, val bulk: Boolean, val context: Context, val statementId: Int?, val branches: List<List<QueryItem>>,
        val cursor: AstNode?, val targets: List<AstNode>, val fetch: AstNode? = null
    )

    /** An assignment of a record's [field], or of an [expression] of it; with [several], of several fields, [field] the first. */
    private class Assignment(
        val target: AstNode, val site: SourceSpan, val field: AstNode, val expression: AstNode?, val functions: List<String>, val context: Context,
        val several: Boolean = false
    )

    /**
     * A value written in PL/SQL: assigned to [target] (`target := value`, at the `:=`), or returned (null [target]). The
     * routine's `IN` parameters it writes are told once the file is left, when its calls are known ([writeFlows]).
     */
    private class ValueWrite(val value: AstNode, val site: SourceSpan, val target: AstNode?, val context: Context, val sql: Boolean = false)

    /**
     * A column, or a record's field, passed to a routine's `IN` parameter ([parameterKey], null when the call does not
     * tie the argument to one), through [functions]; [reason] when the call is not followed.
     */
    private class Pass(
        val argument: AstNode, val column: String?, val field: AstNode?, val functions: List<String>, val parameterKey: String?,
        val reason: String?, val context: Context, val statementId: Int?, val call: SourceSpan,
        /** For an expression of several fields ([field] the first): [PlaceShapes.SEVERAL_COLUMNS] and its shape. */
        val several: String? = null
    )

    /**
     * A routine's `IN` parameter ([parameter], its name in the code) written somewhere: into [target], passed on to
     * [passedTo], or written in a way the index does not follow ([shape]), through [functions].
     */
    private class Flow(
        val parameter: AstNode, val site: SourceSpan, val context: Context, val target: AstNode? = null, val passedTo: String? = null,
        val shape: String? = null, val functions: List<String> = emptyList(), val fromKey: String? = null, val bulk: Boolean = false,
        /** For the single target of an `INTO`: the parameter's item and the number of items. */
        val item: Pair<Int, Int>? = null,
        /** For a value passed on to a parameter ([passedTo]): the call it is an argument of. */
        val call: SourceSpan? = null,
        /** For a function's result ([fromKey] what it returns): the call whose result it is. */
        val resultOf: SourceSpan? = null
    )

    /**
     * Where the results of the functions a value calls go: written into [target], passed on to [passedTo] (a parameter,
     * or what the function around returns; [argumentOf] the call, for a parameter), or, passed where it is not
     * followed, counted by [reason].
     */
    private data class ResultDestination(
        val target: AstNode? = null, val passedTo: String? = null, val reason: String? = null, val argumentOf: SourceSpan? = null
    )

    /** An argument for an `OUT` or `IN OUT` parameter ([parameterKey]) of the [call] at that span. */
    private class OutArgument(val value: AstNode, val parameterKey: String, val context: Context, val call: SourceSpan)

    /**
     * An argument for an `IN` parameter ([parameter], or the [reason] it is not followed) of the [call] at that span: the
     * results of the functions it calls go there.
     */
    private class CallArgument(val argument: AstNode, val parameter: String?, val reason: String?, val context: Context, val call: SourceSpan)

    /** The select items of [statement]'s cursor query, its `INTO` or `RETURNING INTO`, and the columns it passes to routines. */
    fun statement(statement: Statement) {
        if (statement.context.dynamic != null) {
            dynamicQuery(statement)
            return
        }
        val root = statement.root
        if (root.parentOrNull?.type == PlSqlGrammar.CURSOR_DECLARATION || root.parentOrNull?.type == PlSqlGrammar.OPEN_FOR_STATEMENT || isLoopQuery(root)) {
            branches(root).firstOrNull()?.let { state.cursorItems[root] = queryItems.selectItems(statement, it) }
        }
        when (root.type) {
            DmlGrammar.SELECT_EXPRESSION -> selectInto(statement, root)
            DmlGrammar.INSERT_EXPRESSION, DmlGrammar.UPDATE_EXPRESSION, DmlGrammar.DELETE_EXPRESSION -> returningInto(statement, root)
        }
        if (root.parentOrNull?.type == PlSqlGrammar.CURSOR_DECLARATION) fetchedParameters(statement, root)
        passedColumns(statement)
    }

    /**
     * The main query of a text a cursor variable may be opened for: its items, what a FETCH of it reads, unless an item
     * is a hole; the query is marked when a hole in its `FROM` may hide the column an item reads.
     */
    private fun dynamicQuery(statement: Statement) {
        val root = statement.root
        if (root.type != DmlGrammar.SELECT_EXPRESSION ||
            generateSequence(root.parentOrNull) { it.parentOrNull }.any { it.type == DmlGrammar.SELECT_EXPRESSION }) return
        val block = branches(root).firstOrNull()
            ?.takeIf { block -> block.getChildren(DmlGrammar.SELECT_COLUMN).none { statement.context.hasHoles(it) } } ?: return
        val items = queryItems.selectItems(statement, block)
        state.cursorItems[root] = items
        if (hidesColumns(statement, block)) cursors.hide(root)
    }

    /**
     * Whether a hole in the `FROM` of [block] may hide the column an item reads: a name in an item that reads no column
     * of what is known there may be a column of what the hole names.
     */
    private fun hidesColumns(statement: Statement, block: AstNode): Boolean =
        block.getFirstChildOrNull(DmlGrammar.FROM_CLAUSE)?.let(statement.context::hasHoles) == true &&
            block.getChildren(DmlGrammar.SELECT_COLUMN).any { column ->
                val value = column.firstChild
                (sequenceOf(value) + value.getDescendants(PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.MEMBER_EXPRESSION).asSequence())
                    .any { plsql.nameSegments(it) != null && statement.columnReads[it].isNullOrEmpty() }
            }

    /** `SELECT … INTO` ([root]): kept with its items and targets, the parameters it selects, and the values written into the targets. */
    private fun selectInto(statement: Statement, root: AstNode) {
        val into = root.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK)?.getFirstChildOrNull(DmlGrammar.INTO_CLAUSE) ?: return
        pending += Pending(PlaceSource.SELECT_INTO, isBulk(into), statement.context, statement.id,
            branches(root).map { queryItems.selectItems(statement, it) }, null, targetsOf(into))
        for (block in branches(root)) {
            val values = block.getChildren(DmlGrammar.SELECT_COLUMN).map { it.firstChild }
            selectedParameters(statement, values, into)
            // A function called in a select item: its result is written into the item's target.
            val targets = targetsOf(into)
            if (!isBulk(into) && targets.size == values.size) {
                for ((index, value) in values.withIndex()) valueWrites += ValueWrite(value, spanOf(value), targets[index], statement.context, sql = true)
            }
        }
    }

    /** `RETURNING … INTO` of an `INSERT`, `UPDATE` or `DELETE` ([root]): kept with its items and targets, and the parameters it returns. */
    private fun returningInto(statement: Statement, root: AstNode) {
        val returning = root.getFirstDescendantOrNull(DmlGrammar.RETURNING_INTO_CLAUSE) ?: return
        val into = returning.getFirstChild(DmlGrammar.INTO_CLAUSE)
        val values = returning.children
            .filter { it.type != PlSqlKeyword.RETURNING && it.type != PlSqlKeyword.RETURN && it.type != PlSqlPunctuator.COMMA && it !== into }
        val items = values.map { queryItems.item(statement, it, null) }
        pending += Pending(PlaceSource.RETURNING, isBulk(into), statement.context, statement.id, listOf(items), null, targetsOf(into))
        selectedParameters(statement, values, into)
    }

    /** The parameters the cursor query [root] selects: what a FETCH reads of them is not followed. */
    private fun fetchedParameters(statement: Statement, root: AstNode) {
        for (block in branches(root)) for (column in block.getChildren(DmlGrammar.SELECT_COLUMN)) {
            for (name in parameterNames(column.firstChild)) {
                if (queryItems.columnOf(statement, name) == null) flows += Flow(name, spanOf(column), statement.context, shape = ARGUMENT_FETCHED)
            }
        }
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
            for ((name, functions) in parameterChains(value, parameterNames(value).filter { queryItems.columnOf(statement, it) == null })) {
                flows += when {
                    functions == null -> Flow(name, spanOf(value), statement.context, shape = PlaceShapes.ARGUMENT_EXPRESSION + expressions.argumentShape(value, name))
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
    fun function(node: AstNode, key: String?) {
        if (node.type != PlSqlGrammar.FUNCTION_DECLARATION && node.type != PlSqlGrammar.CREATE_FUNCTION) return
        val returns = returnKey(key ?: intoTargets.localKey(node.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) ?: return, true))
        functions[node] = returns
        if (key?.startsWith(FORMS_PREFIX) == true) moduleFunctions += returns
    }

    /** The key of what the function [function] (its entity key, or its nested declaration's [localKey]) returns. */
    private fun returnKey(function: String) = "$function.${PlaceShapes.RETURN_SUFFIX}"

    /** What the function around [node] returns ([returnKey]); null outside a function. */
    private fun enclosingReturn(node: AstNode): String? = generateSequence(node.parentOrNull) { it.parentOrNull }.firstNotNullOfOrNull { functions[it] }

    fun routine(node: AstNode, context: Context) {
        val outside = node.hasDirectChildren(PlSqlGrammar.CALL_SPECIFICATION) || node.hasDirectChildren(PlSqlKeyword.AGGREGATE) ||
            node.children.any { it.hasDirectChildren(PlSqlKeyword.PIPELINED) && it.hasDirectChildren(PlSqlKeyword.USING) } ||
            node.hasDirectChildren(PlSqlKeyword.PIPELINED) && node.hasDirectChildren(PlSqlKeyword.USING)
        if (!outside) return
        // The routine's parameters, by the routine's key (the context is the routine's).
        val routine = context.container ?: return
        for (parameter in node.getFirstChildOrNull(PlSqlGrammar.PARAMETER_DECLARATIONS)?.getChildren(PlSqlGrammar.PARAMETER_DECLARATION).orEmpty()) {
            val name = parameter.firstChild
            flows += Flow(name, spanOf(name), context, shape = PlaceShapes.BODY_NOT_PLSQL, fromKey = "$routine.${normalizeIdentifier(name.tokenOriginalValue)}")
        }
    }

    /** `FETCH c INTO …`: the cursor's query decides the items (its own file's, for a cursor with an entity). */
    fun fetch(node: AstNode, context: Context) {
        if (context.dynamic != null) return
        val into = node.getFirstChildOrNull(DmlGrammar.INTO_CLAUSE) ?: return
        val cursor = node.children.firstOrNull { it.type == PlSqlGrammar.MEMBER_EXPRESSION || it.type == PlSqlGrammar.VARIABLE_NAME } ?: return
        pending += Pending(PlaceSource.FETCH, isBulk(into), context, null, emptyList(), cursor, targetsOf(into), node)
    }

    /** `OPEN cv FOR <string>` ([open]): its [text] when zpa parsed it (null when not), and its `DYNAMIC` occurrence's position. */
    fun openText(open: AstNode, text: DynamicSqlText?, occurrence: Int) {
        cursors.openText(open, text, occurrence)
    }

    /** `EXECUTE IMMEDIATE … INTO` or `RETURNING INTO`: counted, one per target. */
    fun dynamic(node: AstNode) {
        for (into in node.getDescendants(DmlGrammar.INTO_CLAUSE)) state.leftOut(DYNAMIC_INTO, targetsOf(into).size)
    }

    /**
     * `target := r.f`, or an expression of one such field (`upper(r.f)`): kept to be decided once the file is left,
     * when what `r` is is known ([intos]). Through value functions only ([ExpressionShapes.valueChain]), the field is read as is.
     */
    fun assignment(node: AstNode, context: Context) {
        val at = node.children.indexOfFirst { it.type == PlSqlPunctuator.ASSIGNMENT }
        if (at <= 0) return
        val value = node.children.getOrNull(at + 1) ?: return
        valueWrites += ValueWrite(value, spanOf(node.children[at]), node.firstChild, context)
        // A field only in an argument that never lands in the value (the search text of a REPLACE) is not assigned.
        val fields = (if (isFieldOfName(value)) listOf(value) else value.getDescendants(PlSqlGrammar.MEMBER_EXPRESSION).filter(::isFieldOfName))
            .filter { !expressions.inDroppedArgument(value, it) }
        val field = fields.singleOrNull()
        if (field == null) {
            if (fields.isEmpty()) return
            // Several fields, each a part of a concatenation (`r.code || r.note`), or one the value and the others text put
            // into it (the replacement of a REPLACE): a place for each. Any other expression of them is counted by its shape.
            val chains = fields.map { f -> expressions.valueChain(value) { it === f } }
            if (chains.any { it == null } || chains.count { CONCATENATED !in it!! } > 1) {
                assignments += Assignment(node.firstChild, spanOf(node.children[at]), fields.first(), value, emptyList(), context, several = true)
                return
            }
            for ((f, chain) in fields.zip(chains)) assignments += Assignment(node.firstChild, spanOf(node.children[at]), f, null, chain!!, context)
            return
        }
        val functions = expressions.valueChain(value) { it === field }
        assignments += Assignment(node.firstChild, spanOf(node.children[at]), field, if (functions == null) value else null,
            functions.orEmpty(), context)
    }

    /** `RETURN expr`: its value leaves the routine, which the index does not follow ([intos]). */
    fun returnStatement(node: AstNode, context: Context) {
        val value = node.children.firstOrNull { it.type != PlSqlGrammar.LABELS && it.type != PlSqlKeyword.RETURN && it.type != PlSqlPunctuator.SEMICOLON }
            ?: return
        valueWrites += ValueWrite(value, spanOf(node), null, context)
    }

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
    private fun isLoopQuery(query: AstNode): Boolean =
        generateSequence(query.parentOrNull) { it.parentOrNull }.take(6).any { it.type == PlSqlGrammar.ITERATOR }

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
            if (reason != null) for (argument in arguments(node)) passedValue(argument, null, reason, context, spanOf(node))
            return
        }
        for (argument in arguments(node)) resolvedArgument(argument, node, resolved, context)
    }

    /**
     * An argument of the call [node] resolved to [resolved]: for an `OUT` or `IN OUT` parameter, kept with the parameter
     * the call ties it to; for any other, passed as a value ([passedValue]).
     */
    private fun resolvedArgument(argument: AstNode, node: AstNode, resolved: OccurrenceFact, context: Context) {
        // The walk keeps the mode at the argument's expression node, which may wrap or be wrapped by this one.
        val span = spanOf(argument)
        val mode = (generateSequence(argument) { it.parentOrNull }.take(3) + generateSequence(argument) { it.children.firstOrNull() }.take(4))
            .filter { it.hasToken() && spanOf(it) == span }.firstNotNullOfOrNull { state.argumentModes[it] }
        val parameter = resolved.arguments.firstOrNull { it.span == span }?.parameterKey
        if (mode == ProjectParameterMode.OUT || mode == ProjectParameterMode.IN_OUT) {
            if (parameter != null) outArguments += OutArgument(argument, parameter, context, spanOf(node))
            return
        }
        passedValue(argument, parameter, if (parameter == null) notTied(resolved) else null, context, spanOf(node))
    }

    /**
     * An argument of a PL/SQL call for an `IN` parameter ([parameter]): a record's field, read as is or through value
     * functions, is a column's value passed to the routine (decided after the scan); one of the routine's own `IN`
     * parameters is passed on. [call] is the call's span: what comes back out of the routine goes back to it.
     */
    private fun passedValue(argument: AstNode, parameter: String?, reason: String?, context: Context, call: SourceSpan) {
        callArguments += CallArgument(argument, parameter, reason, context, call)
        val fields = if (isFieldOfName(argument)) listOf(argument)
            else argument.getDescendants(PlSqlGrammar.MEMBER_EXPRESSION).filter { isFieldOfName(it) && !expressions.inDroppedArgument(argument, it) }
        if (fields.size > 1) {
            // An expression of several fields: counted by its shape when the first is of a row record.
            passes += Pass(argument, null, fields.first(), emptyList(), parameter, reason, context, null, call, PlaceShapes.SEVERAL_COLUMNS + expressions.expressionShape(argument))
        }
        val field = fields.singleOrNull()
        if (field != null) {
            expressions.valueChain(argument) { it === field }?.let { passes += Pass(argument, null, field, it, parameter, reason, context, null, call) }
            return
        }
        for (name in parameterNames(argument)) {
            val functions = expressions.valueChain(argument) { it === name } ?: continue
            flows += if (reason == null && parameter != null) Flow(name, spanOf(argument), context, passedTo = parameter, functions = functions, call = call)
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
        val subprograms = nearestSubprograms(node, normalizeIdentifier(callee.tokenOriginalValue))
        val subprogram = subprograms.singleOrNull()
        if (subprogram == null) {
            undecidedLocalCall(node, subprograms, context)
            return
        }
        if (subprogram.type == PlSqlGrammar.FUNCTION_DECLARATION) {
            subprogram.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { localFunctions[node] = returnKey(intoTargets.localKey(it, true)) }
        }
        val parameters = parametersOf(subprogram)
        val written = node.getFirstChildOrNull(PlSqlGrammar.ARGUMENTS)?.getChildren(PlSqlGrammar.ARGUMENT).orEmpty()
        for ((index, argument) in written.withIndex()) {
            val parameter = boundParameter(argument, index, parameters)
            if (parameter != null && isOutParameter(parameter)) outArguments += OutArgument(argument.lastChild, intoTargets.localKey(parameter), context, spanOf(node))
            // A nested subprogram's IN parameter: followed by where it is declared in the file.
            else passedValue(argument.lastChild, parameter?.let(intoTargets::localKey), if (parameter == null) ARGUMENT_NOT_TIED else null, context, spanOf(node))
        }
    }

    /** The procedures and functions named [wanted] in the nearest declarations around [node] that declare any so named. */
    private fun nearestSubprograms(node: AstNode, wanted: String): List<AstNode> {
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
        return subprograms
    }

    /**
     * A call [node] of a nested subprogram not decided among [subprograms] (overloads, or none): kept for each of their
     * `OUT` parameters; with overloads, its arguments are not followed.
     */
    private fun undecidedLocalCall(node: AstNode, subprograms: List<AstNode>, context: Context) {
        for (parameter in subprograms.flatMap(::parametersOf).filter(::isOutParameter)) undecidedCalls += spanOf(node) to intoTargets.localKey(parameter)
        if (subprograms.isNotEmpty()) for (argument in arguments(node)) passedValue(argument, null, ARGUMENT_LOCAL_SUBPROGRAM, context, spanOf(node))
    }

    /** The parameter declarations of [subprogram]. */
    private fun parametersOf(subprogram: AstNode) =
        subprogram.getFirstChildOrNull(PlSqlGrammar.PARAMETER_DECLARATIONS)?.getChildren(PlSqlGrammar.PARAMETER_DECLARATION).orEmpty()

    /** Whether the parameter declaration [parameter] is `OUT` or `IN OUT`. */
    private fun isOutParameter(parameter: AstNode) = parameter.hasDirectChildren(PlSqlKeyword.OUT)

    /** The parameter among [parameters] the written argument [argument], at [index], is for: by its name when it has one, else by position. */
    private fun boundParameter(argument: AstNode, index: Int, parameters: List<AstNode>): AstNode? {
        val label = argument.takeIf { it.hasDirectChildren(PlSqlPunctuator.ASSOCIATION) }?.firstChild?.let { normalizeIdentifier(it.tokenOriginalValue) }
        return if (label == null) parameters.getOrNull(index)
            else parameters.firstOrNull { normalizeIdentifier(it.firstChild.tokenOriginalValue) == label }
    }

    /**
     * The functions [value] calls whose result is the value, or a part of it: each one's result going to [to], through
     * the value functions that lead to the call; a call within any other expression is counted by that expression's
     * shape (`RETURNED_EXPRESSION_*`). A call inside the arguments of another routine is that routine's argument.
     */
    private fun resultFlows(value: AstNode, site: SourceSpan, context: Context, to: ResultDestination, called: Map<SourceSpan, OccurrenceFact>) {
        val (target, passedTo, reason, argumentOf) = to
        if (target == null && passedTo == null && reason == null) return
        for ((call, result) in resultCalls(value, called)) {
            if (expressions.inDroppedArgument(value, call)) continue
            val chain = expressions.valueChain(value) { it === call }
            val of = spanOf(call)
            flows += when {
                chain == null -> Flow(call, site, context, shape = RETURNED_EXPRESSION + expressions.argumentShape(value, call), fromKey = result, resultOf = of)
                reason != null -> Flow(call, site, context, shape = reason, fromKey = result, resultOf = of)
                target != null -> Flow(call, site, context, target = target, functions = chain, fromKey = result, resultOf = of)
                else -> Flow(call, site, context, passedTo = passedTo, functions = chain, fromKey = result, resultOf = of, call = argumentOf)
            }
        }
    }

    /**
     * The calls of functions in [value], not counting those inside another call's arguments: each with the key of what
     * the function returns ([returnKey]). A function is a routine call the index resolves (with or without an argument
     * list), a nested function of the file or a function program unit of the Forms module the file is.
     */
    private fun resultCalls(value: AstNode, called: Map<SourceSpan, OccurrenceFact>): List<Pair<AstNode, String>> {
        val result = ArrayList<Pair<AstNode, String>>()
        fun visit(node: AstNode) {
            val key = when (node.type) {
                PlSqlGrammar.METHOD_CALL -> localFunctions[node] ?: called[spanOf(node.firstChild)]?.entityKey
                PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.MEMBER_EXPRESSION ->
                    called[spanOf(node)]?.takeIf { node.parentOrNull?.type != PlSqlGrammar.METHOD_CALL || node.parentOrNull?.firstChild !== node }?.entityKey
                else -> null
            }
            if (key != null && (key.startsWith(PlaceShapes.LOCAL_PREFIX) || ROUTINE_KEY.containsMatchIn(key) || returnKey(key) in moduleFunctions)) {
                result += node to (if (key.startsWith(PlaceShapes.LOCAL_PREFIX)) key else returnKey(key))
                return
            }
            // The arguments of any other routine's call belong to that call.
            if (node.type == PlSqlGrammar.METHOD_CALL && called[spanOf(node.firstChild)] != null) return
            node.children.forEach(::visit)
        }
        visit(value)
        return result
    }

    /**
     * The variables and record fields [write] gives on, as is or through value functions: what `RETURN` gives back (of
     * the function whose result is [returned]), or what is written into an `OUT` or `IN OUT` parameter of the
     * subprogram ([ValueWrite.target]). Whatever column's value they hold goes there. Within any other expression, counted
     * by its shape (`RETURNED_EXPRESSION_*`) when a column's value reaches them: the value leaves the routine either way.
     * A variable passed to a routine (one of [routineCalls], or a nested function) is that call's argument, not this.
     */
    private fun heldVariables(
        write: ValueWrite, returned: String?, parameters: List<AstNode>, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes,
        routineCalls: Set<SourceSpan>
    ) {
        val names = (sequenceOf(write.value) + write.value.getDescendants(PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.MEMBER_EXPRESSION).asSequence())
            .filter { (it.type == PlSqlGrammar.VARIABLE_NAME || it.type == PlSqlGrammar.MEMBER_EXPRESSION) && plsql.isValueName(it) && plsql.nameSegments(it) != null }
            .filter { name -> parameters.none { it === name } && generateSequence(name.parentOrNull) { it.parentOrNull }.takeWhile { it !== write.value.parentOrNull }
                .none { it.type == PlSqlGrammar.METHOD_CALL && (it in localFunctions || spanOf(it.firstChild) in routineCalls) } }
            .filter { !expressions.inDroppedArgument(write.value, it) }
            .toList()
        for (name in names) {
            val held = intoTargets.target(name, bySpan, shapes).takeIf { it.entityKey != null && !it.element } ?: continue
            val key = held.entityKey + held.path.joinToString("") { ".$it" }
            val chain = expressions.valueChain(write.value) { it === name }
            flows += when {
                chain == null -> Flow(name, write.site, write.context, shape = RETURNED_EXPRESSION + expressions.argumentShape(write.value, name), fromKey = key)
                returned == null -> Flow(name, write.site, write.context, target = write.target, functions = chain, fromKey = key)
                else -> Flow(name, write.site, write.context, passedTo = returned, functions = chain, fromKey = key)
            }
        }
    }

    /** Whether [target], a whole name, is an `OUT` or `IN OUT` parameter of the subprogram the code at it is in. */
    private fun isOwnOutParameter(target: AstNode, shapes: TypeShapes): Boolean {
        if (!isParameterName(target)) return false
        val declaration = ownDeclaration(target, target, shapes) ?: return false
        return declaration.type == PlSqlGrammar.PARAMETER_DECLARATION && declaration.hasDirectChildren(PlSqlKeyword.OUT)
    }

    /**
     * The declaration of the variable or parameter [target] names (the record of a field, the collection of an element),
     * when it is declared in the subprogram [site] is in, the innermost one: null for any other.
     */
    private fun ownDeclaration(target: AstNode, site: AstNode, shapes: TypeShapes): AstNode? {
        val name = generateSequence(target) { if (it.type in WHOLE_OF) it.firstChild else null }.last()
        val declared = (nameBinding(name) as? NameBinding.Local)?.declaration?.takeIf { it.fileId == null } ?: return null
        val declaration = shapes.localDeclaration(declared.line, declared.column) ?: return null
        val routine = subprogramOf(site) ?: return null
        return declaration.takeIf { subprogramOf(it) === routine }
    }

    /** The innermost subprogram [node] is in. */
    private fun subprogramOf(node: AstNode): AstNode? = generateSequence(node.parentOrNull) { it.parentOrNull }.firstOrNull { it.type in SUBPROGRAMS }

    /** The key of the nested subprogram's parameter zpa binds [name] to, which has no entity ([IntoTargets.localKey]); null for any other. */
    private fun localParameterKey(name: AstNode, shapes: TypeShapes): String? {
        val declaration = parameterDeclaration(name)?.takeIf { it.fileId == null } ?: return null
        return shapes.localDeclaration(declaration.line, declaration.column)?.takeIf { it.type == PlSqlGrammar.PARAMETER_DECLARATION }?.let(intoTargets::localKey)
    }

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

    /**
     * In static SQL, a column read passed to a routine (not a built-in SQL function), as is or through value functions:
     * kept with the `IN` parameter the call ties it to, or with why it is not followed (the routine is not decided, it
     * is outside the index, or the call does not tie the argument to a parameter).
     */
    private fun passedColumns(statement: Statement) {
        val calls = state.occurrencesOf(statement.id).filter { Role.CALL in it.roles }.associateBy { it.span }
        if (calls.isEmpty()) return
        for (call in statement.root.getDescendants(PlSqlGrammar.METHOD_CALL)) {
            val occurrence = calls[spanOf(call.firstChild)] ?: continue
            for (argument in arguments(call)) {
                val item = queryItems.item(statement, argument, null)
                for (part in listOf(item) + item.parts) {
                val column = part.columnKey ?: continue
                val functions = part.functions
                val parameter = occurrence.arguments.firstOrNull { it.span == spanOf(argument) }?.parameterKey
                val reason = when {
                    occurrence.entityKey == null || occurrence.status !in CALLED_STATUSES -> unresolvedCall(occurrence) ?: continue
                    parameter == null -> notTied(occurrence)
                    else -> null
                }
                passes += Pass(argument, column, null, functions, parameter, reason, statement.context, statement.id, spanOf(call))
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
            same.firstNotNullOfOrNull { name -> expressions.valueChain(value) { it === name }?.let { name to it } } ?: (same.first() to null)
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

    /**
     * The `INTO`s of the file and its assignments of a record's field, their targets and records decided, and the row
     * record fields passed counted: call once the file is left, when the field uses are recorded. The expression items an
     * `INTO` reads are counted after the scan, with the cursors of other files.
     */
    fun intos(shapes: TypeShapes): List<IntoFact> {
        if (pending.isEmpty() && assignments.isEmpty() && outArguments.isEmpty() && undecidedCalls.isEmpty() && passes.isEmpty() &&
            valueWrites.isEmpty() && flows.isEmpty()) return emptyList()
        val bySpan = state.occurrences.filter { it.entityKey != null }.groupBy { it.span }
        val intos = pending.mapNotNullTo(ArrayList()) { pendingInto(it, bySpan, shapes) }
        val callers = state.occurrences.filter { Role.CALL in it.roles && it.entityKey != null && it.status in CALLED_STATUSES }
            .mapTo(HashSet()) { it.span }
        assignments.mapNotNullTo(intos) { assignmentInto(it, callers, bySpan, shapes) }
        // The argument passed for an OUT or IN OUT parameter, as an INTO target: it takes what the parameter is given.
        outArguments.mapTo(intos) { argument ->
            IntoFact(PlaceSource.OUT_ARGUMENT, false, argument.context.unitIndex, argument.context.container, null, emptyList(), null,
                listOf(intoTargets.target(argument.value, bySpan, shapes)), parameterKey = argument.parameterKey, call = argument.call,
                keeps = ownDeclaration(argument.value, argument.value, shapes) != null)
        }
        passes.mapNotNullTo(intos) { passInto(it, bySpan, shapes) }
        // What a routine writes its IN parameters into, passes them on to, or writes them in a way not followed.
        val routineCalls = state.occurrences.filter { Role.CALL in it.roles && it.entityKey != null }.mapTo(HashSet()) { it.span }
        val called = state.occurrences.filter { Role.CALL in it.roles && it.entityKey != null && it.status in CALLED_STATUSES }
            .associateBy { it.span }
        for (write in valueWrites) writeFlows(write, bySpan, shapes, called, routineCalls)
        // The results of the functions an argument calls, passed to the parameter.
        for (argument in callArguments) {
            val reason = argument.reason ?: ARGUMENT_NOT_TIED.takeIf { argument.parameter == null }
            val to = ResultDestination(passedTo = argument.parameter, reason = reason, argumentOf = argument.call)
            resultFlows(argument.argument, spanOf(argument.argument), argument.context, to, called)
        }
        val statusBySpan = state.occurrences.groupBy { it.span }
        flows.mapNotNullTo(intos) { flowInto(it, bySpan, statusBySpan, shapes) }
        // A call of an overloaded nested subprogram: no target, only the parameters it may be for.
        undecidedCalls.mapTo(intos) { (span, parameter) ->
            IntoFact(PlaceSource.OUT_ARGUMENT, false, 0, null, null, emptyList(), null,
                listOf(IntoTarget.of(span, "", null, reason = OUT_CALL_NOT_DECIDED)), parameterKey = parameter, call = span)
        }
        return intos
    }

    /**
     * The `INTO` [into] with its targets decided, and the items of its cursor for a `FETCH`: those of the `OPEN`s of a
     * cursor variable that may run last before it, else those of the cursor's own query. Null when no such `OPEN` is
     * followed.
     */
    private fun pendingInto(into: Pending, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): IntoFact? {
        val targets = into.targets.map { intoTargets.target(it, bySpan, shapes) }
        // A cursor variable: the queries of the OPENs that may run last before the FETCH, when there are some.
        val variable = into.cursor?.let(cursors::cursorDeclaration)
        val opens = variable?.let { cursors.reachingOpens(into.fetch!!, it) }.orEmpty()
        if (opens.isNotEmpty()) return openedFetch(into, opens, targets, shapes)
        val cursor = into.cursor
            ?: return intoFact(into, targets, into.branches)
        val cursorKey = bySpan[spanOf(cursor)]?.firstOrNull()?.entityKey
        if (cursorKey != null) {
            val (kind, type) = cursors.cursorKind(cursor, shapes)
            return intoFact(into, targets, into.branches, cursorKey = cursorKey, cursorKind = kind, cursorType = type)
        }
        val declaration = intoTargets.local(cursor, shapes)
        val query = declaration?.takeIf { it.type == PlSqlGrammar.CURSOR_DECLARATION }?.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)
        val items = query?.let { state.cursorItems[it] }
        // Not known: counted once the types of other files are known ([ColumnPlaces]), by the cursor it is.
        if (items == null) {
            val (kind, type) = cursors.cursorKind(cursor, shapes)
            return intoFact(into, targets, emptyList(), statementId = null, cursorKind = kind, cursorType = type)
        }
        return intoFact(into, targets, listOf(items))
    }

    /** The [IntoFact] of [into], its [targets] decided, reading the items [branches] of [statementId], or the cursor [cursorKey]. */
    private fun intoFact(
        into: Pending, targets: List<IntoTarget>, branches: List<List<QueryItem>>, statementId: Int? = into.statementId, cursorKey: String? = null,
        cursorKind: String? = null, cursorType: String? = null
    ) = IntoFact(into.source, into.bulk, into.context.unitIndex, into.context.container, statementId, branches, cursorKey, targets,
        cursorKind = cursorKind, cursorType = cursorType)

    /**
     * The `FETCH` [into] from a cursor variable, its [targets] decided, that [opens] may have opened: the items of each
     * query followed, the targets counted once when one is not; null when none is followed.
     */
    private fun openedFetch(into: Pending, opens: List<AstNode>, targets: List<IntoTarget>, shapes: TypeShapes): IntoFact? {
        val (kind, type) = cursors.cursorKind(into.cursor!!, shapes)
        val read = opens.map(cursors::opened)
        // One of them not followed: the FETCH's targets counted once, by the first such.
        read.firstNotNullOfOrNull { it as? CursorOpens.Opened.NotFollowed }?.let { state.leftOut(PlaceShapes.FETCH_CURSOR_NOT_KNOWN + it.shape, into.targets.size) }
        val queries = read.mapNotNull { (it as? CursorOpens.Opened.Items)?.items }
        if (queries.isEmpty()) return null
        return intoFact(into, targets, queries, cursorKind = kind ?: "OPENED", cursorType = type)
    }

    /**
     * The assignment of a record's field [assignment], its target and record decided; null when the record is no row
     * record, or when the field is passed to a function the index follows (one of [callers], or a nested function).
     */
    private fun assignmentInto(
        assignment: Assignment, callers: Set<SourceSpan>, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes
    ): IntoFact? {
        // A field passed to a function the index follows is that call's argument, its result what is assigned.
        if (assignment.expression != null && generateSequence(assignment.field.parentOrNull) { it.parentOrNull }
                .takeWhile { it !== assignment.expression.parentOrNull }
                .any { it.type == PlSqlGrammar.METHOD_CALL && (it in localFunctions || spanOf(it.firstChild) in callers) }) return null
        val row = intoTargets.record(assignment.field, bySpan, shapes) ?: return null
        val target = intoTargets.target(assignment.target, bySpan, shapes).copy(span = assignment.site)
        return IntoFact(PlaceSource.ASSIGNMENT, false, assignment.context.unitIndex, assignment.context.container, null, emptyList(), null,
            listOf(target), row, normalizeIdentifier(assignment.field.lastChild.tokenOriginalValue), assignmentShape(assignment), assignment.functions)
    }

    /**
     * The shape of the expression [assignment] assigns: [PlaceShapes.SEVERAL_COLUMNS] and its shape for several fields, else its
     * shape as [ExpressionShapes.valueShape] says it for the field; null for the field read as is or through value functions.
     */
    private fun assignmentShape(assignment: Assignment): String? {
        val expression = assignment.expression ?: return null
        return if (assignment.several) PlaceShapes.SEVERAL_COLUMNS + expressions.expressionShape(expression) else expressions.valueShape(expression, { it === assignment.field })
    }

    /** A column, or a record's field, passed to a routine's `IN` parameter ([pass]); null for a field of no row record. */
    private fun passInto(pass: Pass, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes): IntoFact? {
        val argument = IntoTarget.of(spanOf(pass.argument), writtenText(pass.argument), null, reason = pass.reason)
        if (pass.field != null) {
            val row = intoTargets.record(pass.field, bySpan, shapes) ?: return null
            return IntoFact(PlaceSource.IN_ARGUMENT, false, pass.context.unitIndex, pass.context.container, pass.statementId, emptyList(), null,
                listOf(argument), row, normalizeIdentifier(pass.field.lastChild.tokenOriginalValue), pass.several, pass.functions, pass.parameterKey, call = pass.call)
        }
        return IntoFact(PlaceSource.IN_ARGUMENT, false, pass.context.unitIndex, pass.context.container, pass.statementId,
            listOf(listOf(QueryItem(null, pass.column, null, functions = pass.functions))), null, listOf(argument),
            functions = pass.functions, parameterKey = pass.parameterKey, call = pass.call)
    }

    /**
     * What the value [write] writes of the functions it calls ([called]), of the routine's `IN` parameters and, given
     * back or written into an `OUT` parameter, of the variables it holds: into its target, or to what the function around
     * it returns. Nothing for a `RETURN` outside a function.
     */
    private fun writeFlows(
        write: ValueWrite, bySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes, called: Map<SourceSpan, OccurrenceFact>,
        routineCalls: Set<SourceSpan>
    ) {
        // A returned value goes to what the function around it returns.
        val returned = if (write.target == null) enclosingReturn(write.value) ?: return else null
        resultFlows(write.value, write.site, write.context, ResultDestination(target = write.target, passedTo = returned), called)
        if (write.sql) return
        val names = writtenParameters(write, routineCalls)
        for ((name, functions) in parameterChains(write.value, names)) {
            flows += when {
                functions == null -> Flow(name, write.site, write.context, shape = PlaceShapes.ARGUMENT_EXPRESSION + expressions.argumentShape(write.value, name))
                returned != null -> Flow(name, write.site, write.context, passedTo = returned, functions = functions)
                else -> Flow(name, write.site, write.context, target = write.target, functions = functions)
            }
        }
        if (returned != null || write.target != null && isOwnOutParameter(write.target, shapes)) heldVariables(write, returned, names, bySpan, shapes, routineCalls)
    }

    /**
     * The names of the routine's parameters the value [write] writes: not in an argument that never lands in the value,
     * and not passed to a routine inside it ([routineCalls]), which is that call's argument ([call]).
     */
    private fun writtenParameters(write: ValueWrite, routineCalls: Set<SourceSpan>): List<AstNode> =
        parameterNames(write.value).filter { name ->
            !expressions.inDroppedArgument(write.value, name) && generateSequence(name.parentOrNull) { it.parentOrNull }.takeWhile { it !== write.value.parentOrNull }
                .none { it.type == PlSqlGrammar.METHOD_CALL && spanOf(it.firstChild) in routineCalls }
        }

    /**
     * The `IN` parameter [flow] tells, as an [IntoFact] from the parameter; null when the parameter has no key. A name in
     * SQL a column may take instead of the parameter is the parameter only when the index resolves the name, the
     * statement's tables' columns known and none of them so named: else it is counted as such.
     */
    private fun flowInto(
        flow: Flow, bySpan: Map<SourceSpan, List<OccurrenceFact>>, statusBySpan: Map<SourceSpan, List<OccurrenceFact>>, shapes: TypeShapes
    ): IntoFact? {
        val from = flow.fromKey ?: bySpan[spanOf(flow.parameter)]?.firstOrNull()?.entityKey ?: localParameterKey(flow.parameter, shapes) ?: return null
        val columnPossible = flow.fromKey == null && flow.shape == null && nameBinding(flow.parameter) is NameBinding.ColumnOrLocal &&
            statusBySpan[spanOf(flow.parameter)].orEmpty().none { it.status == Status.RESOLVED }
        if (columnPossible) {
            return IntoFact(PlaceSource.IN_ARGUMENT, false, flow.context.unitIndex, flow.context.container, null, emptyList(), null,
                listOf(IntoTarget.of(flow.site, "", null)), expression = ARGUMENT_COLUMN_POSSIBLE,
                fromParameter = from)
        }
        val targets = flow.target?.let { listOf(intoTargets.target(it, bySpan, shapes).copy(span = flow.site)) }
            ?: listOf(IntoTarget.of(flow.site, "", null))
        val branches = flow.item?.let { (index, size) ->
            listOf(List(size) { if (it == index) QueryItem(null, from, null, functions = flow.functions) else QueryItem(null, null, null) })
        }.orEmpty()
        return IntoFact(PlaceSource.IN_ARGUMENT, flow.bulk, flow.context.unitIndex, flow.context.container, null, branches, null, targets,
            expression = flow.shape, functions = flow.functions, parameterKey = flow.passedTo, fromParameter = from, call = flow.call,
            resultOf = flow.resultOf, keeps = flow.target != null && ownDeclaration(flow.target, flow.parameter, shapes) != null)
    }

    companion object {
        /** The subprograms whose own declarations [ownDeclaration] tells apart, a nested one included. */
        private val SUBPROGRAMS = setOf(
            PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION, PlSqlGrammar.CREATE_PROCEDURE, PlSqlGrammar.CREATE_FUNCTION
        )

        /** What a target goes through to the name it starts with: a field, an element, an object reference. */
        private val WHOLE_OF = setOf(PlSqlGrammar.MEMBER_EXPRESSION, PlSqlGrammar.METHOD_CALL, PlSqlGrammar.OBJECT_REFERENCE)

        private const val DYNAMIC_INTO = "DYNAMIC_INTO"
        private const val ARGUMENT_ROUTINE_UNDECIDED = "ARGUMENT_ROUTINE_UNDECIDED"
        private const val ARGUMENT_ROUTINE_OUTSIDE = "ARGUMENT_ROUTINE_OUTSIDE"
        private const val ARGUMENT_NOT_TIED = "ARGUMENT_NOT_TIED"
        /** The prefix of the shape of a function's result written within an expression ([resultFlows]). */
        private const val RETURNED_EXPRESSION = "RETURNED_EXPRESSION_"
        private const val ARGUMENT_LOCAL_SUBPROGRAM = "ARGUMENT_LOCAL_SUBPROGRAM"
        private const val ARGUMENT_CONSTRUCTOR = "ARGUMENT_CONSTRUCTOR"
        private const val ARGUMENT_FETCHED = "ARGUMENT_FETCHED"
        private const val ARGUMENT_COLUMN_POSSIBLE = "ARGUMENT_COLUMN_POSSIBLE"
        private const val ARGUMENT_INTO_NOT_FOLLOWED = "ARGUMENT_INTO_NOT_FOLLOWED"
        private const val OUT_CALL_NOT_DECIDED = "OUT_CALL_NOT_DECIDED"

        /** The reason of a call of a subprogram nested in a routine, which has no entity. */
        private const val LOCAL_SUBPROGRAM = "LOCAL_SUBPROGRAM"

        /** A routine's key ends with its parameter list. */
        private val ROUTINE_KEY = Regex("""\)(@BODY)?(#\d+)?$""")

        /** The statuses of a call that ties its arguments to the routine's parameters. */
        private val CALLED_STATUSES = setOf(Status.RESOLVED, Status.PARTIAL_SOURCE)
    }
}
