//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * The projects of a folder: every `<name>.yaml` directly in it, read as `index --project` reads it. [warnings] are
 * the files left unread (`.yml`).
 */
class ProjectFolder(val folder: Path, val projects: List<ProjectFile>, val warnings: List<String>) {
    companion object {
        /**
         * The projects of [folder], read once.
         *
         * @throws CliValidationException listing every refused file, or when the folder holds no project file or two
         *     projects write the same index file
         */
        fun read(folder: Path): ProjectFolder {
            if (!Files.isDirectory(folder)) throw CliValidationException("Projects folder does not exist or is not a directory: $folder")
            val files = Files.list(folder).use { entries -> entries.filter { Files.isRegularFile(it) }.sorted().toList() }
            val warnings = files.filter { it.name.endsWith(".yml") }.map {
                "Not read: $it; a project file is named <name>.yaml: rename it to ${it.name.removeSuffix(".yml")}.yaml"
            }
            val projects = ArrayList<ProjectFile>()
            val refused = ArrayList<String>()
            for (file in files.filter { it.name.endsWith(".yaml") }) {
                try {
                    projects += ProjectFile.read(file)
                } catch (e: CliValidationException) {
                    refused += e.message.orEmpty()
                }
            }
            if (refused.isNotEmpty()) {
                throw CliValidationException(
                    "${if (refused.size == 1) "A project file is" else "${refused.size} project files are"} refused in $folder:\n" +
                        refused.joinToString("\n") { "  $it" }
                )
            }
            if (projects.isEmpty()) throw CliValidationException("No project file in $folder: a project is described by <name>.yaml")
            projects.groupBy { Path.of(it["index"]!!).toAbsolutePath().normalize() }.filterValues { it.size > 1 }.entries.firstOrNull()?.let { (index, same) ->
                throw CliValidationException("The projects ${same.joinToString(" and ") { it.name }} write the same index file: $index")
            }
            return ProjectFolder(folder, projects, warnings)
        }
    }
}
