//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/**
 * The order of search hits and of an entity's children: names compared ignoring case, so `_` sorts before the letters
 * (`GET_X` before `GETAWAY`) and a quoted lowercase name sits among the others.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntityOrderTest {
    private val temp: Path = Files.createTempDirectory("atlas-order-test")
    private lateinit var index: AtlasIndex

    @BeforeAll
    fun build() {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(
            sources.resolve("pkg_orders.pks"),
            """
            create or replace package pkg_orders as
              procedure getaway;
              procedure get_x;
              procedure get_total;
              procedure get_charlie;
              procedure "get_beta";
              procedure get_sum_all;
              procedure get_alpha;
            end pkg_orders;
            /
            """.trimIndent()
        )
        Files.writeString(
            sources.resolve("pkg_bills.pks"),
            """
            create or replace package pkg_bills as
              procedure get_total;
            end pkg_bills;
            /
            """.trimIndent()
        )
        Files.writeString(
            sources.resolve("run_all.prc"),
            """
            create or replace procedure run_all is
            begin
              ext_pkg.get_sum;
            end;
            /
            """.trimIndent()
        )
        val db = temp.resolve("order.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "HR", threads = 1))
        index = AtlasIndex(db, sources)
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    private fun search(q: String) = index.searchEntities(q).result.map { it.display + if (it.external) " (external)" else "" }

    @Test
    fun `search hits come by name ignoring case, the same name by schema and package, external ones after`() {
        assertThat(search("get")).containsExactly(
            "HR.PKG_ORDERS.GET_ALPHA()",
            "HR.PKG_ORDERS.get_beta()",
            "HR.PKG_ORDERS.GET_CHARLIE()",
            "HR.PKG_ORDERS.GET_SUM_ALL()",
            "HR.PKG_BILLS.GET_TOTAL()",
            "HR.PKG_ORDERS.GET_TOTAL()",
            "HR.PKG_ORDERS.GET_X()",
            "HR.PKG_ORDERS.GETAWAY()",
            "~.EXT_PKG.GET_SUM (external)"
        )
    }

    @Test
    fun `the exact name comes first, even external`() {
        assertThat(search("get_sum")).containsExactly("~.EXT_PKG.GET_SUM (external)", "HR.PKG_ORDERS.GET_SUM_ALL()")
    }

    @Test
    fun `children come by name ignoring case`() {
        val children = index.entity("db:HR.PKG_ORDERS").result!!.children.map { it.entity.name }
        assertThat(children).containsExactly("GET_ALPHA", "get_beta", "GET_CHARLIE", "GET_SUM_ALL", "GET_TOTAL", "GET_X", "GETAWAY")
    }
}
