//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.index.PlaceTypes.Node
import com.felipebz.zpa.atlas.model.*
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.collections.ArrayDeque

/**
 * One following over [facts] of the places a column's value reaches through routines ([result]), from the places in
 * [found] (by file path) and the columns calls pass to `IN` parameters. Each place is built by [direct], each type
 * followed by [types].
 *
 * Into a routine: each target a routine writes its `IN` parameter's value into (as is or through value functions)
 * is a place of the column, once per column and target however many calls pass it, naming the parameter the column
 * was passed to, how many calls pass it and one of them. A parameter passed on to another routine's `IN` parameter
 * is followed there, once per parameter and column. A parameter only read (compared, in a condition) gives nothing.
 *
 * Back out of it: what a function returns, and what an `OUT` or `IN OUT` parameter is given, goes back to the calls
 * the value came into the routine through. A column passed at one call comes back out at that call only; a column
 * the routine reads itself, or one kept where it outlives the call (a package variable, a variable of the routine
 * around a nested subprogram), at every call. An `OUT` parameter's value is a place of each argument callers pass
 * for it, decided as for an `INTO` target; an argument that is itself an `OUT` or `IN OUT` parameter of the caller
 * goes on to that routine's callers. A variable that holds such a value (a target a value was written into, an
 * argument an `OUT` parameter gave it) goes on to where the routine writes it: what it returns, its own `OUT`
 * parameters.
 *
 * What is not followed is counted by shape, in the file of the call or of the routine's statement: the routine not
 * decided (`ARGUMENT_ROUTINE_UNDECIDED`), outside the index ([PlaceShapes.ARGUMENT_ROUTINE_OUTSIDE]: a call the
 * dictionary resolves, a routine whose body the sources do not have, or one whose body is not PL/SQL), the argument
 * not tied to a parameter (`ARGUMENT_NOT_TIED`), an overloaded nested subprogram (`ARGUMENT_LOCAL_SUBPROGRAM`), a
 * parameter named in SQL on a table whose columns are not known (`ARGUMENT_COLUMN_POSSIBLE`), a parameter a cursor
 * selects (`ARGUMENT_FETCHED`) or selected into a record whose `INTO` targets do not match its items
 * (`ARGUMENT_INTO_NOT_FOLLOWED`), a record field whose column is not known ([PlaceShapes.ARGUMENT_COLUMN_NOT_KNOWN],
 * by why: [PlaceTypes.notKnown]), the parameter returned (`ARGUMENT_RETURNED`) or written within an expression
 * (`ARGUMENT_EXPRESSION_*`, by its shape), a function's result in a record, a collection or an object
 * ([PlaceShapes.RETURNED_NOT_SCALAR]); an `OUT` parameter whose callers cannot be told
 * ([PlaceShapes.OUT_PARAMETER_NOT_FOLLOWED]), an `OUT` argument the index does not bind
 * ([PlaceShapes.OUT_ARGUMENT_NOT_FOLLOWED], an expression or a name zpa binds to nothing), and a call whose routine is
 * not decided among candidates that have such a parameter ([PlaceShapes.OUT_CALL_NOT_DECIDED]).
 */
