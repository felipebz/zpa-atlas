//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.compare

import com.felipebz.zpa.atlas.query.AtlasIndex
import java.nio.file.Files
import java.nio.file.Path

/** How each unit's file text compares with the stored source, and the names its text holds. */
internal class UnitSources(
    val sourceStatus: HashMap<DbObjectId, Pair<SourceStatus, Int?>>,
    /** The names in the text of each unit whose file was read. */
    val names: Map<DbObjectId, Set<String>>
)

/** The texts of [units] under [root] against [database]'s stored sources, for every object in [mapped]. */
internal fun readSources(database: DatabaseDependencies, units: List<IndexUnit>, mapped: Set<DbObjectId>, root: Path?): UnitSources {
    val stored = database.sources.groupBy { DbObjectId(it.owner, it.name, it.type) }
        .mapValues { (_, lines) -> lines.sortedBy { it.line }.joinToString("") { it.text } }
    val unitTexts = HashMap<DbObjectId, String>()
    for (unit in units) {
        val obj = unit.obj ?: continue
        val file = root?.resolve(unit.path)?.takeIf { Files.isRegularFile(it) } ?: continue
        // The charset the indexer used for this file, so that the spans cut at the same characters.
        val text = AtlasIndex.decode(Files.readAllBytes(file), unit.encoding)
        unitTexts.merge(obj, slice(text, unit.span)) { a, b -> a + "\n" + b }
    }
    val sourceStatus = HashMap<DbObjectId, Pair<SourceStatus, Int?>>()
    for (obj in mapped) {
        val text = unitTexts[obj]
        val db = stored[obj]
        sourceStatus[obj] = when {
            !database.sourceReadable || text == null -> SourceStatus.UNAVAILABLE to null
            db == null -> SourceStatus.NO_STORED_SOURCE to null
            // ALL_VIEWS.TEXT and ALL_MVIEWS.QUERY: the query only, with the stars of a view expanded.
            obj.type == "VIEW" -> PlSqlText.viewDifference(text, db)?.let { SourceStatus.DIFFERS to it } ?: (SourceStatus.SAME to null)
            obj.type == "MATERIALIZED VIEW" -> PlSqlText.viewDifference(text, db)?.let {
                if (PlSqlText.isRewrittenQuery(db)) SourceStatus.REWRITTEN to null else SourceStatus.DIFFERS to it
            } ?: (SourceStatus.SAME to null)
            PlSqlText.isWrapped(db) -> SourceStatus.WRAPPED to null
            else -> PlSqlText.firstDifference(text, db)?.let { SourceStatus.DIFFERS to it } ?: (SourceStatus.SAME to null)
        }
    }
    val names = unitTexts.mapValues { PlSqlText.names(it.value) }
    return UnitSources(sourceStatus, names)
}

/** The text of a unit: from its start position to its end position (lines 1-based, columns 0-based). */
private fun slice(text: String, span: IntArray): String {
    val lines = text.replace("\r\n", "\n").split('\n')
    val first = span[0] - 1
    val last = (span[2] - 1).coerceAtMost(lines.size - 1)
    if (first > last) return ""
    if (first == last) return lines[first].substring(span[1].coerceAtMost(lines[first].length), span[3].coerceAtMost(lines[first].length))
    val parts = ArrayList<String>()
    parts += lines[first].substring(span[1].coerceAtMost(lines[first].length))
    for (i in first + 1 until last) parts += lines[i]
    parts += lines[last].substring(0, span[3].coerceAtMost(lines[last].length))
    return parts.joinToString("\n")
}
