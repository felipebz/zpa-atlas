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

    data class Unknown(val reason: String) : TypeShape
}

/** A field of a record type: its [name], its entity [key] when it has one, and its own [local] shape. */
data class ShapeField(val name: String, val key: String?, val local: TypeShape?)

/**
 * A select item, at its position in the select list (a `*` expanded): the column it is ([columnKey]), written bare or
 * qualified; else, for an expression of one column, its [expression] shape (`NVL`, `UPPER`, `CONCATENATION`, …); else
 * neither. [name] is the item's alias or column name; [opaque] marks a `*` whose columns are not known, after which
 * no position can be told.
 */
data class QueryItem(val name: String?, val columnKey: String?, val expression: String?, val opaque: Boolean = false)

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
    val reason: String?
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
    val expression: String? = null
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
    val reason: String?
)
