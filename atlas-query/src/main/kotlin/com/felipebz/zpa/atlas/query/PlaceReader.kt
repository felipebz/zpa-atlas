//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import java.sql.Connection
import java.util.Locale

/**
 * The places a column's value is read into in PL/SQL (`column_place`): those of the column itself and, marked, those of
 * the view columns computed from it, through every level of views, the way "who uses" follows views. Each place's
 * [Verdict] against the size asked for is decided here, as it is read ([PlaceVerdicts]).
 */
internal class PlaceReader(private val connection: Connection) {
    /**
     * The class of each place, in the order of [CLASS_ORDER]: with [concatenated], only the places where the value lands
     * alone (false) or as a part of a concatenation (true).
     */
    fun counts(columnId: Long, concatenated: Boolean? = null): List<Count> {
        val counts = connection.query(
            "$SCOPE SELECT p.class, count(*) FROM column_place p WHERE p.column_id IN (SELECT id FROM viewed)${which(concatenated)} GROUP BY p.class",
            listOf(columnId)) { it.getString(1) to it.getLong(2) }.toMap()
        return CLASS_ORDER.mapNotNull { name -> counts[name]?.let { Count(name, it) } }
    }

    /** How many places of the column [columnId] the value lands in alone (false) or as a part of a concatenation (true). */
    fun total(columnId: Long, concatenated: Boolean): Int = connection.query(
        "$SCOPE SELECT count(*) FROM column_place p WHERE p.column_id IN (SELECT id FROM viewed)${which(concatenated)}", listOf(columnId)
    ) { it.getInt(1) }.single()

    /** The places of the column [key] under [filter], by routine: [limit] groups from [offset]; null when there is no such entity. */
    fun groups(key: String, filter: PlaceFilter, offset: Int, limit: Int): PlaceGroupsPage? {
        val read = read(key, filter) ?: return null
        val groups = read.listed.groupBy { it.groupKey }.values.map { rows ->
            val first = rows.first()
            val sorted = rows.sortedWith(compareBy({ it.place.line }, { it.place.column }, { it.id }))
            PlaceGroup(first.place.container, first.routine, first.packageName, null, first.place.path,
                rows.count { it.place.verdict == Verdict.SMALLER.name }, rows.size, sorted.map { it.place })
        }
        // Overloads of one package with the same name: their parameters tell them apart.
        val overloaded = groups.filter { it.container != null }.groupBy { it.packageName to it.routine }
            .filterValues { same -> same.mapTo(HashSet()) { it.container!!.key.removeSuffix(BODY) }.size > 1 }.values.flatten().toSet()
        val ordered = groups.map { if (it in overloaded) it.copy(parameters = parameters(it.container!!.display, it.routine)) else it }
            .sortedWith(compareByDescending<PlaceGroup> { it.smaller }.thenBy { it.routine }.thenBy { it.packageName.orEmpty() }
                .thenBy { it.path }.thenBy { it.container?.key.orEmpty() })
        val page = ordered.drop(offset).take(limit)
        val next = (offset + page.size).takeIf { it < ordered.size }
        return PlaceGroupsPage(
            read.column, read.size, filter.size != null, read.counts, read.verdicts, read.listed.size, groups.size, read.filtered.size,
            read.filtered.mapTo(HashSet()) { it.groupKey }.size, read.concatenatedCount, read.optionUnit, read.optionSized, offset, page, next,
            (ordered.size - offset - page.size).coerceAtLeast(0)
        )
    }

    /** The places of the column [key] under [filter] as rows, by verdict, file and line: [limit] from [offset]; null when there is no such entity. */
    fun rows(key: String, filter: PlaceFilter, offset: Int, limit: Int): ColumnPlacePage? {
        val read = read(key, filter) ?: return null
        val sorted = read.listed.sortedWith(compareBy({ Verdict.valueOf(it.place.verdict).ordinal }, { it.place.path }, { it.place.line }, { it.place.column }, { it.id }))
        val page = sorted.drop(offset).take(limit).map { it.place }
        return ColumnPlacePage(read.column, read.size, filter.size != null, read.counts, read.verdicts, sorted.size, read.concatenatedCount,
            read.optionUnit, read.optionSized, offset, page, (offset + page.size).takeIf { it < sorted.size })
    }

