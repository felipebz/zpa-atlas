//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.zip.CRC32C

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
 * Kept ([start] with `keep`), the file stays beside the index once the index is written ([keep]), with a manifest that
 * names what it was built from and the checksum of its bytes; the next run that keeps it uses it as is when both still
 * match, else builds it again. The file and then its manifest are each moved into place whole, so a run killed midway
 * leaves the previous pair, or a file its manifest does not match, which is built again.
 *
 * @property paths the files of the texts, in file id order: the writer checks that they are its files
 */
class SourceTextIndex private constructor(val paths: List<String>, private val build: CompletableFuture<Built>, private val kept: Path?) : AutoCloseable {

    private class Built(val file: Path, val millis: Long, val reused: Boolean, val key: String?)

    /** The file the writer attaches; known once the build is done ([await]). */
    internal val file: Path get() = build.join().file

    /** How long the build took on its thread, in ms (with a kept file reused, how long checking it took); null while it runs. */
    val buildMillis: Long? get() = build.getNow(null)?.millis

    /** Whether the kept file was used instead of a build; null while the build runs. */
    val reused: Boolean? get() = build.getNow(null)?.reused

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

    /** Once the index is written: the built file becomes the kept one, with its manifest. Nothing when not kept, or reused. */
    fun keep() {
        val target = kept ?: return
        val built = build.join()
        if (built.reused) return
        val bytes = sealed(built.file)
        Files.move(built.file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        val manifest = manifestFor(target)
        val temporary = Files.createTempFile(target.toAbsolutePath().parent, manifest.fileName.toString(), ".tmp")
        try {
            Files.writeString(temporary, manifestText(built.key!!, bytes))
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Waits for the build, whatever it gave, and deletes its file unless it is the kept one. */
    override fun close() {
        val built = runCatching { build.join() }.getOrNull() ?: return
        if (built.file != kept) Files.deleteIfExists(built.file)
    }

    companion object {
        /** The name the writer attaches the file under. */
        internal const val SCHEMA = "atlas_text"

        /**
         * Raised when the rows built from the same texts would change: how [SourceLines] splits and numbers the lines.
         * [LINES_DIGEST] records the digest of the test fixtures' rows at that format, which a test compares.
         */
        internal const val LINES_FORMAT = 1
        internal const val LINES_DIGEST = "efd58aba0f87bc2b16ecf9605175d246a964c5ce386af2ff7e9177df672a964c"

        private const val MANIFEST_HEADER = "zpa-atlas text index"

        /** The kept text index of the index file [index]. */
        fun keptFor(index: Path): Path = index.resolveSibling(index.fileName.toString() + ".text")

        private fun manifestFor(kept: Path): Path = kept.resolveSibling(kept.fileName.toString() + ".manifest")

        /**
         * Starts building the text index of [texts] (path and decoded text, any order) in a temporary file beside
         * [target], the index file it is for. With [keep], the kept file of [target] is used instead when it was built
         * from the same texts, and the one built is kept for the next run ([keep]).
         */
        fun start(target: Path, texts: List<Pair<String, String>>, keep: Boolean = false): SourceTextIndex {
            val absolute = target.toAbsolutePath()
            Files.createDirectories(absolute.parent)
            val sorted = texts.sortedBy { it.first }
            val kept = if (keep) keptFor(absolute) else null
            val build = CompletableFuture<Built>()
            val thread = Thread({
                val started = System.nanoTime()
                try {
                    val key = kept?.let { key(sorted) }
                    if (kept != null && matches(kept, key!!)) {
                        build.complete(Built(kept, (System.nanoTime() - started) / 1_000_000, true, key))
                        return@Thread
                    }
                    val file = Files.createTempFile(absolute.parent, absolute.fileName.toString(), ".text.tmp")
                    Files.delete(file)
                    try {
                        DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
                            writingSettings(connection)
                            connection.autoCommit = false
                            connection.createStatement().use { statement ->
                                statement.execute(SOURCE_LINE_DDL)
                                SOURCE_LINE_SETTINGS.forEach(statement::execute)
                            }
                            SourceLines.insertLines(connection, sorted.size) { sorted[it].second }
                            connection.commit()
                        }
                    } catch (e: Throwable) {
                        Files.deleteIfExists(file)
                        throw e
                    }
                    build.complete(Built(file, (System.nanoTime() - started) / 1_000_000, false, key))
                } catch (e: Throwable) {
                    build.completeExceptionally(e)
                }
            }, "atlas-text-index")
            thread.isDaemon = true
            thread.start()
            return SourceTextIndex(sorted.map { it.first }, build, kept)
        }

        /** What a text index is built from: the texts in order, their paths, the table's settings, the line rules, SQLite's version. */
        private fun key(sorted: List<Pair<String, String>>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(text: String) {
                digest.update(text.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            add("lines $LINES_FORMAT")
            add(sqliteVersion())
            add(SOURCE_LINE_DDL)
            SOURCE_LINE_SETTINGS.forEach(::add)
            add("files ${sorted.size}")
            for ((path, text) in sorted) {
                add(path)
                add(text)
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        private fun sqliteVersion(): String = DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { it.executeQuery("SELECT sqlite_version()").use { rows -> rows.next(); "sqlite " + rows.getString(1) } }
        }

        /** Whether [kept] and its manifest are there, the manifest names [key], and the file has the size and checksum it records. */
        private fun matches(kept: Path, key: String): Boolean {
            val manifest = manifestFor(kept)
            if (!Files.isRegularFile(kept) || !Files.isRegularFile(manifest)) return false
            val expected = runCatching { Files.readString(manifest) }.getOrNull() ?: return false
            val lines = expected.lines()
            if (lines.size < 3 || lines[0] != MANIFEST_HEADER || lines[1] != key) return false
            return runCatching { manifestText(key, checksum(kept)) == expected }.getOrDefault(false)
        }

        private fun manifestText(key: String, bytes: Pair<Long, String>) = "$MANIFEST_HEADER\n$key\n${bytes.first} ${bytes.second}\n"

        /** Syncs [file] and gives its size and checksum. */
        private fun sealed(file: Path): Pair<Long, String> {
            FileChannel.open(file, StandardOpenOption.WRITE).use { it.force(true) }
            return checksum(file)
        }

        private fun checksum(file: Path): Pair<Long, String> = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            val crc = CRC32C()
            val buffer = ByteBuffer.allocateDirect(1 shl 20)
            var size = 0L
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read < 0) break
                size += read
                buffer.flip()
                crc.update(buffer)
            }
            size to java.lang.Long.toHexString(crc.value)
        }
    }
}
