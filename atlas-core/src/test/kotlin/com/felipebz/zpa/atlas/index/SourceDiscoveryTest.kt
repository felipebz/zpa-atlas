//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.forms.FormsModule
import com.felipebz.zpa.atlas.forms.FormsModuleReader
import com.felipebz.zpa.atlas.forms.FormsModuleReaders
import com.felipebz.zpa.atlas.forms.Frmf2xmlReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class SourceDiscoveryTest {
    @TempDir
    lateinit var temp: Path

    /** `frmf2xml` text under another ending; an empty file makes it throw. */
    private object ThrowingReader : FormsModuleReader {
        override val name = "tfmb"
        override val fileNameEndings = listOf(".tfmb")
        override fun read(fileName: String, bytes: ByteArray): FormsModule? =
            if (bytes.isEmpty()) throw IllegalStateException("no blocks\nsecond line") else Frmf2xmlReader.read(fileName, bytes)
    }

    private val readers = FormsModuleReaders.of(listOf(Frmf2xmlReader, ThrowingReader))

    private val module = javaClass.getResourceAsStream("/forms/order_desk_fmb.xml")!!.use { it.readAllBytes() }

    private val root: Path by lazy {
        val root = temp.resolve("src")
        val db = root.resolve("db").createDirectories()
        for (i in 0 until 40) db.resolve("p_%02d.sql".format(i)).writeText("CREATE PROCEDURE p_$i IS BEGIN NULL; END;\n".repeat(1 + i * 37))
        val forms = root.resolve("forms").createDirectories()
        for (i in 0 until 30) forms.resolve("m_%02d_fmb.xml".format(i)).writeBytes(module)
        forms.resolve("shared_olb.xml").writeText("<Module/>")
        // One identity, ORDERS, in four files: one that throws, one that holds no module, the module, and a copy of it.
        root.resolve("a").createDirectories().resolve("orders.tfmb").writeBytes(ByteArray(0))
        root.resolve("b").createDirectories().resolve("orders_fmb.xml").writeText("<Module")
        root.resolve("c").createDirectories().resolve("orders_fmb.xml").writeBytes(module)
        root.resolve("d").createDirectories().resolve("orders.tfmb").writeBytes(module)
        root.resolve("e").createDirectories().resolve(".tfmb").writeBytes(module)
        root
    }

    private fun discover(threads: Int) = SourceDiscovery.discover(root, IndexOptions.DEFAULT_EXTENSIONS, SourceDecoding.AUTO, readers, threads)

    private fun shown(discovered: SourceDiscovery.Discovered): List<String> =
        discovered.sources.map { "${it.relativePath} ${it.kind} ${it.sha256} ${it.size} ${it.charset} ${it.lineCount} ${it.text.hashCode()}" } +
            discovered.notes.map { "${it.kind}: ${it.message}" } + discovered.outsideRoot

    @Test
    fun `the sources, their order and the notes do not depend on the threads`() {
        val one = shown(discover(1))
        assertThat(one).hasSize(71 + 3)
        for (threads in listOf(2, 8, 64)) assertThat(shown(discover(threads))).`as`("$threads threads").isEqualTo(one)
    }

    @Test
    fun `of the files of one identity, the first by path that holds a module is indexed`() {
        val discovered = discover(8)
        assertThat(discovered.sources.map { it.relativePath }.filter { "orders" in it }).containsExactly("c/orders_fmb.xml")
        assertThat(discovered.notes.map { "${it.kind}: ${it.message}" }).containsExactly(
            "FORMS_FILE_SKIPPED: 1 object library file(s) not indexed: forms/shared_olb.xml",
            "FORMS_FILE_NOT_READ: 3 file(s) held no module their reader could read: a/orders.tfmb (tfmb: IllegalStateException: no blocks), " +
                "b/orders_fmb.xml (frmf2xml), e/.tfmb (tfmb: no module name)",
            "FORMS_MODULE_DUPLICATED: 1 module file(s) not indexed: a file of the same module, first by path, is: d/orders.tfmb (indexed from c/orders_fmb.xml)"
        )
    }
}
