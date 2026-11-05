//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import com.felipebz.zpa.atlas.forms.FormsModuleReaders
import com.felipebz.zpa.atlas.query.ModuleTexts
import java.nio.file.Path

/**
 * The Forms module readers of the distribution, as `index` and `serve` both load them: the built-in one and those the
 * JARs of its `readers` folder register ([FormsModuleReaders.load]). Two readers that claim the same files stop the
 * command; a provider that cannot be loaded is skipped with a warning.
 */
internal fun moduleReaders(folder: Path = applicationHome().resolve("readers")): FormsModuleReaders {
    val readers = try {
        FormsModuleReaders.load(folder)
    } catch (e: IllegalArgumentException) {
        throw CliValidationException("${e.message} (${folder.toAbsolutePath().normalize()})")
    }
    if (readers.unloadable > 0) {
        System.err.println("Warning: ${readers.unloadable} module reader provider(s) of $folder could not be loaded and were skipped")
    }
    return readers
}

/** The text of a Forms module's file as [readers] and [FormsModuleText] make it: the one the indexer walked. */
internal class ReaderModuleTexts(private val readers: FormsModuleReaders) : ModuleTexts {
    override fun text(fileName: String, bytes: ByteArray): String? = try {
        readers.text(fileName, bytes)
    } catch (_: Exception) {
        null
    }
}
