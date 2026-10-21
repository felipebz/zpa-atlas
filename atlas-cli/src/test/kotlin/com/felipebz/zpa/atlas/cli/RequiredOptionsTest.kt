//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class RequiredOptionsTest {
    @TempDir
    lateinit var temp: Path

    /** `zpa-atlas` with [args]: its exit code and what it wrote to stderr. */
    private fun run(vararg args: String): Pair<Int, String> {
        val err = ByteArrayOutputStream()
        val previous = System.err
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        try {
            return execute(arrayOf(*args), { null }) { error("nothing is served") } to err.toString(Charsets.UTF_8)
        } finally {
            System.setErr(previous)
        }
    }

    @Test
    fun `index refuses a required option left out or empty, before reading or writing anything`() {
        val sources = Files.createDirectory(temp.resolve("src"))
        Files.writeString(sources.resolve("p.prc"), "create or replace procedure p is begin null; end;\n/\n")
        val out = temp.resolve("out.db").toString()
        for ((args, option) in listOf(
            arrayOf("index") to "--sources",
            arrayOf("index", "--out", out) to "--sources",
            arrayOf("index", "--sources", "", "--out", out) to "--sources",
            arrayOf("index", "--sources", sources.toString()) to "--out",
            arrayOf("index", "--sources", sources.toString(), "--out", "") to "--out",
            arrayOf("index", "--sources", " ", "--out", out) to "--sources"
        )) {
            val (code, err) = run(*args)
            assertThat(code).describedAs(args.joinToString(" ")).isEqualTo(2)
            assertThat(err.trim()).describedAs(args.joinToString(" ")).isEqualTo("The option is required: $option")
        }
        assertThat(temp.resolve("out.db")).doesNotExist()
        assertThat(Files.list(temp).use { it.toList() }).containsExactly(sources)
    }

    @Test
    fun `index refuses an out that names a folder, and indexes into a file`() {
        val sources = Files.createDirectory(temp.resolve("src"))
        Files.writeString(sources.resolve("p.prc"), "create or replace procedure p is begin null; end;\n/\n")
        val folder = Files.createDirectory(temp.resolve("folder"))
        val (code, err) = run("index", "--sources", sources.toString(), "--out", folder.toString())
        assertThat(code).isEqualTo(2)
        assertThat(err.trim()).isEqualTo("--out names a folder: $folder; give the path of the index file to create")
        assertThat(Files.list(folder).use { it.toList() }).isEmpty()
        assertThat(run("index", "--sources", sources.toString(), "--out", temp.resolve("atlas.db").toString()).first).isEqualTo(0)
        assertThat(temp.resolve("atlas.db")).isRegularFile()
    }
}
