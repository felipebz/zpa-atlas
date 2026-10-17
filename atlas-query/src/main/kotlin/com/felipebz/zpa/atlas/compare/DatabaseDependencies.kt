//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.compare

import java.io.Reader
import java.io.Writer

/** One `ALL_DEPENDENCIES` row: the seven columns read on every Oracle version (no `DEPENDENCY_TYPE`, absent in 10.2). */
data class DbDependency(
    val owner: String,
    val name: String,
    val type: String,
    val referencedOwner: String?,
    val referencedName: String,
    val referencedType: String,
    val referencedLink: String?
)

/** One `ALL_OBJECTS` row. */
data class DbObject(val owner: String, val name: String, val type: String, val status: String)

/** One `ALL_SOURCE` row. */
data class DbSourceLine(val owner: String, val name: String, val type: String, val line: Int, val text: String)

/**
 * One `ALL_TRIGGER_COLS` row: a column of the trigger's table named in its `UPDATE OF` list ([columnList]) or used
 * through a correlation name, with [usage] as Oracle writes it (`NONE`, `NEW IN`, `NEW OUT`, `NEW IN OUT`, `OLD IN`,
 * and combinations such as `NEW OUT OLD IN`).
 */
data class DbTriggerColumn(
    val triggerOwner: String,
    val triggerName: String,
    val tableOwner: String,
    val tableName: String,
    val column: String,
    val columnList: Boolean,
    val usage: String
)

/** One `ALL_PLSQL_OBJECT_SETTINGS` row of a trigger: [debug] is `PLSQL_DEBUG = 'TRUE'`. */
data class DbTriggerSetting(val owner: String, val name: String, val debug: Boolean)

/** One `ALL_PLSQL_OBJECT_SETTINGS` row: [ccflags] is `PLSQL_CCFLAGS` as stored, null when the unit was compiled without flags. */
data class DbCcFlags(val owner: String, val name: String, val type: String, val ccflags: String?)

/**
 * What the database knows about the code of some owners. [sourceReadable] is false when `ALL_SOURCE` could not be read;
 * the comparison then skips the source classes instead of failing.
 */
