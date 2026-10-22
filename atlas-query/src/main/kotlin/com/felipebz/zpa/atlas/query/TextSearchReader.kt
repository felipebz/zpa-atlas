//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import java.nio.file.Path
import java.sql.Connection

/**
 * The text search over the `source_line` table: an FTS5 phrase of the text's trigrams, which matches the lines that
 * contain it, case folded. A line is in a container when its innermost container (`source_line.entity_id`) is that
 * entity or one of its contents (`CONTAINS`), as an occurrence is for `find_occurrences`.
 */
internal class TextSearchReader(private val connection: Connection, private val sourcesRoot: Path?) {

    fun search(text: String, path: String?, container: String?, containerName: String?, after: Long?, limit: Int): TextPage {
        val ctes = ArrayList<String>()
        val cteParameters = ArrayList<Any?>()
        val conditions = arrayListOf("source_line MATCH ?")
        val parameters = arrayListOf<Any?>("\"" + text.replace("\"", "\"\"") + "\"")
        if (path != null) {
            conditions += "file_id IN (SELECT id FROM source_file WHERE path LIKE ? ESCAPE '\\')"
            parameters += if ('*' in path) OccurrenceQuerySql.pattern(path) else OccurrenceQuerySql.escapeLike(path) + "%"
        }
        if (container != null) {
            val id = connection.query("SELECT id FROM entity WHERE key = ?", listOf(container)) { it.getLong(1) }.singleOrNull()
                ?: throw InvalidQueryException("Unknown container: $container")
            ctes += "within(id) AS (SELECT ? UNION ${OccurrenceQuerySql.contained("within")})"
            cteParameters += id
            conditions += "entity_id IN (SELECT id FROM within)"
        }
        if (containerName != null) {
            val (joins, match, named) = OccurrenceQuerySql.parentChain("c", containerName.split('.'), OccurrenceQuerySql::segmentMatch)
            ctes += "within_named(id) AS (SELECT c.id FROM entity c $joins WHERE $match UNION ${OccurrenceQuerySql.contained("within_named")})"
            cteParameters.addAll(named)
            conditions += "entity_id IN (SELECT id FROM within_named)"
        }
        val with = if (ctes.isEmpty()) "" else "WITH RECURSIVE " + ctes.joinToString(",\n") + "\n"
        val where = conditions.joinToString(" AND ")
        val total = connection.query("${with}SELECT count(*) FROM source_line WHERE $where", cteParameters + parameters) { it.getLong(1) }.single()
        val found = connection.query(
            """${with}SELECT l.rowid, f.path, l.line, l.text, e.key, e.kind, e.display, e.external, e.name
               FROM (SELECT rowid, file_id, line, text, entity_id FROM source_line WHERE $where AND rowid > ? ORDER BY rowid LIMIT ?) l
               JOIN source_file f ON f.id = l.file_id LEFT JOIN entity e ON e.id = l.entity_id
               ORDER BY l.rowid""",
            cteParameters + parameters + listOf(after ?: 0L, limit + 1)
        ) {
            val entity = it.getString(5)?.let { key -> EntitySummary(key, it.getString(6), it.getString(7), it.getInt(8) == 1, it.getString(9)) }
            it.getLong(1) to TextHit(it.getLong(1), it.getString(2), it.getInt(3), it.getString(4), entity, null)
        }
        val page = found.take(limit)
        val reader = SourceReader(connection, sourcesRoot)
        val stale = page.map { it.second.path }.distinct().associateWith { reader.stale(it) }
        return TextPage(
            page.map { (_, hit) -> hit.copy(stale = stale.getValue(hit.path)) },
            if (found.size > limit) page.last().first else null,
            total,
            wholeIndex = path == null && container == null && containerName == null
        )
    }
}
