//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.devtools

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

    private fun run(vararg args: String): Pair<Int, String> {
        val err = ByteArrayOutputStream()
        val previous = System.err
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        try {
            return execute(arrayOf(*args)) { null } to err.toString(Charsets.UTF_8)
        } finally {
            System.setErr(previous)
        }
    }

    @Test
    fun `compare-deps refuses a required option left out or empty`() {
        val db = Files.createFile(temp.resolve("atlas.db")).toString()
        val out = temp.resolve("out").toString()
        for ((args, option) in listOf(
            arrayOf("compare-deps") to "--db",
            arrayOf("compare-deps", "--out", out) to "--db",
            arrayOf("compare-deps", "--db", "", "--out", out) to "--db",
            arrayOf("compare-deps", "--db", db) to "--out",
            arrayOf("compare-deps", "--db", db, "--out", "") to "--out"
        )) {
            val (code, err) = run(*args)
            assertThat(code).describedAs(args.joinToString(" ")).isEqualTo(2)
            assertThat(err.trim()).describedAs(args.joinToString(" ")).isEqualTo("The option is required: $option")
        }
        assertThat(temp.resolve("out")).doesNotExist()
    }
}
