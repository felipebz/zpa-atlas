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

    /** The prefix of the key of a nested subprogram's parameter ([IntoTarget.localKey]). */
    const val LOCAL_PREFIX = "local:"
}
