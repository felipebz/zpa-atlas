//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.writer.AtlasWriter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/**
 * The views a write passes through on its way to a column, on a hand-built index: table `T.A` under views `V` (one base,
 * reaches), `V2` over `V` (two hops, reaches), `W` (two bases, stops), materialized view `M` (stops) and view `I` with an
 * INSTEAD OF trigger (stops). Each column has one write.
 */
class ViewWriteChainTest {
    @TempDir
    lateinit var temp: Path

    private fun index(): AtlasIndex {
        val db = temp.resolve("chain.db")
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use { s ->
                (AtlasWriter.tableDdl() + AtlasWriter.indexDdl()).forEach { s.execute(it) }
                s.execute("INSERT INTO run VALUES (1, ${AtlasWriter.SCHEMA_VERSION}, 'a', 'z', '.', 'UTF-8', NULL, 'HR', NULL, NULL, NULL, 't0', 't1')")
                s.execute("INSERT INTO source_file VALUES (1, 'f.sql', 'DB_SOURCE', 'h', 0, NULL, 'OK', 0, 'UTF-8')")
                s.execute("INSERT INTO unit VALUES (1, 1, NULL, 'SQL_STATEMENT', 'u', 1, 0, 9, 0, NULL, NULL)")
                val entities = listOf(
                    "1 db:T TABLE NULL", "2 db:T.A COLUMN 1", "7 db:T.B COLUMN 1",
                    "3 db:V VIEW NULL", "4 db:V.A COLUMN 3",
                    "13 db:V2 VIEW NULL", "14 db:V2.A COLUMN 13",
                    "5 db:W VIEW NULL", "6 db:W.A COLUMN 5",
                    "8 db:M MATERIALIZED_VIEW NULL", "9 db:M.A COLUMN 8",
                    "10 db:I VIEW NULL", "11 db:I.A COLUMN 10", "12 db:TR TRIGGER NULL"
                )
                for (row in entities) {
                    val (id, key, kind, parent) = row.split(' ')
                    s.execute("INSERT INTO entity VALUES ($id, '$key', '$kind', 'S', '${key.substringAfterLast('.')}', $parent, '$key', 0)")
                }
                for ((from, to) in listOf(4 to 2, 14 to 4, 6 to 2, 6 to 7, 9 to 2, 11 to 2)) s.execute("INSERT INTO relation VALUES ($from, 'VIEW_BASE', $to)")
                s.execute("INSERT INTO relation VALUES (12, 'TRIGGER_ON', 10)")
                s.execute("INSERT INTO trigger_event VALUES (12, 'INSTEAD_OF', 'ROW', 'UPDATE', NULL)")
                // One write per column, its value the column's key.
                listOf(2, 4, 14, 6, 9, 11).forEachIndexed { i, entity ->
                    s.execute("INSERT INTO occurrence VALUES (${i + 1}, 1, 1, NULL, NULL, ${i + 1}, 0, ${i + 1}, 1, 'w', $entity, 'WRITE', 'RESOLVED', 'STATIC', NULL, NULL, NULL, NULL, NULL)")
                    s.execute("INSERT INTO written_value VALUES (${i + 1}, 1, 'STRING', (SELECT key FROM entity WHERE id = $entity), ${i + 1}, 0, ${i + 1}, 1)")
                }
            }
        }
        return AtlasIndex(db, null)
    }

    @Test
    fun `a write reaches the base through single-base view columns, not through two bases, a materialized view or a trigger`() {
        val index = index()
        assertThat(index.values("db:T.A").result!!.map { it.value }).containsExactlyInAnyOrder("db:T.A", "db:V.A", "db:V2.A")
        assertThat(index.values("db:V.A").result!!.map { it.value }).containsExactlyInAnyOrder("db:V.A", "db:V2.A")
        assertThat(index.values("db:T.B").result).isEmpty()
        assertThat(index.valueSites("db:T.A", "STRING", "db:V2.A").result!!.rows.single().entity!!.key).isEqualTo("db:V2.A")
    }
}
