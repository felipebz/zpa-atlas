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

    /** [AtlasIndex.searchText] after its checks; with [colorer], each row's colors. */
    fun search(text: String, path: String?, container: String?, containerName: String?, after: Long?, limit: Int, colorer: CodeColorer?): TextPage {
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
        val (total, files) = connection.query("${with}SELECT count(*), count(DISTINCT file_id) FROM source_line WHERE $where", cteParameters + parameters) {
            it.getLong(1) to it.getLong(2)
        }.single()
        val found = connection.query(
            """${with}SELECT l.rowid, f.path, l.line, l.text, e.key, e.kind, e.display, e.external, e.name, l.file_id
               FROM (SELECT rowid, file_id, line, text, entity_id FROM source_line WHERE $where AND rowid > ? ORDER BY rowid LIMIT ?) l
               JOIN source_file f ON f.id = l.file_id LEFT JOIN entity e ON e.id = l.entity_id
               ORDER BY l.rowid""",
            cteParameters + parameters + listOf(after ?: 0L, limit + 1)
        ) {
            val entity = it.getString(5)?.let { key -> EntitySummary(key, it.getString(6), it.getString(7), it.getInt(8) == 1, it.getString(9)) }
            it.getLong(10) to TextHit(it.getLong(1), it.getString(2), it.getInt(3), it.getString(4), entity, null)
        }
        val page = found.take(limit).map { it.second }
        // Each file of the page with its count of lines found, whether or not they are all on this page.
        val fileIds = found.take(limit).associate { (fileId, hit) -> fileId to hit.path }
        val fileLines = if (fileIds.isEmpty()) emptyMap() else connection.query(
            "${with}SELECT file_id, count(*) FROM source_line WHERE $where AND file_id IN (${fileIds.keys.joinToString(",") { "?" }}) GROUP BY file_id",
            cteParameters + parameters + fileIds.keys
        ) { fileIds.getValue(it.getLong(1)) to it.getLong(2) }.toMap()
        val reader = SourceReader(connection, sourcesRoot)
        val served = page.map { it.path }.distinct().associateWith { reader.served(it) }
        val colors = colorer?.let { lineColors(reader, it, page, served) }
        return TextPage(
            page.map { hit -> hit.copy(stale = served.getValue(hit.path)?.stale, colors = colors?.get(hit.id)) },
            if (found.size > limit) page.last().id else null,
            total,
            wholeIndex = path == null && container == null && containerName == null,
            files = files,
            fileLines = fileLines
        )
    }

    /**
     * The colors of each row of [page], by row id, as `[startCol, endCol, color]` on its line: from its whole file, read
     * once, when the file is served as indexed; from the line alone when the file changed since indexing or cannot be
     * read (the whole text would not be the one the line came from). A row the colorer cannot read has none.
     */
    private fun lineColors(reader: SourceReader, colorer: CodeColorer, page: List<TextHit>, served: Map<String, SourceReader.SourceText?>): Map<Long, List<List<Int>>> {
        val result = HashMap<Long, List<List<Int>>>()
        for ((path, hits) in page.groupBy { it.path }) {
            val file = served.getValue(path)
            if (file != null && !file.stale) {
                val spans = reader.colorsOf(colorer, file.text) ?: continue
                val wanted = hits.associateBy { it.line }
                val byLine = HashMap<Int, MutableList<List<Int>>>()
                for (span in spans) {
                    val (startLine, startCol, endLine, endCol, color) = span
                    for (line in startLine..endLine) {
                        val hit = wanted[line] ?: continue
                        val from = if (line == startLine) startCol else 0
                        val to = if (line == endLine) endCol else hit.text.length
                        if (to > from) byLine.getOrPut(line) { ArrayList() } += listOf(from, to, color)
                    }
                }
                for (hit in hits) result[hit.id] = byLine[hit.line].orEmpty()
            } else {
                for (hit in hits) {
                    val spans = reader.colorsOf(colorer, hit.text) ?: continue
                    result[hit.id] = spans.filter { it[0] == 1 }.map { listOf(it[1], if (it[2] == 1) it[3] else hit.text.length, it[4]) }
                }
            }
        }
        return result
    }
}
