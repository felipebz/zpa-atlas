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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
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
     * The sources under [root], read on [threads] threads, in path order: the files one of [readers] reads, whatever
     * their extension, and the files with one of [extensions]; and the relative paths of the files left out because they
     * are symbolic links that lead outside the real [root]. A link that stays inside is read through; folder links are
     * not walked into. The server serves a file only where the same rule holds, so the index never holds the text of a
     * file outside the sources folder.
     *
     * A module is known by the identity its reader gives its file ([FormsModuleReaders.moduleName], by default the file's
     * name): of the files with the same identity, the first by path that holds a module is indexed; a file whose reader
     * gives a blank one is not read.
     * The files a reader knows but does not index, the files that held no module and the modules left out for their
     * name are each counted in a note.
     *
     * What it finds does not depend on [threads]: a file that cannot be read stops it with the error of the first such
     * file by path, and the files of one identity are read on one thread, in path order. Each thread holds the bytes of
     * one file at a time.
     */
    fun discover(root: Path, extensions: List<String>, encoding: String, readers: FormsModuleReaders, threads: Int): Discovered {
        require(Files.isDirectory(root)) { "Sources folder does not exist or is not a directory: $root" }
        val realRoot = root.toRealPath()
        val outside = ArrayList<String>()
        val skipped = TreeMap<String, MutableList<String>>()
        val toRead = ArrayList<ToRead>()
        val noName = ArrayList<Outcome>()
        val identities = HashMap<String, ModuleFiles>()
        for ((position, path) in candidates(root, extensions, readers).withIndex()) {
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
                toRead += DatabaseFile(position, relative, path, real, encoding)
                continue
            }
            // Known by its identity: two files with the same one are the same module.
            val moduleName = readers.moduleName(name)
            if (moduleName == null) {
                noName += Outcome(position, notRead = "$relative (${reader.name}: no module name)")
                continue
            }
            identities.getOrPut(moduleName) { ModuleFiles(moduleName).also { toRead += it } }.files += ModuleFile(position, relative, path, real, reader)
        }
        val outcomes = (read(toRead, threads) + noName).sortedBy { it.position }
        val notModules = outcomes.mapNotNull { it.notRead }
        val duplicates = outcomes.mapNotNull { it.duplicate }
        val notes = ArrayList<CoverageFact>()
        for ((what, files) in skipped) notes += CoverageFact("FORMS_FILE_SKIPPED", "${files.size} $what file(s) not indexed: ${shown(files)}")
        if (notModules.isNotEmpty()) notes += CoverageFact("FORMS_FILE_NOT_READ", "${notModules.size} file(s) held no module their reader could read: ${shown(notModules)}")
        if (duplicates.isNotEmpty()) {
            notes += CoverageFact("FORMS_MODULE_DUPLICATED", "${duplicates.size} module file(s) not indexed: a file of the same module, first by path, is: ${shown(duplicates)}")
        }
        return Discovered(outcomes.mapNotNull { it.source }, outside.sorted(), notes)
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

    /** What [toRead] gives, read on [threads] threads; the error of the first that fails, in their order, stops it. */
    private fun read(toRead: List<ToRead>, threads: Int): List<Outcome> {
        val count = threads.coerceIn(1, maxOf(toRead.size, 1))
        if (count == 1) return toRead.flatMap { it.read() }
        val executor = Executors.newFixedThreadPool(count)
        try {
            // Each task reads its file when it runs: the queue holds paths, never bytes.
            val pending = toRead.map { executor.submit(Callable { it.read() }) }
            return pending.flatMap { outcome ->
                try {
                    outcome.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /** What became of the file at [position] in path order: its [source], or why it is left out. */
    private class Outcome(val position: Int, val source: Source? = null, val notRead: String? = null, val duplicate: String? = null)

    /** What one thread reads at once: a database source, or the files of one module identity. */
    private sealed interface ToRead {
        fun read(): List<Outcome>
    }

    private class DatabaseFile(val position: Int, val relative: String, val path: Path, val real: Path, val encoding: String) : ToRead {
        override fun read() = listOf(Outcome(position, Source.database(relative, path, Files.readAllBytes(real), encoding)))
    }

    /** A Forms module file: [relative] to the sources, [real] its real path, read by [reader]. */
    private class ModuleFile(val position: Int, val relative: String, val path: Path, val real: Path, val reader: FormsModuleReader)

    /**
     * The files of the module identity [moduleName], in path order: they are read in that order until one holds a
     * module, which is indexed; the files after it are not read.
     */
    private class ModuleFiles(private val moduleName: String) : ToRead {
        val files = ArrayList<ModuleFile>()

        override fun read(): List<Outcome> {
            val outcomes = ArrayList<Outcome>()
            var first: String? = null
            for (file in files) {
                outcomes += if (first != null) Outcome(file.position, duplicate = "${file.relative} (indexed from $first)") else read(file)
                if (outcomes.last().source != null) first = file.relative
            }
            return outcomes
        }

        private fun read(file: ModuleFile): Outcome {
            val name = file.path.fileName.toString()
            val bytes = Files.readAllBytes(file.real)
            val module = try {
                file.reader.read(name, bytes)
            } catch (e: Exception) {
                return Outcome(file.position, notRead = "${file.relative} (${file.reader.name}: ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull().orEmpty()})")
            } ?: return Outcome(file.position, notRead = "${file.relative} (${file.reader.name})")
            val text = FormsModuleText.of(module, moduleName)
            val source = Source(file.relative, file.path, text.text, StandardCharsets.UTF_8, sha256(bytes), bytes.size.toLong(), FormsLayout.of(module, moduleName, text))
            return Outcome(file.position, source)
        }
    }

    /** The first items of [items], for a note. */
    private fun shown(items: List<String>): String = items.take(NOTE_SHOWN).joinToString(", ") + if (items.size > NOTE_SHOWN) ", …" else ""

    /** How many paths a note of [discover] names. */
    private const val NOTE_SHOWN = 20
}
