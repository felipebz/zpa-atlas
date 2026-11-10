//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.extract.displayOf
import com.felipebz.zpa.atlas.index.PlaceLengths.LengthUnit
import com.felipebz.zpa.atlas.index.PlaceTypes.Decision
import com.felipebz.zpa.atlas.index.PlaceTypes.Node
import com.felipebz.zpa.atlas.model.*

/**
 * The places a file's own statements read columns into: the select item at position i goes to the target at position
 * i; a single target that takes several items, or a whole row, is a record that takes them field by field, in order; a
 * `BULK COLLECT` target is a collection whose element takes the value. Each place is decided by its target's type
 * ([types]) and its length's unit ([lengths]), as [place] builds it, which [RoutineFlow] builds its places with too.
 */
internal class DirectPlaces(private val types: PlaceTypes, private val lengths: PlaceLengths) {
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
            if (Role.IMPLICIT !in use.roles || Role.READ !in use.roles || types.kinds[use.containerKey] != EntityKind.FORMS_ITEM || use.status !in COLUMN_STATUSES) continue
            places += blockQuery(
                file.path,
                use.containerKey,
                use.entityKey?.takeIf { types.kinds[it] == EntityKind.COLUMN } ?: continue)
        }
    }

    /** The places [into], a statement of the file [path], reads columns into, and what it reads without following it, in [leftOut]. */
    private fun intoPlaces(
        path: String,
        into: IntoFact,
        places: MutableList<ColumnPlaceFact>,
        leftOut: MutableMap<String, Int>
    ) {
        if (into.source == PlaceSource.ASSIGNMENT) {
            assignment(path, into, places, leftOut)
            return
        }
        // The arguments of OUT parameters are followed from the places of their parameters, the values passed to IN
        // parameters from their calls ([RoutineFlow]).
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
        val row = types.shapeOf(cursorKey) as? TypeShape.CursorRow
        if (row == null) {
            leftOut.merge(fetchNotKnown(into, cursorKey), into.targets.size, Int::plus)
            return null
        }
        return listOf(row.items)
    }

    /** The places of the select [items] of one branch of the query [into] reads, at the targets that take them. */
    private fun branchPlaces(
        path: String,
        into: IntoFact,
        items: List<QueryItem>,
        places: MutableList<ColumnPlaceFact>,
        leftOut: MutableMap<String, Int>
    ) {
        // A `*` whose columns are not known: no position can be told after it.
        val known = items.takeWhile { !it.opaque }
        val record = takesRecord(into, items, known.size)
        // A `*` over a relation or collection whose columns are not known: the positions after it cannot be told,
        // those before it only for a record.
        if (into.source == PlaceSource.FETCH && known.size < items.size) {
            leftOut.merge(PlaceShapes.FETCH_CURSOR_STAR_NOT_KNOWN, into.targets.size, Int::plus)
            if (known.isEmpty() || !record) return
        }
        // A cursor variable's query its targets do not match: Oracle raises at the FETCH, or the query is another one.
        if (into.cursorKind != null && !record && into.targets.size != known.size) {
            leftOut.merge(PlaceShapes.FETCH_CURSOR_ITEMS_NOT_MATCHED, into.targets.size, Int::plus)
            return
        }
        itemPlaces(path, into, known, record, places, leftOut)
    }

    /**
     * Whether the target of [into] is a single record that takes the [items] field by field, [known] of them before a `*`
     * whose columns are not known. From a cursor variable, a single target takes several items only when it is a record.
     */
    private fun takesRecord(into: IntoFact, items: List<QueryItem>, known: Int): Boolean = into.targets.size == 1 &&
        if (into.cursorKind != null) types.isRow(
            into.targets[0],
            into.bulk
        ) else items.size > 1 || known < items.size || types.isRow(into.targets[0], into.bulk)

    /** The places of the [known] items of [into], each at its target, or at its field of the single target when that is a [record]. */
    private fun itemPlaces(
        path: String,
        into: IntoFact,
        known: List<QueryItem>,
        record: Boolean,
        places: MutableList<ColumnPlaceFact>,
        leftOut: MutableMap<String, Int>
    ) {
        for ((index, item) in known.withIndex()) {
            val target = if (record) into.targets[0] else into.targets.getOrNull(index) ?: break
            if (item.expression != null) leftOut.merge("EXPRESSION_${item.expression}", 1, Int::plus)
            // An expression of several columns that gives no place (`nvl(code, note)`): counted by its shape.
            if (item.combined != null) leftOut.merge(
                "EXPRESSION_${PlaceShapes.SEVERAL_COLUMNS}${item.combined}",
                1,
                Int::plus
            )
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
     * `%ROWTYPE` not known) is counted by why ([PlaceTypes.notKnown]), and so is an expression of the field
     * (`upper(r.code)`) by its shape.
     */
    private fun assignment(
        path: String,
        into: IntoFact,
        places: MutableList<ColumnPlaceFact>,
        leftOut: MutableMap<String, Int>
    ) {
        val row = types.rowRecord(into.row ?: return) ?: return
        if (into.expression?.startsWith(PlaceShapes.SEVERAL_COLUMNS) == true) {
            leftOut.merge(RECORD_ASSIGNMENT_EXPRESSION + into.expression, 1, Int::plus)
            return
        }
        val column = types.field(row, null, into.rowField).first as? Node.Column
        when {
            column == null -> leftOut.merge(
                types.notKnown(
                    PlaceShapes.RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN,
                    row,
                    into.rowField
                ), 1, Int::plus
            )

            into.expression != null -> leftOut.merge(RECORD_ASSIGNMENT_EXPRESSION + into.expression, 1, Int::plus)
            else -> for ((key, functions) in column.all) places += place(
                path,
                into,
                into.targets.single(),
                null,
                key,
                into.functions + functions
            )
        }
    }

    /** The place of [column] in the item [item] of the file [path], which its block's query reads the column into: in no code. */
    private fun blockQuery(path: String, item: String, column: String): ColumnPlaceFact {
        val decision = types.classify(types.of(item), column, 0)
        val unit = lengths.unitOf(decision, path, null)
        return ColumnPlaceFact(
            null,
            item,
            null,
            null,
            column,
            PlaceSource.BLOCK_QUERY,
            false,
            item,
            displayOf(item),
            null,
            decision.placeClass,
            types.formsType(item),
            decision.length,
            unit.unit,
            decision.scale,
            null,
            null,
            decision.reason,
            unitSource = unit.source
        )
    }

    /** The place of [column] in [target] of [into], a statement of the file [path]. */
    fun place(
        path: String,
        into: IntoFact,
        target: IntoTarget,
        field: Int?,
        column: String,
        functions: List<String>
    ): ColumnPlaceFact {
        var node = types.targetNode(target, into.bulk)
        var fieldName: String? = null
        // The field of a Forms record written after its variable is the target, as zpa binds a database record's field.
        val targetKey: String? = types.recordField(target, node, field, into.bulk) ?: target.entityKey
        var text = targetKey?.let { types.textOf(it) ?: types.formsType(it) } ?: target.declaredType
        if (field != null) {
            val (reached, name) = types.field(node, field, null)
            node = reached
            fieldName = name
            if (reached is Node.Shape && reached.owner != null) types.textOf(reached.owner)?.let { text = it }
        }
        val decision = if (targetKey == null && target.local == null) Decision(
            PlaceClass.UNKNOWN,
            reason = target.reason ?: "TARGET_UNDECIDED"
        )
        else types.classify(node, column, 0)
        // The parameter itself, not a field or an element of it, whose type is the caller's argument's.
        val mode = (node as? Node.Shape)?.shape.let { it as? TypeShape.OutParameter }
            ?.takeIf { field == null && target.path.isEmpty() && !target.element && !into.bulk }?.mode
        val placeClass = if (mode != null) PlaceClass.CALLER_DECIDES else decision.placeClass
        val sized = mode == null
        val unit = if (sized) lengths.unitOf(decision, path, into.unitIndex) else LengthUnit(null)
        // The type source is another entity whose declaration decides the type; the target's own declaration is not repeated.
        return ColumnPlaceFact(
            into.unitIndex,
            into.containerKey,
            into.statementLocalId,
            target.span,
            column,
            into.source,
            into.bulk,
            targetKey,
            target.written,
            fieldName,
            placeClass,
            text,
            decision.length.takeIf { sized },
            unit.unit,
            decision.scale.takeIf { sized },
            decision.source?.takeIf { it != targetKey },
            mode,
            decision.reason,
            functions,
            into.parameterKey,
            target.localKey.takeIf { mode != null },
            unitSource = unit.source,
            unitCompiledIn = unit.compiledIn
        )
    }

    /**
     * The shape a FETCH whose cursor's query is not known is counted under, by the cursor: `_STRONG` for a variable of
     * a `REF CURSOR` type with `RETURN` (its own entity [cursorKey], or the type the extraction found), else what the
     * extraction told ([IntoFact.cursorKind]: opened in the routine, or a parameter), else `_OTHER`.
     */
    private fun fetchNotKnown(into: IntoFact, cursorKey: String?): String {
        val strong =
            into.cursorKind == STRONG || types.refCursor(cursorKey?.let { types.shapeOf(it) })?.strongCursor == true ||
                types.refCursor(into.cursorType?.let { types.shapeOf(it) })?.strongCursor == true
        val kind = when {
            strong -> STRONG
            into.cursorKind != null -> into.cursorKind
            cursorKey != null && types.kinds[cursorKey] == EntityKind.PARAMETER -> "PARAMETER"
            else -> "OTHER"
        }
        return PlaceShapes.FETCH_CURSOR_NOT_KNOWN + kind
    }

    companion object {
        private const val STRONG = "STRONG"

        /** The prefix of the shape of an assignment of an expression of a row record's field, by the expression's shape. */
        private const val RECORD_ASSIGNMENT_EXPRESSION = "RECORD_ASSIGNMENT_EXPRESSION_"

        /** The statuses of a block query's read of its item's column that name the column. */
        private val COLUMN_STATUSES = setOf(Status.RESOLVED, Status.UNVERIFIED, Status.EXTERNAL, Status.PARTIAL_SOURCE)

        /**
         * The functions as a place row tells them: those a value went through, outer first, without the concatenations,
         * and whether there was one. Chains that differ only in where a concatenation is (`||,TRIM` and `TRIM,||`, after a
         * cycle that mixes them) are the same row.
         */
        fun written(functions: List<String>) = functions.filter { it != CONCATENATED } to (CONCATENATED in functions)

        /** [functions] as the row tells them ([written]), as a list: the concatenation, if any, last. */
        private fun rowFunctions(functions: List<String>) =
            written(functions).let { (named, concatenated) -> if (concatenated) named + CONCATENATED else named }
    }
}
