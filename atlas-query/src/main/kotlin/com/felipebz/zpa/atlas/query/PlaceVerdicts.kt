//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

/**
 * How a place a column's value is read into compares with a size `S`: the column's size, or one the reader gives. Decided
 * when the index is read, never stored, so that every reader of the index gets the same answer.
 *
 * - [SMALLER]: the target's own size is less than `S`;
 * - [FITS]: it is `S` or more, or the target's type has no size (`CLOB`, `NUMBER` without precision, `DATE`), or it holds
 *   all that `SUBSTR` or `SUBSTRB` keep of the value ([Kept]);
 * - [CALLER_DECIDES]: an `OUT` or `IN OUT` parameter, whose callers' arguments hold the value;
 * - [UNKNOWN]: the target's type is not known, or its size does not compare with `S` ([VerdictReason]);
 * - [FOLLOWS]: anchored to the column itself;
 * - [OWN_SIZE]: a size of its own, with no `S` to compare it with (the column's size is not known and none was given).
 */
enum class Verdict { SMALLER, CALLER_DECIDES, UNKNOWN, FITS, FOLLOWS, OWN_SIZE }

/**
 * Why a target with a declaration the index knows is [Verdict.UNKNOWN]: [UNIT_MISMATCH] the column counts characters and
 * the target bytes, and the target is neither shorter than the column's characters nor four bytes for each of them;
 * [OTHER_COLUMN_UNKNOWN] the size of the other column a target is anchored to is not known; [NO_LENGTH] a size the
 * declaration decides without a length the index knows (`PLS_INTEGER`); [OTHER_KIND] a number for a character column, or
 * the reverse.
 */
enum class VerdictReason { UNIT_MISMATCH, OTHER_COLUMN_UNKNOWN, NO_LENGTH, OTHER_KIND }

/**
 * A column's size as its declared type ([datatype], `VARCHAR2(10 BYTE)`) writes it: [length] in [unit] (`BYTE`, `CHAR`, or
 * `DIGITS` for a number, then [length] is the digits before the point and [scale] its scale), null for a type without one;
 * [unitWritten] false when the type writes no unit and [unit] is the index's default.
 */
data class TypeSize(val datatype: String, val length: Int?, val unit: String?, val scale: Int?, val unitWritten: Boolean)

/** A [Verdict], why for [Verdict.UNKNOWN], and for a place that fits, the room its size leaves beyond `S` (or beyond the [Limit] that fits). */
data class Decided(val verdict: Verdict, val reason: VerdictReason? = null, val room: Int? = null)

/**
 * What `SUBSTR` and `SUBSTRB` keep of a column's value, as the index stores it: at most [length] in [unit], and [skipped]
 * in [skippedUnit] taken off its front by a start past the first.
 */
data class Kept(val length: Int?, val unit: String?, val skipped: Int?, val skippedUnit: String?)

/** The most of the value that lands, [length] in [unit] (`CHAR`, `BYTE`), which a place is compared with beside `S`. */
data class Limit(val length: Int, val unit: String)

/** What a place is compared with: `S` ([size], in the column's [unit]; null when there is none), and the [limit] `SUBSTR` keeps. */
data class Against(val size: Int?, val unit: String?, val limit: Limit? = null)

object PlaceVerdicts {
    /** How many bytes a character may take: a target counting bytes holds any value of `S` characters from `4·S` bytes. */
    private const val BYTES_PER_CHARACTER = 4