    /** A place as read, with what its group and the text filter need. */
    private class Row(val id: Long, val place: ColumnPlace, val packageName: String?) {
        val groupKey: String get() = place.container?.key ?: "file:${place.path}"
        val routine: String get() = place.container?.name ?: place.path.substringAfterLast('/')
    }

    /**
     * The places of [key] under [filter]: [filtered] under every filter but the verdict one, [listed] under every filter,
     * [counts] of [filtered] by verdict.
     */
    private class Read(
        val column: ColumnSize, val size: Int?, val counts: List<VerdictCount>, val verdicts: List<String>, val filtered: List<Row>,
        val listed: List<Row>, val concatenatedCount: Int, val optionUnit: String, val optionSized: Int
    )

    private fun read(key: String, filter: PlaceFilter): Read? {
        val (id, datatype) = connection.query(
            "SELECT e.id, d.datatype FROM entity e LEFT JOIN declared_text d ON d.entity_id = e.id WHERE e.key = ?", listOf(key)
        ) { it.getLong(1) to it.getString(2) }.singleOrNull() ?: return null
        val optionUnit = connection.query("SELECT nls_length_semantics FROM run") { it.getString(1) }.single()
        val own = PlaceVerdicts.size(datatype, optionUnit)
        val column = ColumnSize(own?.datatype, own?.length, own?.unit, own?.scale)
        val size = filter.size ?: own?.length
        val rows = connection.query(
            """$SCOPE SELECT r.key, r.kind, r.display, r.external, r.name, p.column_id <> ?, p.source, p.bulk, f.path, p.start_line, p.start_col,
                      c.key, c.kind, c.display, c.external, c.name, p.target_written,
                      t.key, t.kind, t.display, t.external, t.name, p.field, p.class, p.declared_type, p.length, p.length_unit,
                      s.key, s.kind, s.display, s.external, s.name, p.reason, p.scale, p.parameter_mode, p.functions,
                      a.key, a.kind, a.display, a.external, a.name, g.key, g.kind, g.display, g.external, g.name,
                      p.call_count, p.call_path, p.call_line, p.concatenated,
                      p.unit_source, p.unit_compiled_owner, p.unit_compiled_name, p.unit_compiled_type, sd.datatype, cp.name, p.id
               FROM column_place p JOIN entity r ON r.id = p.column_id JOIN source_file f ON f.id = p.file_id
               LEFT JOIN entity c ON c.id = p.container_entity_id LEFT JOIN entity cp ON cp.id = c.parent_id
               LEFT JOIN entity t ON t.id = p.target_entity_id
               LEFT JOIN entity s ON s.id = p.type_source_id LEFT JOIN declared_text sd ON sd.entity_id = p.type_source_id
               LEFT JOIN entity a ON a.id = p.argument_parameter_id LEFT JOIN entity g ON g.id = p.argument_routine_id
               WHERE p.column_id IN (SELECT id FROM viewed)""",
            listOf(id, id)
        ) { rs ->
            fun summary(first: Int) = rs.getString(first)?.let { EntitySummary(it, rs.getString(first + 1), rs.getString(first + 2), rs.getInt(first + 3) == 1, rs.getString(first + 4)) }
            fun int(index: Int) = (rs.getObject(index) as Number?)?.toInt()
            val placeClass = rs.getString(24)
            val length = int(26)
            val unit = rs.getString(27)
            val concatenated = rs.getInt(50) == 1
            val other = if (placeClass == "ANCHORED_ELSEWHERE") PlaceVerdicts.size(rs.getString(55), optionUnit) else null
            val decided = PlaceVerdicts.decide(placeClass, length, unit, other, size, column.unit, concatenated)
            val (shown, shownUnit, unitSource) = when {
                placeClass == "FIXED_SIZE" -> Triple(length, unit, rs.getString(51))
                other?.length != null -> Triple(other.length, other.unit,
                    if (other.unit == "BYTE" || other.unit == "CHAR") if (other.unitWritten) "WRITTEN" else "OPTION" else null)
                else -> Triple(null, null, null)
            }
            val compiledIn = rs.getString(52)?.let { CompiledIn(it, rs.getString(53), rs.getString(54)) }
            val place = ColumnPlace(
                summary(1)!!, rs.getInt(6) == 1, rs.getString(7), rs.getInt(8) == 1, rs.getString(9), rs.getInt(10), rs.getInt(11),
                summary(12), rs.getString(17), summary(18), rs.getString(23), placeClass, rs.getString(25), length, unit, summary(28),
                rs.getString(33), int(34), rs.getString(35), rs.getString(36)?.split(',').orEmpty(), summary(37), summary(42),
                int(47), rs.getString(48), int(49), concatenated,
                decided.verdict.name, decided.reason?.name, shown, shownUnit, unitSource, compiledIn, decided.room
            )
            Row(rs.getLong(57), place, rs.getString(56))
        }
        val words = filter.text?.lowercase(Locale.ROOT)?.split(WHITESPACE)?.filter { it.isNotEmpty() }.orEmpty()
        fun matches(row: Row): Boolean {
            if (filter.source != null && row.place.source != filter.source) return false
            if (words.isEmpty()) return true
            val place = row.place
            val text = listOfNotNull(place.targetWritten, place.field, place.container?.display, row.packageName, place.path)
                .joinToString("\u0000").lowercase(Locale.ROOT)
            return words.all { it in text }
        }
        val matching = rows.filter(::matches)
        val filtered = if (filter.concatenated) matching else matching.filter { !it.place.concatenated }
        val byVerdict = filtered.groupingBy { it.place.verdict }.eachCount()
        val named = Verdict.entries.filter { it != Verdict.OWN_SIZE || size == null }
        val listed = filter.listed
        return Read(
            column, size, named.map { VerdictCount(it.name, byVerdict[it.name] ?: 0) }, Verdict.entries.filter { it in listed }.map { it.name },
            filtered, filtered.filter { Verdict.valueOf(it.place.verdict) in listed }, matching.count { it.place.concatenated }, optionUnit,
            filtered.count { it.place.unitSource == "OPTION" }
        )
    }

