//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.forms.FormsModule
import com.felipebz.zpa.atlas.forms.FormsModuleReader
import com.felipebz.zpa.atlas.forms.FormsModuleReaders
import com.felipebz.zpa.atlas.forms.Frmf2xmlReader
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * A reader that says the identity of its modules itself: its `.vfmb` files (`frmf2xml` text) are named
 * `<module>~<copy>.vfmb`, and the module is what comes before the `~`.
 */
class FormsModuleNameTest {
    @TempDir
    lateinit var temp: Path

    private object CopyReader : FormsModuleReader {
        override val name = "vfmb"
        override val fileNameEndings = listOf(".vfmb")
        override fun read(fileName: String, bytes: ByteArray): FormsModule? = Frmf2xmlReader.read(fileName, bytes)
        override fun moduleName(fileName: String) = fileName.substringBefore('~').uppercase()
    }

    private val readers = FormsModuleReaders.of(listOf(Frmf2xmlReader, CopyReader))

    private val bytes = javaClass.getResourceAsStream("/forms/order_desk_fmb.xml")!!.use { it.readAllBytes() }

    private val db: Path by lazy {
        val root = temp.resolve("src/forms").createDirectories()
        // Two copies of FRONT_DESK, and a file whose name gives no module.
        for (name in listOf("front_desk~2.vfmb", "front_desk~1.vfmb", "~3.vfmb")) Files.write(root.resolve(name), bytes)
        temp.resolve("atlas.db").also { Indexer().index(IndexOptions(temp.resolve("src"), it, compilationSchema = "HR", threads = 1, readers = readers)) }
    }

    @Test
    fun `the reader's identity is the module's key, its paths and its text`() {
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE kind = 'FORMS_MODULE'")).containsExactly("forms:FRONT_DESK")
        assertThat(Fixture.query(db, "SELECT u.name FROM unit u JOIN source_file f ON f.id = u.file_id ORDER BY u.id LIMIT 1"))
            .containsExactly("FRONT_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM")
        // What the server rebuilds for the file page.
        assertThat(readers.text("front_desk~1.vfmb", bytes)!!.lines().first()).isEqualTo("BEGIN -- FRONT_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM")
    }

    @Test
    fun `two files of the same identity are one module, and a blank identity is not read`() {
        assertThat(Fixture.query(db, "SELECT path FROM source_file")).containsExactly("forms/front_desk~1.vfmb")
        assertThat(Fixture.query(db, "SELECT kind || ': ' || message FROM coverage_note WHERE kind LIKE 'FORMS%' ORDER BY kind")).containsExactly(
            "FORMS_FILE_NOT_READ: 1 file(s) held no module their reader could read: forms/~3.vfmb (vfmb: no module name)",
            "FORMS_MODULE_DUPLICATED: 1 module file(s) not indexed: a file of the same module, first by path, is: " +
                "forms/front_desk~2.vfmb (indexed from forms/front_desk~1.vfmb)"
        )
        assertThat(readers.moduleName("~3.vfmb")).isNull()
        assertThat(readers.text("~3.vfmb", bytes)).isNull()
    }
}
