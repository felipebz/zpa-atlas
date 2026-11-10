//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.server

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.query.AtlasIndex
import java.nio.file.Files
import java.nio.file.Path

/** The fixture index the server tests share: built once per test JVM, in a folder removed when the JVM exits. */
internal object ServerFixture {
    val temp: Path = Files.createTempDirectory("atlas-server-test").also { folder ->
        Runtime.getRuntime().addShutdownHook(Thread { folder.toFile().deleteRecursively() })
    }
    val database: Path = Fixture.index(temp.resolve("fixture.db"))
    val index = AtlasIndex(database, Fixture.sources)
}

internal val runJob5 = "db:HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE,P_E IN NUMBER)"