    // A type's name, then its size right after it when written: read apart, each pattern stays simple.
    private val CHARACTER = Regex("""^\s*(NVARCHAR2|NCHAR|VARCHAR2|VARCHAR|CHARACTER\s+VARYING|CHAR\s+VARYING|CHARACTER|CHAR|RAW)\b""", RegexOption.IGNORE_CASE)
    private val CHARACTER_SIZE = Regex("""\s*\(\s*(\d+)\s*(BYTE|CHAR)?\s*\)""", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""^\s*(NUMBER|NUMERIC|DECIMAL|DEC)\b""", RegexOption.IGNORE_CASE)
    private val NUMBER_SIZE = Regex("""\s*\(\s*(\d+|\*)\s*(?:,\s*(-?\d+)\s*)?\)""")
    private val INTEGER = Regex("""^\s*(INTEGER|INT|SMALLINT)\b""", RegexOption.IGNORE_CASE)

    /**
     * The size [datatype] writes, its unit [defaultUnit] (the index's `--nls-length-semantics`) when a character length
     * writes none; null for a null [datatype].
     */
    fun size(datatype: String?, defaultUnit: String): TypeSize? {
        val text = datatype?.trim()?.removeSuffix(" NOT NULL")?.trim() ?: return null
        CHARACTER.find(text)?.let { match ->
            val name = match.groupValues[1].uppercase().replace(Regex("\\s+"), " ")
            val size = CHARACTER_SIZE.matchAt(text, match.range.last + 1)
            val written = size?.groups?.get(2)?.value?.uppercase()
            val length = size?.groups?.get(1)?.value?.toInt() ?: if (name == "CHAR" || name == "CHARACTER" || name == "NCHAR") 1 else null
            return when {
                name == "RAW" -> TypeSize(text, length, "BYTE", null, true)
                name.startsWith("N") -> TypeSize(text, length, "CHAR", null, true)
                written != null -> TypeSize(text, length, written, null, true)
                else -> TypeSize(text, length, defaultUnit.takeIf { length != null }, null, length == null)
            }
        }
        NUMBER.find(text)?.let { match ->
            // NUMBER alone has no size; NUMERIC, DECIMAL and DEC alone are NUMBER(38,0).
            val size = NUMBER_SIZE.matchAt(text, match.range.last + 1)
            val precision = size?.groups?.get(1)?.value
                ?: return if (match.groupValues[1].equals("NUMBER", true)) TypeSize(text, null, null, null, true) else TypeSize(text, 38, "DIGITS", 0, true)
            val scale = size.groups[2]?.value?.toInt() ?: 0
            val digits = (if (precision == "*") 38 else precision.toInt()) - scale
            return TypeSize(text, digits.coerceAtLeast(0), "DIGITS", scale, true)
        }
        if (INTEGER.containsMatchIn(text)) return TypeSize(text, 38, "DIGITS", 0, true)
        return TypeSize(text, null, null, null, true)
    }

    /**
     * The most [kept] leaves of the value against [size] (`S`, in [columnUnit]): its length, or `S` less what a start
     * past the first takes off, in the column's unit (none for a column that counts digits, or for bytes taken off a
     * column that counts characters), the smaller when both are known. Null when nothing is kept.
     */
    fun limit(kept: Kept?, size: Int?, columnUnit: String?): Limit? {
        if (kept == null) return null
        val length = if (kept.length != null && kept.unit != null) Limit(kept.length, kept.unit) else null
        val skips = columnUnit == "BYTE" || (columnUnit == "CHAR" && kept.skippedUnit == "CHAR")
        val rest = if (size != null && kept.skipped != null && columnUnit != null && skips) Limit((size - kept.skipped).coerceAtLeast(0), columnUnit) else null
        // As many bytes as characters are fewer: bytes win a tie.
        return listOfNotNull(length, rest).minWithOrNull(compareBy<Limit> { it.length }.thenBy { if (it.unit == "BYTE") 0 else 1 })
    }

    /**
     * The verdict of a place of [placeClass] against [against]: [ownLength] and [ownUnit] the target's own size for
     * `FIXED_SIZE`, [other] the size of the column an `ANCHORED_ELSEWHERE` target is anchored to (null when not known).
     * The room is told for a [concatenated] place. Through `SUBSTR` ([Against.limit], [PlaceVerdicts.limit]), the target
     * is compared with `S` and with the limit, each the same way: it fits when either fits, is smaller when both are,
     * else not known; with the limit alone for a column that counts digits (`SUBSTR` gives text) or when there is no `S`.
     */
    fun decide(placeClass: String, ownLength: Int?, ownUnit: String?, other: TypeSize?, against: Against, concatenated: Boolean): Decided =
        when (placeClass) {
            "CALLER_DECIDES" -> Decided(Verdict.CALLER_DECIDES)
            "ANCHORED_TO_COLUMN" -> Decided(Verdict.FOLLOWS)
            "NO_SIZE" -> Decided(Verdict.FITS)
            "FIXED_SIZE" -> if (ownLength == null || ownUnit == null) Decided(Verdict.UNKNOWN, VerdictReason.NO_LENGTH)
                else within(ownLength, ownUnit, against, concatenated)
            "ANCHORED_ELSEWHERE" -> when {
                other == null -> Decided(Verdict.UNKNOWN, VerdictReason.OTHER_COLUMN_UNKNOWN)
                other.length == null || other.unit == null -> Decided(Verdict.FITS)
                else -> within(other.length, other.unit, against, concatenated)
            }
            else -> Decided(Verdict.UNKNOWN)
        }

    /** [length] in [unit] against `S` and against the limit of [against], as [decide] says. */
    private fun within(length: Int, unit: String, against: Against, concatenated: Boolean): Decided {
        val (size, columnUnit, limit) = against
        if (limit == null) return compare(length, unit, size, columnUnit, concatenated)
        val byLimit = compare(length, unit, limit.length, limit.unit, concatenated)
        if (size == null || columnUnit == "DIGITS") return byLimit
        val both = listOf(byLimit, compare(length, unit, size, columnUnit, concatenated))
        return when {
            both.any { it.verdict == Verdict.FITS } -> Decided(Verdict.FITS, room = both.mapNotNull { it.room }.maxOrNull())
            both.all { it.verdict == Verdict.SMALLER } -> Decided(Verdict.SMALLER)
            else -> Decided(Verdict.UNKNOWN, both.firstNotNullOfOrNull { it.reason } ?: VerdictReason.UNIT_MISMATCH)
        }
    }

    /**
     * [length] in [unit] against [size] in [columnUnit] (the target's unit when the column's is not known): a target counting
     * characters against a column counting bytes compares its length with the column's; one counting bytes against one
     * counting characters is smaller below `S`, fits from `4·S`, and is not known between.
     */
    private fun compare(length: Int, unit: String, size: Int?, columnUnit: String?, concatenated: Boolean): Decided {
        if (size == null) return Decided(Verdict.OWN_SIZE)
        val against = columnUnit ?: unit
        if ((unit == "DIGITS") != (against == "DIGITS")) return Decided(Verdict.UNKNOWN, VerdictReason.OTHER_KIND)
        val needed = if (unit == "BYTE" && against == "CHAR") size * BYTES_PER_CHARACTER else size
        return when {
            length < size -> Decided(Verdict.SMALLER)
            length >= needed -> Decided(Verdict.FITS, room = (length - needed).takeIf { concatenated })
            else -> Decided(Verdict.UNKNOWN, VerdictReason.UNIT_MISMATCH)
        }
    }
}
