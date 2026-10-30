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

    fun file(path: String, colorer: CodeColorer?): FileView? {
        val file = sourceFile(path)
        val text = readSource(file) ?: return null
        // The colors are of the text served, stale or not: they come from reading it now, not from the index.
        val colors = colorer?.let { colorsOf(it, text.text) }
        if (text.stale) return FileView(path, true, text.lines, emptyList(), emptyList(), file.parseStatus, emptyList(), colors)
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
        return FileView(path, false, text.lines, entityIndexes.keys.map { summaries.getValue(it) }, occurrences, file.parseStatus, objects(file), colors)
    }

    /** The colors of [text] with the run's conditional compilation; none when the colorer fails on it. */
    private fun colorsOf(colorer: CodeColorer, text: String): List<List<Int>>? {
        val (ccflags, version) = connection.query("SELECT ccflags, database_version FROM run") { it.getString(1) to it.getString(2) }.single()
        return try {
            colorer.colors(text, ccflags, version)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The objects of [file]: each unit with an entity, at its first line, with the members its entity declares in that
     * unit (a definition or declaration whose entity's parent is the unit's entity), each at its first line, in file order.
     */
    private fun objects(file: SourceFile): List<FileObject> {
        val units = connection.query(
            """SELECT u.id, u.kind, u.start_line, e.key, e.kind, e.display, e.external, e.name
               FROM unit u JOIN entity e ON e.id = u.entity_id WHERE u.file_id = ? ORDER BY u.start_line, u.id""",
            listOf(file.id)
        ) { Triple(it.getLong(1), it.getString(2) to it.getInt(3), EntitySummary(it.getString(4), it.getString(5), it.getString(6), it.getInt(7) == 1, it.getString(8))) }
        val members = connection.query(
            """SELECT o.unit_id, min(o.start_line), m.key, m.kind, m.display, m.external, m.name
               FROM occurrence o JOIN unit u ON u.id = o.unit_id JOIN entity m ON m.id = o.entity_id
               WHERE o.file_id = ? AND o.roles IN ('DEFINITION', 'DECLARATION') AND m.parent_id = u.entity_id AND m.kind IN ($MEMBER_KINDS_SQL)
               GROUP BY o.unit_id, m.id ORDER BY 2, m.id""",
            listOf(file.id)
        ) { it.getLong(1) to FileMember(EntitySummary(it.getString(3), it.getString(4), it.getString(5), it.getInt(6) == 1, it.getString(7)), it.getInt(2)) }
            .groupBy({ it.first }, { it.second })
        return units.map { (id, unit, entity) -> FileObject(unit.first, entity, unit.second, members[id].orEmpty()) }
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

    private class SourceFile(val id: Long, val path: String, val sha256: String, val encoding: String, val parseStatus: String)

    /** A file's [text] as decoded, its [lines], and whether it changed since indexing. */
    private class SourceText(val text: String, val lines: List<String>, val stale: Boolean)

    private fun sourceFile(path: String): SourceFile =
        connection.query("SELECT id, sha256, encoding, parse_status FROM source_file WHERE path = ?", listOf(path)) {
            SourceFile(it.getLong(1), path, it.getString(2), it.getString(3), it.getString(4))
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
        val text = AtlasIndex.decode(bytes, file.encoding)
        return SourceText(text, text.lines(), sha256(bytes) != file.sha256)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** What an object's list shows under it: no parameters, fields or local declarations. */
        val MEMBER_KINDS_SQL = listOf("PROCEDURE", "FUNCTION", "TYPE", "CURSOR", "PACKAGE_CONSTANT", "PACKAGE_VARIABLE", "EXCEPTION")
            .joinToString { "'$it'" }
    }
}
