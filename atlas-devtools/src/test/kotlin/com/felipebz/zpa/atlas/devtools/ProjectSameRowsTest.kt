//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.devtools

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import com.felipebz.zpa.atlas.cli.execute as zpaAtlas

class ProjectSameRowsTest {
    @TempDir
    lateinit var temp: Path

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `a project file builds the same rows as the options it stands for`() {
        // The sources next to the file, so that the file names them by a relative path.
        val sources = temp.resolve("src")
        Fixture.sources.copyToRecursively(sources, followLinks = false, overwrite = false)
        val projectFile = temp.resolve("conf/fixture.yaml")
        Files.createDirectories(projectFile.parent)
        Files.writeString(
            projectFile,
            """
            sources: ../src
            index: ../out/project.db
            extensions: [sql, pks, pkb, trg, typ, tps, tpb]
            compilationSchema: HR
            databaseVersion: "19"
            ccflags: "debug:TRUE, level:2"
            """.trimIndent()
        )
        val fromOptions = temp.resolve("options.db")

        val byOptions = zpaAtlas(
            arrayOf(
                "index", "--sources", sources.toString(), "--out", fromOptions.toString(), "--extensions", "sql,pks,pkb,trg,typ,tps,tpb",
                "--compilation-schema", "HR", "--database-version", "19", "--ccflags", "debug:TRUE, level:2", "--threads", "2"
            )
        ) { null }
        val byProject = zpaAtlas(arrayOf("index", "--project", projectFile.toString(), "--threads", "2")) { null }

        assertThat(listOf(byOptions, byProject)).containsExactly(0, 0)
        val result = SameRows.compare(fromOptions, temp.resolve("out/project.db"))
        assertThat(result.lines).allMatch { it.contains(": same (") }
        assertThat(result.identical).isTrue()
        assertThat(result.lines).anyMatch { it.startsWith("occurrence: same (") && !it.startsWith("occurrence: same (0 rows)") }
    }
}
