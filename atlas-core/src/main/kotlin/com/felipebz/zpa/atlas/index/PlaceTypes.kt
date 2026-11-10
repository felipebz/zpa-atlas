//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.extract.FORMS_PREFIX
import com.felipebz.zpa.atlas.model.*

/**
 * The types of the targets column values are read into, followed across files once every file is scanned: each
 * declaration's shape by its entity key, followed through type names, anchors, records, collections and cursor rows down
 * to the column or the size the type ends at ([classify]). Read only once built.
 *
 * A Forms item or parameter a module's code names is typed by what it holds
 * ([com.felipebz.zpa.atlas.extract.FormsEntities.shapes]); a global holds text of no declared length.
 */
internal class PlaceTypes(
    facts: List<FileFacts>,
    /** The kind of each entity, by key. */
    val kinds: Map<String, EntityKind>,
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
                if (known == null || known !is TypeShape.CursorRow && fact.shape is TypeShape.CursorRow) shapes[fact.entityKey] =
                    fact.shape
            }
            // A Forms item's or parameter's text is what its page shows (`CHAR 5`): its place takes the type from its shape.
            for (text in file.declaredTexts) {
                if (text.datatype != null && kinds[text.entityKey] != EntityKind.FORMS_ITEM && kinds[text.entityKey] != EntityKind.FORMS_PARAMETER) {
                    texts.putIfAbsent(text.entityKey, text.datatype)
                }
            }
        }
    }

    /** What a step of the following reached: a declaration's shape (of [owner], when it has an entity), a column, or a failure. */
    sealed interface Node {
        data class Shape(val shape: TypeShape, val owner: String?) : Node

        /** A column, read through [functions] (a cursor field `nvl(code, '-')`), outer first. */
        data class Column(
            val key: String,
            val functions: List<String> = emptyList(),
            val parts: List<QueryItem> = emptyList()
        ) : Node {
            /** The column and those of the other parts of a concatenation of several, with their functions. */
            val all get() = listOf(key to functions) + parts.mapNotNull { part -> part.columnKey?.let { it to part.functions } }
        }

        data class Failed(val reason: String) : Node
    }

    /** The class of a place ([classify]), its size, and where the type comes from ([source]) or why it is not known ([reason]). */
    class Decision(
        val placeClass: PlaceClass,
        val length: Int? = null,
        val unit: String? = null,
        val scale: Int? = null,
        val source: String? = null,
        val reason: String? = null
    )

    /** The shape the declaration of [key] has; null when none is known. */
    fun shapeOf(key: String): TypeShape? = shapes[key]

    /** The data type text the declaration of [key] writes; null when none is kept. */
    fun textOf(key: String): String? = texts[key]

    /** The data type of the Forms item or parameter [key], as its shape tells it; null for any other entity. */
    fun formsType(key: String): String? = if (!key.startsWith(FORMS_PREFIX)) null else when (val shape = shapes[key]) {
        is TypeShape.Sized -> shape.type
        is TypeShape.Unsized -> shape.type
        else -> null
    }

    /**
     * Whether [target] is a record that takes its one item as a field (`select * into r` of a one-column table): a row
     * record ([rowRecord]) or a variable of a record type ([TypeShape.Record]).
     */
    fun isRow(target: IntoTarget, bulk: Boolean): Boolean {
        val node = structure(targetNode(target, bulk), 0)
        return node is Node.Shape && (node.shape is TypeShape.Record || node.shape is TypeShape.CursorRow || node.shape is TypeShape.Anchored && node.shape.rowType)
    }

    /**
     * The declaration of [target] when it is a row record (a cursor `FOR` loop record, a variable of a `%ROWTYPE` or of a
     * cursor's row), not a variable of a record type ([TypeShape.Record]); else null.
     */
    fun rowRecord(target: IntoTarget): Node.Shape? =
        (structure(
            targetNode(target, false),
            0
        ) as? Node.Shape)?.takeIf { it.shape is TypeShape.CursorRow || it.shape is TypeShape.Anchored && it.shape.rowType }

    /**
     * The field entity [node] reached when the Forms [target] names a field after its variable (`r.code`,
     * `pks.g_rec.code`): the target itself, not one of its elements, nor a field the statement's item picks. Null otherwise.
     */
    fun recordField(target: IntoTarget, node: Node, field: Int?, bulk: Boolean): String? {
        if (target.entityKey?.startsWith(FORMS_PREFIX) != true || target.path.isEmpty() || target.path.last() == IntoTarget.ELEMENT) return null
        if (target.element || bulk || field != null) return null
        return (node as? Node.Shape)?.owner?.takeIf { it != target.entityKey && it.startsWith(FORMS_PREFIX) }
    }

    /** The target's declaration, its written fields followed, then its element for a collection element or `BULK COLLECT`. */
    fun targetNode(target: IntoTarget, bulk: Boolean): Node {
        var node: Node = when {
            target.entityKey != null -> of(target.entityKey)
            target.local != null -> Node.Shape(target.local, null)
            else -> Node.Failed(target.reason ?: "TARGET_UNDECIDED")
        }
        for (name in target.path) node =
            if (name == IntoTarget.ELEMENT) element(node) else field(node, null, name).first
        if (target.element) node = element(node)
        if (bulk) node = element(node)
        return node
    }

    fun of(key: String): Node = shapes[key]?.let { Node.Shape(it, key) } ?: fieldOf(key) ?: when (kinds[key]) {
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
    fun field(node: Node, index: Int?, name: String?, depth: Int = 0): Pair<Node, String?> {
        val record = structure(node, depth)
        if (record !is Node.Shape) return record to name
        return when (val shape = record.shape) {
            is TypeShape.Anchored -> {
                val key = shape.anchorKey
                val row = if (key != null) shapes[key] else shape.local ?: return Node.Failed(
                    shape.reason ?: "ANCHOR_UNRESOLVED"
                ) to name
                when {
                    // A cursor's row (`c%ROWTYPE`).
                    row is TypeShape.CursorRow -> item(row.items, index, name)
                    // A relation's row (`t%ROWTYPE`): its column of that name, or at that position in the dictionary.
                    key != null && kinds[key] in RELATIONS -> when {
                        name != null -> Node.Column("$key.$name") to name
                        else -> relationColumns[key]?.getOrNull(index!!)
                            ?.let { Node.Column(it) to it.substringAfterLast('.') }
                            ?: (Node.Failed("ROWTYPE_COLUMNS_UNKNOWN") to null)
                    }

                    else -> Node.Failed(shape.reason ?: "ROWTYPE_SOURCE_UNKNOWN") to name
                }
            }

            is TypeShape.CursorRow -> item(shape.items, index, name)
            is TypeShape.Record -> {
                val field =
                    if (name != null) shape.fields.firstOrNull { it.name == name } else shape.fields.getOrNull(index!!)
                when {
                    field == null -> Node.Failed("FIELD_UNKNOWN") to name
                    field.key != null && field.key in shapes -> Node.Shape(
                        shapes.getValue(field.key),
                        field.key
                    ) to field.name

                    field.local != null -> Node.Shape(field.local, field.key) to field.name
                    else -> Node.Failed("FIELD_UNKNOWN") to field.name
                }
            }

            else -> Node.Failed("NOT_A_RECORD") to name
        }
    }

    private fun item(items: List<QueryItem>, index: Int?, name: String?): Pair<Node, String?> {
        val item = if (name != null) items.firstOrNull { it.name == name } else items.takeWhile { !it.opaque }
            .getOrNull(index!!)
        return when {
            item == null -> Node.Failed("CURSOR_FIELD_UNKNOWN") to name
            item.columnKey != null -> Node.Column(item.columnKey, item.functions, item.parts) to item.name
            item.expression != null -> Node.Failed("CURSOR_FIELD_EXPRESSION") to item.name
            else -> Node.Failed("CURSOR_FIELD_NOT_A_COLUMN") to item.name
        }
    }

    /**
     * Why the field [name] of the row record [row] has no column known, the shape it is counted under, after [prefix]
     * ([PlaceShapes.ARGUMENT_COLUMN_NOT_KNOWN] for a field passed to a routine,
     * [PlaceShapes.RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN] for one assigned):
     * - `EXPRESSION_*`: the cursor's item is an expression the index does not follow, by its shape as for `EXPRESSION_*`
     *   (`code || note` is `_CONCATENATION`, `decode(code, 'a', 'b')` is `_OTHER_DECODE`); an `NVL`, `NVL2` or
     *   `COALESCE` of several columns ends in `_SEVERAL_COLUMNS` (`nvl(note, code)` is `_NVL_SEVERAL_COLUMNS`);
     * - `ROWTYPE`: a `%ROWTYPE` whose relation or cursor the index does not know (`r missing_tab%rowtype`);
     * - `CURSOR`: a `%ROWTYPE` of a cursor whose query is not known (a cursor of another package with no query);
     * - `REF_CURSOR`: a `%ROWTYPE` of a cursor variable, whose `REF CURSOR` type has a `RETURN` (`r p%rowtype`);
     * - `STAR`: the cursor's `*` over a relation whose columns are not known (`select * from missing_tab`);
     * - `NO_COLUMN`, `COLUMN_UNRESOLVED`, `INLINE_VIEW`: the item, by [QueryItem.notColumn];
     * - `OTHER`: anything else.
     */
    fun notKnown(prefix: String, row: Node.Shape, name: String?): String {
        val items = when (val shape = row.shape) {
            is TypeShape.CursorRow -> shape.items
            is TypeShape.Anchored -> {
                val key = shape.anchorKey
                val source = if (key != null) shapes[key] else shape.local
                (source as? TypeShape.CursorRow)?.items ?: return prefix + when {
                    refCursor(source) != null -> "REF_CURSOR"
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
            // Several columns: the split families say so, as one column says where following stops.
            item.combined != null -> "EXPRESSION_${item.combined}" + if (item.combined in PlaceShapes.SPLIT_SHAPES) "_SEVERAL_COLUMNS" else ""
            else -> item.notColumn ?: "OTHER"
        }
    }

    /** The `REF CURSOR` type [shape] is, through type names: its [TypeShape.Unknown], or null for any other shape. */
    fun refCursor(shape: TypeShape?, depth: Int = 0): TypeShape.Unknown? = when {
        depth > MAX_DEPTH -> null
        shape is TypeShape.Unknown -> shape.takeIf { it.reason == CURSOR_VARIABLE }
        shape is TypeShape.Named -> refCursor(shape.typeKey?.let { shapes[it] } ?: shape.local, depth + 1)
        shape is TypeShape.OutParameter -> refCursor(shape.type, depth + 1)
        else -> null
    }

    /**
     * Whether what a function returns (the declaration of [returned], its return key as a call binds it) is a scalar: not
     * a record, a collection or an object.
     */
    fun isScalar(returned: String): Boolean {
        val node = structure(Node.Shape(shapes[returned] ?: return true, null), 0) as? Node.Shape ?: return true
        return when (val shape = node.shape) {
            is TypeShape.Record, is TypeShape.Collection, is TypeShape.CursorRow -> false
            is TypeShape.Anchored -> !shape.rowType
            is TypeShape.Unsized -> shape.type != "OBJECT"
            else -> true
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
    fun classify(node: Node, column: String, depth: Int): Decision {
        if (depth > MAX_DEPTH) return Decision(PlaceClass.UNKNOWN, reason = "ANCHOR_CYCLE")
        return when (node) {
            // A cursor field read through a function types its record's field as the function's result, not as the column.
            is Node.Column -> if (node.functions.isNotEmpty()) Decision(
                PlaceClass.UNKNOWN,
                reason = "CURSOR_FIELD_EXPRESSION"
            )
            else Decision(
                if (node.key == column) PlaceClass.ANCHORED_TO_COLUMN else PlaceClass.ANCHORED_ELSEWHERE,
                source = node.key
            )

            is Node.Failed -> Decision(PlaceClass.UNKNOWN, reason = node.reason)
            is Node.Shape -> when (val shape = node.shape) {
                is TypeShape.Sized -> Decision(PlaceClass.FIXED_SIZE, shape.length, shape.unit, shape.scale, node.owner)
                is TypeShape.Unsized -> Decision(PlaceClass.NO_SIZE, source = node.owner)
                is TypeShape.Anchored -> if (shape.rowType) Decision(
                    PlaceClass.UNKNOWN,
                    reason = "NOT_A_SCALAR"
                ) else classify(anchor(shape, depth), column, depth + 1)

                is TypeShape.OutParameter -> classify(Node.Shape(shape.type, node.owner), column, depth + 1)
                is TypeShape.Named -> classify(named(shape, node.owner), column, depth + 1)
                is TypeShape.Unknown -> Decision(PlaceClass.UNKNOWN, reason = shape.reason)
                // A record or a collection taking one column: Oracle rejects it.
                is TypeShape.Record, is TypeShape.Collection, is TypeShape.CursorRow -> Decision(
                    PlaceClass.UNKNOWN,
                    reason = "NOT_A_SCALAR"
                )
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 20

        /** The reason of the shape of a `REF CURSOR` type ([TypeShape.Unknown]). */
        const val CURSOR_VARIABLE = "CURSOR_VARIABLE"

        val RELATIONS = setOf(
            EntityKind.TABLE,
            EntityKind.VIEW,
            EntityKind.MATERIALIZED_VIEW,
            EntityKind.OBJECT_TABLE,
            EntityKind.UNKNOWN_RELATION
        )
    }
}
