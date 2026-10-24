//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection

/** Source snippets and the file view, read from [sourcesRoot] over one open connection. */
internal class SourceReader(private val connection: Connection, private val sourcesRoot: Path?) {

    /** [AtlasIndex.source] after its checks. */
    fun source(path: String, line: Int, context: Int): SourceSnippet? {
        val file = sourceFile(path)
        val text = readSource(file) ?: return null
        val first = (line - context).coerceAtLeast(1)
        val last = (line + context).coerceAtMost(text.lines.size)
        return SourceSnippet(path, (first..last).map { SourceLine(it, text.lines[it - 1]) }, text.stale)
    }

    fun file(path: String): FileView? {
        val file = sourceFile(path)
        val text = readSource(file) ?: return null
        if (text.stale) return FileView(path, true, text.lines, emptyList(), emptyList())
        val entityIndexes = LinkedHashMap<Long, Int>()
        fun indexOf(id: Long) = entityIndexes.getOrPut(id) { entityIndexes.size }
        val candidates = connection.query(
            "SELECT c.occurrence_id, c.entity_id FROM occurrence o JOIN occurrence_candidate c ON c.occurrence_id = o.id WHERE o.file_id = ? ORDER BY c.occurrence_id, c.entity_id",
            listOf(file.id)
        ) { it.getLong(1) to it.getLong(2) }.groupBy({ it.first }, { it.second })
        // The name span when it was recorded apart, else the span; read through occurrence_file_line.
        val occurrences = connection.query(
            """SELECT o.id, coalesce(o.name_start_line, o.start_line), coalesce(o.name_start_col, o.start_col),
                      coalesce(o.name_end_line, o.end_line), coalesce(o.name_end_col, o.end_col), o.roles, o.status, o.reason, o.entity_id
               FROM occurrence o WHERE o.file_id = ? ORDER BY o.start_line, o.id""",
            listOf(file.id)
        ) { rs ->
            val entityId = (rs.getObject(9) as Number?)?.toLong()
            FileOccurrence(listOf(rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)), roles(rs.getString(6)), rs.getString(7), rs.getString(8),
                entityId?.let(::indexOf), candidates[rs.getLong(1)].orEmpty().map(::indexOf))
        }
        val summaries = HashMap<Long, EntitySummary>()
        for (chunk in entityIndexes.keys.chunked(500)) {
            connection.query("SELECT id, key, kind, display, external, name FROM entity WHERE id IN (${chunk.joinToString(",") { "?" }})", chunk) {
                it.getLong(1) to EntitySummary(it.getString(2), it.getString(3), it.getString(4), it.getInt(5) == 1, it.getString(6))
            }.forEach { summaries[it.first] = it.second }
        }
        return FileView(path, false, text.lines, entityIndexes.keys.map { summaries.getValue(it) }, occurrences)
    }

    /**
     * Whether the file [path] changed since indexing, or null when there is no sources root, no such file, or the file
     * now resolves outside the sources root: a search answers its other rows, and never reads that file.
     */
    fun stale(path: String): Boolean? = try {
        readSource(sourceFile(path))?.stale
    } catch (_: LeavesRootException) {
        null
    }

    private class SourceFile(val id: Long, val path: String, val sha256: String, val encoding: String)

    private class SourceText(val lines: List<String>, val stale: Boolean)

    private fun sourceFile(path: String): SourceFile =
        connection.query("SELECT id, sha256, encoding FROM source_file WHERE path = ?", listOf(path)) {
            SourceFile(it.getLong(1), path, it.getString(2), it.getString(3))
        }.singleOrNull() ?: throw InvalidQueryException("Not a file of the index: $path")

    private class LeavesRootException(path: String) : InvalidQueryException("Path leaves the sources root: $path")

    /**
     * The lines of [file] under the sources root, or null when there is no root or no such file. The path is checked
     * by its name, then by where it really leads: a symbolic link (of the file or of a folder above it) that leaves the
     * real sources root is refused, one that stays inside is followed.
     */
    private fun readSource(file: SourceFile): SourceText? {
        val root = sourcesRoot?.toAbsolutePath()?.normalize() ?: return null
        val resolved = root.resolve(file.path).normalize()
        if (!resolved.startsWith(root)) throw LeavesRootException(file.path)
        val real = try {
            val real = resolved.toRealPath()
            if (!real.startsWith(root.toRealPath())) throw LeavesRootException(file.path)
            real
        } catch (_: IOException) {
            // No such file, a dangling link, or a root that is gone.
            return null
        }
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) return null
        val bytes = Files.readAllBytes(real)
        // The charset the indexer decoded this file with, so lines and columns match the stored spans.
        return SourceText(AtlasIndex.decode(bytes, file.encoding).lines(), sha256(bytes) != file.sha256)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
