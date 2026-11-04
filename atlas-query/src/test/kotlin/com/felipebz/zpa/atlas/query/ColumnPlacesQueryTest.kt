//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.PlacesFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/** The places of a column as the page and the MCP read them ([PlacesFixture]). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ColumnPlacesQueryTest {
    private val temp: Path = Files.createTempDirectory("atlas-places")
    private lateinit var index: AtlasIndex

    @BeforeAll
    fun build() {
        index = AtlasIndex(PlacesFixture.index(temp), temp.resolve("src"))
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private val code = "db:SHOP.ORDERS.CODE"

    private val every = PlaceFilter(verdicts = Verdict.entries.toSet(), concatenated = true)

    @Test
    fun `a column's places come by verdict, with the reads through a view`() {
        val page = index.columnPlaces(code, every).result!!
        assertThat(page.total).isEqualTo(20)
        assertThat(page.counts.sumOf { it.count }).isEqualTo(20)
        assertThat(page.rows.map { Verdict.valueOf(it.verdict).ordinal }).isSorted()
        // The read of the view's column is a place of the base column, through the view.
        val viewed = page.rows.single { it.viaView }
        assertThat(listOf(viewed.read.key, viewed.targetWritten, viewed.line.toString(), viewed.length.toString(), viewed.unit, viewed.verdict))
            .containsExactly("db:SHOP.V_ORDERS.CODE", "l_code", "32", "12", "BYTE", "FITS")
        val sub = page.rows.single { it.targetWritten == "l_sub" }
        assertThat(listOf(sub.declaredType, sub.length.toString(), sub.unit, sub.typeSource?.key)).containsExactly("t_code", "12", "CHAR", "db:SHOP.PK_ORD.T_CODE")
        // Pages follow each other without repeating a place.
        val first = index.columnPlaces(code, every, 0, 10).result!!
        val second = index.columnPlaces(code, every, first.nextOffset!!, 10).result!!
        assertThat(second.nextOffset).isNull()
        assertThat(first.rows + second.rows).isEqualTo(page.rows)
    }

    @Test
    fun `a column counts its places by class and a target lists the columns it receives`() {
        assertThat(index.entity(code).result!!.placesByClass.sumOf { it.count }).isEqualTo(20)
        assertThat(index.entity("db:SHOP.ORDERS.AMOUNT").result!!.placesByClass.map { "${it.value} ${it.count}" })
            .containsExactly("NO_SIZE 1", "ANCHORED_TO_COLUMN 1")
        val run = "db:SHOP.PK_ORD.RUN(P_CODE OUT VARCHAR2)@BODY"
        assertThat(index.entity("$run.L_CODE").result!!.receives.map { it.key })
            .containsExactly("db:SHOP.ORDERS.CODE", "db:SHOP.V_ORDERS.CODE")
        assertThat(index.entity("$run.R_ORD").result!!.receives.map { it.key })
            .containsExactly("db:SHOP.ORDERS.AMOUNT", "db:SHOP.ORDERS.CODE", "db:SHOP.ORDERS.NOTE", "db:SHOP.ORDERS.ORDER_ID")
        assertThat(index.entity("db:SHOP.ORDERS").result!!.placesByClass).isEmpty()
    }
}
