//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.model

/**
 * The type of a declaration as far as its own file tells it ([com.felipebz.zpa.atlas.extract.TypeShapes]): what is
 * declared elsewhere is named by its entity key and followed after the scan ([com.felipebz.zpa.atlas.index.ColumnPlaces]).
 */
sealed interface TypeShape {
    /**
     * `x%TYPE` or `x%ROWTYPE` ([rowType]): the entity the anchor names ([anchorKey]: a column, a relation, a cursor, a
     * variable, a parameter, a field), else the shape of a declaration without an entity it names ([local]), then the
     * fields [path] names after it (`l_rec.code%TYPE`); [reason] when the anchor is not known.
     */
    data class Anchored(val rowType: Boolean, val anchorKey: String?, val local: TypeShape?, val path: List<String>, val reason: String?) : TypeShape

    /**
     * A type whose size the declaration decides: [length] in [unit] (`CHAR`, `BYTE`, `DIGITS`, or
     * `NLS_LENGTH_SEMANTICS` for a character length written without a unit), null for a predefined bounded type
     * (`PLS_INTEGER`); for a number, [length] is the digits it holds before the point and [scale] its scale;
     * [type] as written, upper-cased.
     */
    data class Sized(val type: String, val length: Int?, val unit: String?, val scale: Int? = null) : TypeShape

    /** A type with no size of its own: a parameter's `VARCHAR2`, `NUMBER` without precision, `CLOB`, `DATE`. */
    data class Unsized(val type: String) : TypeShape

    /**
     * An `OUT` or `IN OUT` parameter ([mode]) of [type]: a value read into it lands in each caller's argument, whose
     * declaration decides the size, since the argument does not take the size of the parameter's type.
     */
    data class OutParameter(val mode: String, val type: TypeShape) : TypeShape

    /** A type name: the type entity [typeKey], else the shape of a type without an entity ([local]); [reason] when neither. */
    data class Named(val typeKey: String?, val local: TypeShape?, val reason: String?) : TypeShape

    /** A record type, its fields in order. */
    data class Record(val fields: List<ShapeField>) : TypeShape

    /** A collection type: nested table, associative array or varray of [element]. */
    data class Collection(val element: TypeShape) : TypeShape

    /** An explicit cursor's row: its query's select items in order. */
    data class CursorRow(val items: List<QueryItem>) : TypeShape

    /** Not known, by [reason]; [strongCursor] for a `REF CURSOR` type with `RETURN` (reason `CURSOR_VARIABLE`). */
    data class Unknown(val reason: String, val strongCursor: Boolean = false) : TypeShape
}

/** A field of a record type: its [name], its entity [key] when it has one, and its own [local] shape. */
data class ShapeField(val name: String, val key: String?, val local: TypeShape?)

/**
 * A select item, at its position in the select list (a `*` expanded): the column it is ([columnKey]), written bare or
 * qualified; else, for an expression of one column, its [expression] shape (`NVL`, `UPPER`, `CONCATENATION`, …); else
 * neither. [name] is the item's alias or column name; [opaque] marks a `*` whose columns are not known, after which
 * no position can be told.
 */
data class QueryItem(
    val name: String?,
    val columnKey: String?,
    val expression: String?,
    val opaque: Boolean = false,
    /** The value functions the item reads [columnKey] through, outer first (`NVL`; `UPPER`, `TRIM`); empty when read directly. */
    val functions: List<String> = emptyList(),
    /**
     * An expression of several columns (`code || note`, `nvl(note, code)`): its shape, as [expression] says it for one
     * column. Only told when a routine is passed the item's field; such an item is not counted as an `EXPRESSION_*`.
     */
    val combined: String? = null,
    /** The other columns of a concatenation of several, each a part of it (`code || note`): each a place as [columnKey] is. */
    val parts: List<QueryItem> = emptyList(),
    /**
     * Why an item that is neither a column nor an expression of columns reads no column the index knows: `NO_COLUMN` (it
     * reads no column of a table or view: a literal, a PL/SQL value, `ROWNUM`, a function of those), `COLUMN_UNRESOLVED`
     * (a column the index cannot resolve) or `INLINE_VIEW` (a column of an inline view); null for any other item.
     */
    val notColumn: String? = null
)

/**
 * In a list of value functions, a concatenation: the value is a part of what is written, other text around it
 * (`'C:' || code`, `CONCAT(code, 'x')`).
 */
const val CONCATENATED = "||"

/** What the declaration of [entityKey] says its type is. */
data class DeclaredShapeFact(val entityKey: String, val shape: TypeShape)

/**
 * A PL/SQL target of an `INTO`, as written at [span]: the entity zpa binds it to ([entityKey]) and the fields written
 * after it ([path], with [ELEMENT] steps for a field through a collection element), or, for a declaration without an entity, its [local] shape and [declaredType]; [element] for a
 * collection element (`l_tab(i)`); [reason] when the target is not known.
 */
data class IntoTarget(
    val span: SourceSpan,
    val written: String,
    val entityKey: String?,
    val path: List<String>,
    val element: Boolean,
    val local: TypeShape?,
    val declaredType: String?,
    val reason: String?,
    /**
     * For a parameter of a subprogram nested in a routine, which has no entity: where its name is in the file
     * (`local:line:column`), as a call of that subprogram names it ([IntoFact.parameterKey]).
     */
    val localKey: String? = null
) {
    companion object {
        /** A step of [path] that takes an element of the collection reached so far (`a(i).f`: `ELEMENT`, `F`). */
        const val ELEMENT = "()"
    }
}

