//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

import com.felipebz.zpa.atlas.model.EntityKind
import com.felipebz.zpa.atlas.model.FileFacts
import com.felipebz.zpa.atlas.model.Role
import com.felipebz.zpa.atlas.model.SourceSpan
import java.sql.Connection

/**
 * The `source_line` rows: each line of each file as decoded for indexing, split as the query side splits a file
 * (`String.lines()`), with its innermost container.
 *
 * A line's container is taken from what the index holds of the file: the span of each `DEFINITION` occurrence of a
 * package, routine, trigger, view, type or cursor, of each `DECLARATION` occurrence of a procedure or function (a
 * specification's subprogram, or a forward declaration), and of each unit with an entity. A line gets the entity of the
 * narrowest of these spans that covers it, comments and blank lines included.
 *
 * That is the container `find_occurrences` gives the occurrences of the line (`occurrence.container_entity_id`), its
 * parameters and types included, with two exceptions:
 * - a subprogram's first line goes to the subprogram, while the occurrence of the subprogram itself there (its
 *   `DEFINITION` or `DECLARATION`) belongs to the enclosing package;
 * - a line shared by two subprograms, or a cursor declared on one line in a specification, goes to one entity only.
 */
internal class SourceLines(private val connection: Connection) {
    fun write(sortedFiles: List<FileFacts>, entityIds: Map<String, Long>, containerKeys: Set<String>, subprogramKeys: Set<String>, texts: (String) -> String?) {
        connection.prepareStatement("INSERT INTO source_line(rowid, text, file_id, line, entity_id) VALUES (?,?,?,?,?)").use { insert ->
            var rowid = 0L
            var pending = 0
            for ((index, file) in sortedFiles.withIndex()) {
                val text = texts(file.path) ?: continue
                val lines = text.lines()
                val containers = containers(file, containerKeys, subprogramKeys, lines.size)
                for ((i, line) in lines.withIndex()) {
                    rowid++
                    // A search needs three characters: a shorter line can never match.
                    if (line.codePointCount(0, line.length) < 3) continue
                    insert.setLong(1, rowid)
                    insert.setString(2, line)
                    insert.setLong(3, index + 1L)
                    insert.setInt(4, i + 1)
                    val container = containers[i]
                    if (container == null) insert.setNull(5, java.sql.Types.INTEGER) else insert.setLong(5, entityIds.getValue(container))
                    insert.addBatch()
                    if (++pending >= BATCH) {
                        insert.executeBatch()
                        pending = 0
                    }
                }
            }
            if (pending > 0) insert.executeBatch()
        }
    }

    /** The key of the innermost container of each line (index 0 is line 1), or null. */
    private fun containers(file: FileFacts, containers: Set<String>, subprograms: Set<String>, lineCount: Int): Array<String?> {
        val spans = file.occurrences.filter { occurrence ->
            occurrence.entityKey in containers &&
                (Role.DEFINITION in occurrence.roles || Role.DECLARATION in occurrence.roles && occurrence.entityKey in subprograms)
        }.map { it.entityKey!! to it.span } +
            file.units.mapNotNull { unit -> unit.entityKey?.let { it to unit.span } }
        val result = arrayOfNulls<String>(lineCount)
        val best = arrayOfNulls<SourceSpan>(lineCount)
        for ((key, span) in spans) {
            for (line in span.startLine.coerceAtLeast(1)..span.endLine.coerceAtMost(lineCount)) {
                val current = best[line - 1]
                if (current == null || narrower(span, key, current, result[line - 1]!!)) {
                    best[line - 1] = span
                    result[line - 1] = key
                }
            }
        }
        return result
    }

    /** Fewer lines first, then the later start (the inner of two on one line), then the key, for the same answer every run. */
    private fun narrower(span: SourceSpan, key: String, than: SourceSpan, thanKey: String): Boolean {
        val lines = span.endLine - span.startLine
        val thanLines = than.endLine - than.startLine
        if (lines != thanLines) return lines < thanLines
        if (span.startLine != than.startLine) return span.startLine > than.startLine
        if (span.startCol != than.startCol) return span.startCol > than.startCol
        return key < thanKey
    }

    companion object {
        private const val BATCH = 20_000

        val CONTAINER_KINDS = setOf(
            EntityKind.PACKAGE, EntityKind.PROCEDURE, EntityKind.FUNCTION, EntityKind.TRIGGER, EntityKind.VIEW,
            EntityKind.MATERIALIZED_VIEW, EntityKind.TYPE, EntityKind.CURSOR
        )
    }
}
