//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class ProjectFileTest {
    @TempDir
    lateinit var directory: Path

    private fun file(name: String, text: String): Path =
        directory.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, text.trimIndent() + "\n") }

    private fun refusal(name: String, text: String): String {
        val file = file(name, text)
        return assertThatThrownBy { ProjectFile.read(file) }.isInstanceOf(CliValidationException::class.java).actual().message!!
    }

    @Test
    fun `every key is read as the text of its option, and paths are relative to the file's folder`() {
        val file = file(
            "conf/hr.yaml", """
            sources: ../src/hr
            index: ../data/hr.db
            extensions: [sql, pks, pkb]
            encoding: windows-1252
            compilationSchema: HR
            databaseVersion: "19"
            ccflags: "debug:TRUE, level:2"
            oracle:
              url: jdbc:oracle:thin:@//db.example.com:1521/orcl
              user: atlas_reader
              passwordEnv: HR_DB_PASSWORD
              schemas: [hr, common]
              relations: [other.some_table]
            """
        )

        val project = ProjectFile.read(file)

        assertThat(project.name).isEqualTo("hr")
        assertThat(project.values).containsExactlyEntriesOf(linkedMapOf(
            "sources" to directory.resolve("src/hr").toAbsolutePath().normalize().toString(),
            "index" to directory.resolve("data/hr.db").toAbsolutePath().normalize().toString(),
            "extensions" to "sql,pks,pkb",
            "encoding" to "windows-1252",
            "compilationSchema" to "HR",
            "databaseVersion" to "19",
            "ccflags" to "debug:TRUE, level:2",
            "oracle.url" to "jdbc:oracle:thin:@//db.example.com:1521/orcl",
            "oracle.user" to "atlas_reader",
            "oracle.passwordEnv" to "HR_DB_PASSWORD",
            "oracle.schemas" to "hr,common",
            "oracle.relations" to "other.some_table"
        ))
    }

    @Test
    fun `a list may also be the comma-separated text of its option, and an absolute path stays as it is`() {
        val absolute = directory.resolve("elsewhere/src").toAbsolutePath()
        val file = file(
            "hr.yaml", """
            sources: '$absolute'
            index: hr.db
            extensions: sql, pks,pkb
            oracle:
              url: jdbc:oracle:thin:@//db.example.com:1521/orcl
              user: atlas_reader
              schemas: "hr, common"
              relations:
                - other.some_table
            """
        )

        val project = ProjectFile.read(file)

        assertThat(project["sources"]).isEqualTo(absolute.normalize().toString())
        assertThat(project["index"]).isEqualTo(directory.resolve("hr.db").toAbsolutePath().normalize().toString())
        assertThat(project["extensions"]).isEqualTo("sql, pks,pkb")
        assertThat(project["oracle.schemas"]).isEqualTo("hr, common")
        assertThat(project["oracle.relations"]).isEqualTo("other.some_table")
        assertThat(project["encoding"]).isNull()
    }

    @Test
    fun `a title and a description are read, trimmed, and are no option of index`() {
        val project = ProjectFile.read(file("core.yaml", "sources: src\nindex: x.db\ntitle: '  Core platform '\ndescription: >\n  The main codebase:\n  billing, jobs and reports.\n"))

        assertThat(project.name).isEqualTo("core")
        assertThat(project.title).isEqualTo("Core platform")
        assertThat(project.description).isEqualTo("The main codebase: billing, jobs and reports.")
        assertThat(ProjectFile.KEYS.keys).doesNotContain("title", "description")
        assertThat(ProjectFile.read(file("bare.yaml", "sources: src\nindex: x.db")).title).isNull()
    }

    @Test
    fun `a title is one line of at most 80 characters, a description at most 500, neither blank nor a list`() {
        assertThat(ProjectFile.read(file("ok.yaml", "sources: src\nindex: x.db\ntitle: ${"t".repeat(80)}\ndescription: ${"d".repeat(500)}")).title).hasSize(80)
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\ntitle: ${"t".repeat(81)}")).endsWith("hr.yaml, line 3: title is at most 80 characters; it has 81")
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\ndescription: ${"d".repeat(501)}"))
            .endsWith("hr.yaml, line 3: description is at most 500 characters; it has 501")
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\ntitle: |\n  Core\n  platform")).endsWith("hr.yaml, line 3: title is one line")
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\ntitle: '  '")).endsWith("hr.yaml, line 3: title has no value")
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\ndescription:")).endsWith("hr.yaml, line 3: description has no value")
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\ntitle: [a, b]")).endsWith("hr.yaml, line 3: title takes one value, not a list")
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\ndescription:\n  a: b")).endsWith("hr.yaml, line 4: description takes one value, not a map")
    }

    @Test
    fun `a byte order mark before the first key is not part of it`() {
        val file = directory.resolve("hr.yaml")
        Files.writeString(file, "\uFEFFsources: src\nindex: x.db\n")

        assertThat(ProjectFile.read(file).values.keys).containsExactly("sources", "index")
    }

    @Test
    fun `a name is lower-case letters, digits, hyphens and underscores, starting with a letter or a digit`() {
        for (name in listOf("hr", "hr-2", "a_b", "9lives")) {
            assertThat(ProjectFile.read(file("$name.yaml", "sources: src\nindex: x.db")).name).isEqualTo(name)
        }
        // "hé" too, but a file of that name cannot be created on a machine whose locale is not UTF-8.
        val accented = runCatching { directory.resolve("hé.yaml") }.isSuccess
        for (name in listOf("HR", "-hr", "_hr", "h r", "hr.v2") + listOfNotNull("hé".takeIf { accented })) {
            assertThat(refusal("$name.yaml", "sources: src\nindex: x.db"))
                .contains("the project name '$name' must be lower-case letters, digits, '-' and '_', starting with a letter or a digit")
        }
        assertThat(refusal("hr.yml", "sources: src\nindex: x.db")).endsWith("hr.yml: a project file is named after its project, <name>.yaml")
    }

    @Test
    fun `an unknown key is refused with the known keys, at the top and under oracle`() {
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\nsorces: src")).endsWith(
            "hr.yaml, line 3: unknown key 'sorces'; the keys are: sources, index, extensions, encoding, compilationSchema, databaseVersion, ccflags, oracle, title, description"
        )
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\noracle:\n  urll: x")).endsWith(
            "hr.yaml, line 4: unknown key 'oracle.urll'; the keys are: oracle.url, oracle.user, oracle.passwordEnv, oracle.schemas, oracle.relations"
        )
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\nthreads: 4"))
            .endsWith("hr.yaml, line 3: threads belongs to the machine, not to the project: give --threads to index instead")
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\nphaseTimes: true"))
            .endsWith("hr.yaml, line 3: phaseTimes belongs to the machine, not to the project: give --phase-times to index instead")
    }

    @Test
    fun `a password is refused wherever it is, pointing to passwordEnv`() {
        val expected = "the password never goes in the file; name the environment variable that holds it in oracle.passwordEnv"
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\noracle:\n  password: secret")).endsWith("hr.yaml, line 4: oracle.password: $expected")
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\nPassword: secret")).endsWith("hr.yaml, line 3: Password: $expected")
    }

    @Test
    fun `a value of the wrong type, a missing value and a missing required key are refused`() {
        assertThat(refusal("hr.yaml", "sources: [a, b]\nindex: x.db")).endsWith("hr.yaml, line 1: sources takes one value, not a list")
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\nextensions:\n  sql: true"))
            .endsWith("hr.yaml, line 4: extensions takes a list, or comma-separated text, not a map")
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\nextensions: [[sql]]")).endsWith("hr.yaml, line 3: extensions is a list of single values")
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\noracle: x"))
            .endsWith("hr.yaml, line 3: oracle is a map of keys: url, user, passwordEnv, schemas, relations")
        assertThat(refusal("hr.yaml", "sources: src\nindex:")).endsWith("hr.yaml, line 2: index has no value")
        assertThat(refusal("hr.yaml", "sources: \"\"\nindex: x.db")).endsWith("hr.yaml, line 1: sources has no value")
        assertThat(refusal("hr.yaml", "sources: src\nindex: \"  \"")).endsWith("hr.yaml, line 2: index has no value")
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\nextensions: []")).endsWith("hr.yaml, line 3: extensions has no value")
        assertThat(refusal("hr.yaml", "sources: src\nindex: x.db\ncompilationSchema: \"\"")).endsWith("hr.yaml, line 3: compilationSchema has no value")
        assertThat(refusal("hr.yaml", "index: x.db")).endsWith("hr.yaml: the key 'sources' is required")
        assertThat(refusal("hr.yaml", "- sources")).endsWith("hr.yaml: a project file is a map of keys: sources, index, extensions, encoding, compilationSchema, databaseVersion, ccflags, oracle, title, description")
        assertThat(refusal("hr.yaml", "sources: src\nsources: other\nindex: x.db")).contains("hr.yaml, line 2: Duplicate key 'sources'")
    }

    /** The exit code of `zpa-atlas` with [args], and what it printed to stderr. */
    private fun run(vararg args: String, environment: (String) -> String? = { null }): Pair<Int, String> {
        val stderr = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
        try {
            return execute(arrayOf(*args), environment) to stderr.toString(Charsets.UTF_8)
        } finally {
            System.setErr(original)
        }
    }

    private fun project(extra: String = ""): Path {
        Files.createDirectories(directory.resolve("src"))
        Files.writeString(directory.resolve("src/unit.sql"), "begin\n  \$if \$\$debug \$then null; \$end\n  null;\nend;\n/\n")
        return file("conf/hr.yaml", "sources: ../src\nindex: ../data/hr.db\n$extra")
    }

    @Test
    fun `a value the option refuses is refused with the option's message, naming the file and the key`() {
        val cases = mapOf(
            "databaseVersion: abc" to "conf/hr.yaml: databaseVersion: ",
            "ccflags: x" to "conf/hr.yaml: ccflags: ",
            "encoding: no-such-charset" to "conf/hr.yaml: encoding: Unknown encoding: no-such-charset",
            "compilationSchema: '\"'" to "conf/hr.yaml: Invalid compilationSchema name",
            "oracle:\n  user: u\n  schemas: [hr]" to "conf/hr.yaml: oracle.user, oracle.schemas and oracle.relations require oracle.url",
            "oracle:\n  url: jdbc:oracle:thin:@//h:1521/s\n  user: u" to "conf/hr.yaml: oracle.url requires oracle.schemas or oracle.relations",
            "oracle:\n  url: jdbc:oracle:thin:@//h:1521/s\n  user: u\n  schemas: [hr]\n  passwordEnv: HR_DB_PASSWORD" to
                "conf/hr.yaml: oracle.passwordEnv: The database password environment variable 'HR_DB_PASSWORD' is not set"
        )
        for ((extra, message) in cases) {
            val (code, stderr) = run("index", "--project", project(extra).toString())
            assertThat(code).describedAs(extra).isEqualTo(2)
            assertThat(stderr.replace('\\', '/')).describedAs(extra).contains(message)
        }
        val noSources = file("conf/gone.yaml", "sources: ../no-such-folder\nindex: ../data/gone.db")
        assertThat(run("index", "--project", noSources.toString()).second.replace('\\', '/'))
            .contains("conf/gone.yaml: sources: Sources folder does not exist or is not a directory: ")
        // The same values as options keep the option's message.
        assertThat(run("index", "--sources", directory.toString(), "--out", directory.resolve("x.db").toString(), "--database-version", "abc").second)
            .startsWith("--database-version: ")
    }

    @Test
    fun `an option beside the project file wins over its key, and the run line names the project`() {
        val file = project("databaseVersion: \"19\"\nccflags: \"debug:TRUE\"")
        val elsewhere = directory.resolve("elsewhere.db")

        val (code, stderr) = run("index", "--project", file.toString(), "--out", elsewhere.toString(), "--database-version", "11.2", "--threads", "1")

        assertThat(code).describedAs(stderr).isEqualTo(0)
        assertThat(stderr).contains("Indexed project hr: 1 files into $elsewhere: scan ")
        assertThat(directory.resolve("data/hr.db")).doesNotExist()
        DriverManager.getConnection("jdbc:sqlite:$elsewhere").use { connection ->
            connection.createStatement().executeQuery("SELECT database_version, database_version_origin, ccflags FROM run").use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(listOf(rows.getString(1), rows.getString(2), rows.getString(3))).containsExactly("11.2", "OPTION", "DEBUG:TRUE")
            }
        }
    }
}
