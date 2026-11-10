//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import java.nio.file.Files
import java.nio.file.Path

/** The `Fixture` index the entity, occurrence, source and value tests read, built once per test JVM. */
internal object SharedFixtureIndex {
    val db: Path by lazy {
        val dir = Files.createTempDirectory("atlas-shared")
        Runtime.getRuntime().addShutdownHook(Thread { dir.toFile().deleteRecursively() })
        Fixture.index(dir.resolve("fixture.db"))
    }
}

internal const val runJob5 = "db:HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE,P_E IN NUMBER)"
internal const val runJob4 = "db:HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE)"
internal const val pCode = "db:HR.PACK_ARGS.P(P_CODE IN NUMBER,P_NAME IN VARCHAR2,P_FLAG IN BOOLEAN).P_CODE"

internal fun FileView.at(line: Int, col: Int) =
    occurrences.filter { it.at[0] == line && it.at[1] == col }.map { "${it.at.joinToString(",")} ${it.entity?.let { e -> entities[e].key }} ${it.roles}" }
