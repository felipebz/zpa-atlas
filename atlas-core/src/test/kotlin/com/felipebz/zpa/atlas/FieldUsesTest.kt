//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The uses of the fields of a package's record types, as zpa binds them (hr/pk_f.sql, hr/use_f.sql). */
class FieldUsesTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy { Fixture.index(temp.resolve("fixture.db")) }

    /** The field uses in [path]: position, written text, field, roles and status. */
    private fun fieldUses(path: String): List<String> = Fixture.query(
        db, """SELECT o.start_line || ':' || o.start_col || ' ' || o.written || ' ' || substr(e.key, 12) || ' ' || o.roles || ' ' || o.status
               FROM occurrence o JOIN entity e ON e.id = o.entity_id JOIN source_file f ON f.id = o.file_id
               WHERE f.path = '$path' AND e.kind = 'RECORD_FIELD' AND o.roles NOT IN ('DEFINITION', 'DECLARATION')
               ORDER BY o.start_line, o.start_col"""
    )

    @Test
    fun `each segment that names a field is a use of it, with the roles of the value the chain starts from`() {
        assertThat(fieldUses("hr/pk_f.sql")).containsExactly(
            "18:10 code T_ROW.CODE WRITE RESOLVED",       // g_rec.code := 'x'
            "19:14 items T_ROW.ITEMS WRITE RESOLVED",     // g_rows(1).items.delete
            "20:10 inner T_OUTER.INNER WRITE RESOLVED",   // l_out.inner.code := 'y': both fields written
            "20:16 code T_ROW.CODE WRITE RESOLVED",
            "21:19 items T_ROW.ITEMS READ RESOLVED",      // f_row().items.count
            "22:13 code T_ROW.CODE READ RESOLVED",        // p_rec.code = 'a'
            "22:35 items T_ROW.ITEMS WRITE RESOLVED",     // p_rec.items := t_nums()
            "23:51 code T_ROW.CODE READ RESOLVED"         // in SQL: where g_rec.code = 'b'
        )
    }

    @Test
    fun `a field of another file's type is a use of the spec's field`() {
        assertThat(fieldUses("hr/use_f.sql")).containsExactly(
            "4:6 items T_ROW.ITEMS WRITE RESOLVED",       // l_r.items := pk_f.t_nums()
            "5:13 code T_ROW.CODE WRITE RESOLVED",        // pk_f.s_rec.code := 'w'
            "6:34 code T_ROW.CODE READ RESOLVED"          // put_line(pk_f.s_rec.code)
        )
    }

    @Test
    fun `the use at the start of a chain stays as it was`() {
        assertThat(Fixture.query(db, """SELECT o.written || ' ' || e.name || ' ' || o.roles FROM occurrence o JOIN entity e ON e.id = o.entity_id
            JOIN source_file f ON f.id = o.file_id WHERE f.path = 'hr/use_f.sql' AND e.kind = 'PACKAGE_VARIABLE' ORDER BY o.start_line"""))
            .containsExactly("pk_f.s_rec.code S_REC WRITE", "pk_f.s_rec.code S_REC READ")
    }
}
