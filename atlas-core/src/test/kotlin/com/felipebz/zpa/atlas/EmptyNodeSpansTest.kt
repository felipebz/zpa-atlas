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

/** A grammar node that matched nothing (the constraint state after `primary key (…)`) holds no text. */
class EmptyNodeSpansTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `a statement ending in a node that matched nothing ends at its last token`() {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(sources.resolve("orders.sql"), """
            create table orders (order_id number(10), amount number(8,2), constraint pk_orders primary key (order_id));

            create or replace procedure set_up_orders is
            begin
              execute immediate 'alter table orders add constraint uk_orders unique (order_id)';
              execute immediate 'alter table orders add constraint ck_amount check (amount > 0)';
            end;
            /
        """.trimIndent())
        val db = temp.resolve("empty.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SHOP", dictionary = AtlasDictionary.NONE, threads = 1))
        assertThat(Fixture.query(db, "SELECT parse_status FROM source_file")).containsExactly("OK")
        // Each dynamic text's statement ends at its `)`, not at the quote and semicolon after it.
        assertThat(Fixture.query(db, """
            SELECT start_line || ':' || start_col || '-' || end_line || ':' || end_col FROM sql_statement
            WHERE kind = 'DDL' AND start_line > 1 ORDER BY start_line""")).containsExactly("5:21-5:82", "6:21-6:83")
    }
}
