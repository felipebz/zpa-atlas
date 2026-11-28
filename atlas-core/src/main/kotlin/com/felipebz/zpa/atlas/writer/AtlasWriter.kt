//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

import com.felipebz.zpa.atlas.index.PhaseTimes
import com.felipebz.zpa.atlas.model.*
import com.felipebz.zpa.atlas.writer.AtlasWriter.Companion.REPLACE_WAIT_MILLIS
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager

/**
 * The only owner of the SQLite connection, the DDL and every INSERT, which a [RowWriter] executes on a thread of its
 * own while the next rows are built. The database is written to a temporary file next to the target and moved over it
 * at the end, so a reader never sees a partial index. There are no migrations: a schema change means re-indexing. Ids are assigned in a sorted order (entity key; file path, then source position),
 * never in thread-completion order, so two runs over the same input produce identical rows.
 */
class AtlasWriter private constructor(private val connection: Connection, private val textIndex: SourceTextIndex) : AutoCloseable by connection {

    /**
     * Writes every row; the caller ([writeAtomically]) owns the transaction, the indexes and the commit. [texts] gives
     * the decoded text of each file by path, for the `source_line` table. [phase] is told each step as it starts.
     * [dictionaryDeclaredTexts] are the datatypes of the dictionary columns, [keys] the dictionary keys, [places] the
     * places column values are read into, by file path, [placesLeftOut] what is counted instead, by file path, and
     * [valueFields] the record fields values name, with the columns of row records' fields, by file path and value span.
     * [note] is told, at the end, how long the building and the writing of the rows waited for each other.
     */
    fun write(
        run: RunInfo, files: List<FileFacts>, globalEntities: List<EntityFact>, globalRelations: List<RelationFact>, globalCoverage: List<CoverageFact>,
        texts: (String) -> String?,
        phase: (String) -> Unit = {},
        dictionaryDeclaredTexts: List<DeclaredTextFact> = emptyList(),
        keys: List<KeyFact> = emptyList(),
        places: Map<String, List<ColumnPlaceFact>> = emptyMap(),
        placesLeftOut: Map<String, Map<String, Int>> = emptyMap(),
        valueFields: Map<String, Map<SourceSpan, ValueField>> = emptyMap(),
        note: (String) -> Unit = {}
    ) {
        // The rows are built on this thread and written on the writer's, which has the connection until it finishes. A
        // step ends once its rows are written, so that each phase counts its own.
RowWriter(connection, "atlas-index rows").use { writer ->
            val waits = ArrayList<String>()
            var idle = 0L
            var waited = 0L
            fun waited(step: String) {
                waits += "$step: SQLite waited ${writer.idleMillis - idle} ms for rows, the rows ${writer.waitedMillis - waited} ms for SQLite"
                idle = writer.idleMillis
                waited = writer.waitedMillis
            }
            phase(PhaseTimes.WRITING_ENTITIES)
            val globalRows = GlobalRows(writer)
            globalRows.insertRun(run)
            val sortedFiles = files.sortedBy { it.path }
            val entityIds = globalRows.insertEntities(globalEntities + sortedFiles.flatMap { it.entities })
            globalRows.insertRelations((globalRelations + sortedFiles.flatMap { it.relations }), entityIds)
            globalRows.insertTriggerEvents(sortedFiles.flatMap { it.triggerEvents }, entityIds)
            globalRows.insertDeclaredTexts(dictionaryDeclaredTexts + sortedFiles.flatMap { it.declaredTexts }, entityIds)
            globalRows.insertUsesGaps(sortedFiles.flatMap { it.usesGaps }, entityIds)
            globalRows.insertMembersGaps(sortedFiles.flatMap { it.membersGaps }, entityIds)
            globalRows.insertKeys(keys, entityIds)
            writer.drain()
            waited("entities and relations")
            phase(PhaseTimes.WRITING_FILES)
            FileRows(writer).write(sortedFiles, entityIds, globalCoverage, places, placesLeftOut, valueFields)
            writer.drain()
            waited("file rows")
            phase(PhaseTimes.WRITING_LINES)
            val entities = globalEntities + sortedFiles.flatMap { it.entities }
            val containerKeys = entities.filter { it.kind in SourceLines.CONTAINER_KINDS }.map { it.key }.toSet()
            val subprogramKeys = entities.filter { it.kind == EntityKind.PROCEDURE || it.kind == EntityKind.FUNCTION }.map { it.key }.toSet()
            SourceLines(writer).write(sortedFiles, entityIds, containerKeys, subprogramKeys, texts, textIndex)
            writer.finish()
            waited("source lines")
            note("Index rows built on this thread while SQLite writes the earlier ones on its own: ${waits.joinToString("; ")}")
        }
    }

