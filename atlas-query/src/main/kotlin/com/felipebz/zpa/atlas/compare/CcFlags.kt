//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.compare

import java.util.Locale

/**
 * The `PLSQL_CCFLAGS` syntax and the database version text, normalized so that two settings compare by meaning rather
 * than by spelling. It follows zpa's `ConditionalCompilationSettings.parseFlags` and `parseVersion`, which this module
 * does not depend on.
 */
object CcFlags {
    private val FLAG_NAME = Regex("[A-Za-z][A-Za-z0-9_$#]*")
    private val INTEGER = Regex("-?\\d+")
    private val VERSION = Regex("(\\d+)(?:\\.(\\d+))?")

    /**
     * Parses `name1:value1, name2:value2` into upper-cased names and values `TRUE`, `FALSE`, `NULL` or a `PLS_INTEGER`
     * in decimal; a later duplicate overrides an earlier one. Null or blank text gives no flags; null is returned for
     * text that does not parse.
     */
    fun parse(text: String?): Map<String, String>? {
        val result = HashMap<String, String>()
        if (text.isNullOrBlank()) return result
        for (entry in text.split(',')) {
            val parts = entry.split(':')
            if (parts.size != 2) return null
            val name = parts[0].trim()
            val value = parts[1].trim()
            if (!FLAG_NAME.matches(name)) return null
            result[name.uppercase(Locale.ROOT)] = when {
                value.equals("TRUE", ignoreCase = true) -> "TRUE"
                value.equals("FALSE", ignoreCase = true) -> "FALSE"
                value.equals("NULL", ignoreCase = true) -> "NULL"
                INTEGER.matches(value) -> value.toIntOrNull()?.toString() ?: return null
                else -> return null
            }
        }
        return result
    }

    /** `19`, `19.0` and ` 19.0 ` as `19.0`; text that is not a version is kept as it is. */
    fun version(text: String): String {
        val match = VERSION.matchEntire(text.trim()) ?: return text
        val version = match.groupValues[1].toIntOrNull()
        val release = match.groupValues[2].ifEmpty { "0" }.toIntOrNull()
        return if (version == null || release == null) text else "$version.$release"
    }
}
