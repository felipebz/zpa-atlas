//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.extract.FORMS_PREFIX
import com.felipebz.zpa.atlas.extract.IntoClauses
import com.felipebz.zpa.atlas.extract.TypeShapes
import com.felipebz.zpa.atlas.extract.displayOf
import com.felipebz.zpa.atlas.model.*
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.collections.ArrayDeque

/**
 * The places column values are read into ([ColumnPlaceFact]), decided once every file is scanned, since a target, its
 * type, an anchor or a cursor may be declared in another file. The select item at position i goes to the target at
 * position i; a single target that takes several items, or a whole row, is a record that takes them field by field, in
 * order; a `BULK COLLECT` target is a collection whose element takes the value.
 *
 * The class of a place follows the target's type to where it ends ([PlaceClass]):
 * - an anchor (`%TYPE`) to a column is [PlaceClass.ANCHORED_TO_COLUMN] when it is the column read, else
 *   [PlaceClass.ANCHORED_ELSEWHERE]; a `%ROWTYPE` field is the relation's column of that name or position, a cursor
 *   record's field the column its select item is;
 * - an anchor to a variable, parameter or field, a subtype and a type name are followed to their declaration;
 * - a written length or precision (or a bounded type such as `PLS_INTEGER`) is [PlaceClass.FIXED_SIZE], a number's as
 *   the digits before the point and the scale; a type without a size (`NUMBER`, `CLOB`, `DATE`) is [PlaceClass.NO_SIZE];
 * - an `OUT` or `IN OUT` parameter taking the value itself is [PlaceClass.CALLER_DECIDES]: the value lands in each
 *   caller's argument, which does not take the size of the parameter's type;
 * - anything that cannot be followed is [PlaceClass.UNKNOWN], with the reason.
 *
 * A Forms item or parameter a module's code names is typed by what it holds
 * ([com.felipebz.zpa.atlas.extract.FormsEntities.shapes]); a global holds text of no declared length.
 *
 * A character length written without a unit counts in the `NLS_LENGTH_SEMANTICS` the unit declaring it was compiled
 * with, as the dictionary gives it ([compiledSemantics]): the package body for a variable of the body, the package spec
 * for a field of a record type of the spec, wherever the place is. Without it, in [defaultSemantics].
 */
