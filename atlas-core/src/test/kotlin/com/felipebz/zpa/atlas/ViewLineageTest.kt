//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The `VIEW_BASE` edges of the fixture views: every column a view column's value is computed from. */
class ViewLineageTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy { Fixture.index(temp.resolve("fixture.db")) }

    /** The edges of [view] as `COLUMN -> base key`, sorted. */
    private fun edges(view: String): List<String> = Fixture.query(
        db,
        """SELECT v.name || ' -> ' || b.key FROM relation r JOIN entity v ON v.id = r.from_id JOIN entity b ON b.id = r.to_id
           WHERE r.kind = 'VIEW_BASE' AND v.parent_id = (SELECT id FROM entity WHERE key = 'db:HR.$view') ORDER BY 1"""
    )

    private fun notes(kind: String, view: String): List<String> = Fixture.query(
        db, "SELECT message FROM coverage_note WHERE kind = '$kind' AND message LIKE 'db:HR.$view: %' ORDER BY message"
    ).map { it.substringAfter(": column ").substringBefore(' ') }

    @Test
    fun `a column list maps each name to the item at its position`() {
        assertThat(edges("V_COMPANY_LIST")).containsExactly("COMPANY_ID -> db:HR.COMPANY.ID", "COMPANY_LABEL -> db:HR.COMPANY.COMPANY_NAME")
    }

    @Test
    fun `an item gives every column read in its expression`() {
        assertThat(edges("V_COMPANY_CALC")).containsExactly(
            "ID -> db:HR.COMPANY.ID", "LABEL -> db:HR.COMPANY.COMPANY_NAME", "NAME_LEN -> db:HR.COMPANY.COMPANY_NAME"
        )
        // The scalar subquery's WHERE decides the value, the view's own WHERE only chooses rows.
        assertThat(edges("V_LIN_SCALAR")).containsExactly(
            "ID -> db:HR.COMPANY.ID", "LAST_MSG -> db:HR.COMPANY.ID", "LAST_MSG -> db:HR.EVENT_LOG.ID", "LAST_MSG -> db:HR.EVENT_LOG.MSG"
        )
        assertThat(edges("V_LIN_CASE")).containsExactly("LABEL -> db:HR.COMPANY.COMPANY_NAME", "LABEL -> db:HR.COMPANY.ST_ACTIVE")
        assertThat(edges("V_LIN_AGG")).containsExactly("LAST_CHANGE -> db:HR.COMPANY.DT_CHANGED", "ST_ACTIVE -> db:HR.COMPANY.ST_ACTIVE")
        assertThat(edges("V_LIN_ANALYTIC")).containsExactly("ID -> db:HR.COMPANY.ID", "RN -> db:HR.COMPANY.DT_CHANGED", "RN -> db:HR.COMPANY.ST_ACTIVE")
    }

    @Test
    fun `constants, pseudocolumns, sequences and calls without column arguments have no base and no note`() {
        assertThat(edges("V_LIN_NONE")).isEmpty()
        assertThat(edges("V_LIN_AGG")).noneMatch { it.startsWith("TOTAL ") }
        assertThat(edges("V_SEND_Q")).noneMatch { it.startsWith("TAG ") }
        assertThat(notes("VIEW_LINEAGE_UNKNOWN", "V_LIN_NONE") + notes("VIEW_LINEAGE_UNKNOWN", "V_LIN_AGG")).isEmpty()
    }

    @Test
    fun `a star gives the column of the same name of the relation it covers`() {
        assertThat(edges("V_SEND_ALL")).containsExactly(
            "ID -> db:HR.SEND_QUEUE.ID", "PAYLOAD -> db:HR.SEND_QUEUE.PAYLOAD", "RECORD_TYPE -> db:HR.SEND_QUEUE.RECORD_TYPE"
        )
        assertThat(edges("V_SEND_Q")).containsExactly(
            "ID -> db:HR.SEND_QUEUE.ID", "PAYLOAD -> db:HR.SEND_QUEUE.PAYLOAD", "RECORD_TYPE -> db:HR.SEND_QUEUE.RECORD_TYPE"
        )
    }

    @Test
    fun `a compound query gives the item at the same position of every branch`() {
        assertThat(edges("V_LOG_UNION")).containsExactly(
            "ID -> db:HR.EVENT_LOG.ID", "ID -> db:HR.SEND_QUEUE.ID", "TEXT -> db:HR.EVENT_LOG.MSG", "TEXT -> db:HR.SEND_QUEUE.PAYLOAD"
        )
        // A column list over a UNION ALL whose second branch is a star, expanded by dictionary position.
        assertThat(edges("V_LIN_UNION")).containsExactly(
            "K -> db:HR.EVENT_LOG.ID", "K -> db:HR.SEND_QUEUE.ID", "V -> db:HR.EVENT_LOG.MSG", "V -> db:HR.SEND_QUEUE.PAYLOAD"
        )
    }

    @Test
    fun `names of inline views and WITH subqueries are followed into their items`() {
        assertThat(edges("V_ACTIVE_COMPANY")).containsExactly("COMPANY_NAME -> db:HR.COMPANY.COMPANY_NAME", "ID -> db:HR.COMPANY.ID")
        // `x.a` and plain `b` over an inline view, not through a star.
        assertThat(edges("V_LIN_INLINE")).containsExactly("A -> db:HR.COMPANY.ID", "B -> db:HR.COMPANY.COMPANY_NAME")
        // A WITH column list maps by position; a WITH reading another WITH.
        assertThat(edges("V_LIN_WITH")).containsExactly("K -> db:HR.COMPANY.ID", "LABEL -> db:HR.COMPANY.COMPANY_NAME")
        assertThat(edges("V_LIN_WITH_STAR")).containsExactly("ID -> db:HR.EVENT_LOG.ID", "MSG -> db:HR.EVENT_LOG.MSG")
        // The recursive branch reaches the column it computes: only the anchor branch gives a base.
        assertThat(edges("V_LIN_RECURSIVE")).containsExactly("N -> db:HR.COMPANY.ID")
    }

    @Test
    fun `a star over an inline view gives the inner items, every branch of a compound one`() {
        assertThat(edges("V_INLINE")).containsExactly("COMPANY_NAME -> db:HR.COMPANY.COMPANY_NAME", "X -> db:HR.COMPANY.ID")
        assertThat(edges("V_INLINE_NESTED")).containsExactly("ID -> db:HR.EVENT_LOG.ID", "NOTE -> db:HR.EVENT_LOG.MSG")
        assertThat(edges("V_INLINE_UNION")).containsExactly(
            "ID -> db:HR.EVENT_LOG.ID", "ID -> db:HR.SEND_QUEUE.ID", "MSG -> db:HR.EVENT_LOG.MSG", "MSG -> db:HR.SEND_QUEUE.PAYLOAD"
        )
    }

    @Test
    fun `a column of another view is a base column, one hop`() {
        assertThat(edges("V_COMPANY_NAMES")).containsExactly(
            "COMPANY_ID -> db:HR.V_COMPANY_LIST.COMPANY_ID", "COMPANY_LABEL -> db:HR.V_COMPANY_LIST.COMPANY_LABEL"
        )
        assertThat(edges("V_CHAIN2")).containsExactly("ID -> db:HR.V_CHAIN1.ID", "LABEL -> db:HR.V_CHAIN1.COMPANY_NAME")
        assertThat(edges("V_CHAIN1")).containsExactly("COMPANY_NAME -> db:HR.COMPANY.COMPANY_NAME", "ID -> db:HR.COMPANY.ID")
    }

    @Test
    fun `a source whose columns cannot be followed gives a note instead of edges`() {
        assertThat(edges("V_TABLE_FN")).isEmpty()
        assertThat(notes("VIEW_LINEAGE_UNKNOWN", "V_TABLE_FN")).containsExactly("COL_A", "COL_B")
        // The p.* columns come from a relation with incomplete columns; ONE is a constant.
        assertThat(edges("V_PARTIAL")).isEmpty()
        assertThat(notes("VIEW_LINEAGE_UNKNOWN", "V_PARTIAL")).containsExactly("EXTRA", "ID")
        // A named column of a table function.
        assertThat(notes("VIEW_LINEAGE_UNKNOWN", "V_LIN_FN")).containsExactly("COL_A")
    }

    @Test
    fun `a column list matches the dictionary columns by name`() {
        // The dictionary has A, EXTRA, B; the file lists (a, b): EXTRA has no site, B keeps the second item.
        assertThat(edges("V_LIN_SHORT")).containsExactly("A -> db:HR.COMPANY.ID", "B -> db:HR.COMPANY.COMPANY_NAME")
        assertThat(notes("VIEW_COLUMN_UNMATCHED", "V_LIN_SHORT")).containsExactly("EXTRA")
        assertThat(notes("VIEW_LINEAGE_UNKNOWN", "V_LIN_SHORT")).isEmpty()
        assertThat(Fixture.query(db, "SELECT o.written FROM occurrence o JOIN entity e ON e.id = o.entity_id WHERE e.key = 'db:HR.V_LIN_SHORT.B' AND o.roles = 'DEFINITION'"))
            .containsExactly("b")
    }

    @Test
    fun `a column without a site gets neither edges nor a lineage note`() {
        assertThat(notes("VIEW_COLUMN_UNMATCHED", "V_COMPANY_CALC")).containsExactly("LEGACY_COL")
        assertThat(edges("V_COMPANY_CALC")).noneMatch { it.startsWith("LEGACY_COL ") }
        assertThat(notes("VIEW_LINEAGE_UNKNOWN", "V_COMPANY_CALC")).isEmpty()
    }
}
