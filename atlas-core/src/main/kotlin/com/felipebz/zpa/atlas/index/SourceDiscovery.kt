//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.PlSqlFile
import com.felipebz.zpa.atlas.extract.sha256
import com.felipebz.zpa.atlas.forms.FormsLayout
import com.felipebz.zpa.atlas.forms.FormsModuleReader
import com.felipebz.zpa.atlas.forms.FormsModuleReaders
import com.felipebz.zpa.atlas.forms.FormsModuleText
import com.felipebz.zpa.atlas.model.CoverageFact
import com.felipebz.zpa.atlas.model.FileKind
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile

/**
 * A source: a database source decoded with its own charset ([SourceDecoding]), or a Forms module, whose [text] is
 * its module text ([FormsModuleText]) and whose [forms] says where each unit of it is. ZPA gets the text, never the
 * bytes: the charset of `PlSqlConfiguration`/`AstScanner` is only used by the lexer to read files and URLs, so one
 * value for the run is enough. The file's bytes are not kept: only their hash and size are written.
 */
internal class Source(
    val relativePath: String, val absolute: Path, val text: String, val charset: Charset, val sha256: String, val size: Long,
    val forms: FormsLayout? = null
) : PlSqlFile {
    /** `text.lines().size`, counted without making the lines: a line ends at `\r\n`, `\n` or `\r`. */
    val lineCount = lineCount(text)
    val kind: FileKind get() = if (forms == null) FileKind.DB_SOURCE else FileKind.FORMS_MODULE
    override fun contents(): String = text
    override fun fileName(): String = absolute.fileName.toString()
    override fun type(): PlSqlFile.Type = PlSqlFile.Type.MAIN
    override fun path(): Path = absolute
    override fun toString(): String = relativePath

    companion object {
        fun database(relativePath: String, absolute: Path, bytes: ByteArray, encoding: String): Source {
            val decoded = SourceDecoding.decode(bytes, encoding)
            return Source(relativePath, absolute, decoded.text, decoded.charset, sha256(bytes), bytes.size.toLong())
        }
    }
}

private fun lineCount(text: String): Int {
    var count = 1
    var i = 0
    while (i < text.length) {
        when (text[i]) {
            '\n' -> count++
            '\r' -> {
                count++
                if (i + 1 < text.length && text[i + 1] == '\n') i++
            }
        }
        i++
    }
    return count
}

/**
 * Finds and reads the sources of a run under its folder ([discover]): the database sources by extension, the Forms
 * modules by their readers, and the notes of the files it leaves out.
 */
internal object SourceDiscovery {
    /** What [discover] found: the sources, the files left out as links outside the folder, and the notes of what else it left out. */
    class Discovered(val sources: List<Source>, val outsideRoot: List<String>, val notes: List<CoverageFact>)

    /**
     * The sources under [root], read, in path order: the files one of [readers] reads, whatever their extension, and the
     * files with one of [extensions]; and the relative paths of the files left out because they are symbolic links that
     * lead outside the real [root]. A link that stays inside is read through; folder links are not walked into. The
     * server serves a file only where the same rule holds, so the index never holds the text of a file outside the
     * sources folder.
     *
     * A module is known by the identity its reader gives its file ([FormsModuleReaders.moduleName], by default the file's
     * name): of the files with the same identity, the first by path is indexed; a file whose reader gives a blank one
     * is not read.
     * The files a reader knows but does not index, the files that held no module and the modules left out for their
     * name are each counted in a note.
     */
    fun discover(root: Path, extensions: List<String>, encoding: String, readers: FormsModuleReaders): Discovered {
        require(Files.isDirectory(root)) { "Sources folder does not exist or is not a directory: $root" }
        val realRoot = root.toRealPath()
        val found = candidates(root, extensions, readers)
        val outside = ArrayList<String>()
        val sources = ArrayList<Source>()
        val skipped = TreeMap<String, MutableList<String>>()
        val modules = ModuleFiles(readers)
        for (path in found) {
            val relative = root.relativize(path).invariantSeparatorsPathString
            val real = path.toRealPath()
            if (!real.startsWith(realRoot)) {
                outside += relative
                continue
            }
            val name = path.fileName.toString()
            readers.skipped(name)?.let { what ->
                skipped.getOrPut(what) { ArrayList() } += relative
                continue
            }
            val reader = readers.readerOf(name)
            if (reader == null) {
                sources += Source.database(relative, path, Files.readAllBytes(real), encoding)
            } else {
                modules.read(relative, path, real, reader)?.let { sources += it }
            }
        }
        val notes = skipped.entries.map { CoverageFact("FORMS_FILE_SKIPPED", "${it.value.size} ${it.key} file(s) not indexed: ${shown(it.value)}") } +
            modules.notes()
        return Discovered(sources, outside.sorted(), notes)
    }

