//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `self` in a type body is the object, declared as a parameter or not, and when no schema is known:
 * hr/ty_peg.typ, hr/ty_tray.typ and hr/ty_tray.tpb indexed alone, without a compilation schema.
 */
class ImplicitSelfTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy {
        val sources = Files.createDirectories(temp.resolve("src"))
        for (name in listOf("ty_peg.typ", "ty_tray.typ", "ty_tray.tpb")) Files.copy(Fixture.sources.resolve("hr/$name"), sources.resolve(name))
        val output = temp.resolve("self.db")
        Indexer().index(IndexOptions(sources, output, compilationSchema = null, dictionary = AtlasDictionary.NONE, threads = 1))
        output
    }

    /** The occurrences of [line] of the type body but its type uses: written text, entity and roles. */
    private fun line(line: Int): List<String> = Fixture.query(
        db, """SELECT o.written || ' ' || coalesce(e.key, '-') || ' ' || o.roles FROM occurrence o JOIN source_file f ON f.id = o.file_id
               LEFT JOIN entity e ON e.id = o.entity_id
               WHERE f.path = 'ty_tray.tpb' AND o.start_line = $line AND coalesce(e.kind, '-') <> 'TYPE' ORDER BY o.start_col"""
    )

    private val items = "db:~.TY_TRAY.ITEMS"
    private val slots = "db:~.TY_TRAY.SLOTS"
    private val tag = "db:~.TY_PEG.TAG"

    @Test
    fun `an attribute written through the implicit self is a write, with no call`() {
        for (number in listOf(5, 6, 13)) assertThat(line(number)).describedAs("line $number").containsExactly("items $items WRITE")
        assertThat(line(14)).containsExactly("items $items WRITE", "items $items READ")
        for (number in listOf(8, 15)) assertThat(line(number)).describedAs("line $number").containsExactly("slots $slots WRITE", "tag $tag WRITE")
        assertThat(line(25)).containsExactly("slots $slots READ", "tag $tag READ")
    }

    @Test
    fun `the implicit self gives the rows of an explicit one`() {
        assertThat(line(19)).isEqualTo(line(13))
        assertThat(line(20)).isEqualTo(line(14))
        assertThat(line(21)).isEqualTo(line(15))
    }
}
