//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.DmlGrammar
import com.felipebz.zpa.parser.PlSqlParser
import com.felipebz.zpa.squid.PlSqlConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WrittenValuesTest {
    private val parser = PlSqlParser.create(PlSqlConfiguration(Charsets.UTF_8))

    /** The values an `INSERT` gives its [count] columns, as their written text per row, or the reason it gives none. */
    private fun insert(statement: String, count: Int): Any {
        val clause = parser.parse("begin $statement; end;").getFirstDescendant(DmlGrammar.INSERT_INTO_CLAUSE)
        val source = clause.nextSiblingOrNull?.takeIf { it.type == DmlGrammar.VALUES_CLAUSE }?.let { WrittenValues.valuesClause(it) }
            ?: WriteSource.Query(WrittenValues.insertQuery(clause))
        return text(source.forColumns(count))
    }

    private fun text(values: ColumnValues): Any = when (values) {
        is ColumnValues.Rows -> values.rows.map { row -> row.map { writtenText(it) } }
        is ColumnValues.None -> values.reason
    }

    private fun update(statement: String, count: Int): Any {
        val column: AstNode = parser.parse("begin $statement; end;").getFirstDescendant(DmlGrammar.UPDATE_COLUMN)
        return text(WriteSource.Query(WrittenValues.setValue(column)).forColumns(count))
    }

    @Test
    fun `values and select lists pair by position, one row per VALUES row`() {
        assertThat(insert("insert into t (a, b) values (1, 'x'), (2, default)", 2)).isEqualTo(listOf(listOf("1", "'x'"), listOf("2", "default")))
        assertThat(insert("insert into t (a, b) select x, 'y' as y from d", 2)).isEqualTo(listOf(listOf("x", "'y'")))
        assertThat(update("update t set (a, b) = (select 'S', 1 from dual)", 2)).isEqualTo(listOf(listOf("'S'", "1")))
    }

    @Test
    fun `a parenthesized query pairs like the query inside, at any depth`() {
        assertThat(insert("insert into t (a, b) (select 7, 's' from dual)", 2)).isEqualTo(listOf(listOf("7", "'s'")))
        assertThat(insert("insert into t (a, b) ((select 7, 's' from dual))", 2)).isEqualTo(listOf(listOf("7", "'s'")))
        assertThat(update("update t set (a, b) = ((select x, y from dual))", 2)).isEqualTo(listOf(listOf("x", "y")))
        assertThat(insert("insert into t (a, b) (select 1, 2 from d) union (select 3, 4 from e)", 2)).isEqualTo(WrittenValues.SET_OR_STAR)
    }

    @Test
    fun `a count that does not match gives no value`() {
        assertThat(insert("insert into t (a, b) values (1)", 2)).isEqualTo(WrittenValues.COUNT_MISMATCH)
        assertThat(insert("insert into t (a, b) values (1, 2), (3)", 2)).isEqualTo(WrittenValues.COUNT_MISMATCH)
        assertThat(insert("insert into t (a) select x, y from d", 1)).isEqualTo(WrittenValues.COUNT_MISMATCH)
        assertThat(update("update t set (a, b) = (select 1 from dual)", 2)).isEqualTo(WrittenValues.COUNT_MISMATCH)
    }

    @Test
    fun `a set operator or a star gives no value`() {
        assertThat(insert("insert into t (a) select x from d union select y from e", 1)).isEqualTo(WrittenValues.SET_OR_STAR)
        assertThat(insert("insert into t (a) select x from d minus select y from e", 1)).isEqualTo(WrittenValues.SET_OR_STAR)
        assertThat(insert("insert into t select * from d", 3)).isEqualTo(WrittenValues.SET_OR_STAR)
        assertThat(insert("insert into t select d.* from d", 3)).isEqualTo(WrittenValues.SET_OR_STAR)
    }

    @Test
    fun `a record gives no value`() {
        assertThat(insert("insert into t values r", 3)).isEqualTo(WrittenValues.RECORD)
    }
}