    /**
     * The regular files under [root] that one of [readers] reads or skips, or with one of [extensions], in relative path
     * order.
     */
    private fun candidates(root: Path, extensions: List<String>, readers: FormsModuleReaders): List<Path> {
        val accepted = extensions.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        return Files.walk(root).use { paths ->
            paths.filter { path ->
                path.isRegularFile() && path.fileName.toString().let { name ->
                    readers.readerOf(name) != null || readers.skipped(name) != null || path.extension.lowercase(Locale.ROOT) in accepted
                }
            }.toList()
        }.sortedBy { root.relativize(it).invariantSeparatorsPathString }
    }

    /**
     * The Forms module files [discover] reads with [readers]: the first file by path of each module identity, and the
     * files left out because they held no module or a module already read.
     */
    private class ModuleFiles(private val readers: FormsModuleReaders) {
        private val modules = HashMap<String, String>()
        private val notModules = ArrayList<String>()
        private val duplicates = ArrayList<String>()

        /** The source of the module [reader] reads in the file [path] ([relative] to the sources, [real] its real path); null when left out. */
        fun read(relative: String, path: Path, real: Path, reader: FormsModuleReader): Source? {
            val name = path.fileName.toString()
            // Known by its identity: two files with the same one are the same module.
            val moduleName = readers.moduleName(name)
            if (moduleName == null) {
                notModules += "$relative (${reader.name}: no module name)"
                return null
            }
            modules[moduleName]?.let { first ->
                duplicates += "$relative (indexed from $first)"
                return null
            }
            val bytes = Files.readAllBytes(real)
            val module = try {
                reader.read(name, bytes)
            } catch (e: Exception) {
                notModules += "$relative (${reader.name}: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull().orEmpty()})"
                return null
            }
            if (module == null) {
                notModules += "$relative (${reader.name})"
                return null
            }
            modules[moduleName] = relative
            val text = FormsModuleText.of(module, moduleName)
            return Source(relative, path, text.text, StandardCharsets.UTF_8, sha256(bytes), bytes.size.toLong(), FormsLayout.of(module, moduleName, text))
        }

        /** The notes of the files that held no module, then of the module files left out for a module already read. */
        fun notes(): List<CoverageFact> {
            val notes = ArrayList<CoverageFact>()
            if (notModules.isNotEmpty()) notes += CoverageFact("FORMS_FILE_NOT_READ", "${notModules.size} file(s) held no module their reader could read: ${shown(notModules)}")
            if (duplicates.isNotEmpty()) {
                notes += CoverageFact("FORMS_MODULE_DUPLICATED", "${duplicates.size} module file(s) not indexed: a file of the same module, first by path, is: ${shown(duplicates)}")
            }
            return notes
        }
    }

    /** The first items of [items], for a note. */
    private fun shown(items: List<String>): String = items.take(NOTE_SHOWN).joinToString(", ") + if (items.size > NOTE_SHOWN) ", …" else ""

    /** How many paths a note of [discover] names. */
    private const val NOTE_SHOWN = 20
}
