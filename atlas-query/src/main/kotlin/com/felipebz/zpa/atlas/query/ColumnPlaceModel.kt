//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query


import kotlinx.serialization.Serializable

/**
 * A place a column's value is read into in PL/SQL: the column read ([read], the page's column or a view column computed
 * from it, then [viaView]), by [source] (`SELECT_INTO`, `FETCH`, `RETURNING`, `ASSIGNMENT` of a row record's field; [bulk] for `BULK COLLECT`), at [path]
 * and [line], [column] in [container]; the target as written ([targetWritten]), its entity ([target], absent for a
 * declaration without one) and the [field] of a record target. A Forms item a data block's query reads the column into
 * (`BLOCK_QUERY`) is in no code: no [line] or [column], the item its [container] and [target], its path [targetWritten].
 * How the target is declared: [placeClass], [declaredType]
 * as written, [length] and [unit] (`CHAR`, `BYTE` or `DIGITS`; for a number, [length] is the digits it holds before the
 * point and [scale] its scale), [typeSource] the entity whose declaration decides it (the column an anchor ends at, the
 * variable, field or type that writes the size), [parameterMode] (`OUT`, `IN OUT`) for a parameter target, whose
 * callers' arguments decide the size, and [reason] for `UNKNOWN`.
 *
 * Against the size `S` of the page ([PlaceGroupsPage.size]): its [verdict] ([Verdict]), [verdictReason] ([VerdictReason])
 * for an `UNKNOWN` one whose declaration is known, the [size] compared in [sizeUnit] (the target's own, or the other
 * column's for `ANCHORED_ELSEWHERE`), where a character unit comes from ([unitSource]: `WRITTEN`, `COMPILED` with
 * [unitCompiledIn], `OPTION`), and for a concatenated place that fits, the [room] its size leaves for the other text.
 * Through `SUBSTR` or `SUBSTRB`, the most of the value they keep, [kept] in [keptUnit] (`CHAR`, `BYTE`), which the size
 * is compared with too: it fits when either comparison fits.
 */
@Serializable
data class ColumnPlace(
    val read: EntitySummary,
    val viaView: Boolean,
    val source: String,
    val bulk: Boolean,
    val path: String,
    val line: Int?,
    val column: Int?,
    val container: EntitySummary?,
    val targetWritten: String,
    val target: EntitySummary?,
    val field: String?,
    val placeClass: String,
    val declaredType: String?,
    val length: Int?,
    val unit: String?,
    val typeSource: EntitySummary?,
    val reason: String?,
    val scale: Int?,
    val parameterMode: String?,
    /**
     * The value functions the column is read through, outer first (`NVL`; `UPPER`, `TRIM`); empty when read directly.
     * Through them the value that lands may be other than the column's (the other argument of `NVL`).
     */
    val functions: List<String>,
    /**
     * For source `OUT_ARGUMENT`: the `OUT` or `IN OUT` parameter the argument is passed for, and the routine called. For
     * source `IN_ARGUMENT`: the `IN` parameter the column is passed to, and its routine.
     */
    val argumentOf: EntitySummary?,
    val routine: EntitySummary?,
    /** For source `IN_ARGUMENT`: how many calls pass the column to [argumentOf], and one of them ([callPath], [callLine]). */
    val callCount: Int?,
    val callPath: String?,
    val callLine: Int?,
    /** The column's value is a part of a concatenation written into the target (`'C:' || code`): other text lands with it. */
    val concatenated: Boolean,
    val verdict: String = Verdict.UNKNOWN.name,
    val verdictReason: String? = null,
    val size: Int? = null,
    val sizeUnit: String? = null,
    val unitSource: String? = null,
    val unitCompiledIn: CompiledIn? = null,
    val room: Int? = null,
    val kept: Int? = null,
    val keptUnit: String? = null,
    /** The line of code the row shows, with the piece it marks ([PlaceLine]). */
    val shown: PlaceLine? = null,
    /** In a Forms module, the code [line] is in and its line there ([CodeAt]); null elsewhere. */
    val code: CodeAt? = null,
    /** Likewise for [callLine]. */
    val callCode: CodeAt? = null
)

/**
 * The line of code a place's row shows, at [path] and [line], marking [from] to [to] on it: for a value passed to a
 * parameter, the call's argument; else the column where the place's own statement reads it; else the target ([to]
 * null: to the end of the line). With
 * the page's answer, its indexed [text], whether the file changed since ([stale]) and its [colors].
 */
