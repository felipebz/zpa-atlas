//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query


import kotlinx.serialization.Serializable

/**
 * One line a text search found: [line] of [path], its [text] as indexed, and [entity], the innermost container of the
 * line when the index knows it. [id] orders the lines (file, then line) and is what `after` takes. [stale] is true when
 * the file changed since indexing, null when the server cannot read it. [colors], when asked for, are the colored
 * spans of [text] as `[startCol, endCol, color]` (the [CodeColor] ordinal): read from the whole file, so a line inside a
 * comment or a string that opens above it is colored as such; read from the line alone when the file changed or
 * cannot be read; null when not asked or the colorer cannot read it.
 */
@Serializable
data class TextHit(
    val id: Long,
    val path: String,
    val line: Int,
    val text: String,
    val entity: EntitySummary?,
    val stale: Boolean?,
    val colors: List<List<Int>>? = null,
    /** In a Forms module, the code the line is in and its line there ([CodeAt]); null elsewhere. */
    val code: CodeAt? = null
)

/**
 * A page of a text search: [rows] in file and line order, [nextAfter] to pass as `after` for the next page (null on the
 * last), [total] lines found in [files] files, and [wholeIndex] when no path or container narrowed the search.
 * [fileLines] gives each file of [rows] its count of lines found, those of later pages included.
 */
@Serializable
data class TextPage(
    val rows: List<TextHit>,
    val nextAfter: Long?,
    val total: Long,
    val wholeIndex: Boolean,
    val files: Long,
    val fileLines: Map<String, Long>
)