    companion object {
        const val SCHEMA_VERSION = 35

        /**
         * Writes the index to a temporary file beside [target] and moves it over [target] when everything succeeded.
         * [phase] is told each step as it starts. [textIndex], started earlier for the same files, gives the text index
         * of `source_line`, which is not built here.
         */
        fun writeAtomically(target: Path, textIndex: SourceTextIndex, phase: (String) -> Unit = {}, body: (AtlasWriter) -> Unit) {
            phase(PhaseTimes.WRITING_TABLES)
            val absolute = target.toAbsolutePath()
            require(!Files.isDirectory(absolute)) { "Cannot write the index to $absolute: it is a folder" }
            Files.createDirectories(absolute.parent)
            val temporary = Files.createTempFile(absolute.parent, absolute.fileName.toString(), ".tmp")
            try {
                Files.delete(temporary)
                DriverManager.getConnection("jdbc:sqlite:${temporary}").use { connection ->
                    // The CHECKs of the schema are made from the enums the rows are written from, so every row meets them;
                    // evaluated on each insert, they were most of SQLite's time on the file rows. Readers still see them.
                    connection.createStatement().use { it.execute("PRAGMA ignore_check_constraints = ON") }
                    writingSettings(connection)
                    // One transaction: the tables, the rows, then the secondary indexes (cheaper on loaded tables). With a
                    // text index built apart, two: see SourceLines.
                    connection.autoCommit = false
                    execute(connection, tableDdl())
                    body(AtlasWriter(connection, textIndex))
                    phase(PhaseTimes.WRITING_INDEXES)
                    execute(connection, indexDdl())
                    phase(PhaseTimes.WRITING_COMMIT)
                    connection.commit()
                }
                // The writes were not synced: the whole file is, once, before it replaces the index.
                FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
                replace(temporary, absolute)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        /**
         * Moves [temporary] over [target]. On Windows the move fails while another process (the server between two
         * requests is the usual one) has the target open, so it is retried for about [REPLACE_WAIT_MILLIS]; then the
         * error says what the file system answered, and that another process may hold the file.
         */
        private fun replace(temporary: Path, target: Path) {
            val deadline = System.nanoTime() + REPLACE_WAIT_MILLIS * 1_000_000
            while (true) {
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    return
                } catch (e: java.nio.file.FileSystemException) {
                    // AccessDeniedException is a FileSystemException.
                    if (System.nanoTime() > deadline) {
                        throw IllegalStateException("Cannot replace $target: ${e.javaClass.simpleName}${e.reason?.let { " ($it)" } ?: ""}; another process may have it open", e)
                    }
                    Thread.sleep(100)
                }
            }
        }

        private const val REPLACE_WAIT_MILLIS = 5_000L

        /** Creates the empty schema: the tables, then the secondary indexes. */
        fun createSchema(connection: Connection) = com.felipebz.zpa.atlas.writer.createSchema(connection)

        /** The tables, with their inline constraints (`PRIMARY KEY`, `UNIQUE`), in creation order. */
        fun tableDdl(): List<String> = com.felipebz.zpa.atlas.writer.tableDdl()

        /** The secondary indexes, created after the rows are loaded. Readers do not depend on their creation order. */
        fun indexDdl(): List<String> = com.felipebz.zpa.atlas.writer.indexDdl()
    }
}
