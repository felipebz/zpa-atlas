//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** An unqualified name in a query over one relation whose columns are unknown: its column, or undecided. */
class UndecidedSqlNameTest {
    @TempDir
    lateinit var temp: Path

    /** The index of the procedure [text]: the reads of `order_total`, and the file's undecided-name note. */
    private fun reads(text: String): List<String> {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(sources.resolve("proc_total.prc"), text.trimIndent() + "\n")
        val index = temp.resolve("names.db")
        Indexer().index(IndexOptions(sources, index, compilationSchema = "HR", threads = 1))
        return Fixture.query(index, "SELECT 'read ' || e.key FROM occurrence o JOIN entity e ON e.id = o.entity_id WHERE lower(o.written) = 'order_total'") +
            Fixture.query(index, "SELECT 'note ' || kind FROM coverage_note WHERE kind = 'SQL_NAME_UNDECIDED'")
    }

    @Test
    fun `a name no declaration has is the column of the only relation`() {
        assertThat(reads("""
            create or replace procedure proc_total is
              v number;
            begin
              select order_total into v from shop_orders;
            end;
            /
            """)).containsExactly("read db:HR.SHOP_ORDERS.ORDER_TOTAL")
    }

    @Test
    fun `a name a local variable has stays undecided`() {
        assertThat(reads("""
            create or replace procedure proc_total is
              order_total number;
              v number;
            begin
              select order_total into v from shop_orders;
            end;
            /
            """)).containsExactly("note SQL_NAME_UNDECIDED")
    }

    @Test
    fun `a name in a conditional compilation region stays undecided`() {
        assertThat(reads("""
            create or replace procedure proc_total is
              v number;
            begin
              ${'$'}IF ${'$'}${'$'}shop_audit ${'$'}THEN
                select order_total into v from shop_orders;
              ${'$'}END
              null;
            end;
            /
            """)).containsExactly("note SQL_NAME_UNDECIDED")
    }

    /** A procedure that runs [text] as a dynamic statement. */
    private fun dynamic(text: String) = """
        create or replace procedure proc_total is
          v number;
        begin
          execute immediate '$text';
        end;
        /
        """

    @Test
    fun `a name of a dynamic query is the column of the only relation`() {
        assertThat(reads(dynamic("select order_total from shop_orders"))).containsExactly("read db:HR.SHOP_ORDERS.ORDER_TOTAL")
    }

    @Test
    fun `a name a dynamic block declares stays undecided, wherever it declares it`() {
        val variable = "declare order_total number; begin select order_total into order_total from shop_orders; end;"
        val index = "declare n number; begin for order_total in 1 .. 2 loop select order_total into n from shop_orders; end loop; end;"
        val parameter = "declare cursor c(order_total number) is select order_total from shop_orders; begin null; end;"
        for (text in listOf(variable, index, parameter)) assertThat(reads(dynamic(text))).describedAs(text).containsExactly("note SQL_NAME_UNDECIDED")
    }

    @Test
    fun `a dynamic block that declares another name leaves the column read`() {
        assertThat(reads(dynamic("declare n number; begin select order_total into n from shop_orders; end;")))
            .containsExactly("read db:HR.SHOP_ORDERS.ORDER_TOTAL")
    }
}