internal class ColumnPlaces(
    facts: List<FileFacts>,
    private val kinds: Map<String, EntityKind>,
    /** The columns of each dictionary relation, in their position order. */
    private val relationColumns: Map<String, List<String>>,
    /** The `NLS_LENGTH_SEMANTICS` each PL/SQL unit was compiled with (`ALL_PLSQL_OBJECT_SETTINGS`). */
    private val compiledSemantics: Map<CompiledUnit, String> = emptyMap(),
    /** `BYTE` or `CHAR`: the unit of a length written without one that [compiledSemantics] does not decide. */
    private val defaultSemantics: String = "BYTE"
) {
    private val shapes = HashMap<String, TypeShape>()
    private val texts = HashMap<String, String>()

    /** The units of each file, by path. */
    private val units = HashMap<String, List<UnitFact>>()

    /** The unit that declares each entity with a type, whose compilation decides a length written there without a unit. */
    private val declaredIn = HashMap<String, UnitFact>()

    init {
        for (file in facts) {
            // A package cursor declared in the spec and defined in the body: the body's query is its row.
            for (fact in file.declaredShapes) {
                val known = shapes[fact.entityKey]
                if (known == null || known !is TypeShape.CursorRow && fact.shape is TypeShape.CursorRow) shapes[fact.entityKey] = fact.shape
            }
            // A Forms item's or parameter's text is what its page shows (`CHAR 5`): its place takes the type from its shape.
            for (text in file.declaredTexts) {
                if (text.datatype != null && kinds[text.entityKey] != EntityKind.FORMS_ITEM && kinds[text.entityKey] != EntityKind.FORMS_PARAMETER) {
                    texts.putIfAbsent(text.entityKey, text.datatype)
                }
            }
            units[file.path] = file.units
        }
        for (file in facts) for (occurrence in file.occurrences) {
            val key = occurrence.entityKey ?: continue
            if (Role.DEFINITION !in occurrence.roles && Role.DECLARATION !in occurrence.roles) continue
            if (key in shapes || kinds[key] == EntityKind.RECORD_FIELD) file.units.getOrNull(occurrence.unitIndex)?.let { declaredIn.putIfAbsent(key, it) }
        }
    }

    /** What a step of the following reached: a declaration's shape (of [owner], when it has an entity), a column, or a failure. */
    private sealed interface Node {
        data class Shape(val shape: TypeShape, val owner: String?) : Node
        /** A column, read through [functions] (a cursor field `nvl(code, '-')`), outer first. */
        data class Column(val key: String, val functions: List<String> = emptyList(), val parts: List<QueryItem> = emptyList()) : Node {
            /** The column and those of the other parts of a concatenation of several, with their functions. */
            val all get() = listOf(key to functions) + parts.mapNotNull { part -> part.columnKey?.let { it to part.functions } }
        }
        data class Failed(val reason: String) : Node
    }

    private class Decision(
        val placeClass: PlaceClass, val length: Int? = null, val unit: String? = null, val scale: Int? = null, val source: String? = null,
        val reason: String? = null
    )

    /** A character length's [unit], where it comes from ([source]) and the unit whose compilation gave it ([compiledIn]). */
    private class LengthUnit(val unit: String?, val source: UnitSource? = null, val compiledIn: CompiledUnit? = null)

    /**
     * The places of [file], and what its `INTO`s and assignments read without being followed, by shape: the expression
     * items of one column, the fetches from an unknown cursor, the assignments of a row record's field whose column is
     * not known, and those of an expression of such a field. In a Forms module, each item its block's query reads a
     * column into ([PlaceSource.BLOCK_QUERY]: the implicit read of the item's column) is a place of the column.
     */
    fun of(file: FileFacts): Pair<List<ColumnPlaceFact>, Map<String, Int>> {
        val places = ArrayList<ColumnPlaceFact>()
        val leftOut = HashMap<String, Int>()
        blockQueries(file, places)
        for (into in file.intos) intoPlaces(file.path, into, places, leftOut)
        // The same column at the same target more than once (two branches of a compound query reading it, a field
        // concatenated twice): one place.
        return places.distinctBy { it.copy(functions = rowFunctions(it.functions)) } to leftOut
    }

    /** The places of the Forms items of [file] whose block's query reads a column into them. */
    private fun blockQueries(file: FileFacts, places: MutableList<ColumnPlaceFact>) {
        for (use in file.formsUses) {
            if (Role.IMPLICIT !in use.roles || Role.READ !in use.roles || kinds[use.containerKey] != EntityKind.FORMS_ITEM || use.status !in COLUMN_STATUSES) continue
            places += blockQuery(file.path, use.containerKey, use.entityKey?.takeIf { kinds[it] == EntityKind.COLUMN } ?: continue)
        }
    }

    /** The places [into], a statement of the file [path], reads columns into, and what it reads without following it, in [leftOut]. */
    private fun intoPlaces(path: String, into: IntoFact, places: MutableList<ColumnPlaceFact>, leftOut: MutableMap<String, Int>) {
        if (into.source == PlaceSource.ASSIGNMENT) {
            assignment(path, into, places, leftOut)
            return
        }
        // The arguments of OUT parameters are followed from the places of their parameters, the values passed to IN
        // parameters from their calls ([followed]).
        if (into.source == PlaceSource.OUT_ARGUMENT || into.source == PlaceSource.IN_ARGUMENT) return
        // A FETCH from a cursor variable, which has no entity: its row is not known.
        if (into.source == PlaceSource.FETCH && into.cursorKey == null && into.branches.isEmpty()) {
            leftOut.merge(fetchNotKnown(into, null), into.targets.size, Int::plus)
            return
        }
        for (items in branchesOf(into, leftOut) ?: return) branchPlaces(path, into, items, places, leftOut)
    }

    /** The branches of the query [into] reads: its cursor's row, for a cursor; null, counted in [leftOut], when that row is not known. */
    private fun branchesOf(into: IntoFact, leftOut: MutableMap<String, Int>): List<List<QueryItem>>? {
        val cursorKey = into.cursorKey ?: return into.branches
        val row = shapes[cursorKey] as? TypeShape.CursorRow
        if (row == null) {
            leftOut.merge(fetchNotKnown(into, cursorKey), into.targets.size, Int::plus)
            return null
        }
        return listOf(row.items)
    }

    /** The places of the select [items] of one branch of the query [into] reads, at the targets that take them. */
    private fun branchPlaces(
        path: String, into: IntoFact, items: List<QueryItem>, places: MutableList<ColumnPlaceFact>, leftOut: MutableMap<String, Int>
    ) {
        // A `*` whose columns are not known: no position can be told after it.
        val known = items.takeWhile { !it.opaque }
        val record = takesRecord(into, items, known.size)
        // A `*` over a relation or collection whose columns are not known: the positions after it cannot be told,
        // those before it only for a record.
        if (into.source == PlaceSource.FETCH && known.size < items.size) {
            leftOut.merge(FETCH_CURSOR_STAR_NOT_KNOWN, into.targets.size, Int::plus)
            if (known.isEmpty() || !record) return
        }
        // A cursor variable's query its targets do not match: Oracle raises at the FETCH, or the query is another one.
        if (into.cursorKind != null && !record && into.targets.size != known.size) {
            leftOut.merge(FETCH_CURSOR_ITEMS_NOT_MATCHED, into.targets.size, Int::plus)
            return
        }
        itemPlaces(path, into, known, record, places, leftOut)
    }

    /**
     * Whether the target of [into] is a single record that takes the [items] field by field, [known] of them before a `*`
     * whose columns are not known. From a cursor variable, a single target takes several items only when it is a record.
     */
    private fun takesRecord(into: IntoFact, items: List<QueryItem>, known: Int): Boolean = into.targets.size == 1 &&
        if (into.cursorKind != null) isRow(into.targets[0], into.bulk) else items.size > 1 || known < items.size || isRow(into.targets[0], into.bulk)

    /** The places of the [known] items of [into], each at its target, or at its field of the single target when that is a [record]. */
    private fun itemPlaces(
        path: String, into: IntoFact, known: List<QueryItem>, record: Boolean, places: MutableList<ColumnPlaceFact>, leftOut: MutableMap<String, Int>
    ) {
        for ((index, item) in known.withIndex()) {
            val target = if (record) into.targets[0] else into.targets.getOrNull(index) ?: break
            if (item.expression != null) leftOut.merge("EXPRESSION_${item.expression}", 1, Int::plus)
            val field = index.takeIf { record }
            for (part in listOf(item) + item.parts) {
                places += place(path, into, target, field, part.columnKey ?: continue, part.functions)
            }
        }
    }

    /**
     * `target := r.f`: a place of the column `f` stands for when `r` is a row record (a cursor `FOR` loop record, a
     * variable of a `%ROWTYPE`, or of a cursor's row), decided as a `FETCH` from that cursor decides it. Any other record
     * is not a row: nothing. A field whose column is not known (an expression of the cursor's query, a cursor or
     * `%ROWTYPE` not known) is counted by why ([notKnown]), and so is an expression of the field (`upper(r.code)`) by its shape.
     */
    private fun assignment(path: String, into: IntoFact, places: MutableList<ColumnPlaceFact>, leftOut: MutableMap<String, Int>) {
        val row = structure(targetNode(into.row ?: return, false), 0)
        if (row !is Node.Shape || !(row.shape is TypeShape.CursorRow || row.shape is TypeShape.Anchored && row.shape.rowType)) return
        val column = field(row, null, into.rowField).first as? Node.Column
        when {
            column == null -> leftOut.merge(notKnown("RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN_", row, into.rowField), 1, Int::plus)
            into.expression != null -> leftOut.merge("RECORD_ASSIGNMENT_EXPRESSION_${into.expression}", 1, Int::plus)
            else -> for ((key, functions) in column.all) places += place(path, into, into.targets.single(), null, key, into.functions + functions)
        }
    }

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
    private data class OutValue(val parameter: String, val column: String, val functions: List<String>, val entry: Entry?)

    private fun placeKey(path: String, place: ColumnPlaceFact) = listOf(path, place.span, place.columnKey, written(place.functions))

    /**
     * The functions as a place row tells them: those a value went through, outer first, without the concatenations,
     * and whether there was one. Chains that differ only in where a concatenation is (`||,TRIM` and `TRIM,||`, after a
     * cycle that mixes them) are the same row.
     */
    private fun written(functions: List<String>) = functions.filter { it != CONCATENATED } to (CONCATENATED in functions)

    /** [functions] as the row tells them ([written]), as a list: the concatenation, if any, last. */
    private fun rowFunctions(functions: List<String>) = written(functions).let { (named, concatenated) -> if (concatenated) named + CONCATENATED else named }

    /** Whether the call site [path]/[call] is one of [incoming]'s calls: one the extraction did not tie is everyone's (not expected). */
    private fun isCall(incoming: Incoming, path: String, call: SourceSpan?) = incoming.path == path && (call == null || incoming.call == null || incoming.call == call)

    /**
     * The places a column's value reaches through routines, from the places in [found] (by file path) and the columns
     * calls pass to `IN` parameters.
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
     * decided (`ARGUMENT_ROUTINE_UNDECIDED`), outside the index (`ARGUMENT_ROUTINE_OUTSIDE`: a call the dictionary
     * resolves, a routine whose body the sources do not have, or one whose body is not PL/SQL), the argument not tied
     * to a parameter (`ARGUMENT_NOT_TIED`), an overloaded nested subprogram (`ARGUMENT_LOCAL_SUBPROGRAM`), a parameter
     * named in SQL on a table whose columns are not known (`ARGUMENT_COLUMN_POSSIBLE`), a parameter a cursor selects
     * (`ARGUMENT_FETCHED`) or selected into a record whose `INTO` targets do not match its items
     * (`ARGUMENT_INTO_NOT_FOLLOWED`), a record field whose column is not known (`ARGUMENT_COLUMN_NOT_KNOWN_*`, by why:
     * [notKnown]), the parameter returned (`ARGUMENT_RETURNED`) or written within an expression
     * (`ARGUMENT_EXPRESSION_*`, by its shape); an `OUT` argument the index does not bind (`OUT_ARGUMENT_NOT_FOLLOWED`,
     * an expression or a name zpa binds to nothing), and a call whose routine is not decided among candidates that have
     * such a parameter (`OUT_CALL_NOT_DECIDED`).
     */
    fun followed(facts: List<FileFacts>, found: Map<String, List<ColumnPlaceFact>>): Pair<Map<String, List<ColumnPlaceFact>>, Map<String, Map<String, Int>>> =
        Following(facts, found).result()

    /** One following of [followed] over [facts], from the places in [found]: what it reached, and the work left. */
    private inner class Following(private val facts: List<FileFacts>, private val found: Map<String, List<ColumnPlaceFact>>) {
        private val places = HashMap<String, MutableList<ColumnPlaceFact>>()
        private val leftOut = HashMap<String, MutableMap<String, Int>>()

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
        private val queue = ArrayDeque<Any>()
        private val outQueue = ArrayDeque<Pair<OutValue, Incoming?>>()
        private val seen = HashSet<Pair<Entry?, Triple<String, String, List<String>>>>()

        // The calls each entry came in through; what reached the return of its routine, and what its OUT parameters were
        // given: given back to each call that brings it in, now or later.
        private val incoming = HashMap<Entry, MutableList<Incoming>>()
        private val exits = HashMap<Entry, MutableList<Reached>>()
        private val outs = HashMap<Entry, MutableList<OutValue>>()
        private val calledWith = HashSet<List<Any?>>()

        private val outSeen = HashSet<OutValue>()
        private val notFollowedOut = HashSet<List<Any?>>()
        private val placed = HashMap<List<Any>, Pair<String, Int>>()

        /** The places found directly (a row record's field assigned to the target): not made again through a variable. */
        private val direct = found.flatMapTo(HashSet()) { (path, list) -> list.map { placeKey(path, it) } }

        /** The calls each place of a function's result was written by: another call of it into the same target counts once more. */
        private val placedCalls = HashMap<List<Any>, MutableSet<SourceSpan?>>()
        private val counted = HashSet<List<Any>>()
        private val outPlaced = HashSet<List<Any?>>()
        private val undecided = HashSet<Pair<String, SourceSpan>>()
        private val notFollowed = HashSet<List<Any>>()

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

        /** The routines of [facts] whose body the sources have, as calls name them. */
        private fun definedRoutines(): Set<String> {
            val defined = HashSet<String>()
            for (file in facts) for (occurrence in file.occurrences) {
                if (Role.DEFINITION in occurrence.roles && occurrence.entityKey != null) defined += occurrence.entityKey.removeSuffix("@BODY")
            }
            return defined
        }

        /** The parameters of the routines of [facts] whose body is not PL/SQL. */
        private fun notPlsqlParameters(): Set<String> {
            val outside = HashSet<String>()
            for (file in facts) for (into in file.intos) {
                if (into.fromParameter != null && into.expression == IntoClauses.BODY_NOT_PLSQL) outside += parameterKey(file.path, into.fromParameter)
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
        private fun isDefined(parameter: String) = parameter !in outside && ('|' in parameter || parameter.substringBeforeLast('.') in defined)

        /** The `IN` arguments of [facts]: into [flows], those within a routine, and into [seeds], the columns calls pass. */
        private fun collectArguments() {
            for (file in facts) for (into in file.intos) if (into.source == PlaceSource.IN_ARGUMENT) inArgument(file.path, into)
        }

        /** The `IN` argument [into] of the file [path]: a flow of its routine's parameter, or a column a call passes. */
        private fun inArgument(path: String, into: IntoFact) {
            if (into.fromParameter == null) {
                seed(path, into)
                return
            }
            if (into.expression != IntoClauses.BODY_NOT_PLSQL) flows.getOrPut(parameterKey(path, into.fromParameter)) { ArrayList() } += path to into
        }

        /** The columns the call argument [into] of the file [path] passes to its parameter, into [seeds]; counted when not followed. */
        private fun seed(path: String, into: IntoFact) {
            val columns = passedColumns(path, into) ?: return
            val reason = into.targets.single().reason
            if (reason != null) {
                count(path, reason)
                return
            }
            val parameter = parameterKey(path, into.parameterKey ?: return)
            if (!isDefined(parameter)) {
                count(path, "ARGUMENT_ROUTINE_OUTSIDE")
                return
            }
            for ((column, functions) in columns) seeds.getOrPut(Triple(parameter, column, functions)) { ArrayList() } += path to into
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
            val row = structure(targetNode(into.row, false), 0)
            if (row !is Node.Shape || !(row.shape is TypeShape.CursorRow || row.shape is TypeShape.Anchored && row.shape.rowType)) return null
            val column = field(row, null, into.rowField).first as? Node.Column
            if (column == null) {
                count(path, notKnown("ARGUMENT_COLUMN_NOT_KNOWN_", row, into.rowField))
                return null
            }
            return column.all.map { (key, functions) -> key to (into.functions + functions) }
        }

        /** The value [column], through [functions], at [node], come in at [entry]: queued, unless it was there already. */
        private fun reach(node: String, column: String, functions: List<String>, calls: List<Pair<String, IntoFact>>, entry: Entry?, previous: Reached?) {
            if (seen.add(entry to Triple(node, column, functions))) queue += Reached(node, column, functions, calls, entry, previous)
        }

        /** [entry] brought in by [coming]: what reached the return of its routine, and what its OUT parameters were given, go back to it. */
        private fun call(entry: Entry, coming: Incoming) {
            if (!calledWith.add(listOf(entry, coming.path, coming.call, coming.entry))) return
            incoming.getOrPut(entry) { ArrayList() } += coming
            for (exit in exits[entry].orEmpty()) queue += exit to coming
            for (out in outs[entry].orEmpty()) outQueue += out to coming
        }

        /** An OUT parameter's value ([place] in [path], whose parameter it is), come into its routine at each of [entries]. */
        private fun follow(path: String, place: ColumnPlaceFact, entries: Collection<Entry?>) {
            val parameter = place.targetKey ?: place.localParameter
            if (parameter == null) {
                // A CALLER_DECIDES place whose parameter can be told neither by its entity nor by its declaration.
                if (notFollowedOut.add(placeKey(path, place))) count(path, "OUT_PARAMETER_NOT_FOLLOWED")
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
        private fun held(path: String, flow: IntoFact?, place: ColumnPlaceFact, entry: Entry?, previous: Reached?, calls: List<Pair<String, IntoFact>>) {
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
                reach(key.first, key.second, key.third, calls.sortedWith(compareBy({ it.first }, { it.second.targets.single().span })), entry, null)
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
                flow.expression != null -> if (counted.add(listOf(path, site, reached.column))) count(path, flow.expression)
                flow.parameterKey != null -> passedOn(path, flow, parameterKey(path, flow.parameterKey), reached, entry, previous, functions)
                else -> intoTarget(path, flow, reached, entry, previous, functions)
            }
        }

        /** [flow] of [path] passing the value [reached] has, through [functions], on to the parameter [next], as [along] takes it. */
        private fun passedOn(
            path: String, flow: IntoFact, next: String, reached: Reached, entry: Entry?, previous: Reached, functions: List<String>
        ) {
            val returns = reached.parameter.endsWith(".$RETURN")
            // A function's result passed to a parameter: that call is the one that passes the column.
            val calls = if (returns && !next.endsWith(".$RETURN")) listOf(path to flow) else reached.calls
            // A cycle (a routine passing its parameter to itself, a function returning its own result) only
            // wraps the value in more functions on each turn: what it reaches, the path without it reached.
            val again = if (isDefined(next)) previous.at(next) else null
            when {
                !isDefined(next) -> if (counted.add(listOf(path, flow.targets.single().span, reached.column))) count(path, "ARGUMENT_ROUTINE_OUTSIDE")
                // Into a parameter already on the path (a routine reached again within itself, or the same function
                // nested in its own argument): not walked again, but what its entry there gives back comes back
                // out at this call too.
                again != null -> if (!next.endsWith(".$RETURN")) again.entry?.let { call(it, Incoming(path, flow.call, entry, previous)) }
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
        private fun intoTarget(path: String, flow: IntoFact, reached: Reached, entry: Entry?, previous: Reached, functions: List<String>) {
            val returns = reached.parameter.endsWith(".$RETURN")
            val key = listOf(path, flow.targets.single().span, reached.column, written(functions))
            if (!returns && key in direct) return
            val earlier = placed[key]
            val place = if (earlier != null) placedAgain(key, earlier, flow, returns) else newPlace(path, flow, reached, functions, key)
            if (place.placeClass == PlaceClass.CALLER_DECIDES) follow(path, place, listOf(entry))
            held(path, flow, place, entry, previous, if (returns) listOf(path to flow) else reached.calls)
        }

        /** The place made before for [key] ([earlier]: its file and position), one call more when another call of a function writes there. */
        private fun placedAgain(key: List<Any>, earlier: Pair<String, Int>, flow: IntoFact, returns: Boolean): ColumnPlaceFact {
            val list = places.getValue(earlier.first)
            if (returns && placedCalls.getValue(key).add(flow.resultOf)) {
                val place = list[earlier.second]
                list[earlier.second] = place.copy(callCount = (place.callCount ?: 1) + 1)
            }
            return list[earlier.second]
        }

        /** The new place, kept as [key], [flow] of [path] makes of the value [reached] has through [functions]. */
        private fun newPlace(path: String, flow: IntoFact, reached: Reached, functions: List<String>, key: List<Any>): ColumnPlaceFact {
            // Read by an INTO with a single target: a record takes the item as its field, as for a column.
            val target = flow.targets.single()
            val items = flow.branches.singleOrNull()
            val field = items?.takeIf { it.size > 1 || isRow(target, flow.bulk) }?.indexOfFirst { it.columnKey == flow.fromParameter }
            val made = place(path, flow, target, field, reached.column, functions)
            val place = if (reached.parameter.endsWith(".$RETURN")) {
                made.copy(source = PlaceSource.RETURNED, argumentOf = specKey(reached.parameter), callCount = 1, callPath = path, callLine = target.span.startLine)
            } else {
                // A column read in the routine itself, held by a variable written here: no call brought it,
                // the value lands by the statement that writes the target (`p_out := l`), as any other.
                val (callPath, call) = reached.calls.firstOrNull() ?: (null to null)
                if (call == null) made.copy(source = if (flow.branches.isEmpty()) PlaceSource.ASSIGNMENT else PlaceSource.SELECT_INTO)
                else made.copy(argumentOf = call.parameterKey, callCount = reached.calls.size, callPath = callPath, callLine = call.targets.single().span.startLine)
            }
            val list = places.getOrPut(path) { ArrayList() }
            placed[key] = path to list.size
            placedCalls[key] = hashSetOf(flow.resultOf)
            list += place
            return place
        }

        /** What reached a function's return ([exit]) goes back to the calls of it [to] names (all, for a value with no entry). */
        private fun back(exit: Reached, to: Incoming?) {
            val calls = flows[exit.parameter].orEmpty().filter { (path, flow) -> to == null || isCall(to, path, flow.resultOf) }
            // A function that returns a record, a collection or an object: its callers are not followed, each counted.
            if (!isScalar(exit.parameter)) {
                for ((path, flow) in calls) if (counted.add(listOf(path, flow.targets.single().span))) count(path, "RETURNED_NOT_SCALAR")
                return
            }
            // Back in the caller: the path goes on from the callee's and from the caller's value at the call.
            val joined = if (to?.reached == null) exit else Reached(exit.parameter, exit.column, exit.functions, exit.calls, to.entry, exit, to.reached)
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
            if (target.reason == "OUT_CALL_NOT_DECIDED") {
                if (undecided.add(path to target.span)) count(path, "OUT_CALL_NOT_DECIDED")
                return
            }
            if (target.entityKey == null && target.local == null) {
                if (notFollowed.add(listOf(path, target.span, column, written(functions)))) count(path, "OUT_ARGUMENT_NOT_FOLLOWED")
                return
            }
            val place = place(path, into, target, null, column, functions)
            if (outPlaced.add(placeKey(path, place))) places.getOrPut(path) { ArrayList() } += place
            if (place.placeClass == PlaceClass.CALLER_DECIDES) follow(path, place, back.mapTo(LinkedHashSet()) { it?.entry })
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
                val work = queue.removeFirst()
                if (work is Pair<*, *>) back(work.first as Reached, work.second as Incoming) else step(work as Reached)
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
            val routines = outSeen.filter { !it.parameter.contains('|') }.mapTo(HashSet()) { it.parameter.substringBeforeLast('.') }
            for (file in facts) for (occurrence in file.occurrences) {
                if (Role.CALL in occurrence.roles && occurrence.entityKey == null && occurrence.candidateKeys.any { it in routines }) {
                    count(file.path, "OUT_CALL_NOT_DECIDED")
                }
            }
        }
    }

    /**
     * A parameter as a call binds it: a place's target in a package body is the body's parameter (`…)@BODY.P`), a
     * call's argument the spec's (`…).P`); a routine only the body declares is the same key on both sides.
     */
    private fun specKey(parameter: String) = parameter.replace(")@BODY.", ").")

    /** The key [followed] follows a parameter by: a nested subprogram's is only known in its own file ([path]). */
    private fun parameterKey(path: String, parameter: String) =
        if (parameter.startsWith(LOCAL_PARAMETER)) "$path|$parameter" else specKey(parameter)

    /** Whether [target] is a record that takes its one item as a field (`select * into r` of a one-column table). */
    private fun isRow(target: IntoTarget, bulk: Boolean): Boolean {
        val node = structure(targetNode(target, bulk), 0)
        return node is Node.Shape && (node.shape is TypeShape.Record || node.shape is TypeShape.CursorRow || node.shape is TypeShape.Anchored && node.shape.rowType)
    }

    /** The place of [column] in the item [item] of the file [path], which its block's query reads the column into: in no code. */
    private fun blockQuery(path: String, item: String, column: String): ColumnPlaceFact {
        val decision = classify(of(item), column, 0)
        val unit = unitOf(decision, path, null)
        return ColumnPlaceFact(null, item, null, null, column, PlaceSource.BLOCK_QUERY, false, item, displayOf(item), null, decision.placeClass,
            formsType(item), decision.length, unit.unit, decision.scale, null, null, decision.reason, unitSource = unit.source)
    }

    /** The data type of the Forms item or parameter [key], as its shape tells it; null for any other entity. */
    private fun formsType(key: String): String? = if (!key.startsWith(FORMS_PREFIX)) null else when (val shape = shapes[key]) {
        is TypeShape.Sized -> shape.type
        is TypeShape.Unsized -> shape.type
        else -> null
    }

    /** The place of [column] in [target] of [into], a statement of the file [path]. */
    private fun place(path: String, into: IntoFact, target: IntoTarget, field: Int?, column: String, functions: List<String>): ColumnPlaceFact {
        var node = targetNode(target, into.bulk)
        var fieldName: String? = null
        var text = target.entityKey?.let { texts[it] ?: formsType(it) } ?: target.declaredType
        if (field != null) {
            val (reached, name) = field(node, field, null)
            node = reached
            fieldName = name
            if (reached is Node.Shape && reached.owner != null) texts[reached.owner]?.let { text = it }
        }
        val decision = if (target.entityKey == null && target.local == null) Decision(PlaceClass.UNKNOWN, reason = target.reason ?: "TARGET_UNDECIDED")
            else classify(node, column, 0)
        // The parameter itself, not a field or an element of it, whose type is the caller's argument's.
        val mode = (node as? Node.Shape)?.shape.let { it as? TypeShape.OutParameter }
            ?.takeIf { field == null && target.path.isEmpty() && !target.element && !into.bulk }?.mode
        val placeClass = if (mode != null) PlaceClass.CALLER_DECIDES else decision.placeClass
        val sized = mode == null
        val unit = if (sized) unitOf(decision, path, into.unitIndex) else LengthUnit(null)
        // The type source is another entity whose declaration decides the type; the target's own declaration is not repeated.
        return ColumnPlaceFact(
            into.unitIndex, into.containerKey, into.statementLocalId, target.span, column, into.source, into.bulk, target.entityKey, target.written,
            fieldName, placeClass, text, decision.length.takeIf { sized }, unit.unit, decision.scale.takeIf { sized },
            decision.source?.takeIf { it != target.entityKey }, mode, decision.reason, functions, into.parameterKey,
            target.localKey.takeIf { mode != null }, unitSource = unit.source, unitCompiledIn = unit.compiledIn
        )
    }

    /** The unit of [decision]'s length, at a place in the unit [unitIndex] of the file [path]: one written without a unit, [lengthUnit]'s. */
    private fun unitOf(decision: Decision, path: String, unitIndex: Int?): LengthUnit = when (val written = decision.unit) {
        TypeShapes.NLS_LENGTH_SEMANTICS -> lengthUnit(decision.source, path, unitIndex)
        "BYTE", "CHAR" -> LengthUnit(written, UnitSource.WRITTEN)
        else -> LengthUnit(written)
    }

    /**
     * The unit of a length written without one in the declaration of [owner] (or, for one without an entity, in the unit
     * [unitIndex] of [path]): the `NLS_LENGTH_SEMANTICS` the unit declaring it was compiled with, else [defaultSemantics].
     * A Forms item's or parameter's is the one its module was compiled with, which the dictionary does not tell.
     */
    private fun lengthUnit(owner: String?, path: String, unitIndex: Int?): LengthUnit {
        if (owner?.startsWith(FORMS_PREFIX) == true) return LengthUnit(defaultSemantics, UnitSource.OPTION)
        val declaring = owner?.let { declaredIn[it] } ?: unitIndex?.let { units[path]?.getOrNull(it) }
        val compiled = declaring?.let { unit -> COMPILED_TYPES[unit.kind]?.let { type -> unit.compilationSchema?.let { CompiledUnit(it, unit.name, type) } } }
        val setting = compiled?.let { compiledSemantics[it] }
        return if (setting != null) LengthUnit(setting, UnitSource.COMPILED, compiled) else LengthUnit(defaultSemantics, UnitSource.OPTION)
    }

    /** The target's declaration, its written fields followed, then its element for a collection element or `BULK COLLECT`. */
    private fun targetNode(target: IntoTarget, bulk: Boolean): Node {
        var node: Node = when {
            target.entityKey != null -> of(target.entityKey)
            target.local != null -> Node.Shape(target.local, null)
            else -> Node.Failed(target.reason ?: "TARGET_UNDECIDED")
        }
        for (name in target.path) node = if (name == IntoTarget.ELEMENT) element(node) else field(node, null, name).first
        if (target.element) node = element(node)
        if (bulk) node = element(node)
        return node
    }

    private fun of(key: String): Node = shapes[key]?.let { Node.Shape(it, key) } ?: fieldOf(key) ?: when (kinds[key]) {
        EntityKind.COLUMN -> Node.Column(key)
        EntityKind.TYPE -> Node.Failed("TYPE_NOT_DECLARED")
        // A Forms global holds text whose length no declaration gives.
        EntityKind.FORMS_GLOBAL -> Node.Failed("GLOBAL_LENGTH_NOT_GIVEN")
        else -> Node.Failed("DECLARATION_UNKNOWN")
    }

    /** A field of a record type known by its type's declaration only: its own shape there. */
    private fun fieldOf(key: String): Node? {
        val record = shapes[key.substringBeforeLast('.', "")] as? TypeShape.Record ?: return null
        return record.fields.firstOrNull { it.key == key }?.local?.let { Node.Shape(it, key) }
    }

    /** [node] followed through type names and `%TYPE` anchors down to a record, a collection, a row or a scalar. */
    private fun structure(node: Node, depth: Int): Node {
        if (depth > MAX_DEPTH) return Node.Failed("ANCHOR_CYCLE")
        if (node !is Node.Shape) return node
        return when (val shape = node.shape) {
            is TypeShape.Named -> structure(named(shape, node.owner), depth + 1)
            is TypeShape.Anchored -> if (shape.rowType) node else structure(anchor(shape, depth), depth + 1)
            is TypeShape.OutParameter -> structure(Node.Shape(shape.type, node.owner), depth + 1)
            is TypeShape.Unknown -> Node.Failed(shape.reason)
            else -> node
        }
    }

    private fun named(shape: TypeShape.Named, owner: String?): Node = when {
        shape.typeKey != null -> of(shape.typeKey)
        shape.local != null -> Node.Shape(shape.local, owner)
        else -> Node.Failed(shape.reason ?: "TYPE_UNRESOLVED")
    }

    /** What a `%TYPE` anchor names, its written fields followed. */
    private fun anchor(shape: TypeShape.Anchored, depth: Int): Node {
        var node: Node = when {
            shape.anchorKey != null -> of(shape.anchorKey)
            shape.local != null -> Node.Shape(shape.local, null)
            else -> return Node.Failed(shape.reason ?: "ANCHOR_UNRESOLVED")
        }
        for (name in shape.path) node = field(node, null, name, depth + 1).first
        return node
    }

    /** The field at [index] or of [name] of the record [node] is, and its name. */
    private fun field(node: Node, index: Int?, name: String?, depth: Int = 0): Pair<Node, String?> {
        val record = structure(node, depth)
        if (record !is Node.Shape) return record to name
        return when (val shape = record.shape) {
            is TypeShape.Anchored -> {
                val key = shape.anchorKey
                val row = if (key != null) shapes[key] else shape.local ?: return Node.Failed(shape.reason ?: "ANCHOR_UNRESOLVED") to name
                when {
                    // A cursor's row (`c%ROWTYPE`).
                    row is TypeShape.CursorRow -> item(row.items, index, name)
                    // A relation's row (`t%ROWTYPE`): its column of that name, or at that position in the dictionary.
                    key != null && kinds[key] in RELATIONS -> when {
                        name != null -> Node.Column("$key.$name") to name
                        else -> relationColumns[key]?.getOrNull(index!!)?.let { Node.Column(it) to it.substringAfterLast('.') }
                            ?: (Node.Failed("ROWTYPE_COLUMNS_UNKNOWN") to null)
                    }
                    else -> Node.Failed(shape.reason ?: "ROWTYPE_SOURCE_UNKNOWN") to name
                }
            }
            is TypeShape.CursorRow -> item(shape.items, index, name)
            is TypeShape.Record -> {
                val field = if (name != null) shape.fields.firstOrNull { it.name == name } else shape.fields.getOrNull(index!!)
                when {
                    field == null -> Node.Failed("FIELD_UNKNOWN") to name
                    field.key != null && field.key in shapes -> Node.Shape(shapes.getValue(field.key), field.key) to field.name
                    field.local != null -> Node.Shape(field.local, field.key) to field.name
                    else -> Node.Failed("FIELD_UNKNOWN") to field.name
                }
            }
            else -> Node.Failed("NOT_A_RECORD") to name
        }
    }

    private fun item(items: List<QueryItem>, index: Int?, name: String?): Pair<Node, String?> {
        val item = if (name != null) items.firstOrNull { it.name == name } else items.takeWhile { !it.opaque }.getOrNull(index!!)
        return when {
            item == null -> Node.Failed("CURSOR_FIELD_UNKNOWN") to name
            item.columnKey != null -> Node.Column(item.columnKey, item.functions, item.parts) to item.name
            item.expression != null -> Node.Failed("CURSOR_FIELD_EXPRESSION") to item.name
            else -> Node.Failed("CURSOR_FIELD_NOT_A_COLUMN") to item.name
        }
    }

    /**
     * Why the field [name] of the row record [row] has no column known, the shape it is counted under, after [prefix]
     * (`ARGUMENT_COLUMN_NOT_KNOWN_` for a field passed to a routine, `RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN_` for one assigned):
     * - `EXPRESSION_*`: the cursor's item is an expression the index does not follow, by its shape as for `EXPRESSION_*`
     *   (`code || note` is `_CONCATENATION`, `decode(code, 'a', 'b')` is `_OTHER_DECODE`);
     * - `ROWTYPE`: a `%ROWTYPE` whose relation or cursor the index does not know (`r missing_tab%rowtype`);
     * - `CURSOR`: a `%ROWTYPE` of a cursor whose query is not known (a cursor of another package with no query);
     * - `REF_CURSOR`: a `%ROWTYPE` of a cursor variable, whose `REF CURSOR` type has a `RETURN` (`r p%rowtype`);
     * - `STAR`: the cursor's `*` over a relation whose columns are not known (`select * from missing_tab`);
     * - `NO_COLUMN`, `COLUMN_UNRESOLVED`, `INLINE_VIEW`: the item, by [QueryItem.notColumn];
     * - `OTHER`: anything else.
     */
    private fun notKnown(prefix: String, row: Node.Shape, name: String?): String {
        val items = when (val shape = row.shape) {
            is TypeShape.CursorRow -> shape.items
            is TypeShape.Anchored -> {
                val key = shape.anchorKey
                val source = if (key != null) shapes[key] else shape.local
                (source as? TypeShape.CursorRow)?.items ?: return prefix + when {
                    isCursorVariable(source) -> "REF_CURSOR"
                    key == null || kinds[key] in RELATIONS -> "ROWTYPE"
                    kinds[key] == EntityKind.CURSOR -> "CURSOR"
                    else -> "OTHER"
                }
            }
            else -> return prefix + "OTHER"
        }
        val item = items.firstOrNull { it.name == name }
        return prefix + when {
            item == null -> if (items.any { it.opaque }) "STAR" else "OTHER"
            item.expression != null -> "EXPRESSION_${item.expression}"
            item.combined != null -> "EXPRESSION_${item.combined}"
            else -> item.notColumn ?: "OTHER"
        }
    }

    /** The `REF CURSOR` type [shape] is, through type names: its [TypeShape.Unknown], or null for any other shape. */
    private fun refCursor(shape: TypeShape?, depth: Int = 0): TypeShape.Unknown? = when {
        depth > MAX_DEPTH -> null
        shape is TypeShape.Unknown -> shape.takeIf { it.reason == CURSOR_VARIABLE }
        shape is TypeShape.Named -> refCursor(shape.typeKey?.let { shapes[it] } ?: shape.local, depth + 1)
        shape is TypeShape.OutParameter -> refCursor(shape.type, depth + 1)
        else -> null
    }

    private fun isCursorVariable(shape: TypeShape?) = refCursor(shape) != null

    /** Whether what a function returns ([returned], its return key) is a scalar: not a record, a collection or an object. */
    private fun isScalar(returned: String): Boolean {
        val node = structure(Node.Shape(shapes[specKey(returned)] ?: return true, null), 0) as? Node.Shape ?: return true
        return when (val shape = node.shape) {
            is TypeShape.Record, is TypeShape.Collection, is TypeShape.CursorRow -> false
            is TypeShape.Anchored -> !shape.rowType
            is TypeShape.Unsized -> shape.type != "OBJECT"
            else -> true
        }
    }

    /**
     * The shape a FETCH whose cursor's query is not known is counted under, by the cursor: `_STRONG` for a variable of
     * a `REF CURSOR` type with `RETURN` (its own entity [cursorKey], or the type the extraction found), else what the
     * extraction told ([IntoFact.cursorKind]: opened in the routine, or a parameter), else `_OTHER`.
     */
    private fun fetchNotKnown(into: IntoFact, cursorKey: String?): String {
        val strong = into.cursorKind == STRONG || refCursor(cursorKey?.let { shapes[it] })?.strongCursor == true ||
            refCursor(into.cursorType?.let { shapes[it] })?.strongCursor == true
        val kind = when {
            strong -> STRONG
            into.cursorKind != null -> into.cursorKind
            cursorKey != null && kinds[cursorKey] == EntityKind.PARAMETER -> "PARAMETER"
            else -> "OTHER"
        }
        return IntoClauses.FETCH_CURSOR_NOT_KNOWN + kind
    }

    /** The element of the collection [node] is. */
    private fun element(node: Node): Node {
        val collection = structure(node, 0)
        if (collection !is Node.Shape) return collection
        val shape = collection.shape as? TypeShape.Collection ?: return Node.Failed("NOT_A_COLLECTION")
        return Node.Shape(shape.element, collection.owner)
    }

    /** The class of the place of [column] in [node], its type followed to where it ends. */
    private fun classify(node: Node, column: String, depth: Int): Decision {
        if (depth > MAX_DEPTH) return Decision(PlaceClass.UNKNOWN, reason = "ANCHOR_CYCLE")
        return when (node) {
            // A cursor field read through a function types its record's field as the function's result, not as the column.
            is Node.Column -> if (node.functions.isNotEmpty()) Decision(PlaceClass.UNKNOWN, reason = "CURSOR_FIELD_EXPRESSION")
                else Decision(if (node.key == column) PlaceClass.ANCHORED_TO_COLUMN else PlaceClass.ANCHORED_ELSEWHERE, source = node.key)
            is Node.Failed -> Decision(PlaceClass.UNKNOWN, reason = node.reason)
            is Node.Shape -> when (val shape = node.shape) {
                is TypeShape.Sized -> Decision(PlaceClass.FIXED_SIZE, shape.length, shape.unit, shape.scale, node.owner)
                is TypeShape.Unsized -> Decision(PlaceClass.NO_SIZE, source = node.owner)
                is TypeShape.Anchored -> if (shape.rowType) Decision(PlaceClass.UNKNOWN, reason = "NOT_A_SCALAR") else classify(anchor(shape, depth), column, depth + 1)
                is TypeShape.OutParameter -> classify(Node.Shape(shape.type, node.owner), column, depth + 1)
                is TypeShape.Named -> classify(named(shape, node.owner), column, depth + 1)
                is TypeShape.Unknown -> Decision(PlaceClass.UNKNOWN, reason = shape.reason)
                // A record or a collection taking one column: Oracle rejects it.
                is TypeShape.Record, is TypeShape.Collection, is TypeShape.CursorRow -> Decision(PlaceClass.UNKNOWN, reason = "NOT_A_SCALAR")
            }
        }
    }

    companion object {
        private const val MAX_DEPTH = 20

        /** The reason of the shape of a `REF CURSOR` type ([TypeShape.Unknown]). */
        private const val CURSOR_VARIABLE = "CURSOR_VARIABLE"
        private const val STRONG = "STRONG"
        private const val RETURN = IntoClauses.RETURN_SUFFIX

        /** The shape of a `FETCH` from a cursor variable whose targets do not match the items of a query it reads. */
        private const val FETCH_CURSOR_ITEMS_NOT_MATCHED = "FETCH_CURSOR_ITEMS_NOT_MATCHED"

        /** The shape of a `FETCH` whose query's `*` is over a relation or collection whose columns are not known. */
        private const val FETCH_CURSOR_STAR_NOT_KNOWN = "FETCH_CURSOR_STAR_NOT_KNOWN"

        /** The prefix of a nested subprogram's parameter key, as the extraction writes it. */
        private const val LOCAL_PARAMETER = "local:"

        /** The `TYPE` of `ALL_PLSQL_OBJECT_SETTINGS` of each unit kind that has one. */
        private val COMPILED_TYPES = mapOf(
            UnitKind.PACKAGE to "PACKAGE", UnitKind.PACKAGE_BODY to "PACKAGE BODY", UnitKind.PROCEDURE to "PROCEDURE", UnitKind.FUNCTION to "FUNCTION",
            UnitKind.TRIGGER to "TRIGGER", UnitKind.TYPE to "TYPE", UnitKind.TYPE_BODY to "TYPE BODY"
        )

        private val RELATIONS = setOf(EntityKind.TABLE, EntityKind.VIEW, EntityKind.MATERIALIZED_VIEW, EntityKind.OBJECT_TABLE, EntityKind.UNKNOWN_RELATION)

        /** The statuses of a block query's read of its item's column that name the column. */
        private val COLUMN_STATUSES = setOf(Status.RESOLVED, Status.UNVERIFIED, Status.EXTERNAL, Status.PARTIAL_SOURCE)
    }
}