data class DatabaseDependencies(
    val dependencies: List<DbDependency>,
    val objects: List<DbObject>,
    val sources: List<DbSourceLine>,
    val sourceReadable: Boolean,
    val triggerColumns: List<DbTriggerColumn> = emptyList(),
    /** Absent from older files and unreadable databases: every trigger then counts as compiled without debug. */
    val triggerSettings: List<DbTriggerSetting> = emptyList(),
    /** False for older files and when `ALL_PLSQL_OBJECT_SETTINGS` could not be read: the flags are then not checked. */
    val ccflagsReadable: Boolean = false,
    val ccflags: List<DbCcFlags> = emptyList(),
    /** `DBMS_DB_VERSION.VERSION` and `RELEASE` as `23.0`; null when not read (older files). */
    val databaseVersion: String? = null,
    /** `DATABASE` (`DBMS_DB_VERSION`) or `JDBC` (the driver's major and minor version), with a [databaseVersion]. */
    val databaseVersionOrigin: String? = null
) {
    /**
     * Writes the dependencies file: CSV (RFC 4180, UTF-8, `\n` line ends) with the header
     * `kind,owner,name,type,c1,c2,c3,c4` and one row per fact, sorted:
     * - `META,source_readable,true|false` and `META,ccflags_readable,true|false` (the other columns empty);
     * - `META,database_version,23.0` and `META,database_version_origin,DATABASE|JDBC` (optional);
     * - `OBJ,owner,name,type,status`;
     * - `DEP,owner,name,type,referenced_owner,referenced_name,referenced_type,referenced_link` (empty = null);
     * - `SRC,owner,name,type,line,text`;
     * - `TCOL,trigger_owner,trigger_name,column_name,table_owner,table_name,column_list,column_usage` (`ALL_TRIGGER_COLS`,
     *   `column_list` `YES` or `NO`);
     * - `TSET,owner,name,TRIGGER,plsql_debug` (`ALL_PLSQL_OBJECT_SETTINGS`, `TRUE` or `FALSE`; optional);
     * - `CCF,owner,name,type,plsql_ccflags` (`ALL_PLSQL_OBJECT_SETTINGS`, empty = null; optional).
     */
    fun write(writer: Writer) {
        writer.write(Csv.line(listOf("kind", "owner", "name", "type", "c1", "c2", "c3", "c4")))
        writer.write(Csv.line(listOf("META", "source_readable", sourceReadable.toString(), "", "", "", "", "")))
        writer.write(Csv.line(listOf("META", "ccflags_readable", ccflagsReadable.toString(), "", "", "", "", "")))
        databaseVersion?.let { writer.write(Csv.line(listOf("META", "database_version", it, "", "", "", "", ""))) }
        databaseVersionOrigin?.let { writer.write(Csv.line(listOf("META", "database_version_origin", it, "", "", "", "", ""))) }
        for (o in objects.sortedWith(compareBy({ it.owner }, { it.name }, { it.type }))) {
            writer.write(Csv.line(listOf("OBJ", o.owner, o.name, o.type, o.status, "", "", "")))
        }
        for (d in dependencies.sortedWith(DEPENDENCY_ORDER)) {
            writer.write(Csv.line(listOf("DEP", d.owner, d.name, d.type, d.referencedOwner ?: "", d.referencedName, d.referencedType, d.referencedLink ?: "")))
        }
        for (s in sources.sortedWith(compareBy({ it.owner }, { it.name }, { it.type }, { it.line }))) {
            writer.write(Csv.line(listOf("SRC", s.owner, s.name, s.type, s.line.toString(), s.text, "", "")))
        }
        for (c in triggerColumns.sortedWith(compareBy({ it.triggerOwner }, { it.triggerName }, { it.column }))) {
            writer.write(Csv.line(listOf("TCOL", c.triggerOwner, c.triggerName, c.column, c.tableOwner, c.tableName, if (c.columnList) "YES" else "NO", c.usage)))
        }
        for (t in triggerSettings.sortedWith(compareBy({ it.owner }, { it.name }))) {
            writer.write(Csv.line(listOf("TSET", t.owner, t.name, "TRIGGER", if (t.debug) "TRUE" else "FALSE", "", "", "")))
        }
        for (c in ccflags.sortedWith(compareBy({ it.owner }, { it.name }, { it.type }))) {
            writer.write(Csv.line(listOf("CCF", c.owner, c.name, c.type, c.ccflags ?: "", "", "", "")))
        }
    }

    companion object {
        val DEPENDENCY_ORDER = compareBy<DbDependency>(
            { it.owner }, { it.name }, { it.type }, { it.referencedOwner ?: "" }, { it.referencedName }, { it.referencedType }, { it.referencedLink ?: "" }
        )

        /** Reads a file written by [write]. */
        fun read(reader: Reader): DatabaseDependencies {
            val rows = Csv.parse(reader.readText())
            require(rows.isNotEmpty() && rows[0].take(4) == listOf("kind", "owner", "name", "type")) { "Not a dependencies file: missing header" }
            val dependencies = ArrayList<DbDependency>()
            val objects = ArrayList<DbObject>()
            val sources = ArrayList<DbSourceLine>()
            val triggerColumns = ArrayList<DbTriggerColumn>()
            val triggerSettings = ArrayList<DbTriggerSetting>()
            val ccflags = ArrayList<DbCcFlags>()
            var sourceReadable = true
            var ccflagsReadable = false
            var databaseVersion: String? = null
            var databaseVersionOrigin: String? = null
            for ((index, row) in rows.withIndex().drop(1)) {
                fun col(i: Int) = row.getOrElse(i) { "" }
                fun nullable(i: Int) = col(i).ifEmpty { null }
                when (col(0)) {
                    "META" -> when (col(1)) {
                        "source_readable" -> sourceReadable = col(2).toBooleanStrict()
                        "ccflags_readable" -> ccflagsReadable = col(2).toBooleanStrict()
                        "database_version" -> databaseVersion = nullable(2)
                        "database_version_origin" -> databaseVersionOrigin = nullable(2)
                    }
                    "OBJ" -> objects += DbObject(col(1), col(2), col(3), col(4))
                    "DEP" -> dependencies += DbDependency(col(1), col(2), col(3), nullable(4), col(5), col(6), nullable(7))
                    "SRC" -> sources += DbSourceLine(col(1), col(2), col(3), col(4).toInt(), col(5))
                    "TCOL" -> triggerColumns += DbTriggerColumn(col(1), col(2), col(4), col(5), col(3), col(6) == "YES", col(7))
                    "TSET" -> triggerSettings += DbTriggerSetting(col(1), col(2), col(4) == "TRUE")
                    "CCF" -> ccflags += DbCcFlags(col(1), col(2), col(3), nullable(4))
                    else -> throw IllegalArgumentException("Line ${index + 1}: unknown kind '${col(0)}'")
                }
            }
            return DatabaseDependencies(dependencies, objects, sources, sourceReadable, triggerColumns, triggerSettings, ccflagsReadable, ccflags,
                databaseVersion, databaseVersionOrigin)
        }
    }
}

/** Minimal RFC 4180 CSV: fields with a comma, quote, CR or LF are quoted; quotes are doubled. */
object Csv {
    fun line(fields: List<String>): String = fields.joinToString(",") { quote(it) } + "\n"

    fun quote(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field

    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var pending = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else {
                    field.append(c)
                }
            } else when (c) {
                '"' -> { quoted = true; pending = true }
                ',' -> { row.add(field.toString()); field.clear(); pending = true }
                '\r' -> Unit
                '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = ArrayList(); pending = false }
                else -> { field.append(c); pending = true }
            }
            i++
        }
        if (pending) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}
