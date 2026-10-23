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
    /**
     * Writes the lines of [sortedFiles]: the trigram index of [textIndex], built apart from the same texts, is copied,
     * and only the content rows, with their containers, are written here.
     */
    fun write(
        sortedFiles: List<FileFacts>, entityIds: Map<String, Long>, containerKeys: Set<String>, subprogramKeys: Set<String>, texts: (String) -> String?,
        textIndex: SourceTextIndex
    ) {
        check(textIndex.paths == sortedFiles.map { it.path }) { "The text index was built for other files" }
        val entities = { file: Int, lineCount: Int ->
            val keys = containers(sortedFiles[file], containerKeys, subprogramKeys, lineCount)
            ({ line: Int -> keys[line]?.let { entityIds.getValue(it) } })
        }
        val text = { file: Int -> texts(sortedFiles[file].path) }
        // ATTACH cannot be in a transaction, nor read a file another connection is still writing: the rows so far
        // are committed, the build waited for, and the rest goes in a new transaction (the file is moved only at the end).
        connection.autoCommit = true
        textIndex.await()
        connection.prepareStatement("ATTACH DATABASE ? AS ${SourceTextIndex.SCHEMA}").use {
            it.setString(1, textIndex.file.toString())
            it.execute()
        }
        connection.autoCommit = false
        connection.createStatement().use { statement ->
            for (table in COPIED) {
                statement.execute("DELETE FROM source_line_$table")
                statement.execute("INSERT INTO source_line_$table SELECT * FROM ${SourceTextIndex.SCHEMA}.source_line_$table")
            }
        }
        insertLines(connection, sortedFiles.size, text, INSERT_CONTENT, entities)
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

        /** Every column of a line, through the FTS5 table, which indexes the text. */
        private const val INSERT = "INSERT INTO source_line(rowid, text, file_id, line, entity_id) VALUES (?,?,?,?,?)"

        /** The same values into the table FTS5 keeps them in, for a text index built apart ([SourceTextIndex]). */
        private const val INSERT_CONTENT = "INSERT INTO source_line_content(id, c0, c1, c2, c3) VALUES (?,?,?,?,?)"

        /** The shadow tables of `source_line` that hold its index, its sizes and its settings: all but the content. */
        private val COPIED = listOf("data", "idx", "docsize", "config")

        val CONTAINER_KINDS = setOf(
            EntityKind.PACKAGE, EntityKind.PROCEDURE, EntityKind.FUNCTION, EntityKind.TRIGGER, EntityKind.VIEW,
            EntityKind.MATERIALIZED_VIEW, EntityKind.TYPE, EntityKind.CURSOR
        )

        /**
         * The lines of [fileCount] files through `source_line`, without their containers: what [SourceTextIndex]
         * builds. These were the rows of `source_line` before it was built apart, and the shadow tables stay theirs.
         */
        fun insertLines(connection: Connection, fileCount: Int, text: (Int) -> String?) =
            insertLines(connection, fileCount, text, INSERT) { _, _ -> { null } }

        /**
         * Inserts with [sql] one row per line of each file of [text] (by file index, the file id less one): rowids
         * count every line of the files in order, but a line shorter than three characters has no row. [entities]
         * gives a file's container of each line.
         */
        private fun insertLines(
            connection: Connection, fileCount: Int, text: (Int) -> String?, sql: String, entities: (Int, Int) -> (Int) -> Long?
        ) {
            connection.prepareStatement(sql).use { insert ->
                var rowid = 0L
                var pending = 0
                for (index in 0 until fileCount) {
                    val lines = (text(index) ?: continue).lines()
                    val entity = entities(index, lines.size)
                    for ((i, line) in lines.withIndex()) {
                        rowid++
                        // A search needs three characters: a shorter line can never match.
                        if (line.codePointCount(0, line.length) < 3) continue
                        insert.setLong(1, rowid)
                        insert.setString(2, line)
                        insert.setLong(3, index + 1L)
                        insert.setInt(4, i + 1)
                        val container = entity(i)
                        if (container == null) insert.setNull(5, java.sql.Types.INTEGER) else insert.setLong(5, container)
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
    }
}
