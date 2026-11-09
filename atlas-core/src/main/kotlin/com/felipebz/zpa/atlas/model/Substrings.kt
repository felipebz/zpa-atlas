//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.model

/**
 * The most of a column's value `SUBSTR` and `SUBSTRB` keep ([Substrings.kept]): at most [length] in [unit] (a literal
 * length, or a start counted from the end), and [skipped] in [skippedUnit] taken off the front by a start past the
 * first, which leaves at most the size compared less [skipped]. `CHAR` for `SUBSTR`, `BYTE` for `SUBSTRB`.
 */
data class KeptLimit(val length: Int?, val unit: String?, val skipped: Int?, val skippedUnit: String?)

/**
 * `SUBSTR` and `SUBSTRB` in a list of value functions: the function's name, then its start and length in parentheses,
 * each a whole-number literal or `?` (`SUBSTR(1,5)`, `SUBSTRB(16)`, `SUBSTR(1,?)`). The name alone keeps no limit.
 */
object Substrings {
    /** The functions that give back a part of their first argument. */
    val NAMES = setOf("SUBSTR", "SUBSTRB")

    /** The functions that may make the value longer or put other text before it, beside a concatenation. */
    private val LENGTHENING = setOf("REPLACE", "REGEXP_REPLACE")

    /** The entry of [function] (`SUBSTR`, `SUBSTRB`) with its start and length ([arguments]; null for one not a whole-number literal). */
    fun entry(function: String, arguments: List<Int?>): String = arguments.joinToString(",", "$function(", ")") { it?.toString() ?: "?" }

    /** The name of the function of an entry of a list of value functions, without the arguments of a `SUBSTR` entry. */
    fun name(entry: String): String = entry.substringBefore('(')

    /**
     * The most of the value [functions] (outer first, [CONCATENATED] where a concatenation is) keep: the smallest literal
     * length or start counted from the end, and the largest start past the first. A `REPLACE` or `REGEXP_REPLACE` outside
     * a `SUBSTR` drops what it keeps; one inside it, or a concatenation, drops what its start takes off, since the value
     * may then be longer than the column's or have other text before it. Null when nothing is kept.
     */
    fun kept(functions: List<String>): KeptLimit? {
        var length: Pair<Int, String>? = null
        var skipped: Pair<Int, String>? = null
        for ((index, entry) in functions.withIndex()) {
            val name = name(entry)
            if (name !in NAMES || name == entry || functions.subList(0, index).any { name(it) in LENGTHENING }) continue
            val arguments = entry.substring(name.length + 1, entry.length - 1).split(',').map { it.toIntOrNull() }
            val start = arguments.getOrNull(0)
            val unit = if (name == "SUBSTRB") "BYTE" else "CHAR"
            val most = listOfNotNull(arguments.getOrNull(1)?.coerceAtLeast(0), start?.takeIf { it < 0 }?.let { -it }).minOrNull()
            if (most != null) length = smaller(length, most to unit)
            val inner = functions.subList(index + 1, functions.size)
            if (start != null && start > 1 && inner.none { it == CONCATENATED || name(it) in LENGTHENING }) skipped = larger(skipped, start - 1 to unit)
        }
        if (length == null && skipped == null) return null
        return KeptLimit(length?.first, length?.second, skipped?.first, skipped?.second)
    }

    /** The smaller of two lengths: as many bytes as characters are fewer, so bytes win a tie. */
    private fun smaller(known: Pair<Int, String>?, next: Pair<Int, String>): Pair<Int, String> = when {
        known == null || next.first < known.first -> next
        next.first == known.first && next.second == "BYTE" -> next
        else -> known
    }

    /** The larger of two starts taken off: characters win a tie, since they take off at least as many bytes. */
    private fun larger(known: Pair<Int, String>?, next: Pair<Int, String>): Pair<Int, String> = when {
        known == null || next.first > known.first -> next
        next.first == known.first && next.second == "CHAR" -> next
        else -> known
    }
}
