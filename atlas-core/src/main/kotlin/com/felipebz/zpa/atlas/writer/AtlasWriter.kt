//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

import com.felipebz.zpa.atlas.index.PhaseTimes
import com.felipebz.zpa.atlas.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager

/**
 * The only owner of the SQLite connection, the DDL and every INSERT. The database is written to a temporary file next
 * to the target and moved over it at the end, so a reader never sees a partial index. There are no migrations: a
 * schema change means re-indexing. Ids are assigned in a sorted order (entity key; file path, then source position),
 * never in thread-completion order, so two runs over the same input produce identical rows.
 */
class AtlasWriter private constructor(private val connection: Connection) : AutoCloseable {

    /**
     * Writes every row; the caller ([writeAtomically]) owns the transaction, the indexes and the commit. [texts] gives
     * the decoded text of each file by path, for the `source_line` table. [phase] is told each step as it starts.
     */
    fun write(
        run: RunInfo, files: List<FileFacts>, globalEntities: List<EntityFact>, globalRelations: List<RelationFact>, globalCoverage: List<CoverageFact>,
        texts: (String) -> String?,
        phase: (String) -> Unit = {}
    ) {
        phase(PhaseTimes.WRITING_ENTITIES)
        val globalRows = GlobalRows(connection)
        globalRows.insertRun(run)
        val sortedFiles = files.sortedBy { it.path }
        val entityIds = globalRows.insertEntities(globalEntities + sortedFiles.flatMap { it.entities })
        globalRows.insertRelations((globalRelations + sortedFiles.flatMap { it.relations }), entityIds)
        globalRows.insertTriggerEvents(sortedFiles.flatMap { it.triggerEvents }, entityIds)
        phase(PhaseTimes.WRITING_FILES)
        FileRows(connection).write(sortedFiles, entityIds, globalCoverage)
        phase(PhaseTimes.WRITING_LINES)
        val entities = globalEntities + sortedFiles.flatMap { it.entities }
        val containerKeys = entities.filter { it.kind in SourceLines.CONTAINER_KINDS }.map { it.key }.toSet()
        val subprogramKeys = entities.filter { it.kind == EntityKind.PROCEDURE || it.kind == EntityKind.FUNCTION }.map { it.key }.toSet()
        SourceLines(connection).write(sortedFiles, entityIds, containerKeys, subprogramKeys, texts)
    }

    override fun close() = connection.close()

    companion object {
        const val SCHEMA_VERSION = 13

        /**
         * Writes the index to a temporary file beside [target] and moves it over [target] when everything succeeded.
         * [phase] is told each step as it starts.
         */
        fun writeAtomically(target: Path, phase: (String) -> Unit = {}, body: (AtlasWriter) -> Unit) {
            phase(PhaseTimes.WRITING_TABLES)
            val absolute = target.toAbsolutePath()
            require(!Files.isDirectory(absolute)) { "Cannot write the index to $absolute: it is a folder" }
            Files.createDirectories(absolute.parent)
            val temporary = Files.createTempFile(absolute.parent, absolute.fileName.toString(), ".tmp")
            try {
                Files.delete(temporary)
                DriverManager.getConnection("jdbc:sqlite:${temporary}").use { connection ->
                    // One transaction: the tables, the rows, then the secondary indexes (cheaper on loaded tables).
                    connection.autoCommit = false
                    execute(connection, tableDdl())
                    body(AtlasWriter(connection))
                    phase(PhaseTimes.WRITING_INDEXES)
                    execute(connection, indexDdl())
                    phase(PhaseTimes.WRITING_COMMIT)
                    connection.commit()
                }
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
