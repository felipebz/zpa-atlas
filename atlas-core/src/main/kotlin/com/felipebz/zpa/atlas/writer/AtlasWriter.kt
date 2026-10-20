//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

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

    /** Writes every row; the caller ([writeAtomically]) owns the transaction, the indexes and the commit. */
    fun write(run: RunInfo, files: List<FileFacts>, globalEntities: List<EntityFact>, globalRelations: List<RelationFact>, globalCoverage: List<CoverageFact>) {
        val globalRows = GlobalRows(connection)
        globalRows.insertRun(run)
        val sortedFiles = files.sortedBy { it.path }
        val entityIds = globalRows.insertEntities(globalEntities + sortedFiles.flatMap { it.entities })
        globalRows.insertRelations((globalRelations + sortedFiles.flatMap { it.relations }), entityIds)
        globalRows.insertTriggerEvents(sortedFiles.flatMap { it.triggerEvents }, entityIds)
        FileRows(connection).write(sortedFiles, entityIds, globalCoverage)
    }

    override fun close() = connection.close()

    companion object {
        const val SCHEMA_VERSION = 12

        /**
         * Writes the index to a temporary file beside [target] and moves it over [target] when everything succeeded.
         */
        fun writeAtomically(target: Path, body: (AtlasWriter) -> Unit) {
            val absolute = target.toAbsolutePath()
            Files.createDirectories(absolute.parent)
            val temporary = Files.createTempFile(absolute.parent, absolute.fileName.toString(), ".tmp")
            try {
                Files.delete(temporary)
                DriverManager.getConnection("jdbc:sqlite:${temporary}").use { connection ->
                    // One transaction: the tables, the rows, then the secondary indexes (cheaper on loaded tables).
                    connection.autoCommit = false
                    execute(connection, tableDdl())
                    body(AtlasWriter(connection))
                    execute(connection, indexDdl())
                    connection.commit()
                }
                replace(temporary, absolute)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        /**
         * Moves [temporary] over [target]. On Windows the move fails while another process (the server between two
         * requests is the usual one) has the target open, so it is retried for about [REPLACE_WAIT_MILLIS].
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
                        throw IllegalStateException("Cannot replace $target: the index is open in another process (${e.javaClass.simpleName})", e)
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
