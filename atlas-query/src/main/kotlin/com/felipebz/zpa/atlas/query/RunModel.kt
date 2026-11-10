//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query


import kotlinx.serialization.Serializable

/** The run an answer comes from; every API response carries it. */
@Serializable
data class RunStamp(
    val finishedAt: String,
    val atlasVersion: String,
    val zpaVersion: String,
    val compilationSchema: String?,
    val metadataOrigin: String?
)

/** A query result with the run it was read from. */
@Serializable
data class Answer<T>(val run: RunStamp, val result: T)

@Serializable
data class Count(val value: String, val count: Long)

@Serializable
data class RunSummary(
    val schemaVersion: Int,
    val sourcesRoot: String,
    val startedAt: String,
    val filesByParseStatus: List<Count>,
    val occurrencesByStatus: List<Count>,
    val coverageByKind: List<Count>,
    /** The files whose parse failed or was recovered, by path: at most [PARSE_PROBLEMS_SHOWN] of [parseProblemTotal]. */
    val parseProblems: List<FileParse>,
    val parseProblemTotal: Long
) {
    companion object {
        /** The most files [parseProblems] lists: enough to read, few enough for a broken index not to send thousands. */
        const val PARSE_PROBLEMS_SHOWN = 50
    }
}

@Serializable
data class EntitySummary(
    val key: String,
    val kind: String,
    val display: String,
    val external: Boolean,
    /** The entity's own name (`entity.name`), the end of [display]: `P_A`, `RUN_JOB`, `NOTIFY_REQ@FAR.EXAMPLE.COM`. */
    val name: String
)

/** One-based lines, zero-based columns, exclusive end column, as stored. */
@Serializable
data class Span(val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int)

/**
 * A definition or declaration: where, its roles and status, and the kind of the unit it sits in ([unitKind]: `PACKAGE`
 * for a package spec, `PACKAGE_BODY` for its body, `TYPE` and `TYPE_BODY` likewise; null outside any unit).
 */
@Serializable
data class Location(
    val path: String,
    val span: Span,
    val roles: List<String>,
    val status: String,
    val written: String,
    val unitKind: String?,
    /** In a Forms module, the code the location is in and its line there ([CodeAt]); null elsewhere. */
    val code: CodeAt? = null
)

/**
 * Where a line of a Forms module's text is in the code it belongs to (a trigger, a program unit, a record group query):
 * the code's entity [key], its [unitKind] (`PACKAGE` or `PACKAGE_BODY` for a package's halves, else null) and the
 * [line] in that code's own text, numbered from 1 as the Form Builder numbers it. A line of the header the module text
 * adds before the code is its line 1.
 */
@Serializable
data class CodeAt(val key: String, val line: Int, val unitKind: String? = null)