internal class RoutineFlow(
    private val types: PlaceTypes,
    private val direct: DirectPlaces,
    private val facts: List<FileFacts>,
    private val found: Map<String, List<ColumnPlaceFact>>
) {
    /**
     * Where the values of a routine came in: through its parameter [parameter] (its key), carrying [column] read
     * through [functions]. A value read in the routine itself has no entry (null): it comes back out to every caller.
     */
    private data class Entry(val parameter: String, val column: String, val functions: List<String>)

    /**
     * A node (a parameter, a variable a function returns, what a function returns) a column's value reached, through
     * [functions], from [previous]: the path back to where the value came in. [entry] is where it came into the routine
     * the node is in; [calls] the calls that passed the column, for the places it gives.
     */
    private class Reached(
        val parameter: String, val column: String, val functions: List<String>, val calls: List<Pair<String, IntoFact>>,
        val entry: Entry?, val previous: Reached?,
        /** For a value given back to a call: the caller's value at the call, whose path joins [previous]'s (the callee's). */
        val caller: Reached? = null
    ) {
        /** The value on the paths that reached this one, this one included, at [node]; null when none is. */
        fun at(node: String): Reached? {
            val seen = Collections.newSetFromMap(IdentityHashMap<Reached, Boolean>())
            val stack = ArrayDeque<Reached>()
            stack += this
            while (stack.isNotEmpty()) {
                val reached = stack.removeLast()
                if (!seen.add(reached)) continue
                if (reached.parameter == node) return reached
                reached.previous?.let { stack += it }
                reached.caller?.let { stack += it }
            }
            return null
        }

        /** Whether [node] is on the paths that reached this one, this one included: going there again is a cycle. */
        fun onPath(node: String): Boolean = at(node) != null
    }

    /**
     * A call that brings values into a routine through an [Entry]: the call ([call], its span in the file [path]), and
     * the caller's own entry and value there ([entry], [reached]), which what comes back out of the routine goes on with.
     */
    private class Incoming(val path: String, val call: SourceSpan?, val entry: Entry?, val reached: Reached?)

    /** A value an `OUT` parameter ([parameter]) is given: [column] through [functions], come into its routine at [entry]. */
    private data class OutValue(
        val parameter: String,
        val column: String,
        val functions: List<String>,
        val entry: Entry?
    )

    /** The work [queue] holds: a value at its node, or what reached a function's return given back to one call. */
    private sealed interface Work {
        class Step(val reached: Reached) : Work
        class Back(val exit: Reached, val to: Incoming) : Work
    }

    /** A place by its file, position, column and functions as its row tells them ([DirectPlaces.written]). */
    private data class PlaceKey(
        val path: String,
        val span: SourceSpan?,
        val column: String,
        val functions: Pair<List<String>, Boolean>
    )

    /** A statement of the file [path] at [span], for [column] (null: whatever the column), counted once ([countOnce]). */
    private data class Site(val path: String, val span: SourceSpan, val column: String?)

    /** A call ([call] of the file [path], from a caller come in at [callerEntry]) bringing [entry] in: taken once. */
    private data class Call(val entry: Entry, val path: String, val call: SourceSpan?, val callerEntry: Entry?)

    // What the facts tell: the routines, where their parameters' values go, the arguments of their OUT parameters, and
    // the columns calls pass.

    /** The routines whose body the sources have, as calls name them. */
    private val defined = definedRoutines()

    /** The parameters of routines whose body is not PL/SQL: what calls give them is counted at each call. */
    private val outside = notPlsqlParameters()

    /** Where the value of each routine's parameter goes: the statements of the routine that write it or pass it on. */
    private val flows = HashMap<String, MutableList<Pair<String, IntoFact>>>()

    /** The arguments callers pass for each OUT or IN OUT parameter. */
    private val byParameter = outArguments()

    /** The calls that pass each column, through its functions, to a parameter. */
    private val seeds = LinkedHashMap<Triple<String, String, List<String>>, MutableList<Pair<String, IntoFact>>>()

    // The work: a value at a node, or what reached a function's return given back to one call that brought its entry in;
    // once none is left, an OUT parameter's value given back to its callers (all of them, or one call that came in later).
    private val queue = ArrayDeque<Work>()
    private val outQueue = ArrayDeque<Pair<OutValue, Incoming?>>()
    private val seen = HashSet<Pair<Entry?, Triple<String, String, List<String>>>>()
    private val outSeen = HashSet<OutValue>()

    // The calls each entry came in through; what reached the return of its routine, and what its OUT parameters were
    // given: given back to each call that brings it in, now or later.
    private val incoming = HashMap<Entry, MutableList<Incoming>>()
    private val exits = HashMap<Entry, MutableList<Reached>>()
    private val outs = HashMap<Entry, MutableList<OutValue>>()
    private val calledWith = HashSet<Call>()

    // What it reached: the places, by file path, each made once, and what is not followed, counted by shape in each
    // file, each once.
    private val places = HashMap<String, MutableList<ColumnPlaceFact>>()
    private val leftOut = HashMap<String, MutableMap<String, Int>>()

    /** The places found directly (a row record's field assigned to the target): not made again through a variable. */
    private val directKeys = found.flatMapTo(HashSet()) { (path, list) -> list.map { placeKey(path, it) } }

    /** Each place made through a variable, by its file and position in the file's [places]. */
    private val placed = HashMap<PlaceKey, Pair<String, Int>>()

    /** The calls each place of a function's result was written by: another call of it into the same target counts once more. */
    private val placedCalls = HashMap<PlaceKey, MutableSet<SourceSpan?>>()
    private val outPlaced = HashSet<PlaceKey>()
    private val counted = HashSet<Site>()
    private val notFollowedOut = HashSet<PlaceKey>()
    private val notFollowed = HashSet<PlaceKey>()
    private val undecided = HashSet<Pair<String, SourceSpan>>()

    /** The places followed, by file path, and what is not followed, counted by shape in each file. */
    fun result(): Pair<Map<String, List<ColumnPlaceFact>>, Map<String, Map<String, Int>>> {
        collectArguments()
        enterSeeds()
        enterFound()
        drain()
        countUndecidedCalls()
        return places to leftOut
    }

    private fun count(path: String, shape: String) {
        leftOut.getOrPut(path) { HashMap() }.merge(shape, 1, Int::plus)
    }

    /** Counts [shape] in [path] once per statement at [span] and [column] (null: once whatever the column). */
    private fun countOnce(path: String, span: SourceSpan, column: String?, shape: String) {
        if (counted.add(Site(path, span, column))) count(path, shape)
    }

    private fun placeKey(path: String, span: SourceSpan?, column: String, functions: List<String>) =
        PlaceKey(path, span, column, DirectPlaces.written(functions))

    private fun placeKey(path: String, place: ColumnPlaceFact) =
        placeKey(path, place.span, place.columnKey, place.functions)

    /** Whether the call site [path]/[call] is one of [incoming]'s calls: one the extraction did not tie is everyone's (not expected). */
    private fun isCall(incoming: Incoming, path: String, call: SourceSpan?) =
        incoming.path == path && (call == null || incoming.call == null || incoming.call == call)

    /** The routines of [facts] whose body the sources have, as calls name them. */
    private fun definedRoutines(): Set<String> {
        val defined = HashSet<String>()
        for (file in facts) for (occurrence in file.occurrences) {
            if (Role.DEFINITION in occurrence.roles && occurrence.entityKey != null) defined += occurrence.entityKey.removeSuffix(
                "@BODY"
            )
        }
        return defined
    }

    /** The parameters of the routines of [facts] whose body is not PL/SQL. */
    private fun notPlsqlParameters(): Set<String> {
        val outside = HashSet<String>()
        for (file in facts) for (into in file.intos) {
            if (into.fromParameter != null && into.expression == PlaceShapes.BODY_NOT_PLSQL) outside += parameterKey(
                file.path,
                into.fromParameter
            )
        }
        return outside
    }

    /** The arguments the calls of [facts] pass for each OUT or IN OUT parameter. */
    private fun outArguments(): Map<String, List<Pair<String, IntoFact>>> {
        val byParameter = HashMap<String, MutableList<Pair<String, IntoFact>>>()
        for (file in facts) for (into in file.intos) if (into.source == PlaceSource.OUT_ARGUMENT && into.parameterKey != null) {
            byParameter.getOrPut(parameterKey(file.path, into.parameterKey)) { ArrayList() } += file.path to into
        }
        return byParameter
    }

    /** Whether the body of [parameter]'s routine is in the sources: a nested subprogram's parameter (its file's) has it where declared. */
    private fun isDefined(parameter: String) =
        parameter !in outside && ('|' in parameter || parameter.substringBeforeLast('.') in defined)

    /** The `IN` arguments of [facts]: into [flows], those within a routine, and into [seeds], the columns calls pass. */
    private fun collectArguments() {
        for (file in facts) for (into in file.intos) if (into.source == PlaceSource.IN_ARGUMENT) inArgument(
            file.path,
            into
        )
    }

    /** The `IN` argument [into] of the file [path]: a flow of its routine's parameter, or a column a call passes. */
    private fun inArgument(path: String, into: IntoFact) {
        if (into.fromParameter == null) {
            seed(path, into)
            return
        }
        if (into.expression != PlaceShapes.BODY_NOT_PLSQL) flows.getOrPut(
            parameterKey(
                path,
                into.fromParameter
            )
        ) { ArrayList() } += path to into
    }

    /** The columns the call argument [into] of the file [path] passes to its parameter, into [seeds]; counted when not followed. */
    private fun seed(path: String, into: IntoFact) {
        if (into.row != null && into.expression?.startsWith(PlaceShapes.SEVERAL_COLUMNS) == true) {
            // An expression of several fields of a row record (`nvl(r.code, r.note)`): counted by its shape.
            if (types.rowRecord(into.row) != null) count(path, PlaceShapes.ARGUMENT_EXPRESSION + into.expression)
            return
        }
        val columns = passedColumns(path, into) ?: return
        val reason = into.targets.single().reason
        if (reason != null) {
            count(path, reason)
            return
        }
        val parameter = parameterKey(path, into.parameterKey ?: return)
        if (!isDefined(parameter)) {
            count(path, PlaceShapes.ARGUMENT_ROUTINE_OUTSIDE)
            return
        }
        for ((column, functions) in columns) seeds.getOrPut(
            Triple(
                parameter,
                column,
                functions
            )
        ) { ArrayList() } += path to into
    }

    /**
     * The columns, with their functions, the argument [into] of the file [path] passes: those of a row record's field, or
     * its item's; null when none is known, a field whose column is not known counted.
     */
    private fun passedColumns(path: String, into: IntoFact): List<Pair<String, List<String>>>? {
        if (into.row == null) {
            val item = into.branches.single().single()
            return listOf((item.columnKey ?: return null) to item.functions)
        }
        val row = types.rowRecord(into.row) ?: return null
        val column = types.field(row, null, into.rowField).first as? Node.Column
        if (column == null) {
            count(path, types.notKnown(PlaceShapes.ARGUMENT_COLUMN_NOT_KNOWN, row, into.rowField))
            return null
        }
        return column.all.map { (key, functions) -> key to (into.functions + functions) }
    }

    /** The value [column], through [functions], at [node], come in at [entry]: queued, unless it was there already. */
    private fun reach(
        node: String,
        column: String,
        functions: List<String>,
        calls: List<Pair<String, IntoFact>>,
        entry: Entry?,
        previous: Reached?
    ) {
        if (seen.add(entry to Triple(node, column, functions))) queue += Work.Step(
            Reached(
                node,
                column,
                functions,
                calls,
                entry,
                previous
            )
        )
    }

    /** [entry] brought in by [coming]: what reached the return of its routine, and what its OUT parameters were given, go back to it. */
    private fun call(entry: Entry, coming: Incoming) {
        if (!calledWith.add(Call(entry, coming.path, coming.call, coming.entry))) return
        incoming.getOrPut(entry) { ArrayList() } += coming
        for (exit in exits[entry].orEmpty()) queue += Work.Back(exit, coming)
        for (out in outs[entry].orEmpty()) outQueue += out to coming
    }

    /** An OUT parameter's value ([place] in [path], whose parameter it is), come into its routine at each of [entries]. */
    private fun follow(path: String, place: ColumnPlaceFact, entries: Collection<Entry?>) {
        val parameter = place.targetKey ?: place.localParameter
        if (parameter == null) {
            // A CALLER_DECIDES place whose parameter can be told neither by its entity nor by its declaration.
            if (notFollowedOut.add(placeKey(path, place))) count(path, PlaceShapes.OUT_PARAMETER_NOT_FOLLOWED)
            return
        }
        for (entry in entries) {
            val value = OutValue(parameterKey(path, parameter), place.columnKey, place.functions, entry)
            if (!outSeen.add(value)) continue
            if (entry != null) outs.getOrPut(entry) { ArrayList() } += value
            outQueue += value to null
        }
    }

    /**
     * A variable or field holding a column's value ([place], made by [flow]) that the routine returns or writes into
     * its OUT parameters: what its places hold goes there, with the [calls] that brought it. A variable that outlives
     * the call the value came in at (a package variable, a variable of the routine around a nested subprogram) holds
     * it for every caller: what returns it gives it back to every call, as a column the routine reads itself. A
     * variable of the subprogram the value came into ([IntoFact.keeps]) keeps the call.
     */
    private fun held(
        path: String,
        flow: IntoFact?,
        place: ColumnPlaceFact,
        entry: Entry?,
        previous: Reached?,
        calls: List<Pair<String, IntoFact>>
    ) {
        val key = place.targetKey ?: return
        val kept = entry?.takeIf { flow?.keeps == true }
        val held = listOfNotNull(place.field?.let { "$key.$it" }, key).map { parameterKey(path, it) }
        for (node in held) {
            if (node !in flows || previous?.onPath(node) == true) continue
            reach(node, place.columnKey, place.functions, calls, kept, previous)
        }
    }

    /** Each column calls pass to a parameter, at the parameter, come in through those calls. */
    private fun enterSeeds() {
        for ((key, calls: List<Pair<String, IntoFact>>) in seeds) {
            val entry = Entry(key.first, key.second, key.third)
            for ((path, into) in calls) call(entry, Incoming(path, into.call, null, null))
            reach(
                key.first,
                key.second,
                key.third,
                calls.sortedWith(compareBy({ it.first }, { it.second.targets.single().span })),
                entry,
                null
            )
        }
    }

    /** The places in [found]: what holds their values goes on, and an OUT parameter's value goes back to its callers. */
    private fun enterFound() {
        for ((path, list) in found) for (place in list) {
            held(path, null, place, null, null, emptyList())
            if (place.placeClass == PlaceClass.CALLER_DECIDES) follow(path, place, listOf(null))
        }
    }

    /**
     * [flow] of [path] taking the value [reached] has ([reached] at a function's return: the flow is at a call of it), in
     * a routine whose values came in at [entry], [previous] the path to it.
     */
    private fun along(path: String, flow: IntoFact, reached: Reached, entry: Entry?, previous: Reached) {
        // A function the value already went through adds nothing when it comes again (`||` of a value already
        // concatenated, `upper` of an upper-cased one): kept once, so that a value that goes round through an `OUT`
        // parameter it is written back into (`o_msg := o_msg || …`) reaches a node already seen instead of a new one
        // on every turn.
        val functions = (flow.functions + reached.functions).distinct()
        val site = flow.targets.single().span
        when {
            flow.expression != null -> countOnce(path, site, reached.column, flow.expression)
            flow.parameterKey != null -> passedOn(
                path,
                flow,
                parameterKey(path, flow.parameterKey),
                reached,
                entry,
                previous,
                functions
            )

            else -> intoTarget(path, flow, reached, entry, previous, functions)
        }
    }

    /** [flow] of [path] passing the value [reached] has, through [functions], on to the parameter [next], as [along] takes it. */
    private fun passedOn(
        path: String,
        flow: IntoFact,
        next: String,
        reached: Reached,
        entry: Entry?,
        previous: Reached,
        functions: List<String>
    ) {
        val returns = reached.parameter.endsWith(".$RETURN")
        // A function's result passed to a parameter: that call is the one that passes the column.
        val calls = if (returns && !next.endsWith(".$RETURN")) listOf(path to flow) else reached.calls
        // A cycle (a routine passing its parameter to itself, a function returning its own result) only
        // wraps the value in more functions on each turn: what it reaches, the path without it reached.
        val again = if (isDefined(next)) previous.at(next) else null
        when {
            !isDefined(next) -> countOnce(
                path,
                flow.targets.single().span,
                reached.column,
                PlaceShapes.ARGUMENT_ROUTINE_OUTSIDE
            )
            // Into a parameter already on the path (a routine reached again within itself, or the same function
            // nested in its own argument): not walked again, but what its entry there gives back comes back
            // out at this call too.
            again != null -> if (!next.endsWith(".$RETURN")) again.entry?.let {
                call(
                    it,
                    Incoming(path, flow.call, entry, previous)
                )
            }

            next.endsWith(".$RETURN") -> reach(next, reached.column, functions, calls, entry, previous)
            else -> {
                // Into another routine through this call: what comes back out of it, back here.
                val into = Entry(next, reached.column, functions)
                call(into, Incoming(path, flow.call, entry, previous))
                reach(next, reached.column, functions, calls, into, previous)
            }
        }
    }

    /** [flow] of [path] writing the value [reached] has, through [functions], into its target, as [along] takes it: a place. */
    private fun intoTarget(
        path: String,
        flow: IntoFact,
        reached: Reached,
        entry: Entry?,
        previous: Reached,
        functions: List<String>
    ) {
        val returns = reached.parameter.endsWith(".$RETURN")
        val key = placeKey(path, flow.targets.single().span, reached.column, functions)
        if (!returns && key in directKeys) return
        val earlier = placed[key]
        val place = if (earlier != null) placedAgain(key, earlier, flow, returns) else newPlace(
            path,
            flow,
            reached,
            functions,
            key
        )
        if (place.placeClass == PlaceClass.CALLER_DECIDES) follow(path, place, listOf(entry))
        held(path, flow, place, entry, previous, if (returns) listOf(path to flow) else reached.calls)
    }

    /** The place made before for [key] ([earlier]: its file and position), one call more when another call of a function writes there. */
    private fun placedAgain(
        key: PlaceKey,
        earlier: Pair<String, Int>,
        flow: IntoFact,
        returns: Boolean
    ): ColumnPlaceFact {
        val list = places.getValue(earlier.first)
        if (returns && placedCalls.getValue(key).add(flow.resultOf)) {
            val place = list[earlier.second]
            list[earlier.second] = place.copy(callCount = (place.callCount ?: 1) + 1)
        }
        return list[earlier.second]
    }

    /** The new place, kept as [key], [flow] of [path] makes of the value [reached] has through [functions]. */
    private fun newPlace(
        path: String,
        flow: IntoFact,
        reached: Reached,
        functions: List<String>,
        key: PlaceKey
    ): ColumnPlaceFact {
        // Read by an INTO with a single target: a record takes the item as its field, as for a column.
        val target = flow.targets.single()
        val items = flow.branches.singleOrNull()
        val field = items?.takeIf { it.size > 1 || types.isRow(target, flow.bulk) }
            ?.indexOfFirst { it.columnKey == flow.fromParameter }
        val made = direct.place(path, flow, target, field, reached.column, functions)
        val place = if (reached.parameter.endsWith(".$RETURN")) {
            made.copy(
                source = PlaceSource.RETURNED,
                argumentOf = specKey(reached.parameter),
                callCount = 1,
                callPath = path,
                callLine = target.span.startLine
            )
        } else {
            // A column read in the routine itself, held by a variable written here: no call brought it,
            // the value lands by the statement that writes the target (`p_out := l`), as any other.
            val (callPath, call) = reached.calls.firstOrNull() ?: (null to null)
            if (call == null) made.copy(source = if (flow.branches.isEmpty()) PlaceSource.ASSIGNMENT else PlaceSource.SELECT_INTO)
            else made.copy(
                argumentOf = call.parameterKey,
                callCount = reached.calls.size,
                callPath = callPath,
                callLine = call.targets.single().span.startLine
            )
        }
        val list = places.getOrPut(path) { ArrayList() }
        placed[key] = path to list.size
        placedCalls[key] = hashSetOf(flow.resultOf)
        list += place
        return place
    }

    /** What reached a function's return ([exit]) goes back to the calls of it [to] names (all, for a value with no entry). */
    private fun back(exit: Reached, to: Incoming?) {
        val calls =
            flows[exit.parameter].orEmpty().filter { (path, flow) -> to == null || isCall(to, path, flow.resultOf) }
        // A function that returns a record, a collection or an object: its callers are not followed, each counted.
        if (!types.isScalar(specKey(exit.parameter))) {
            for ((path, flow) in calls) countOnce(
                path,
                flow.targets.single().span,
                null,
                PlaceShapes.RETURNED_NOT_SCALAR
            )
            return
        }
        // Back in the caller: the path goes on from the callee's and from the caller's value at the call.
        val joined = if (to?.reached == null) exit else Reached(
            exit.parameter,
            exit.column,
            exit.functions,
            exit.calls,
            to.entry,
            exit,
            to.reached
        )
        for ((path, flow) in calls) along(path, flow, exit, to?.entry, joined)
    }

    /**
     * The arguments callers pass for the OUT parameter of [value]: the calls the value came into the routine through take
     * it ([to] alone, a call that came in after the value was given; all, for a value with no entry), each going on in
     * its caller with the caller's entry and value at the call.
     */
    private fun outBack(value: OutValue, to: Incoming?) {
        for ((path, into) in byParameter[value.parameter].orEmpty()) outArgument(value, to, path, into)
    }

    /** The argument [into] of the file [path] for the OUT parameter of [value], as [outBack] takes it. */
    private fun outArgument(value: OutValue, to: Incoming?, path: String, into: IntoFact) {
        val (_, column, functions, entry) = value
        val back = callsBack(entry, to, path, into.call)
        if (back.isEmpty()) return
        val target = into.targets.single()
        if (target.reason == PlaceShapes.OUT_CALL_NOT_DECIDED) {
            if (undecided.add(path to target.span)) count(path, PlaceShapes.OUT_CALL_NOT_DECIDED)
            return
        }
        if (target.entityKey == null && target.local == null) {
            if (notFollowed.add(placeKey(path, target.span, column, functions))) count(
                path,
                PlaceShapes.OUT_ARGUMENT_NOT_FOLLOWED
            )
            return
        }
        val place = direct.place(path, into, target, null, column, functions)
        if (outPlaced.add(placeKey(path, place))) places.getOrPut(path) { ArrayList() } += place
        if (place.placeClass == PlaceClass.CALLER_DECIDES) follow(
            path,
            place,
            back.mapTo(LinkedHashSet()) { it?.entry })
        else for (coming in back) held(path, into, place, coming?.entry, coming?.reached, listOf(path to into))
    }

    /**
     * The calls the call site [path]/[call] takes a value come in at [entry] back through: [to] alone when given, else
     * every call [entry] came in through; one with no call (null) for a value with no entry.
     */
    private fun callsBack(entry: Entry?, to: Incoming?, path: String, call: SourceSpan?): List<Incoming?> = when {
        entry == null -> listOf(null)
        to != null -> listOf(to).filter { isCall(it, path, call) }
        else -> incoming[entry].orEmpty().filter { isCall(it, path, call) }
    }

    /** Follows the work in [queue] and, whenever none is left there, the next value in [outQueue], until neither has any. */
    private fun drain() {
        while (queue.isNotEmpty() || outQueue.isNotEmpty()) {
            if (queue.isEmpty()) {
                val (value, to) = outQueue.removeFirst()
                outBack(value, to)
                continue
            }
            when (val work = queue.removeFirst()) {
                is Work.Step -> step(work.reached)
                is Work.Back -> back(work.exit, work.to)
            }
        }
    }

    /** The value [reached] at its node: at a function's return, given back to its calls; else along the node's flows. */
    private fun step(reached: Reached) {
        if (reached.parameter.endsWith(".$RETURN")) returned(reached)
        else for ((path, flow) in flows[reached.parameter].orEmpty()) along(path, flow, reached, reached.entry, reached)
    }

    /** What reached a function's return: given back to each call that brought its entry in, now or later (all, with no entry). */
    private fun returned(reached: Reached) {
        val entry = reached.entry
        if (entry == null) {
            back(reached, null)
            return
        }
        exits.getOrPut(entry) { ArrayList() } += reached
        for (coming in incoming[entry].orEmpty().toList()) back(reached, coming)
    }

    /** Counts each call the index could not decide among routines, one of which leaves a column's size to its callers. */
    private fun countUndecidedCalls() {
        val routines =
            outSeen.filter { !it.parameter.contains('|') }.mapTo(HashSet()) { it.parameter.substringBeforeLast('.') }
        for (file in facts) for (occurrence in file.occurrences) {
            if (Role.CALL in occurrence.roles && occurrence.entityKey == null && occurrence.candidateKeys.any { it in routines }) {
                count(file.path, PlaceShapes.OUT_CALL_NOT_DECIDED)
            }
        }
    }

    /**
     * A parameter as a call binds it: a place's target in a package body is the body's parameter (`…)@BODY.P`), a
     * call's argument the spec's (`…).P`); a routine only the body declares is the same key on both sides.
     */
    private fun specKey(parameter: String) = parameter.replace(")@BODY.", ").")

    /** The key a parameter is followed by: a nested subprogram's is only known in its own file ([path]). */
    private fun parameterKey(path: String, parameter: String) =
        if (parameter.startsWith(PlaceShapes.LOCAL_PREFIX)) "$path|$parameter" else specKey(parameter)

    private companion object {
        const val RETURN = PlaceShapes.RETURN_SUFFIX
    }
}
