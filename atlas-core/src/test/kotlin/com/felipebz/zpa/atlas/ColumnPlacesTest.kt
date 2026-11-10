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

/** The counts of what the column places do not follow, without a dictionary, and in an incremental run. */
class ColumnPlacesTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `what is not followed is counted by shape`() {
        val db = PlacesFixture.index(temp)
        assertThat(Fixture.query(db, "SELECT substr(message, 1, instr(message, ':') - 1) FROM coverage_note WHERE kind = 'COLUMN_VALUE_NOT_FOLLOWED' ORDER BY 1"))
            .containsExactly("DYNAMIC_INTO")
        // log_it(r_ord.code) passes the column to a parameter log_it does nothing with: no place, nothing counted.
    }

    @Test
    fun `without a dictionary the columns the code names still have their places`() {
        val rows = places(PlacesFixture.index(temp, AtlasDictionary.NONE))
        assertThat(rows).contains(
            "19 ORDERS.CODE SELECT_INTO l_code FIXED_SIZE varchar2(12) 12 BYTE - -",
            "20 ORDERS.CODE SELECT_INTO l_code2 ANCHORED_TO_COLUMN orders.code%type - ORDERS.CODE -"
        )
        // A `*` without the dictionary's columns names none.
        assertThat(rows).noneMatch { it.startsWith("29 ") }
    }

    @Test
    fun `an incremental run follows a declaration that changed in another file`() {
        val db = PlacesFixture.index(temp)
        Files.writeString(temp.resolve("src/pk_ord.pks"), PlacesFixture.spec(codeLength = 30))
        Indexer().index(IndexOptions(temp.resolve("src"), db, compilationSchema = "SHOP", dictionary = PlacesFixture.dictionary, threads = 1), incremental = true)
        assertThat(places(db)).contains("22 ORDERS.CODE SELECT_INTO l_sub FIXED_SIZE t_code 30 CHAR PK_ORD.T_CODE -")
    }
}
