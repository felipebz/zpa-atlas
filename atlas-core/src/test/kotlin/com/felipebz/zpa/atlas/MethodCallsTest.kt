//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * `hr/pack_methods.pkb`: a method called on a value (after a call or an indexed element, or on a column or row of an
 * SQL source) is not a schema-level name.
 */
class MethodCallsTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy { Fixture.index(temp.resolve("fixture.db")) }

    /** The occurrences of [line] as `col written key roles`. */
    private fun line(line: Int) = Fixture.query(db,
        """SELECT o.start_col || '-' || o.end_col || ' ' || o.written || ' ' || coalesce(e.key, '-') || ' ' || o.roles
           FROM occurrence o JOIN source_file f ON f.id = o.file_id LEFT JOIN entity e ON e.id = o.entity_id
           WHERE f.path = 'hr/pack_methods.pkb' AND o.start_line = $line ORDER BY o.id""")

    @Test
    fun `a method of an indexed element is not a call`() {
        // `i_doc(1).items.exists(1)`, `.next(n)`: no call of a name ITEMS.
        assertThat(line(8)).isEmpty()
        assertThat(line(11)).isEmpty()
        // `i_doc(1).line_rec(1).line_no`: the field LINE_REC is not a constructor of the record type LINE_REC.
        assertThat(line(9)).isEmpty()
    }

    @Test
    fun `a method after a call is not the public function of its name, the calls in its arguments are`() {
        // `f_x(1).path(f_x(2))`: no call of XDB.PATH through the public synonym PATH.
        assertThat(line(12)).containsExactly(
            "9-12 f_x db:HR.PACK_METHODS.F_X(P IN NUMBER) CALL", "21-24 f_x db:HR.PACK_METHODS.F_X(P IN NUMBER) CALL"
        )
    }

    @Test
    fun `a method of a column through an alias reads the column`() {
        // `e.msg.extract(f_x(3)).getstringval()`: the read spans `e.msg`; the call in the arguments stays.
        assertThat(line(13)).containsExactly(
            "11-16 e.msg db:HR.EVENT_LOG.MSG READ", "25-28 f_x db:HR.PACK_METHODS.F_X(P IN NUMBER) CALL", "60-69 event_log db:HR.EVENT_LOG SQL_SELECT"
        )
        // Through a schema-qualified relation without alias.
        assertThat(line(16)).containsExactly("11-27 hr.event_log.msg db:HR.EVENT_LOG.MSG READ", "69-81 hr.event_log db:HR.EVENT_LOG SQL_SELECT")
    }

    @Test
    fun `a method of a pseudocolumn or of a row gives nothing`() {
        // `x.column_value.extract(...)` and `elem.extract(...)`: only the calls of the FROM clause.
        for (number in listOf(14, 15)) {
            assertThat(line(number)).noneMatch { it.contains(" x.") || it.contains("elem.") }
        }
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE key LIKE 'db:~.X.%' OR key LIKE 'db:~.ELEM%' OR key LIKE 'db:~.E.%' OR key LIKE '%.EXTRACT' OR key LIKE 'db:XDB.%'"))
            .isEmpty()
    }

    @Test
    fun `a package function still wins over an alias of the same name, and resolves in SQL`() {
        assertThat(line(17)).contains("11-27 pack_methods.f_x db:HR.PACK_METHODS.F_X(P IN NUMBER) CALL")
        assertThat(line(18)).containsExactly("11-27 pack_methods.f_x db:HR.PACK_METHODS.F_X(P IN NUMBER) CALL")
    }
}
