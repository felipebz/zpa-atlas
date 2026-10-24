//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * The text index of `source_line` (its FTS5 shadow tables but the content), built on a thread and in a file of its
 * own from the moment the sources are read, before the dictionary loads. The trigram index of every line was most of the writing, and depends on nothing a
 * walk finds: only on the texts and their order.
 *
 * The writer attaches the file, which it can do only between two transactions and once the build is done: it commits
 * the rows it wrote before the lines, waits for the build, attaches, copies the shadow tables over its own and writes
 * the content rows, whose `entity_id` needs the entities.
 *
 * The rows are those of inserting into one `source_line`, with [SOURCE_LINE_SETTINGS], the lines [SourceLines]
 * inserts, in the same order, through the same statement in batches of the same size, in one transaction: FTS5 lays
 * out its index by what it is given, its settings and when it flushes, never by the table it is in.
 *
 * @property paths the files of the texts, in file id order: the writer checks that they are its files
 */
class SourceTextIndex private constructor(val file: Path, val paths: List<String>, private val build: CompletableFuture<Long>) : AutoCloseable {

    /** How long the build took on its thread, in ms; null while it runs. */
    val buildMillis: Long? get() = build.getNow(null)

    /** How long the writer waited for the build, in ms. */
    @Volatile
    var waitedMillis = 0L
        private set

    /** Waits for the build; throws what it failed with. */
    internal fun await() {
        val waiting = System.nanoTime()
        try {
            build.join()
            waitedMillis = (System.nanoTime() - waiting) / 1_000_000
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
    }

    /** Waits for the build, whatever it gave, and deletes its file. */
    override fun close() {
        runCatching { build.join() }
        Files.deleteIfExists(file)
    }

    companion object {
        /** The name the writer attaches the file under. */
        internal const val SCHEMA = "atlas_text"

        /**
         * Starts building the text index of [texts] (path and decoded text, any order) in a temporary file beside
         * [target], the index file it is for.
         */
        fun start(target: Path, texts: List<Pair<String, String>>): SourceTextIndex {
            val absolute = target.toAbsolutePath()
            Files.createDirectories(absolute.parent)
            val file = Files.createTempFile(absolute.parent, absolute.fileName.toString(), ".text.tmp")
            val sorted = texts.sortedBy { it.first }
            val build = CompletableFuture<Long>()
            val thread = Thread({
                val started = System.nanoTime()
                try {
                    Files.delete(file)
                    DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                        connection.autoCommit = false
                        connection.createStatement().use { statement ->
                            statement.execute(SOURCE_LINE_DDL)
                            SOURCE_LINE_SETTINGS.forEach(statement::execute)
                        }
                        SourceLines.insertLines(connection, sorted.size) { sorted[it].second }
                        connection.commit()
                    }
                    build.complete((System.nanoTime() - started) / 1_000_000)
                } catch (e: Throwable) {
                    build.completeExceptionally(e)
                }
            }, "atlas-text-index")
            thread.isDaemon = true
            thread.start()
            return SourceTextIndex(file, sorted.map { it.first }, build)
        }
    }
}
