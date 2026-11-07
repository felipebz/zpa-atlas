//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.api.project.ProjectExternalContext
import com.felipebz.zpa.api.project.ProjectExternalMetadata
import com.felipebz.zpa.api.project.ProjectIdentifier
import com.felipebz.zpa.api.project.ProjectSchemaPaths
import java.nio.file.Path

/**
 * The schemas the run's files compile in, as zpa gives them from `--compilation-schema` and `--schema-paths`: [context]
 * is the one the project is prepared with, so the atlas's keys and zpa's lookups agree.
 */
class FileSchemas(val context: ProjectExternalContext) {
    /** The exact name of the schema a unit of [fileId] written without an owner compiles in; null when none applies. */
    fun of(fileId: String): String? = context.fileSchema(fileId).map { exact(it) }.orElse(null)

    /** The mapping as `pattern=SCHEMA,…`, null without one: what the run records and the facts cache compares. */
    val paths: String? = context.schemaPaths.toString().ifEmpty { null }

    /** Every schema the options name: the default and the mapped ones. */
    val all: Set<String> = (listOfNotNull(context.defaultCompilationSchema.orElse(null)) + context.schemaPaths.entries.map { it.schema })
        .mapTo(LinkedHashSet()) { exact(it) }

    companion object {
        val NONE = FileSchemas(ProjectExternalContext.none())

        /**
         * [compilationSchema] is an exact dictionary name; [schemaPaths] is `pattern=SCHEMA,…` relative to [sources], as
         * zpa reads it (an invalid value throws [IllegalArgumentException]).
         */
        fun of(sources: Path, compilationSchema: String?, schemaPaths: String): FileSchemas {
            val paths = ProjectSchemaPaths.parse(sources, schemaPaths.trim())
            if (compilationSchema == null && paths.entries.isEmpty()) return NONE
            return FileSchemas(ProjectExternalContext.of(
                ProjectExternalMetadata.none(), compilationSchema?.let { ProjectIdentifier.fromSource("\"$it\"") }, paths
            ))
        }

        private fun exact(identifier: ProjectIdentifier): String = normalizeIdentifier(identifier.originalSpelling)
    }
}
