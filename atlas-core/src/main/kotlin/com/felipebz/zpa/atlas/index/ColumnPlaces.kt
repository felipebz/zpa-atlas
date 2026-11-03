//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.extract.IntoClauses
import com.felipebz.zpa.atlas.model.*

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
 */
internal class ColumnPlaces(
    facts: List<FileFacts>,
    private val kinds: Map<String, EntityKind>,
    /** The columns of each dictionary relation, in their position order. */
    private val relationColumns: Map<String, List<String>>
) {
    private val shapes = HashMap<String, TypeShape>()
    private val texts = HashMap<String, String>()

    init {
        for (file in facts) {
            // A package cursor declared in the spec and defined in the body: the body's query is its row.
            for (fact in file.declaredShapes) {
                val known = shapes[fact.entityKey]
                if (known == null || known !is TypeShape.CursorRow && fact.shape is TypeShape.CursorRow) shapes[fact.entityKey] = fact.shape
            }
            for (text in file.declaredTexts) if (text.datatype != null) texts.putIfAbsent(text.entityKey, text.datatype)
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

    /**
     * The places of [file], and what its `INTO`s and assignments read without being followed, by shape: the expression
     * items of one column, the fetches from an unknown cursor, the assignments of a row record's field whose column is
     * not known, and those of an expression of such a field.
     */
    fun of(file: FileFacts): Pair<List<ColumnPlaceFact>, Map<String, Int>> {
        val places = ArrayList<ColumnPlaceFact>()
        val leftOut = HashMap<String, Int>()
        for (into in file.intos) {
            if (into.source == PlaceSource.ASSIGNMENT) {
                assignment(into, places, leftOut)
                continue
            }
            // The arguments of OUT parameters are followed from the places of their parameters ([arguments]), the values
            // passed to IN parameters from their calls ([passed]).
            if (into.source == PlaceSource.OUT_ARGUMENT || into.source == PlaceSource.IN_ARGUMENT) continue
            // A FETCH from a cursor variable, which has no entity: its row is not known.
            if (into.source == PlaceSource.FETCH && into.cursorKey == null && into.branches.isEmpty()) {
                leftOut.merge(fetchNotKnown(into, null), into.targets.size, Int::plus)
                continue
            }
            val branches = if (into.cursorKey != null) {
                val row = shapes[into.cursorKey] as? TypeShape.CursorRow
                if (row == null) {
                    leftOut.merge(fetchNotKnown(into, into.cursorKey), into.targets.size, Int::plus)
                    continue
                }
                listOf(row.items)
            } else {
                into.branches
            }
            for (items in branches) {
                // A `*` whose columns are not known: no position can be told after it.
                val known = items.takeWhile { !it.opaque }
                val record = into.targets.size == 1 && (items.size > 1 || known.size < items.size || isRow(into.targets[0], into.bulk))
                for ((index, item) in known.withIndex()) {
                    val target = if (record) into.targets[0] else into.targets.getOrNull(index) ?: break
                    if (item.expression != null) leftOut.merge("EXPRESSION_${item.expression}", 1, Int::plus)
                    for (part in listOf(item) + item.parts) {
                        places += place(into, target, if (record) index else null, part.columnKey ?: continue, part.functions)
                    }
                }
            }
        }
        return places to leftOut
    }

    /**
     * `target := r.f`: a place of the column `f` stands for when `r` is a row record (a cursor `FOR` loop record, a
     * variable of a `%ROWTYPE`, or of a cursor's row), decided as a `FETCH` from that cursor decides it. Any other record
     * is not a row: nothing. A field whose column is not known (an expression of the cursor's query, a cursor or
     * `%ROWTYPE` not known) is counted by why ([notKnown]), and so is an expression of the field (`upper(r.code)`) by its shape.
     */
    private fun assignment(into: IntoFact, places: MutableList<ColumnPlaceFact>, leftOut: MutableMap<String, Int>) {
        val row = structure(targetNode(into.row ?: return, false), 0)
        if (row !is Node.Shape || !(row.shape is TypeShape.CursorRow || row.shape is TypeShape.Anchored && row.shape.rowType)) return
        val column = field(row, null, into.rowField).first as? Node.Column
        when {
            column == null -> leftOut.merge(notKnown("RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN_", row, into.rowField), 1, Int::plus)
            into.expression != null -> leftOut.merge("RECORD_ASSIGNMENT_EXPRESSION_${into.expression}", 1, Int::plus)
            else -> for ((key, functions) in column.all) places += place(into, into.targets.single(), null, key, into.functions + functions)
        }
    }

    /**
     * The places inside routines of the columns their calls pass to `IN` parameters: each target a routine writes its
     * parameter's value into (as is or through value functions) is a place of the column, once per column and target
     * however many calls pass it, naming the parameter the column was passed to, how many calls pass it and one of
     * them. A parameter passed on to another routine's `IN` parameter is followed there, once per parameter and column.
     * A parameter only read (compared, in a condition) gives nothing. What is not followed is counted by shape, in the
     * file of the call or of the routine's statement: the routine not decided (`ARGUMENT_ROUTINE_UNDECIDED`), outside
     * the index (`ARGUMENT_ROUTINE_OUTSIDE`: a call the dictionary resolves, a routine whose body the sources do not have,
     * or one whose body is not PL/SQL), the argument not tied to a parameter (`ARGUMENT_NOT_TIED`), an overloaded nested
     * subprogram (`ARGUMENT_LOCAL_SUBPROGRAM`), a parameter named in SQL on a table whose columns are not known
     * (`ARGUMENT_COLUMN_POSSIBLE`), a parameter a cursor selects (`ARGUMENT_FETCHED`) or selected into a record
     * whose `INTO` targets do not match its items (`ARGUMENT_INTO_NOT_FOLLOWED`), a record field whose column is not known (`ARGUMENT_COLUMN_NOT_KNOWN_*`, by why: [notKnown]), and the
     * parameter returned (`ARGUMENT_RETURNED`) or written within an expression (`ARGUMENT_EXPRESSION_*`, by its shape).
     */
    fun passed(facts: List<FileFacts>, found: Map<String, List<ColumnPlaceFact>>): Pair<Map<String, List<ColumnPlaceFact>>, Map<String, Map<String, Int>>> {
        val places = HashMap<String, MutableList<ColumnPlaceFact>>()
        val leftOut = HashMap<String, MutableMap<String, Int>>()
        fun count(path: String, shape: String) {
            leftOut.getOrPut(path) { HashMap() }.merge(shape, 1, Int::plus)
        }
        // The routines whose body the sources have, as calls name them.
        val defined = HashSet<String>()
        for (file in facts) for (occurrence in file.occurrences) {
            if (Role.DEFINITION in occurrence.roles && occurrence.entityKey != null) defined += occurrence.entityKey.removeSuffix("@BODY")
        }
        // The parameters of routines whose body is not PL/SQL: what calls give them is counted at each call.
        val outside = HashSet<String>()
        for (file in facts) for (into in file.intos) {
            if (into.fromParameter != null && into.expression == IntoClauses.BODY_NOT_PLSQL) outside += parameterKey(file.path, into.fromParameter)
        }
        // A nested subprogram's parameter (its file's) has its body where it is declared.
        fun isDefined(parameter: String) = parameter !in outside && ('|' in parameter || parameter.substringBeforeLast('.') in defined)
        val flows = HashMap<String, MutableList<Pair<String, IntoFact>>>()
        val seeds = LinkedHashMap<Triple<String, String, List<String>>, MutableList<Pair<String, IntoFact>>>()
        for (file in facts) for (into in file.intos) {
            if (into.source != PlaceSource.IN_ARGUMENT) continue
            if (into.fromParameter != null) {
                if (into.expression == IntoClauses.BODY_NOT_PLSQL) continue
                flows.getOrPut(parameterKey(file.path, into.fromParameter)) { ArrayList() } += file.path to into
                continue
            }
            val columns = if (into.row != null) {
                val row = structure(targetNode(into.row, false), 0)
                if (row !is Node.Shape || !(row.shape is TypeShape.CursorRow || row.shape is TypeShape.Anchored && row.shape.rowType)) continue
                val found = field(row, null, into.rowField).first as? Node.Column
                if (found == null) {
                    count(file.path, notKnown("ARGUMENT_COLUMN_NOT_KNOWN_", row, into.rowField))
                    continue
                }
                found.all.map { (key, functions) -> key to (into.functions + functions) }
            } else {
                val item = into.branches.single().single()
                listOf((item.columnKey ?: continue) to item.functions)
            }
            val reason = into.targets.single().reason
            if (reason != null) {
                count(file.path, reason)
                continue
            }
            val parameter = parameterKey(file.path, into.parameterKey ?: continue)
            if (!isDefined(parameter)) {
                count(file.path, "ARGUMENT_ROUTINE_OUTSIDE")
                continue
            }
            for ((column, functions) in columns) seeds.getOrPut(Triple(parameter, column, functions)) { ArrayList() } += file.path to into
        }
        /**
         * A node (a parameter, a variable a function returns, what a function returns) a column's value reached, through
         * [functions], from [previous]: the path back to where the value came in.
         */
        class Reached(
            val parameter: String, val column: String, val functions: List<String>, val calls: List<Pair<String, IntoFact>>, val previous: Reached? = null
        ) {
            /** Whether [node] is on the path that reached this one, this one included: going there again is a cycle. */
            fun onPath(node: String): Boolean = generateSequence(this) { it.previous }.any { it.parameter == node }
        }
        val queue = ArrayDeque<Reached>()
        val seen = HashSet<Triple<String, String, List<String>>>()
        for ((key, calls) in seeds) {
            seen += key
            queue += Reached(key.first, key.second, key.third, calls.sortedWith(compareBy({ it.first }, { it.second.targets.single().span })))
        }
        // A variable or field holding a column's value that a function returns: what its places hold goes to the return.
        fun held(path: String, place: ColumnPlaceFact, previous: Reached? = null) {
            val key = place.targetKey ?: return
            val held = listOfNotNull(place.field?.let { "$key.$it" }, key).map { parameterKey(path, it) }
            for (node in held) {
                if (node !in flows || previous?.onPath(node) == true || !seen.add(Triple(node, place.columnKey, place.functions))) continue
                queue += Reached(node, place.columnKey, place.functions, emptyList(), previous)
            }
        }
        for ((path, list) in found) for (place in list) held(path, place)
        val placed = HashMap<List<Any>, Pair<String, Int>>()
        val counted = HashSet<List<Any>>()
        while (queue.isNotEmpty()) {
            val reached = queue.removeFirst()
            val returns = reached.parameter.endsWith(".$RETURN")
            // A function that returns a record, a collection or an object: its callers are not followed, each counted.
            if (returns && !isScalar(reached.parameter)) {
                for ((path, flow) in flows[reached.parameter].orEmpty()) {
                    if (counted.add(listOf(path, flow.targets.single().span))) count(path, "RETURNED_NOT_SCALAR")
                }
                continue
            }
            for ((path, flow) in flows[reached.parameter].orEmpty()) {
                val functions = flow.functions + reached.functions
                val site = flow.targets.single().span
                when {
                    flow.expression != null -> if (counted.add(listOf(path, site, reached.column))) count(path, flow.expression)
                    flow.parameterKey != null -> {
                        val next = parameterKey(path, flow.parameterKey)
                        // A function's result passed to a parameter: that call is the one that passes the column.
                        val calls = if (returns && !next.endsWith(".$RETURN")) listOf(path to flow) else reached.calls
                        // A cycle (a routine passing its parameter to itself, a function returning its own result) only
                        // wraps the value in more functions on each turn: what it reaches, the path without it reached.
                        if (!isDefined(next)) {
                            if (counted.add(listOf(path, site, reached.column))) count(path, "ARGUMENT_ROUTINE_OUTSIDE")
                        } else if (!reached.onPath(next) && seen.add(Triple(next, reached.column, functions))) {
                            queue += Reached(next, reached.column, functions, calls, reached)
                        }
                    }
                    else -> {
                        val key = listOf(path, site, reached.column, functions)
                        val earlier = placed[key]
                        if (earlier != null) {
                            // Another call of a function writing its result into the same target.
                            if (returns) places.getValue(earlier.first)[earlier.second].let { place ->
                                places.getValue(earlier.first)[earlier.second] = place.copy(callCount = (place.callCount ?: 1) + 1)
                            }
                            continue
                        }
                        // Read by an INTO with a single target: a record takes the item as its field, as for a column.
                        val target = flow.targets.single()
                        val items = flow.branches.singleOrNull()
                        val field = items?.takeIf { it.size > 1 || isRow(target, flow.bulk) }?.indexOfFirst { it.columnKey == flow.fromParameter }
                        val made = place(flow, target, field, reached.column, functions)
                        val place = if (returns) {
                            made.copy(source = PlaceSource.RETURNED, argumentOf = specKey(reached.parameter), callCount = 1, callPath = path, callLine = site.startLine)
                        } else {
                            val (callPath, call) = reached.calls.firstOrNull() ?: continue
                            made.copy(argumentOf = call.parameterKey, callCount = reached.calls.size, callPath = callPath, callLine = call.targets.single().span.startLine)
                        }
                        val list = places.getOrPut(path) { ArrayList() }
                        placed[key] = path to list.size
                        list += place
                        held(path, place, reached)
                    }
                }
            }
        }
        return places to leftOut
    }

    /**
     * The places of the arguments callers pass for the `OUT` and `IN OUT` parameters of the [CALLER_DECIDES][PlaceClass.CALLER_DECIDES]
     * places in [found] (by file path): each argument is a place of the same column, with the same functions, its class
     * decided as for an `INTO` target. An argument that is itself an `OUT` or `IN OUT` parameter of the calling routine is
     * followed to that routine's callers, once per parameter and column. What is not followed is counted by shape, in
     * the file of the argument or call: an argument the index does not bind (`OUT_ARGUMENT_NOT_FOLLOWED`, an expression
     * or a name zpa binds to nothing) and a call whose routine is not decided among candidates that have such a
     * parameter (`OUT_CALL_NOT_DECIDED`).
     */
    fun arguments(facts: List<FileFacts>, found: Map<String, List<ColumnPlaceFact>>): Pair<Map<String, List<ColumnPlaceFact>>, Map<String, Map<String, Int>>> {
        val byParameter = HashMap<String, MutableList<Pair<String, IntoFact>>>()
        for (file in facts) for (into in file.intos) if (into.source == PlaceSource.OUT_ARGUMENT && into.parameterKey != null) {
            byParameter.getOrPut(parameterKey(file.path, into.parameterKey)) { ArrayList() } += file.path to into
        }
        val places = HashMap<String, MutableList<ColumnPlaceFact>>()
        val leftOut = HashMap<String, MutableMap<String, Int>>()
        val seen = HashSet<Triple<String, String, List<String>>>()
        val undecided = HashSet<Pair<String, SourceSpan>>()
        val queue = ArrayDeque<Triple<String, String, List<String>>>()
        fun follow(path: String, place: ColumnPlaceFact, column: String, functions: List<String>) {
            val parameter = place.targetKey ?: place.localParameter
            if (parameter == null) {
                // A CALLER_DECIDES place whose parameter can be told neither by its entity nor by its declaration.
                leftOut.getOrPut(path) { HashMap() }.merge("OUT_PARAMETER_NOT_FOLLOWED", 1, Int::plus)
                return
            }
            val key = parameterKey(path, parameter)
            if (seen.add(Triple(key, column, functions))) queue += Triple(key, column, functions)
        }
        for ((path, list) in found) for (place in list) {
            if (place.placeClass == PlaceClass.CALLER_DECIDES) follow(path, place, place.columnKey, place.functions)
        }
        while (queue.isNotEmpty()) {
            val (parameter, column, functions) = queue.removeFirst()
            for ((path, into) in byParameter[parameter].orEmpty()) {
                val target = into.targets.single()
                if (target.reason == "OUT_CALL_NOT_DECIDED") {
                    if (undecided.add(path to target.span)) leftOut.getOrPut(path) { HashMap() }.merge("OUT_CALL_NOT_DECIDED", 1, Int::plus)
                    continue
                }
                if (target.entityKey == null && target.local == null) {
                    leftOut.getOrPut(path) { HashMap() }.merge("OUT_ARGUMENT_NOT_FOLLOWED", 1, Int::plus)
                    continue
                }
                val place = place(into, target, null, column, functions)
                places.getOrPut(path) { ArrayList() } += place
                if (place.placeClass == PlaceClass.CALLER_DECIDES) follow(path, place, column, functions)
            }
        }
        val routines = seen.filter { !it.first.contains('|') }.mapTo(HashSet()) { it.first.substringBeforeLast('.') }
        // A call the index could not decide among routines, one of which leaves a column's size to its callers.
        for (file in facts) for (occurrence in file.occurrences) {
            if (Role.CALL in occurrence.roles && occurrence.entityKey == null && occurrence.candidateKeys.any { it in routines }) {
                leftOut.getOrPut(file.path) { HashMap() }.merge("OUT_CALL_NOT_DECIDED", 1, Int::plus)
            }
        }
        return places to leftOut
    }

    /**
     * A parameter as a call binds it: a place's target in a package body is the body's parameter (`…)@BODY.P`), a
     * call's argument the spec's (`…).P`); a routine only the body declares is the same key on both sides.
     */
    private fun specKey(parameter: String) = parameter.replace(")@BODY.", ").")

    /** The key [arguments] follows a parameter by: a nested subprogram's is only known in its own file ([path]). */
    private fun parameterKey(path: String, parameter: String) =
        if (parameter.startsWith(LOCAL_PARAMETER)) "$path|$parameter" else specKey(parameter)

    /** Whether [target] is a record that takes its one item as a field (`select * into r` of a one-column table). */
    private fun isRow(target: IntoTarget, bulk: Boolean): Boolean {
        val node = structure(targetNode(target, bulk), 0)
        return node is Node.Shape && (node.shape is TypeShape.Record || node.shape is TypeShape.CursorRow || node.shape is TypeShape.Anchored && node.shape.rowType)
    }

    private fun place(into: IntoFact, target: IntoTarget, field: Int?, column: String, functions: List<String>): ColumnPlaceFact {
        var node = targetNode(target, into.bulk)
        var fieldName: String? = null
        var text = target.entityKey?.let { texts[it] } ?: target.declaredType
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
        // The type source is another entity whose declaration decides the type; the target's own declaration is not repeated.
        return ColumnPlaceFact(
            into.unitIndex, into.containerKey, into.statementLocalId, target.span, column, into.source, into.bulk, target.entityKey, target.written,
            fieldName, placeClass, text, decision.length.takeIf { sized }, decision.unit.takeIf { sized }, decision.scale.takeIf { sized },
            decision.source?.takeIf { it != target.entityKey }, mode, decision.reason, functions, into.parameterKey,
            target.localKey.takeIf { mode != null }
        )
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

        /** The prefix of a nested subprogram's parameter key, as the extraction writes it. */
        private const val LOCAL_PARAMETER = "local:"

        private val RELATIONS = setOf(EntityKind.TABLE, EntityKind.VIEW, EntityKind.MATERIALIZED_VIEW, EntityKind.OBJECT_TABLE, EntityKind.UNKNOWN_RELATION)
    }
}
