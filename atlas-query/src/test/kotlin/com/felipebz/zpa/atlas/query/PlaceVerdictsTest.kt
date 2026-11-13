//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

@file:OptIn(ZpaExperimentalApi::class)

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.api.annotations.ZpaExperimentalApi
import com.felipebz.zpa.atlas.SizesFixture
import com.felipebz.zpa.atlas.lookupTables
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/** The verdict of each place against the column's size or a size given, with either unit for a length written without one ([SizesFixture]). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlaceVerdictsTest {
    private val temp: Path = Files.createTempDirectory("atlas-verdicts")
    private lateinit var bytes: AtlasIndex
    private lateinit var chars: AtlasIndex

    /**
     * A column of a table the sources do not create, read into a length, an anchor to another such column and a number;
     * the dictionary lists its columns, not their datatypes.
     */
    private val other = mapOf("read_other.prc" to """
        create or replace procedure read_other is
          v varchar2(5); w other_t.y%type; z number(3);
        begin
          select x, x, x into v, w, z from other_t;
        end;
        /
    """.trimIndent() + "\n")

    @BeforeAll
    fun build() {
        val columns = lookupTables("SHOP", SizesFixture.ORDERS, "OTHER_T" to listOf("X" to null, "Y" to null))
        bytes = AtlasIndex(SizesFixture.index(temp.resolve("byte"), more = other, dictionary = columns), temp.resolve("byte/src"))
        chars = AtlasIndex(SizesFixture.index(temp.resolve("char"), "CHAR"), temp.resolve("char/src"))
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private fun column(name: String) = "db:SHOP.ORDERS.$name"

    private fun rows(index: AtlasIndex, key: String, size: Int?) =
        index.columnPlaces(key, PlaceFilter(size, Verdict.entries.toSet(), concatenated = true)).result!!.rows.sortedWith(compareBy({ it.line }, { it.column }))

    /** Each place of [key] in line order: its target, its verdict against [size], why when not known, and the room left when told. */
    private fun verdicts(index: AtlasIndex, key: String, size: Int? = null): List<String> = rows(index, key, size).map {
        listOfNotNull(it.targetWritten, it.verdict, it.verdictReason, it.room?.let { room -> "room $room" }).joinToString(" ")
    }

    /** Each place of [key] with a size: the size compared, its unit and where the unit comes from. */
    private fun sizes(index: AtlasIndex, key: String): List<String> = rows(index, key, null).filter { it.size != null }.map {
        listOfNotNull(it.targetWritten, it.size, it.sizeUnit, it.unitSource).joinToString(" ")
    }

    @Test
    fun `each place compares with the column's size and with a size given, a length without a unit in bytes`() {
        assertThat(verdicts(bytes, column("CODE"))).containsExactly(
            "c1 SMALLER", "c2 FITS", "c3 FITS", "c4 FITS room 20", "c5 FOLLOWS", "c6 FITS", "c7 FITS", "r.code SMALLER", "p_out CALLER_DECIDES")
        assertThat(verdicts(bytes, column("CODE"), 20)).containsExactly(
            "c1 SMALLER", "c2 SMALLER", "c3 SMALLER", "c4 FITS room 10", "c5 FOLLOWS", "c6 SMALLER", "c7 FITS", "r.code SMALLER", "p_out CALLER_DECIDES")
        // Ten characters may take forty bytes: ten bytes neither fit for sure nor are shorter than ten characters.
        assertThat(verdicts(bytes, column("NOTE"))).containsExactly("n1 UNKNOWN UNIT_MISMATCH", "n2 SMALLER", "n3 FITS")
        assertThat(verdicts(bytes, column("NOTE"), 30)).containsExactly("n1 SMALLER", "n2 SMALLER", "n3 UNKNOWN UNIT_MISMATCH")
        // The digits before the point: NUMBER(6,2) holds 4 of the column's 6; a smaller scale alone is never smaller.
        assertThat(verdicts(bytes, column("AMOUNT"))).containsExactly("a1 SMALLER", "a2 FITS", "a3 FITS")
        assertThat(verdicts(bytes, column("AMOUNT"), 8)).containsExactly("a1 SMALLER", "a2 FITS", "a3 FITS")
        // The other column's size decides an anchor to it: LABEL's 10, in the index's unit since it writes none.
        assertThat(sizes(bytes, column("CODE"))).containsExactly(
            "c1 5 BYTE OPTION", "c2 10 BYTE OPTION", "c3 10 CHAR WRITTEN", "c4 30 BYTE OPTION", "c6 10 BYTE OPTION", "r.code 8 BYTE OPTION")
    }

    @Test
    fun `a length without a unit counts in characters when the index says so`() {
        assertThat(verdicts(chars, column("CODE"))).containsExactly(
            "c1 SMALLER", "c2 FITS", "c3 FITS", "c4 FITS room 20", "c5 FOLLOWS", "c6 FITS", "c7 FITS", "r.code SMALLER", "p_out CALLER_DECIDES")
        assertThat(verdicts(chars, column("CODE"), 20)).containsExactly(
            "c1 SMALLER", "c2 SMALLER", "c3 SMALLER", "c4 FITS room 10", "c5 FOLLOWS", "c6 SMALLER", "c7 FITS", "r.code SMALLER", "p_out CALLER_DECIDES")
        assertThat(verdicts(chars, column("NOTE"))).containsExactly("n1 UNKNOWN UNIT_MISMATCH", "n2 SMALLER", "n3 FITS")
        assertThat(sizes(chars, column("CODE"))).containsExactly(
            "c1 5 CHAR OPTION", "c2 10 CHAR OPTION", "c3 10 CHAR WRITTEN", "c4 30 CHAR OPTION", "c6 10 CHAR OPTION", "r.code 8 CHAR OPTION")
        assertThat(sizes(chars, column("NOTE"))).containsExactly("n1 10 BYTE WRITTEN", "n2 4 CHAR OPTION", "n3 40 BYTE WRITTEN")
    }

    @Test
    fun `the counts leave the concatenated places out unless asked, and tell the sizes that count in the index's default`() {
        val page = bytes.columnPlaceGroups(column("CODE")).result!!
        // The datatype as the dictionary writes it.
        assertThat(page.column).isEqualTo(ColumnSize("VARCHAR2(10 BYTE)", 10, "BYTE", null))
        assertThat(listOf(page.size, page.sizeGiven)).containsExactly(10, false)
        assertThat(page.counts.map { "${it.name} ${it.count}" }).containsExactly("SMALLER 2", "CALLER_DECIDES 1", "UNKNOWN 0", "FITS 4", "FOLLOWS 1")
        assertThat(listOf(page.optionUnit, page.optionSized, page.concatenatedCount)).containsExactly("BYTE", 4, 1)
        val joined = bytes.columnPlaceGroups(column("CODE"), PlaceFilter(concatenated = true)).result!!
        assertThat(joined.counts.map { "${it.name} ${it.count}" }).containsExactly("SMALLER 2", "CALLER_DECIDES 1", "UNKNOWN 0", "FITS 5", "FOLLOWS 1")
        assertThat(listOf(joined.optionSized, joined.concatenatedCount)).containsExactly(5, 1)
        assertThat(chars.columnPlaceGroups(column("CODE")).result!!.optionUnit).isEqualTo("CHAR")
    }

    @Test
    fun `a column whose size is not known has none to compare with until one is given`() {
        val key = "db:SHOP.OTHER_T.X"
        val page = bytes.columnPlaceGroups(key).result!!
        assertThat(page.column).isEqualTo(ColumnSize(null, null, null, null))
        assertThat(page.size).isNull()
        assertThat(page.counts.map { "${it.name} ${it.count}" })
            .containsExactly("SMALLER 0", "CALLER_DECIDES 0", "UNKNOWN 1", "FITS 0", "FOLLOWS 0", "OWN_SIZE 2")
        assertThat(verdicts(bytes, key)).containsExactly("v OWN_SIZE", "w UNKNOWN OTHER_COLUMN_UNKNOWN", "z OWN_SIZE")
        // A size given compares each target in its own unit.
        assertThat(verdicts(bytes, key, 4)).containsExactly("v FITS", "w UNKNOWN OTHER_COLUMN_UNKNOWN", "z SMALLER")
        assertThat(bytes.columnPlaceGroups(key, PlaceFilter(4)).result!!.counts.map { it.name }).doesNotContain("OWN_SIZE")
    }
}
