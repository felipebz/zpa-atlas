//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

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
        conditionalCompilation(r.conditionalCompilation)
        line("Source")
        if (!r.sourceReadable) line("  ALL_SOURCE not readable: SOURCE_DIFFERS skipped")
        if (!r.textAvailable) line("  source files not available: NAME_NOT_IN_TEXT and SOURCE_DIFFERS skipped")
        for (status in SourceStatus.entries) line("  $status: ${r.units.count { it.sourceStatus == status }}")
        val differing = r.units.filter { it.sourceStatus == SourceStatus.DIFFERS }
        for (u in differing.take(20)) line("    ${u.unit}: first difference at stored line ${u.firstDifferentLine}")
        line()
        val db = r.matched.size + r.databaseOnly.size
        val expected = r.databaseOnly.count { it.dbClass in EXPECTED_CLASSES }
        line("Edges")
        line("  database: $db")
        line("  index: ${r.units.filter { it.databaseStatus != null }.sumOf { it.indexEdges }}")
        line("  matched: ${r.matched.size} (exact ${r.matched.count { it.matchClass == "EXACT" }}, by name ${r.matched.count { it.matchClass == "BY_NAME" }}, " +
            "synonym ${r.matched.count { it.matchClass == "SYNONYM" }})")
        line("  matched only through collapsed AMBIGUOUS calls: ${r.matched.count { it.collapsedOnly }} (collapsed occurrences: ${r.collapsedOccurrences})")
        line()
        line("Match rate (matched / (database - BUILTIN - NON_EXISTENT - MVIEW_CONTAINER))")
        line("  overall: ${rate(r.matched.size, db - expected)}")
        // The same ratio without the rows whose name the unit's text does not contain (TRANSITIVE, NAME_NOT_IN_TEXT).
        val unnamed = r.databaseOnly.count { it.dbClass in NOT_IN_TEXT_CLASSES }
        line("  overall, names in the text: ${rate(r.matched.size, db - expected - unnamed)}")
        // Types whose rows are all expected (BUILTIN, NON_EXISTENT) have no rate.
        val types = (r.matched.map { it.reference.referencedType } +
            r.databaseOnly.filter { it.dbClass !in EXPECTED_CLASSES }.map { it.reference.referencedType }).toSortedSet()
        for (type in types) {
            val m = r.matched.count { it.reference.referencedType == type }
            val d = m + r.databaseOnly.count { it.reference.referencedType == type }
            val b = r.databaseOnly.count { it.reference.referencedType == type && it.dbClass in EXPECTED_CLASSES }
            val n = r.databaseOnly.count { it.reference.referencedType == type && it.dbClass in NOT_IN_TEXT_CLASSES }
            line("  $type: ${rate(m, d - b)}; names in the text ${rate(m, d - b - n)}")
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
        line("Trigger columns (ALL_TRIGGER_COLS, per trigger, column and facet)")
        val compared = r.triggerColumns.filter { it.group == "COMPARED" }
        fun triggers(rows: List<TriggerColumnFact>) = rows.map { it.owner to it.trigger }.toSet()
        line("  compared triggers: ${triggers(compared).size}, compiled with debug: ${triggers(compared.filter { it.debug }).size}")
        fun counts(rows: List<TriggerColumnFact>) =
            "matched ${rows.count { it.side == "MATCHED" }}, database only ${rows.count { it.side == "DB_ONLY" }}, index only ${rows.count { it.side == "INDEX_ONLY" }}"
        for (facet in listOf("LIST", "IN", "OUT", "USED")) {
            val rows = compared.filter { it.facet == facet }
            line("  $facet: ${counts(rows)}; without debug: ${counts(rows.filter { !it.debug })}")
        }
        val triggersDiffering = r.triggerColumns.filter { it.group == "SOURCE_DIFFERS" }
        line("  SOURCE_DIFFERS (not compared): ${triggers(triggersDiffering).size} trigger(s), ${counts(triggersDiffering)}")
        val triggersInvalid = r.triggerColumns.filter { it.group == "INVALID" }
        line("  INVALID (not compared): ${triggers(triggersInvalid).size} trigger(s), ${counts(triggersInvalid)}")
        line("  database triggers without unit (not compared): ${triggers(r.triggerColumns.filter { it.group == "NO_UNIT" }).size}")
        line()
        top("Top NOT_EXTRACTED names", r.databaseOnly.filter { it.dbClass == "NOT_EXTRACTED" }.map { it.reference.referencedName })
        top("Top UNRESOLVED_IN_INDEX names", r.databaseOnly.filter { it.dbClass == "UNRESOLVED_IN_INDEX" }.map { it.reference.referencedName })
        top("Top INDEX_ONLY names", r.indexOnly.filter { it.indexClass == "INDEX_ONLY" }.map { it.target.toString() })
        top("Top TRANSITIVE paths", r.databaseOnly.filter { it.dbClass == "TRANSITIVE" && it.via != null }.map {
            "${it.via} -> ${it.reference.referencedOwner}.${it.reference.referencedName} ${it.reference.referencedType}"
        })
    }

    /**
     * The index's conditional compilation settings next to the database's: a warning when the index was built for
     * another database version, or for none, and the compared units whose `PLSQL_CCFLAGS` differ from the index's.
     */
    private fun StringBuilder.conditionalCompilation(cc: ConditionalCompilationCheck) {
        fun line(text: String = "") = append(text).append('\n')
        fun version(version: String?, origin: String?, absent: String) = version?.let { v -> origin?.let { "$v ($it)" } ?: v } ?: absent
        line("Conditional compilation")
        line("  database version: index ${version(cc.indexVersion, cc.indexVersionOrigin, "(none)")}, " +
            "database ${version(cc.databaseVersion, cc.databaseVersionOrigin, "(not read)")}")
        if (cc.versionDiffers) {
            if (cc.indexVersion == null) {
                line("  WARNING: the index was built without a database version setting; the database is ${cc.databaseVersion}")
            } else {
                line("  WARNING: the index was built for database version ${cc.indexVersion}; the database is ${cc.databaseVersion}")
            }
        }
        line("  flags: index ${cc.indexFlags ?: "(none)"}")
        if (!cc.flagsChecked) {
            line("  flags not checked: ALL_PLSQL_OBJECT_SETTINGS not read")
        } else {
            line("  compared PL/SQL units: same flags ${cc.sameFlags}, different flags ${cc.differentFlags.size}, without settings row ${cc.withoutSettings}")
            for ((unit, flags) in cc.differentFlags.take(20)) {
                val text = when {
                    flags.isNullOrBlank() -> "(none)"
                    CcFlags.parse(flags) == null -> "$flags (not valid PLSQL_CCFLAGS)"
                    else -> flags
                }
                line("    $unit: $text")
            }
        }
        line()
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
        "BUILTIN", "REMOTE", "NON_EXISTENT", "MVIEW_CONTAINER", "SOURCE_DIFFERS", "TRANSITIVE", "NAME_NOT_IN_TEXT", "UNRESOLVED_IN_INDEX",
        "RESOLVED_ELSEWHERE", "GUESSED_ELSEWHERE", "NOT_EXTRACTED"
    )
    /** Database-only classes left out of the match-rate denominator. */
    val EXPECTED_CLASSES = setOf("BUILTIN", "NON_EXISTENT", "MVIEW_CONTAINER")

    /** Database-only classes whose name the unit's text does not contain: also left out of the "names in the text" rates. */
    val NOT_IN_TEXT_CLASSES = setOf("TRANSITIVE", "NAME_NOT_IN_TEXT")

    val INDEX_CLASSES = listOf("SOURCE_DIFFERS", "UNIT_INVALID", "BY_NAME_ONLY", "INDEX_ONLY")

    private fun unit(u: DbObjectId) = listOf(u.owner, u.name, u.type)
    private fun ref(d: DbDependency) = listOf(d.referencedOwner ?: "", d.referencedName, d.referencedType, d.referencedLink ?: "")

    /** Writes `units.csv`, `matched.csv`, `db_only.csv`, `index_only.csv` and `trigger_columns.csv` (the differences) into [directory]. */
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
            result.matched.map { m -> listOf(m.matchClass) + unit(m.unit) + ref(m.reference) + listOf(m.collapsedOnly.toString(), m.locations.joinToString(" ")) })
        write("db_only.csv", listOf("class") + unitHeader + refHeader + listOf("detail"),
            result.databaseOnly.map { d -> listOf(d.dbClass) + unit(d.unit) + ref(d.reference) + listOf(d.detail) })
        write("index_only.csv", listOf("class") + unitHeader + listOf("target_owner", "target_name", "target_type", "target_link", "locations"),
            result.indexOnly.map { e ->
                listOf(e.indexClass) + unit(e.unit) + listOf(e.target.owner ?: "~", e.target.name, e.target.type ?: "", e.target.link ?: "", e.locations.joinToString(" "))
            })
        // Compared triggers: the differences; the triggers left out of the comparison: every row, under their group.
        write("trigger_columns.csv", listOf("group", "side", "owner", "trigger", "column", "facet", "debug", "locations"),
            result.triggerColumns.filter { it.group != "COMPARED" || it.side != "MATCHED" }.map {
                listOf(it.group, it.side, it.owner, it.trigger, it.column, it.facet, it.debug.toString(), it.locations.joinToString(" "))
            })
    }
}
