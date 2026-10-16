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
 * What the database knows about the code of some owners. [sourceReadable] is false when `ALL_SOURCE` could not be read;
 * the comparison then skips the source classes instead of failing.
 */
data class DatabaseDependencies(
    val dependencies: List<DbDependency>,
    val objects: List<DbObject>,
    val sources: List<DbSourceLine>,
    val sourceReadable: Boolean
) {
    /**
     * Writes the dependencies file: CSV (RFC 4180, UTF-8, `\n` line ends) with the header
     * `kind,owner,name,type,c1,c2,c3,c4` and one row per fact, sorted:
     * - `META,source_readable,true|false` (the other columns empty);
     * - `OBJ,owner,name,type,status`;
     * - `DEP,owner,name,type,referenced_owner,referenced_name,referenced_type,referenced_link` (empty = null);
     * - `SRC,owner,name,type,line,text`.
     */
    fun write(writer: Writer) {
        writer.write(Csv.line(listOf("kind", "owner", "name", "type", "c1", "c2", "c3", "c4")))
        writer.write(Csv.line(listOf("META", "source_readable", sourceReadable.toString(), "", "", "", "", "")))
        for (o in objects.sortedWith(compareBy({ it.owner }, { it.name }, { it.type }))) {
            writer.write(Csv.line(listOf("OBJ", o.owner, o.name, o.type, o.status, "", "", "")))
        }
        for (d in dependencies.sortedWith(DEPENDENCY_ORDER)) {
            writer.write(Csv.line(listOf("DEP", d.owner, d.name, d.type, d.referencedOwner ?: "", d.referencedName, d.referencedType, d.referencedLink ?: "")))
        }
        for (s in sources.sortedWith(compareBy({ it.owner }, { it.name }, { it.type }, { it.line }))) {
            writer.write(Csv.line(listOf("SRC", s.owner, s.name, s.type, s.line.toString(), s.text, "", "")))
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
            var sourceReadable = true
            for ((index, row) in rows.withIndex().drop(1)) {
                fun col(i: Int) = row.getOrElse(i) { "" }
                fun nullable(i: Int) = col(i).ifEmpty { null }
                when (col(0)) {
                    "META" -> if (col(1) == "source_readable") sourceReadable = col(2).toBooleanStrict()
                    "OBJ" -> objects += DbObject(col(1), col(2), col(3), col(4))
                    "DEP" -> dependencies += DbDependency(col(1), col(2), col(3), nullable(4), col(5), col(6), nullable(7))
                    "SRC" -> sources += DbSourceLine(col(1), col(2), col(3), col(4).toInt(), col(5))
                    else -> throw IllegalArgumentException("Line ${index + 1}: unknown kind '${col(0)}'")
                }
            }
            return DatabaseDependencies(dependencies, objects, sources, sourceReadable)
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
