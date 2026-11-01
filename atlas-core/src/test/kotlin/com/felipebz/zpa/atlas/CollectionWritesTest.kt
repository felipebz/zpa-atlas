//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** A method that changes a collection writes the value its chain starts from (hr/collection_writes.sql). */
class CollectionWritesTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy { Fixture.index(temp.resolve("fixture.db")) }

    /** Line, written text, entity and roles of each use on [line] but of a record field, definitions left out. */
    private fun line(line: Int): List<String> = Fixture.query(
        db, """SELECT o.written || ' ' || coalesce(e.key, '-') || ' ' || o.roles FROM occurrence o JOIN source_file f ON f.id = o.file_id
               LEFT JOIN entity e ON e.id = o.entity_id
               WHERE f.path = 'hr/collection_writes.sql' AND o.start_line = $line AND o.roles <> 'DEFINITION'
                 AND coalesce(e.kind, '-') <> 'RECORD_FIELD' ORDER BY o.start_col"""
    )

    /** The roles of the field ITEMS on [line]. */
    private fun items(line: Int): List<String> = Fixture.query(
        db, """SELECT o.roles FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id
               WHERE f.path = 'hr/collection_writes.sql' AND o.start_line = $line AND e.key = 'db:HR.PK_R.T_ROW.ITEMS'"""
    )

    @Test
    fun `delete, extend and trim write a body global, through an element and a field as directly`() {
        val rows = "db:HR.PK_R.G_ROWS@BODY"
        val rec = "db:HR.PK_R.G_REC@BODY"
        // g_rows(1).items.delete / .extend(2) / .trim / .delete(1)
        for (number in 17..20) assertThat(line(number)).describedAs("line $number").containsExactly("g_rows $rows WRITE")
        assertThat(line(21)).containsExactly("g_rec.items.delete $rec WRITE")
        assertThat(line(22)).containsExactly("g_rec.items.extend $rec WRITE")
        assertThat(line(23)).containsExactly("pk_r.g_rows $rows WRITE")
        assertThat(line(24)).containsExactly("g_rows.delete $rows WRITE")
        assertThat(line(25)).containsExactly("g_rows $rows WRITE")
        // The field the method changes is written with its variable.
        assertThat(items(17)).containsExactly("WRITE")
        // A method that does not change the collection still reads it.
        assertThat(line(27)).containsExactly("g_rows $rows READ")
    }

    @Test
    fun `a spec variable follows the same rule`() {
        assertThat(line(33)).containsExactly("pk_r.s_rows db:HR.PK_R.S_ROWS WRITE")
        assertThat(line(34)).containsExactly("pk_r.s_rec.items.extend db:HR.PK_R.S_REC WRITE")
        assertThat(line(35)).containsExactly("pk_r.s_rows.delete db:HR.PK_R.S_ROWS WRITE")
    }

    @Test
    fun `the result of a function is not written back - its call stays a call`() {
        assertThat(line(26)).containsExactly("f_row db:HR.PK_R.F_ROW(P IN NUMBER) CALL")
        // `f_row(1).items.delete`: the field of the copy is read, not written.
        assertThat(items(26)).containsExactly("READ")
    }
}
