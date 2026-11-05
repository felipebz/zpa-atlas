//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.forms

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * The module readers of a run: which reads a file, by the end of its name. No two readers may claim the same files:
 * an ending of one that ends with an ending of another (`_fmb.xml` and `.xml`) is refused when the set is made.
 */
class FormsModuleReaders private constructor(
    val readers: List<FormsModuleReader>,
    /** The providers in the readers folder that could not be loaded (a broken or incompatible JAR), skipped. */
    val unloadable: Int
) {
    /** The reader of [fileName], or null when no reader reads it. */
    fun readerOf(fileName: String): FormsModuleReader? {
        val name = fileName.lowercase(Locale.ROOT)
        return readers.firstOrNull { reader -> reader.fileNameEndings.any { name.endsWith(it.lowercase(Locale.ROOT)) } }
    }

    /**
     * The name a module is known by: [fileName] without the ending its reader claims, in upper case
     * (`demo0004_fmb.xml` is `DEMO0004`), as `CALL_FORM` and `OPEN_FORM` name a form by its file. Null when no reader
     * reads [fileName]. The module's `Name` property is only its internal name.
     */
    fun moduleName(fileName: String): String? {
        val name = fileName.lowercase(Locale.ROOT)
        val ending = readers.firstNotNullOfOrNull { reader -> reader.fileNameEndings.firstOrNull { name.endsWith(it.lowercase(Locale.ROOT)) } } ?: return null
        return fileName.substring(0, fileName.length - ending.length).uppercase(Locale.ROOT)
    }

    /** The module text of the file [fileName] holding [bytes], as the indexer builds it; null when no reader reads a module in it. */
    fun text(fileName: String, bytes: ByteArray): String? {
        val module = readerOf(fileName)?.read(fileName, bytes) ?: return null
        return FormsModuleText.of(module, moduleName(fileName)!!).text
    }

    /** What [fileName] is when a reader knows its format but does not index it (`object library`), else null. */
    fun skipped(fileName: String): String? {
        val name = fileName.lowercase(Locale.ROOT)
        for (reader in readers) for ((ending, what) in reader.skippedFileNameEndings) if (name.endsWith(ending.lowercase(Locale.ROOT))) return what
        return null
    }

    /** The readers' names and classes, in order: what the facts cache compares between runs. */
    val description: String get() = readers.joinToString(",") { "${it.name}=${it.javaClass.name}" }

    companion object {
        /** The built-in reader only. */
        val BUILT_IN = of(listOf(Frmf2xmlReader))

        /** [readers], checked: no two claim the same files. */
        fun of(readers: List<FormsModuleReader>, unloadable: Int = 0): FormsModuleReaders {
            val endings = readers.flatMap { reader -> (reader.fileNameEndings + reader.skippedFileNameEndings.keys).map { it.lowercase(Locale.ROOT) to reader } }
            for ((ending, reader) in endings) {
                require(ending.isNotEmpty()) { "The module reader ${reader.name} claims an empty file name ending" }
                for ((other, otherReader) in endings) {
                    if (otherReader !== reader && ending.endsWith(other)) {
                        throw IllegalArgumentException(
                            "The module readers ${reader.name} and ${otherReader.name} both read the files whose name ends with '$ending'")
                    }
                }
            }
            return FormsModuleReaders(readers, unloadable)
        }

        /**
         * The built-in reader, then the readers the JARs of [folder] register for [ServiceLoader] under
         * [FormsModuleReader] (`META-INF/services/com.felipebz.zpa.atlas.forms.FormsModuleReader`), in the order of the
         * JARs' names. A provider that cannot be loaded is skipped and counted ([unloadable]); a missing folder has none.
         *
         * @throws IllegalArgumentException when two readers claim the same files
         */
        fun load(folder: Path?): FormsModuleReaders {
            val jars = folder?.takeIf { Files.isDirectory(it) }?.let { directory ->
                Files.list(directory).use { paths -> paths.filter { it.isRegularFile() && it.extension.equals("jar", ignoreCase = true) }.sorted().toList() }
            }.orEmpty()
            if (jars.isEmpty()) return BUILT_IN
            val loader = URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), FormsModuleReaders::class.java.classLoader)
            val found = ArrayList<FormsModuleReader>()
            var unloadable = 0
            val providers = ServiceLoader.load(FormsModuleReader::class.java, loader).iterator()
            while (true) {
                val reader = try {
                    if (!providers.hasNext()) break
                    providers.next()
                } catch (_: ServiceConfigurationError) {
                    unloadable++
                    continue
                }
                if (found.none { it.javaClass == reader.javaClass }) found += reader
            }
            return of(listOf(Frmf2xmlReader) + found, unloadable)
        }
    }
}
