//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.squid.CcValue
import com.felipebz.zpa.squid.ConditionalCompilationSettings
import com.felipebz.zpa.squid.DatabaseVersion

/** Where the target database version of a run comes from, as stored in `run.database_version_origin`. */
enum class DatabaseVersionOrigin {
    /** `--database-version`. */
    OPTION,

    /** `DBMS_DB_VERSION.VERSION` and `RELEASE` of the metadata connection. */
    DATABASE,

    /** The JDBC driver's major and minor database version, when `DBMS_DB_VERSION` could not be read. */
    JDBC
}

/** The textual forms of [ConditionalCompilationSettings] stored in the index and printed by the tools. */
object ConditionalCompilationText {
    /** `11.2`. */
    fun version(version: DatabaseVersion): String = "${version.version}.${version.release}"

    /** The flags as `NAME:VALUE, …` in their order; null without flags. */
    fun flags(flags: Map<String, CcValue>): String? = flags.takeIf { it.isNotEmpty() }?.entries?.joinToString(", ") { (name, value) ->
        name + ":" + when (value) {
            CcValue.True -> "TRUE"
            CcValue.False -> "FALSE"
            CcValue.Null -> "NULL"
            is CcValue.Integer -> value.value.toString()
        }
    }

    /** The startup line, e.g. `Conditional compilation: database version 11.2 (from the database), flags none`. */
    fun describe(settings: ConditionalCompilationSettings, origin: DatabaseVersionOrigin?): String {
        val version = settings.databaseVersion?.let { version(it) + " (" + originText(origin) + ")" } ?: "none"
        return "Conditional compilation: database version $version, flags ${flags(settings.flags) ?: "none"}"
    }

    private fun originText(origin: DatabaseVersionOrigin?) = when (origin) {
        DatabaseVersionOrigin.OPTION -> "from --database-version"
        DatabaseVersionOrigin.DATABASE -> "from the database"
        DatabaseVersionOrigin.JDBC -> "from the JDBC driver"
        null -> "origin unknown"
    }
}