    /** The parameters of the routine [name] in [display] (`PK_LV.SHOW_LV(P_ID IN NUMBER,P_OUT OUT VARCHAR2)`): `(P_ID IN NUMBER, P_OUT OUT VARCHAR2)`. */
    private fun parameters(display: String, name: String): String? {
        val start = display.lastIndexOf("$name(").takeIf { it >= 0 }?.let { it + name.length } ?: return null
        var depth = 0
        val parts = ArrayList<String>()
        val part = StringBuilder()
        for (char in display.substring(start)) {
            when {
                char == '(' && depth++ == 0 -> continue
                char == ')' && --depth == 0 -> {
                    parts += part.toString().trim()
                    return "(" + parts.filter { it.isNotEmpty() }.joinToString(", ") + ")"
                }
                char == ',' && depth == 1 -> {
                    parts += part.toString().trim()
                    part.clear()
                    continue
                }
            }
            part.append(char)
        }
        return null
    }

    /** The columns whose value is read into the entity [targetId], or into one of its fields. */
    fun receives(targetId: Long): List<EntitySummary> = connection.query(
        """SELECT DISTINCT e.key, e.kind, e.display, e.external, e.name FROM column_place p JOIN entity e ON e.id = p.column_id
           WHERE p.target_entity_id = ? ORDER BY e.key""", listOf(targetId), ::entitySummary
    )

    /** The condition on `p.concatenated` that keeps the places [concatenated] says, none for all of them. */
    private fun which(concatenated: Boolean?) = when (concatenated) {
        null -> ""
        true -> " AND p.concatenated = 1"
        false -> " AND p.concatenated = 0"
    }

    companion object {
        /** The classes in the order the counts of a column's page list them. */
        val CLASS_ORDER = listOf("FIXED_SIZE", "UNKNOWN", "CALLER_DECIDES", "ANCHORED_ELSEWHERE", "NO_SIZE", "ANCHORED_TO_COLUMN")

        /** The end of the key of a routine's body, which its spec's key lacks. */
        private const val BODY = "@BODY"

        private val WHITESPACE = Regex("\\s+")

        /** The column `?` and the view columns computed from it, transitively (`VIEW_BASE` edges, one hop per view). */
        private const val SCOPE = """WITH RECURSIVE viewed(id) AS (
            SELECT ? UNION SELECT r.from_id FROM relation r JOIN viewed v ON r.to_id = v.id AND r.kind = 'VIEW_BASE')"""
    }
}