/**
 * One `INTO`: [source], [bulk] for `BULK COLLECT`, where it is, the select items of each branch of its query
 * ([branches]; a local cursor's for a `FETCH`), or the cursor entity it fetches from ([cursorKey]), and its [targets].
 * An assignment `target := r.f` ([PlaceSource.ASSIGNMENT]) has one target, the record [row] (a cursor loop record or a
 * variable; whether it is a row record is decided after the scan) and its field [rowField], and the [expression] shape
 * when the right side is an expression of that field rather than the field itself.
 */
data class IntoFact(
    val source: PlaceSource,
    val bulk: Boolean,
    val unitIndex: Int,
    val containerKey: String?,
    val statementLocalId: Int?,
    val branches: List<List<QueryItem>>,
    val cursorKey: String?,
    val targets: List<IntoTarget>,
    val row: IntoTarget? = null,
    val rowField: String? = null,
    val expression: String? = null,
    /** The value functions an assignment reads its record's field through, outer first (`l := nvl(r.code, '-')`). */
    val functions: List<String> = emptyList(),
    /**
     * For [PlaceSource.OUT_ARGUMENT]: the `OUT` or `IN OUT` parameter the argument ([targets]) is passed for. For a
     * [PlaceSource.IN_ARGUMENT] call: the `IN` parameter the column ([branches], or the record's field [row].[rowField])
     * is passed to (null when the call does not tie it to one), [targets] the argument, its reason when not followed.
     * For a [PlaceSource.IN_ARGUMENT] flow ([fromParameter]): the parameter the value is passed on to.
     */
    val parameterKey: String? = null,
    /**
     * A flow inside a routine: its `IN` parameter whose value, through [functions], is written into [targets], passed on
     * to [parameterKey], or written in a way not followed (the shape in [expression]). Read by an `INTO` with a single
     * target, [branches] are its items, the parameter's the one whose column key is [fromParameter]: the target takes it
     * as it would take a column in that position.
     */
    val fromParameter: String? = null,
    /**
     * For a [PlaceSource.FETCH] from a cursor variable: `STRONG` when its type is a `REF CURSOR` type of this file with
     * `RETURN`, `OPENED_STATIC` or `OPENED_DYNAMIC` when the routine opens it `FOR` static queries only or `FOR` a
     * string, `PARAMETER` when it comes in as a parameter the routine does not open; else null.
     */
    val cursorKind: String? = null,
    /** For a [PlaceSource.FETCH] from a cursor variable: the entity of its type, when it names one (a `REF CURSOR` type). */
    val cursorType: String? = null,
    /**
     * The call this is an argument of, by its span in the file: for a [PlaceSource.IN_ARGUMENT] call or a flow passed
     * on to a parameter, and for a [PlaceSource.OUT_ARGUMENT]. A value that comes back out of the called routine goes
     * back to this call only.
     */
    val call: SourceSpan? = null,
    /** For a flow from what a function returns: the call whose result it is, by its span in the file. */
    val resultOf: SourceSpan? = null,
    /**
     * For a flow or a [PlaceSource.OUT_ARGUMENT]: whether its single target is a variable or parameter of the subprogram
     * the code is in (a nested one included). What it holds came in with the call of that subprogram; a package
     * variable, or one of a routine around a nested subprogram, outlives that call.
     */
    val keeps: Boolean = false
)

/**
 * A place a column's value is read into, decided after the scan: [columnKey] read by [source] into the target at
 * [span] ([targetKey], as [targetWritten], its [field] for a record), of [placeClass]; [declaredType] as the target's
 * declaration writes it, [length] and [unit] when it has a size ([scale] too for a number: [length] is then the digits
 * before the point), [typeSourceKey] the entity whose declaration decides the type (the column an anchor ends at, or the
 * variable, field or type that writes the size), [parameterMode] when the target is an `OUT` or `IN OUT` parameter,
 * [reason] for `UNKNOWN`.
 */
data class ColumnPlaceFact(
    val unitIndex: Int,
    val containerKey: String?,
    val statementLocalId: Int?,
    val span: SourceSpan,
    val columnKey: String,
    val source: PlaceSource,
    val bulk: Boolean,
    val targetKey: String?,
    val targetWritten: String,
    val field: String?,
    val placeClass: PlaceClass,
    val declaredType: String?,
    val length: Int?,
    val unit: String?,
    val scale: Int?,
    val typeSourceKey: String?,
    val parameterMode: String?,
    val reason: String?,
    /** The value functions the column is read through, outer first; empty when read directly. */
    val functions: List<String> = emptyList(),
    /** For an argument of a call ([PlaceSource.OUT_ARGUMENT]): the `OUT` or `IN OUT` parameter it is passed for. */
    val argumentOf: String? = null,
    /** The target's [IntoTarget.localKey]: a nested subprogram's parameter, whose callers are followed in its file. */
    val localParameter: String? = null,
    /**
     * For [PlaceSource.IN_ARGUMENT]: how many calls pass the column to [argumentOf], and one of them ([callPath], [callLine]).
     */
    val callCount: Int? = null,
    val callPath: String? = null,
    val callLine: Int? = null
)
