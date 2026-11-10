//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.model

/**
 * The shapes and key parts of the places a column's value is read into that the extraction writes
 * ([com.felipebz.zpa.atlas.extract.IntoClauses]) and the index reads back ([com.felipebz.zpa.atlas.index.ColumnPlaces]).
 */
object PlaceShapes {
    /** The last segment of the key of what a function returns: `<function key>.#RETURN`. */
    const val RETURN_SUFFIX = "#RETURN"

    /**
     * The prefix of the shape of an expression a parameter is written within
     * ([com.felipebz.zpa.atlas.extract.ExpressionShapes.argumentShape]).
     */
    const val ARGUMENT_EXPRESSION = "ARGUMENT_EXPRESSION_"

    /**
     * The start of the shape of an expression of several columns or fields that gives no place
     * (`SEVERAL_COLUMNS_NVL` for `nvl(code, note)`), after its family's prefix.
     */
    const val SEVERAL_COLUMNS = "SEVERAL_COLUMNS_"

    /** The shapes of the value functions whose count is split by where following the value stops (`NVL_TO_CHAR`). */
    val SPLIT_SHAPES = setOf("NVL", "NVL2", "OTHER_COALESCE")

    /**
     * Marks a parameter of a routine whose body is not PL/SQL ([com.felipebz.zpa.atlas.extract.IntoClauses.routine]); never
     * counted itself.
     */
    const val BODY_NOT_PLSQL = "BODY_NOT_PLSQL"

    /** The prefix of the shape of a `FETCH` from a cursor whose query is not known, by the cursor it is. */
    const val FETCH_CURSOR_NOT_KNOWN = "FETCH_CURSOR_NOT_KNOWN_"

    /** The shape of a `FETCH` from a cursor variable whose targets do not match the items of a query it reads. */
    const val FETCH_CURSOR_ITEMS_NOT_MATCHED = "FETCH_CURSOR_ITEMS_NOT_MATCHED"

    /** The shape of a `FETCH` whose query's `*` is over a relation or collection whose columns are not known. */
    const val FETCH_CURSOR_STAR_NOT_KNOWN = "FETCH_CURSOR_STAR_NOT_KNOWN"

    /**
     * The shape of a column's value passed to a routine outside the index: a call the dictionary resolves, a routine
     * whose body the sources do not have, or one whose body is not PL/SQL.
     */
    const val ARGUMENT_ROUTINE_OUTSIDE = "ARGUMENT_ROUTINE_OUTSIDE"

    /**
     * The prefix of the shape of a row record's field passed to a routine whose column is not known, by why
     * ([com.felipebz.zpa.atlas.index.PlaceTypes.notKnown]).
     */
    const val ARGUMENT_COLUMN_NOT_KNOWN = "ARGUMENT_COLUMN_NOT_KNOWN_"

    /**
     * The prefix of the shape of an assignment of a row record's field whose column is not known, by why
     * ([com.felipebz.zpa.atlas.index.PlaceTypes.notKnown]).
     */
    const val RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN = "RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN_"

    /** The shape of a call of a function that returns a column's value in a record, a collection or an object. */
    const val RETURNED_NOT_SCALAR = "RETURNED_NOT_SCALAR"

    /** The shape of an `OUT` parameter a column's value is read into whose callers cannot be told. */
    const val OUT_PARAMETER_NOT_FOLLOWED = "OUT_PARAMETER_NOT_FOLLOWED"

    /** The shape of an argument for an `OUT` parameter a column's value is read into that is not a name, field or element the index binds. */
    const val OUT_ARGUMENT_NOT_FOLLOWED = "OUT_ARGUMENT_NOT_FOLLOWED"

    /**
     * The reason of a call whose routine is not decided among routines with an `OUT` parameter, and the shape it is
     * counted under when a column's value is read into one of them.
     */
    const val OUT_CALL_NOT_DECIDED = "OUT_CALL_NOT_DECIDED"

    /** The prefix of the key of a nested subprogram's parameter ([IntoTarget.localKey]). */
    const val LOCAL_PREFIX = "local:"
}
