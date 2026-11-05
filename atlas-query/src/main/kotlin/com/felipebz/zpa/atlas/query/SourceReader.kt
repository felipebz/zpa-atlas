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

/**
 * Source snippets and the file view, read from [sourcesRoot] over one open connection; a Forms module's text is
 * rebuilt from its file by [moduleTexts].
 */
internal class SourceReader(private val connection: Connection, private val sourcesRoot: Path?, private val moduleTexts: ModuleTexts? = null) {

    /** [AtlasIndex.source] after its checks; with [colorer], each line's colors. */
    fun source(path: String, line: Int, context: Int, colorer: CodeColorer? = null): SourceSnippet? {
        val file = sourceFile(path)
        val text = readSource(file) ?: return null
        val first = (line - context).coerceAtLeast(1)
        val last = (line + context).coerceAtMost(text.lines.size)
        val numbers = first..last
        val colors = colorer?.let { lineColors(it, text, numbers.associateWith { number -> text.lines[number - 1] }) }
        return SourceSnippet(path, numbers.map { SourceLine(it, text.lines[it - 1]) }, text.stale, colors?.let { byLine -> numbers.map { byLine[it].orEmpty() } })
    }

    /**
     * The line each occurrence of [ids] starts on, as indexed ([AtlasIndex.occurrenceLines]): the text of its
     * `source_line` row, so the occurrence's span is on it whether or not the file is served or changed since. [stale]
     * says whether the file changed (null when the server cannot read it), each file read once; with [colorer], the
     * colors as the text search gives them. An id the index does not have is left out.
     */
    fun occurrenceLines(ids: List<Long>, colorer: CodeColorer?): List<OccurrenceLine> {
        if (ids.isEmpty()) return emptyList()
        val found = connection.query(
            "SELECT o.id, o.file_id, f.path, o.start_line FROM occurrence o JOIN source_file f ON f.id = o.file_id WHERE o.id IN (${ids.joinToString(",") { "?" }})",
            ids
        ) { OccurrenceAt(it.getLong(1), it.getLong(2), it.getString(3), it.getInt(4)) }
        val result = HashMap<Long, OccurrenceLine>()
        for ((fileId, rows) in found.groupBy { it.fileId }) {
            val file = served(rows.first().path)
            val texts = indexedLines(fileId, rows.mapTo(HashSet()) { it.line })
            val colors = if (colorer == null || texts.isEmpty()) null else lineColors(colorer, file, texts)
            for (row in rows) {
                val text = texts[row.line]
                result[row.id] = OccurrenceLine(row.id, text, file?.stale, if (text == null) null else colors?.get(row.line))
            }
        }
        return ids.mapNotNull { result[it] }
    }

    /**
     * Where the target [written] is on [text]: at [at] when it starts there, else the last time it is written before
     * [at] (the target before its `:=`), else the first; null when the line does not have it.
     */
    private fun targetOn(text: String, written: String, at: Int): Pair<Int, Int>? {
        if (written.isEmpty()) return null
        val starts = generateSequence(text.indexOf(written, ignoreCase = true).takeIf { it >= 0 }) { previous ->
            text.indexOf(written, previous + 1, ignoreCase = true).takeIf { it >= 0 }
        }.toList()
        val start = starts.firstOrNull { it == at } ?: starts.lastOrNull { it < at } ?: starts.firstOrNull() ?: return null
        return start to start + written.length
    }

    private class OccurrenceAt(val id: Long, val fileId: Long, val path: String, val line: Int)

    /**
     * [lines] with their indexed text, as [occurrenceLines] reads an occurrence's: each file read once, its changed
     * flag and, with [colorer], its colors; a mark that runs past its line ends with it. A line the index holds no text
     * for is left as it was.
     */
    fun placeLines(lines: Collection<PlaceLine>, colorer: CodeColorer?): Map<PlaceLine, PlaceLine> {
        val result = HashMap<PlaceLine, PlaceLine>()
        for ((path, same) in lines.groupBy { it.path }) {
            val fileId = connection.query("SELECT id FROM source_file WHERE path = ?", listOf(path)) { it.getLong(1) }.singleOrNull() ?: continue
            val file = served(path)
            val texts = indexedLines(fileId, same.mapTo(HashSet()) { it.line })
            val colors = if (colorer == null || texts.isEmpty()) null else lineColors(colorer, file, texts)
            for (line in same) {
                val text = texts[line.line] ?: continue
                val (from, to) = line.target?.let { targetOn(text, it, line.from) } ?: (line.from to (line.to ?: text.length).coerceAtMost(text.length))
                result[line] = line.copy(from = from, to = to, text = text, stale = file?.stale, colors = colors?.get(line.line), target = null)
            }
        }
        return result
    }