@Serializable
data class PlaceLine(
    val path: String,
    val line: Int,
    val from: Int,
    val to: Int?,
    val text: String? = null,
    val stale: Boolean? = null,
    val colors: List<List<Int>>? = null,
    /** In a Forms module, the code [line] is in and its line there ([CodeAt]); null elsewhere. */
    val code: CodeAt? = null,
    /** The target as written, to find on the line in place of [from] and [to] (an assignment's span is its `:=`). */
    @kotlinx.serialization.Transient val target: String? = null
)

/** The PL/SQL unit whose `NLS_LENGTH_SEMANTICS` gave a length its unit: `ALL_PLSQL_OBJECT_SETTINGS`'s [owner], [name] and [type]. */
@Serializable
data class CompiledIn(val owner: String, val name: String, val type: String)

/** A column's declared type ([datatype], null when not known) and its size: [length] in [unit], [scale] for a number. */
@Serializable
data class ColumnSize(val datatype: String?, val length: Int?, val unit: String?, val scale: Int?)

@Serializable
data class VerdictCount(val name: String, val count: Int)

/**
 * Which places of a column to list: compared with [size] (the column's when null), those of [verdicts] (all but
 * [Verdict.FOLLOWS] when null), whose target, routine, package or file has every word of [text], read by [source], and
 * with [concatenated] those where the value is one part of a concatenation too.
 */
data class PlaceFilter(
    val size: Int? = null,
    val verdicts: Set<Verdict>? = null,
    val text: String? = null,
    val source: String? = null,
    val concatenated: Boolean = false
) {
    val listed: Set<Verdict> get() = verdicts ?: (Verdict.entries.toSet() - Verdict.FOLLOWS)

    companion object {
        /** The verdicts [names] names, in any case. */
        fun verdicts(names: Collection<String>): Set<Verdict> = names.mapTo(LinkedHashSet()) { name ->
            Verdict.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                ?: throw InvalidQueryException(
                    "Unknown verdict: $name; the verdicts are ${
                        Verdict.entries.joinToString(
                            ", "
                        )
                    }"
                )
        }
    }
}

/**
 * The places of one routine ([container]; null for an anonymous block or a script, grouped by file): its short name
 * [routine] (the file name without a container), [packageName] the package or routine it is in, [parameters] only for
 * overloads of one package with the same name, [path] its file, [smaller] and [places] counted under the filter, and
 * its [rows] in line order.
 */
@Serializable
data class PlaceGroup(
    val container: EntitySummary?,
    val routine: String,
    val packageName: String?,
    val parameters: String?,
    val path: String,
    val smaller: Int,
    val places: Int,
    val rows: List<ColumnPlace>
)

/**
 * The places of a column ([PlaceFilter]) by routine, a page of [groups] at a time from [offset] ([nextOffset] null on the
 * last, [groupsLeft] after this page): the [column]'s size, the [size] `S` compared with ([sizeGiven] when the reader gave
 * it; null when the column's is not known and none was given), the [counts] by verdict under every filter but the verdict
 * one, the [verdicts] listed, [places] and [routines] listed, [totalPlaces] and [totalRoutines] under every filter but the
 * verdict one, [concatenatedCount] the places where the value is one part of a concatenation under the text and source
 * filters, [optionUnit] the unit a length written without one counts in when the dictionary does not tell it, and
 * [optionSized] how many of the places under every filter but the verdict one count so.
 */
@Serializable
data class PlaceGroupsPage(
    val column: ColumnSize,
    val size: Int?,
    val sizeGiven: Boolean,
    val counts: List<VerdictCount>,
    val verdicts: List<String>,
    val places: Int,
    val routines: Int,
    val totalPlaces: Int,
    val totalRoutines: Int,
    val concatenatedCount: Int,
    val optionUnit: String,
    val optionSized: Int,
    val offset: Int,
    val groups: List<PlaceGroup>,
    val nextOffset: Int?,
    val groupsLeft: Int
)

/**
 * A page of the places of a column ([PlaceFilter]) as rows, by verdict, then by file and line: as [PlaceGroupsPage]
 * says of the same fields, [total] the places listed, [rows] from [offset], [nextOffset] the offset of the next page
 * (null on the last).
 */
@Serializable
data class ColumnPlacePage(
    val column: ColumnSize,
    val size: Int?,
    val sizeGiven: Boolean,
    val counts: List<VerdictCount>,
    val verdicts: List<String>,
    val total: Int,
    val concatenatedCount: Int,
    val optionUnit: String,
    val optionSized: Int,
    val offset: Int,
    val rows: List<ColumnPlace>,
    val nextOffset: Int?
)
