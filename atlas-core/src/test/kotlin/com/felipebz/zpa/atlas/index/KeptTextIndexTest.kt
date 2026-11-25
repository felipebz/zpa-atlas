//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.writer.SourceTextIndex
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

/** The text index an incremental run keeps beside the index, used again while the texts are the same. */
class KeptTextIndexTest {
    @TempDir
    lateinit var temp: Path

    private val sources: Path by lazy {
        val root = temp.resolve("src")
        Files.walk(Fixture.sources.resolve("hr")).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach {
                it.copyTo(root.resolve(Fixture.sources.resolve("hr").relativize(it).toString()).also { target -> target.parent.createDirectories() })
            }
        }
        root
    }

    private val db: Path get() = temp.resolve("atlas.db")

    private val kept: Path get() = SourceTextIndex.keptFor(db)

    /** Indexes [sources] into [output]; with [incremental], whether the kept text index was used. */
    private fun index(output: Path = db, incremental: Boolean = true): Boolean? {
        val indexer = Indexer()
        val options = IndexOptions(sources, output, compilationSchema = "HR", dictionary = Fixture.dictionary, threads = 2)
        indexer.readSources(sources, options.extensions, options.encoding, output, keepText = incremental).use { read ->
            indexer.index(options, incremental = incremental, read = read)
            return read.textIndex.reused
        }
    }

    @Test
    fun `an unchanged run uses the kept text index, a changed or damaged one builds it again, and the index is a full run's`() {
        assertThat(index()).isFalse()
        assertThat(kept).exists()
        assertThat(index()).isTrue()
        assertSameRows(db, full("full1.db"))

        // A file's text changed: built again, and kept for the next run.
        val file = sources.resolve("pack_ddl.pkb")
        Files.writeString(file, Files.readString(file).replace("compile body", "compile  body"))
        assertThat(index()).isFalse()
        assertSameRows(db, full("full2.db"))
        assertThat(index()).isTrue()

        // The kept file altered, then cut short: never used.
        RandomAccessFile(kept.toFile(), "rw").use { it.seek(it.length() / 2); it.write(it.read() xor 0xFF) }
        assertThat(index()).isFalse()
        assertSameRows(db, full("full3.db"))
        RandomAccessFile(kept.toFile(), "rw").use { it.setLength(it.length() - 4096) }
        assertThat(index()).isFalse()
        assertThat(index()).isTrue()
        assertSameRows(db, full("full4.db"))
    }

    @Test
    fun `a run without incremental neither uses nor writes the kept text index`() {
        assertThat(index(incremental = false)).isFalse()
        assertThat(kept).doesNotExist()
        assertThat(temp.toFile().list()!!.filter { it.endsWith(".tmp") }).isEmpty()
    }

    private fun full(name: String): Path = temp.resolve(name).also { index(it, incremental = false) }

    private fun assertSameRows(first: Path, second: Path) {
        val tables = Fixture.query(first, "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
        assertThat(tables).contains("source_line", "source_line_data", "source_line_idx", "source_line_content", "source_line_config")
        assertThat(Fixture.query(second, "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")).isEqualTo(tables)
        for (table in tables) {
            val sql = when (table) {
                "run" -> "SELECT id, schema_version, atlas_version, zpa_version, sources_root, metadata_origin, compilation_schema FROM run"
                "source_line" -> "SELECT rowid, * FROM source_line ORDER BY rowid"
                "source_line_data", "source_line_idx", "source_line_config" -> "SELECT * FROM $table ORDER BY 1, 2"
                else -> "SELECT * FROM $table ORDER BY rowid"
            }
            assertThat(Fixture.query(second, sql)).describedAs(table).isEqualTo(Fixture.query(first, sql))
        }
    }
}