    /**
     * The indexed text of [lines] of the file [fileId], from `source_line`: its rowids count every line of every file
     * in file order (a line shorter than three characters has no row), so a line's rowid is the file's first rowid plus
     * the line less one. The file's first rowid is found by a binary search on rowids, each step one rowid lookup.
     */
    private fun indexedLines(fileId: Long, lines: Set<Int>): Map<Int, String> {
        fun firstFrom(rowid: Long) = connection.query(
            "SELECT rowid, file_id, line FROM source_line WHERE rowid >= ? ORDER BY rowid LIMIT 1", listOf(rowid)
        ) { Triple(it.getLong(1), it.getLong(2), it.getInt(3)) }.firstOrNull()
        var low = 1L
        var high = connection.query("SELECT coalesce(max(rowid), 0) FROM source_line") { it.getLong(1) }.single() + 1
        // The first row whose file is [fileId] or later.
        while (low < high) {
            val middle = low + (high - low) / 2
            val row = firstFrom(middle)
            if (row == null || row.second >= fileId) high = middle else low = row.first + 1
        }
        val first = firstFrom(low)?.takeIf { it.second == fileId } ?: return emptyMap()
        val base = first.first - (first.third - 1)
        val result = HashMap<Int, String>()
        for (line in lines) {
            connection.query("SELECT file_id, line, text FROM source_line WHERE rowid = ?", listOf(base + line - 1)) {
                if (it.getLong(1) == fileId && it.getInt(2) == line) it.getString(3) else null
            }.firstOrNull()?.let { result[line] = it }
        }
        return result
    }

    /**
     * The colors of [lines] (line number to its text) as `[startCol, endCol, color]` on each line: from the whole of
     * [file], read once, when it is served as indexed; from each line alone when the file changed since indexing or
     * cannot be read (the whole text would not be the one the line came from). A line the colorer cannot read has none.
     */
    fun lineColors(colorer: CodeColorer, file: SourceText?, lines: Map<Int, String>): Map<Int, List<List<Int>>> {
        val result = HashMap<Int, List<List<Int>>>()
        if (file != null && !file.stale) {
            val spans = colorsOf(colorer, file.text) ?: return result
            val byLine = HashMap<Int, MutableList<List<Int>>>()
            for (span in spans) {
                val (startLine, startCol, endLine, endCol, color) = span
                for (line in startLine..endLine) {
                    val text = lines[line] ?: continue
                    val from = if (line == startLine) startCol else 0
                    val to = if (line == endLine) endCol else text.length
                    if (to > from) byLine.getOrPut(line) { ArrayList() } += listOf(from, to, color)
                }
            }
            for (line in lines.keys) result[line] = byLine[line].orEmpty()
        } else {
            for ((line, text) in lines) {
                val spans = colorsOf(colorer, text) ?: continue
                result[line] = spans.filter { it[0] == 1 }.map { listOf(it[1], if (it[2] == 1) it[3] else text.length, it[4]) }
            }
        }
        return result
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
    fun colorsOf(colorer: CodeColorer, text: String): List<List<Int>>? {
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
     * The file [path] as the server reads it now, or null when there is no sources root, no such file, or the file now
     * resolves outside the sources root: a search answers its other rows, and never reads that file.
     */
    fun served(path: String): SourceText? = try {
        readSource(sourceFile(path))
    } catch (_: LeavesRootException) {
        null
    }

    private class SourceFile(val id: Long, val path: String, val sha256: String, val encoding: String, val parseStatus: String, val kind: String)

    /** A file's [text] as decoded, its [lines], and whether it changed since indexing. */
    class SourceText(val text: String, val lines: List<String>, val stale: Boolean)

    private fun sourceFile(path: String): SourceFile =
        connection.query("SELECT id, sha256, encoding, parse_status, kind FROM source_file WHERE path = ?", listOf(path)) {
            SourceFile(it.getLong(1), path, it.getString(2), it.getString(3), it.getString(4), it.getString(5))
        }.singleOrNull() ?: throw InvalidQueryException("Not a file of the index: $path")

    private class LeavesRootException(path: String) : InvalidQueryException("Path leaves the sources root: $path")

    /**
     * The lines of [file] under the sources root, or null when there is no root or no such file. The path is checked
     * by its name, then by where it really leads: a symbolic link (of the file or of a folder above it) that leaves the
     * real sources root is refused, one that stays inside is followed. A Forms module's lines are its module text,
     * rebuilt from the file as the indexer built it ([ModuleTexts]); null when no reader reads it now.
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
        val text = if (file.kind == FORMS_MODULE) {
            moduleTexts?.text(resolved.fileName.toString(), bytes) ?: return null
        } else {
            // The charset the indexer decoded this file with, so lines and columns match the stored spans.
            AtlasIndex.decode(bytes, file.encoding)
        }
        return SourceText(text, text.lines(), sha256(bytes) != file.sha256)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val FORMS_MODULE = "FORMS_MODULE"

        /** What an object's list shows under it: no parameters, fields or local declarations. */
        val MEMBER_KINDS_SQL = listOf("PROCEDURE", "FUNCTION", "TYPE", "CURSOR", "PACKAGE_CONSTANT", "PACKAGE_VARIABLE", "EXCEPTION")
            .joinToString { "'$it'" }
    }
}
