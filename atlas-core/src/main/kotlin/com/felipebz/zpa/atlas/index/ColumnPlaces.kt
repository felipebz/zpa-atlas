//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

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
        data class Column(val key: String, val functions: List<String> = emptyList()) : Node
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
            val branches = if (into.cursorKey != null) {
                val row = shapes[into.cursorKey] as? TypeShape.CursorRow
                if (row == null) {
                    leftOut.merge("FETCH_CURSOR_NOT_KNOWN", into.targets.size, Int::plus)
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
                    val column = item.columnKey ?: continue
                    places += place(into, target, if (record) index else null, column, item.functions)
                }
            }
        }
        return places to leftOut
    }

    /**
     * `target := r.f`: a place of the column `f` stands for when `r` is a row record (a cursor `FOR` loop record, a
     * variable of a `%ROWTYPE`, or of a cursor's row), decided as a `FETCH` from that cursor decides it. Any other record
     * is not a row: nothing. A field whose column is not known (an expression of the cursor's query, a cursor or
     * `%ROWTYPE` not known) is counted, and so is an expression of the field (`upper(r.code)`) by its shape.
     */
    private fun assignment(into: IntoFact, places: MutableList<ColumnPlaceFact>, leftOut: MutableMap<String, Int>) {
        val row = structure(targetNode(into.row ?: return, false), 0)
        if (row !is Node.Shape || !(row.shape is TypeShape.CursorRow || row.shape is TypeShape.Anchored && row.shape.rowType)) return
        val column = field(row, null, into.rowField).first as? Node.Column
        when {
            column == null -> leftOut.merge("RECORD_ASSIGNMENT", 1, Int::plus)
            into.expression != null -> leftOut.merge("RECORD_ASSIGNMENT_EXPRESSION_${into.expression}", 1, Int::plus)
            else -> places += place(into, into.targets.single(), null, column.key, into.functions + column.functions)
        }
    }

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
            decision.source?.takeIf { it != target.entityKey }, mode, decision.reason, functions
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
            item.columnKey != null -> Node.Column(item.columnKey, item.functions) to item.name
            item.expression != null -> Node.Failed("CURSOR_FIELD_EXPRESSION") to item.name
            else -> Node.Failed("CURSOR_FIELD_NOT_A_COLUMN") to item.name
        }
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

        private val RELATIONS = setOf(EntityKind.TABLE, EntityKind.VIEW, EntityKind.MATERIALIZED_VIEW, EntityKind.OBJECT_TABLE, EntityKind.UNKNOWN_RELATION)
    }
}
