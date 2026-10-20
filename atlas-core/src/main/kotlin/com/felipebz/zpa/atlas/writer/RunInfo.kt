//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

/** Run provenance written to the `run` table. */
data class RunInfo(
    val atlasVersion: String,
    val zpaVersion: String,
    val sourcesRoot: String,
    /** The `--encoding` option: `auto` or the forced charset name. */
    val sourceEncoding: String,
    val metadataOrigin: String?,
    val compilationSchema: String?,
    /** The target database version of conditional compilation (`11.2`), or null. */
    val databaseVersion: String? = null,
    /** `OPTION`, `DATABASE` or `JDBC` with a [databaseVersion], else null. */
    val databaseVersionOrigin: String? = null,
    /** The `PLSQL_CCFLAGS` of the run as `NAME:VALUE, …`, or null. */
    val ccflags: String? = null,
    val startedAt: String,
    val finishedAt: String
)
