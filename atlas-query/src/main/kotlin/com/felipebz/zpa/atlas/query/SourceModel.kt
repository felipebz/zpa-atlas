//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query


import kotlinx.serialization.Serializable

/** Line [number] of a file and its [text]; in a Forms module, the code it is in and its line there ([code]). */
@Serializable
data class SourceLine(val number: Int, val text: String, val code: CodeAt? = null)

/**
 * Lines of a file around one line. [colors], when asked for, has one list per line of [lines], each span as
 * `[startCol, endCol, color]` (the [CodeColor] ordinal), read as [TextHit.colors] is; null when not asked or unreadable.
 */
@Serializable
data class SourceSnippet(
    val path: String,
    val lines: List<SourceLine>,
    val stale: Boolean,
    val colors: List<List<List<Int>>>? = null
)

/**
 * The line occurrence [id] starts on, as indexed, so its span is on [text]: null only when the index holds no text for
 * it (a line shorter than three characters). [stale] is true when the file changed since indexing, null when the server
 * cannot read it; [colors] as [TextHit.colors], when asked for.
 */
@Serializable
data class OccurrenceLine(val id: Long, val text: String?, val stale: Boolean?, val colors: List<List<Int>>? = null)

/**
 * One occurrence of a [FileView], compact: [at] is the span of its name as `[startLine, startCol, endLine, endCol]`
 * (lines from 1, columns from 0, end exclusive); [entity] and [candidates] are indexes into [FileView.entities].
 */
@Serializable
data class FileOccurrence(
    val at: List<Int>,
    val roles: List<String>,
    val status: String,
    val reason: String?,
    val entity: Int?,
    val candidates: List<Int>
)

/**
 * A whole indexed file: its [lines] and, unless it is [stale] (changed since it was indexed, so spans would point at
 * the wrong text), its occurrences, with every entity they refer to listed once in [entities], and its [objects].
 * [parseStatus] is how the indexer's parse went. [colors] are the colored spans of the text when they were asked for
 * and could be read, else null: each `[startLine, startCol, endLine, endCol, color]` with the [CodeColor] ordinal.
 * [kind] is the file's (`DB_SOURCE`, `FORMS_MODULE`: whose page is the code page of its units, [FormsFile]).
 */
@Serializable
data class FileView(
    val path: String,
    val stale: Boolean,
    val lines: List<String>,
    val entities: List<EntitySummary>,
    val occurrences: List<FileOccurrence>,
    val parseStatus: String,
    val objects: List<FileObject>,
    val colors: List<List<Int>>?,
    val kind: String
)

/**
 * An object a file declares at its top level (a package spec or body, a type or type body, a standalone routine, a
 * view, a trigger): its [unitKind], its [entity], the [line] it starts at, and the [members] it declares directly.
 */
@Serializable
data class FileObject(val unitKind: String, val entity: EntitySummary, val line: Int, val members: List<FileMember>)

/** A routine, type, cursor, constant, variable or exception an object declares directly, at [line] of the file. */
@Serializable
data class FileMember(val entity: EntitySummary, val line: Int)

/** What a span of code is, as the analyzer's highlighter colors it. */
enum class CodeColor { KEYWORD, LITERAL, COMMENT, DOC_COMMENT }

/**
 * Colors a file's text the way the analyzer reads it; the server is handed one by the command that starts it, so this
 * module does not depend on the analyzer. [ccflags] and [databaseVersion] are the run's, as stored. Answers the spans
 * as [FileView.colors] has them, or null when the text cannot be read.
 */
fun interface CodeColorer {
    fun colors(text: String, ccflags: String?, databaseVersion: String?): List<List<Int>>?
}

/**
 * Rebuilds the text the indexer read from a Forms module's file (`source_file.kind` `FORMS_MODULE`): the module text
 * the readers of the run make of the file's [bytes] ([fileName] is its name, without folders), or null when no reader
 * reads it. The command that starts the server hands one over, so this module does not depend on the readers.
 */
fun interface ModuleTexts {
    fun text(fileName: String, bytes: ByteArray): String?
}
