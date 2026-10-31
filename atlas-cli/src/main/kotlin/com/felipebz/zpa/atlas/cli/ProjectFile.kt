//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import com.felipebz.zpa.atlas.server.TAB_WIDTHS
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * A project: one codebase, indexed into one index file from its own sources with its own options. It is described by
 * `<name>.yaml`, whose keys are the options of `index` that make the index (not `--threads`, `--incremental` or `--phase-times`, the machine's),
 * so that one file rebuilds the same index every time. Its paths are relative to the file's folder.
 *
 * Every value is kept as the option's text (a list joined with commas), to be checked by `index` as the option is.
 */
class ProjectFile(
    /** The project's name: the file's name without `.yaml`. */
    val name: String,
    /** The file, as it was given. */
    val file: Path,
    /** The file's keys (`oracle.url` for a nested one) and their values as the text of the option each one stands for. */
    val values: Map<String, String>
) {
    /** The file's [key] and its option's text, or null when the file leaves the key out. */
    operator fun get(key: String): String? = values[key]

    /** The project's readable name, one line; its [name] stays what URLs and the MCP's `project` argument use. */
    val title: String? get() = values["title"]

    /** What the codebase is, in a sentence or two, for the people and agents that pick a project. */
    val description: String? get() = values["description"]

    /** How wide the project's pages show a tab, in spaces (2, 4 or 8), or null when the file leaves it to `serve`. */
    val tabWidth: Int? get() = values["tabWidth"]?.toInt()

    companion object {
        /** A project name: it goes into URLs and into an MCP argument. */
        private val NAME = Regex("[a-z0-9][a-z0-9_-]*")

        private const val EXTENSION = ".yaml"

        /** The keys of a project file and the option each one stands for, in the order they are listed. */
        val KEYS: Map<String, String> = linkedMapOf(
            "sources" to "--sources",
            "index" to "--out",
            "extensions" to "--extensions",
            "encoding" to "--encoding",
            "compilationSchema" to "--compilation-schema",
            "databaseVersion" to "--database-version",
            "ccflags" to "--ccflags",
            "oracle.url" to "--oracle-url",
            "oracle.user" to "--oracle-user",
            "oracle.passwordEnv" to "--oracle-password-env",
            "oracle.schemas" to "--oracle-schemas",
            "oracle.relations" to "--oracle-relations"
        )

        /** The keys that describe the project to people and agents, not options of `index`, with their longest length. */
        private val DESCRIPTIVE: Map<String, Int> = linkedMapOf("title" to 80, "description" to 500)

        /** The key that sets how the project's code is shown, not an option of `index`: the width of a tab. */
        private const val TAB_WIDTH = "tabWidth"

        private val REQUIRED = listOf("sources", "index")

        /** The options of `index` that belong to the machine, by the key a project file might give them under. */
        private val MACHINE_OPTIONS = mapOf("threads" to "--threads", "incremental" to "--incremental", "phaseTimes" to "--phase-times")

        /** The keys whose value is a list: a YAML list, or the comma-separated text the option takes. */
        private val LISTS = setOf("extensions", "oracle.schemas", "oracle.relations")

        /** The keys that are paths, relative to the file's folder. */
        private val PATHS = setOf("sources", "index")

        /** The project file [file], read and checked: its name, its keys and the type of each value. */
        fun read(file: Path): ProjectFile {
            val shown = file.toString()
            val fileName = file.fileName?.toString().orEmpty()
            if (!fileName.endsWith(EXTENSION)) {
                throw CliValidationException("$shown: a project file is named after its project, <name>$EXTENSION")
            }
            val name = fileName.removeSuffix(EXTENSION)
            if (!NAME.matches(name)) {
                throw CliValidationException(
                    "$shown: the project name '$name' must be lower-case letters, digits, '-' and '_', starting with a letter or a digit " +
                        "(it goes into URLs and into an MCP argument)"
                )
            }
            if (!Files.isRegularFile(file)) throw CliValidationException("Project file does not exist: $shown")
            val text = try {
                // Windows editors and PowerShell write UTF-8 with a byte order mark: it is not part of the first key.
                Files.readString(file).removePrefix("\uFEFF")
            } catch (e: IOException) {
                throw CliValidationException("$shown: cannot be read: ${e.message}")
            }
            val root = try {
                Yaml.default.parseToYamlNode(text)
            } catch (e: YamlException) {
                throw CliValidationException("$shown, line ${e.line}: ${e.message}")
            }
            val values = linkedMapOf<String, String>()
            val folder = file.toAbsolutePath().parent
            read(shown, untagged(root), "", folder, values)
            REQUIRED.filter { it !in values }.forEach { throw CliValidationException("$shown: the key '$it' is required") }
            return ProjectFile(name, file, values)
        }

        private fun read(shown: String, node: YamlNode, prefix: String, folder: Path, values: MutableMap<String, String>) {
            val map = node as? YamlMap
                ?: throw CliValidationException(
                    if (prefix.isEmpty()) "$shown: a project file is a map of keys: ${topKeys().joinToString(", ")}"
                    else "$shown, line ${node.location.line}: ${prefix.removeSuffix(".")} is a map of keys: ${keysUnder(prefix).joinToString(", ")}"
                )
            for ((keyNode, valueNode) in map.entries) {
                val key = prefix + keyNode.content
                val line = keyNode.location.line
                MACHINE_OPTIONS[keyNode.content]?.let { option ->
                    throw CliValidationException("$shown, line $line: $key belongs to the machine, not to the project: give $option to index instead")
                }
                if (keyNode.content.contains("password", ignoreCase = true) && keyNode.content != "passwordEnv") {
                    throw CliValidationException(
                        "$shown, line $line: $key: the password never goes in the file; " +
                            "name the environment variable that holds it in oracle.passwordEnv"
                    )
                }
                val value = untagged(valueNode)
                if (key == "oracle") {
                    read(shown, value, "oracle.", folder, values)
                    continue
                }
                DESCRIPTIVE[key]?.let { max ->
                    values[key] = descriptive(shown, key, value, max)
                    continue
                }
                if (key == TAB_WIDTH) {
                    val width = text(shown, key, value, Path.of("")).trim()
                    if (width.toIntOrNull() !in TAB_WIDTHS) {
                        throw CliValidationException("$shown, line ${value.location.line}: $key is 2, 4 or 8, the spaces a tab shows as; it is '$width'")
                    }
                    values[key] = width
                    continue
                }
                if (key !in KEYS) {
                    val known = if (prefix.isEmpty()) topKeys() else keysUnder(prefix).map { prefix + it }
                    throw CliValidationException("$shown, line $line: unknown key '$key'; the keys are: ${known.joinToString(", ")}")
                }
                values[key] = text(shown, key, value, folder)
            }
        }

        /** The option's text of the value of [key]: a scalar, or for a list key a YAML list or comma-separated text. */
        private fun text(shown: String, key: String, node: YamlNode, folder: Path): String {
            val line = node.location.line
            // Empty is not a way to unset a key (leaving it out is): an empty path would be the file's own folder.
            val empty = node is YamlNull || node is YamlScalar && node.content.isBlank() || node is YamlList && node.items.isEmpty()
            if (empty) throw CliValidationException("$shown, line $line: $key has no value")
            return when (node) {
                is YamlScalar -> if (key in PATHS) folder.resolve(node.content).normalize().toString() else node.content
                is YamlList -> {
                    if (key !in LISTS) throw CliValidationException("$shown, line $line: $key takes one value, not a list")
                    node.items.joinToString(",") { item ->
                        (untagged(item) as? YamlScalar)?.content
                            ?: throw CliValidationException("$shown, line ${item.location.line}: $key is a list of single values")
                    }
                }
                else -> throw CliValidationException(
                    "$shown, line $line: $key takes ${if (key in LISTS) "a list, or comma-separated text" else "one value"}, not a map"
                )
            }
        }

        /** The text of a [DESCRIPTIVE] key: one value of at most [max] characters, and for `title` one line. */
        private fun descriptive(shown: String, key: String, node: YamlNode, max: Int): String {
            val line = node.location.line
            val text = text(shown, key, node, Path.of("")).trim()
            if (key == "title" && text.any { it == '\n' || it == '\r' }) throw CliValidationException("$shown, line $line: title is one line")
            if (text.length > max) throw CliValidationException("$shown, line $line: $key is at most $max characters; it has ${text.length}")
            return text
        }

        private fun untagged(node: YamlNode): YamlNode = if (node is YamlTaggedNode) untagged(node.innerNode) else node

        private fun topKeys() = KEYS.keys.map { it.substringBefore('.') }.distinct() + DESCRIPTIVE.keys + TAB_WIDTH

        private fun keysUnder(prefix: String) = KEYS.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }
}
