//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * The identifiers written in the source files, by a cheap lexical pass: unquoted words upper-cased, quoted words
 * exact. Comments and string literals are not skipped, so the set may hold more than the code names; it is only used to
 * narrow what is read from the database.
 */
object SourceIdentifiers {
    private val WORD = Regex("\"([^\"\\r\\n]+)\"|([A-Za-z][A-Za-z0-9_$#]*)")

    fun scan(root: Path, extensions: List<String>): Set<String> {
        val accepted = extensions.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        val names = HashSet<String>()
        Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() && it.extension.lowercase(Locale.ROOT) in accepted }.forEach { file ->
                // Identifiers are ASCII in practice: Latin-1 decodes any byte, whatever the file's encoding.
                val text = String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)
                for (match in WORD.findAll(text)) {
                    names += match.groups[1]?.value ?: match.value.uppercase(Locale.ROOT)
                }
            }
        }
        return names
    }
}
