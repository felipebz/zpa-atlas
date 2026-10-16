package com.felipebz.zpa.atlas.compare

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * The outputs of a comparison: a summary safe to paste in a chat (counts and top-20 name lists only, never source
 * text) and four CSV files with the detail. Both are sorted, so the same input gives identical files.
 */
object ComparisonReport {
    fun summary(result: ComparisonResult): String = buildString {
        fun line(text: String = "") = append(text).append('\n')
        val r = result
        line("compare-deps summary")
        line()
        line("Units")
        line("  mapped to database objects: ${r.units.size}")
        line("  scripts and anonymous blocks (not compared): ${r.scriptUnits}, with ${r.scriptEdges} edge occurrence(s)")
        line("  units without ALL_OBJECTS row (not compared): ${r.unitsWithoutObject.size}, with ${r.uncomparedEdges} index edge(s)")
        line("  database objects without unit in the index: ${r.objectsWithoutUnit.size}")
        line()
        line("Source")
        if (!r.sourceReadable) line("  ALL_SOURCE not readable: SOURCE_DIFFERS skipped")
        if (!r.textAvailable) line("  source files not available: NAME_NOT_IN_TEXT and SOURCE_DIFFERS skipped")
        for (status in SourceStatus.entries) line("  $status: ${r.units.count { it.sourceStatus == status }}")
        val differing = r.units.filter { it.sourceStatus == SourceStatus.DIFFERS }
        for (u in differing.take(20)) line("    ${u.unit}: first difference at stored line ${u.firstDifferentLine}")
        line()
        val db = r.matched.size + r.databaseOnly.size
        val builtin = r.databaseOnly.count { it.dbClass == "BUILTIN" }
        line("Edges")
        line("  database: $db")
        line("  index: ${r.units.filter { it.databaseStatus != null }.sumOf { it.indexEdges }}")
        line("  matched: ${r.matched.size} (exact ${r.matched.count { !it.byName }}, by name ${r.matched.count { it.byName }})")
        line("  matched only through collapsed AMBIGUOUS calls: ${r.matched.count { it.collapsedOnly }} (collapsed occurrences: ${r.collapsedOccurrences})")
        line()
        line("Match rate (matched / (database - BUILTIN))")
        line("  overall: ${rate(r.matched.size, db - builtin)}")
        val types = (r.matched.map { it.reference.referencedType } + r.databaseOnly.map { it.reference.referencedType }).toSortedSet()
        for (type in types) {
            val m = r.matched.count { it.reference.referencedType == type }
            val d = m + r.databaseOnly.count { it.reference.referencedType == type }
            val b = r.databaseOnly.count { it.reference.referencedType == type && it.dbClass == "BUILTIN" }
            line("  $type: ${rate(m, d - b)}")
        }
        line()
        line("Database only: ${r.databaseOnly.size}")
        for (c in DB_CLASSES) line("  $c: ${r.databaseOnly.count { it.dbClass == c }}")
        for ((type, count) in r.databaseOnly.filter { it.dbClass == "NOT_EXTRACTED" }.groupingBy { it.reference.referencedType }.eachCount().toSortedMap()) {
            line("    NOT_EXTRACTED $type: $count")
        }
        line()
        line("Index only: ${r.indexOnly.size}")
        for (c in INDEX_CLASSES) line("  $c: ${r.indexOnly.count { it.indexClass == c }}")
        line()
        top("Top NOT_EXTRACTED names", r.databaseOnly.filter { it.dbClass == "NOT_EXTRACTED" }.map { it.reference.referencedName })
        top("Top UNRESOLVED_IN_INDEX names", r.databaseOnly.filter { it.dbClass == "UNRESOLVED_IN_INDEX" }.map { it.reference.referencedName })
        top("Top INDEX_ONLY names", r.indexOnly.filter { it.indexClass == "INDEX_ONLY" }.map { it.target.toString() })
    }

    private fun StringBuilder.top(title: String, names: List<String>) {
        append(title).append('\n')
        val counts = names.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).take(20)
        if (counts.isEmpty()) append("  (none)\n")
        for ((name, count) in counts) append("  ").append(name).append(": ").append(count).append('\n')
        append('\n')
    }

    private fun rate(numerator: Int, denominator: Int): String =
        if (denominator <= 0) "n/a ($numerator/$denominator)" else String.format(Locale.ROOT, "%.1f%% (%d/%d)", 100.0 * numerator / denominator, numerator, denominator)

    val DB_CLASSES = listOf(
        "BUILTIN", "REMOTE", "NON_EXISTENT", "SOURCE_DIFFERS", "NAME_NOT_IN_TEXT", "UNRESOLVED_IN_INDEX", "RESOLVED_ELSEWHERE",
        "GUESSED_ELSEWHERE", "NOT_EXTRACTED"
    )
    val INDEX_CLASSES = listOf("SOURCE_DIFFERS", "UNIT_INVALID", "BY_NAME_ONLY", "INDEX_ONLY")

    private fun unit(u: DbObjectId) = listOf(u.owner, u.name, u.type)
    private fun ref(d: DbDependency) = listOf(d.referencedOwner ?: "", d.referencedName, d.referencedType, d.referencedLink ?: "")

    /** Writes `units.csv`, `matched.csv`, `db_only.csv` and `index_only.csv` into [directory]. */
    fun writeCsv(result: ComparisonResult, directory: Path) {
        Files.createDirectories(directory)
        fun write(name: String, header: List<String>, rows: List<List<String>>) {
            Files.writeString(directory.resolve(name), buildString {
                append(Csv.line(header))
                rows.forEach { append(Csv.line(it)) }
            })
        }
        val unitHeader = listOf("owner", "name", "type")
        write("units.csv", unitHeader + listOf("files", "db_status", "source_status", "first_different_line", "db_edges", "index_edges", "matched", "dynamic", "partial_source"),
            result.units.map { u ->
                unit(u.unit) + listOf(u.files.joinToString(" "), u.databaseStatus ?: "MISSING", u.sourceStatus.name, u.firstDifferentLine?.toString() ?: "",
                    u.databaseEdges.toString(), u.indexEdges.toString(), u.matched.toString(), u.dynamic.toString(), u.partialSource.toString())
            } + result.objectsWithoutUnit.map { unit(it) + listOf("", "NO_UNIT", "", "", "", "", "", "", "") })
        val refHeader = listOf("ref_owner", "ref_name", "ref_type", "ref_link")
        write("matched.csv", listOf("match") + unitHeader + refHeader + listOf("collapsed_only", "locations"),
            result.matched.map { m -> listOf(if (m.byName) "BY_NAME" else "EXACT") + unit(m.unit) + ref(m.reference) + listOf(m.collapsedOnly.toString(), m.locations.joinToString(" ")) })
        write("db_only.csv", listOf("class") + unitHeader + refHeader + listOf("detail"),
            result.databaseOnly.map { d -> listOf(d.dbClass) + unit(d.unit) + ref(d.reference) + listOf(d.detail) })
        write("index_only.csv", listOf("class") + unitHeader + listOf("target_owner", "target_name", "target_type", "target_link", "locations"),
            result.indexOnly.map { e ->
                listOf(e.indexClass) + unit(e.unit) + listOf(e.target.owner ?: "~", e.target.name, e.target.type ?: "", e.target.link ?: "", e.locations.joinToString(" "))
            })
    }
}
