//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class ServeProjectsTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var projects: Path

    @BeforeEach
    fun folder() {
        projects = Files.createDirectory(directory.resolve("projects"))
        Files.createDirectories(directory.resolve("src/hr"))
        Files.createDirectories(directory.resolve("data"))
        Files.writeString(directory.resolve("src/hr/unit.sql"), "begin\n  null;\nend;\n/\n")
        // The index of hr exists (any file: serve reads it only when asked); billing has none yet, nor sources here.
        Files.writeString(directory.resolve("data/hr.db"), "")
        project("hr", "sources: ../src/hr\nindex: ../data/hr.db")
        project("billing", "sources: ../src/billing\nindex: ../data/billing.db")
    }

    private fun project(name: String, text: String, extension: String = "yaml"): Path =
        Files.writeString(projects.resolve("$name.$extension"), text + "\n")

    private class Run(val code: Int, val stderr: String, val launch: ServeLaunch?)

    private fun serve(vararg args: String, environment: Map<String, String> = emptyMap()): Run {
        val stderr = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
        var launched: ServeLaunch? = null
        try {
            val code = execute(arrayOf("serve", *args), { environment[it] }) { launched = it }
            return Run(code, stderr.toString(Charsets.UTF_8).replace("\r\n", "\n"), launched)
        } finally {
            System.setErr(original)
        }
    }

    @Test
    fun `every project file of the folder is served, with one line each, and those not indexed or without sources are warned`() {
        val run = serve("--projects", projects.toString())

        assertThat(run.code).describedAs(run.stderr).isEqualTo(0)
        val launch = run.launch!!
        assertThat(launch.database).isNull()
        assertThat(launch.projects.map { it.name }).containsExactly("billing", "hr")
        assertThat(launch.projects[1].sources).isEqualTo(directory.resolve("src/hr").toAbsolutePath().normalize())
        assertThat(launch.projects[0].sources).isNull()
        val lines = run.stderr.lines()
        assertThat(lines[0]).isEqualTo("Serving 2 projects of $projects on http://127.0.0.1:8080/")
        assertThat(lines[1]).isEqualTo(
            "Project billing at http://127.0.0.1:8080/p/billing/: index ${directory.resolve("data/billing.db").toAbsolutePath().normalize()}, " +
                "sources not available; not indexed yet"
        )
        assertThat(lines[2]).startsWith("Project hr at http://127.0.0.1:8080/p/hr/: index ${directory.resolve("data/hr.db").toAbsolutePath().normalize()}, " +
            "sources ${directory.resolve("src/hr").toAbsolutePath().normalize()}; unavailable: ")
        assertThat(lines[3]).isEqualTo("MCP at http://127.0.0.1:8080/mcp, for Host localhost, 127.0.0.1, [::1]: every project, picked by the tools' project argument")
        assertThat(lines).contains(
            "Warning: project billing is not indexed yet: its index ${directory.resolve("data/billing.db").toAbsolutePath().normalize()} does not exist; " +
                "its API answers 503 until `zpa-atlas index --project ${projects.resolve("billing.yaml")}` writes it",
            "Warning: project billing: sources folder ${directory.resolve("src/billing").toAbsolutePath().normalize()} does not exist here; it is served without sources"
        )
    }

    @Test
    fun `a yml file is not read, and the warning says to rename it`() {
        project("legacy", "sources: ../src/hr\nindex: ../data/legacy.db", extension = "yml")

        val run = serve("--projects", projects.toString())

        assertThat(run.code).isEqualTo(0)
        assertThat(run.launch!!.projects.map { it.name }).containsExactly("billing", "hr")
        assertThat(run.stderr).contains("Warning: Not read: ${projects.resolve("legacy.yml")}; a project file is named <name>.yaml: rename it to legacy.yaml")
    }

    @Test
    fun `every refused project file is listed, and the server does not start`() {
        project("typo", "sources: ../src/hr\nindex: ../data/typo.db\nsorces: x")
        project("Bad Name", "sources: ../src/hr\nindex: ../data/x.db")

        val run = serve("--projects", projects.toString())

        assertThat(run.code).isEqualTo(2)
        assertThat(run.launch).isNull()
        assertThat(run.stderr).startsWith("2 project files are refused in $projects:\n")
            .contains("  ${projects.resolve("Bad Name.yaml")}: the project name 'Bad Name' must be lower-case letters")
            .contains("  ${projects.resolve("typo.yaml")}, line 3: unknown key 'sorces'")
    }

    @Test
    fun `a folder without project files, a missing folder and two projects with one index are refused`() {
        val empty = Files.createDirectory(directory.resolve("empty"))
        assertThat(serve("--projects", empty.toString()).stderr).startsWith("No project file in $empty: a project is described by <name>.yaml")
        assertThat(serve("--projects", directory.resolve("nope").toString()).stderr)
            .startsWith("Projects folder does not exist or is not a directory: ${directory.resolve("nope")}")
        project("copy", "sources: ../src/hr\nindex: ../data/hr.db")
        val same = serve("--projects", projects.toString())
        assertThat(same.code).isEqualTo(2)
        assertThat(same.stderr).startsWith("The projects copy and hr write the same index file: ${directory.resolve("data/hr.db").toAbsolutePath().normalize()}")
    }

    @Test
    fun `--db and --sources beside --projects on the command line are refused, and from the environment ignored with a line`() {
        for (option in listOf("--db", "--sources")) {
            val run = serve("--projects", projects.toString(), option, directory.toString())
            assertThat(run.code).describedAs(option).isEqualTo(2)
            assertThat(run.stderr).startsWith("$option and --projects both say what to serve: give --projects alone, as each project file names its index and sources")
        }
        val image = mapOf("ZPA_ATLAS_DB" to "/data/atlas.db", "ZPA_ATLAS_SOURCES" to "/sources", "ZPA_ATLAS_PROJECTS" to projects.toString())
        val run = serve(environment = image)
        assertThat(run.code).describedAs(run.stderr).isEqualTo(0)
        assertThat(run.stderr.lines().first()).isEqualTo("Ignoring ZPA_ATLAS_DB and ZPA_ATLAS_SOURCES: --projects says what to serve")
        assertThat(run.launch!!.projects.map { it.name }).containsExactly("billing", "hr")
    }

    @Test
    fun `without --projects, serve is the single index it was`() {
        val run = serve("--db", directory.resolve("data/hr.db").toString(), "--sources", directory.resolve("src/hr").toString())

        assertThat(run.code).isEqualTo(0)
        assertThat(run.launch!!.projects).isEmpty()
        assertThat(run.launch.database).isEqualTo(directory.resolve("data/hr.db"))
        assertThat(run.stderr.lines().first()).startsWith("Serving ${directory.resolve("data/hr.db")} on http://127.0.0.1:8080/")
    }
}
