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
 * The attributes of standalone object types and their uses: hr/ty_gauge.typ (the supertype), hr/ty_dial.typ (its
 * subtype), hr/ty_gauge.tpb (a type body) and hr/use_gauge.prc (a procedure).
 */
class AttributeUsesTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy { Fixture.index(temp.resolve("fixture.db")) }

    /** The attribute occurrences in [path]: position, written text, attribute key and roles. */
    private fun attributes(path: String): List<String> = Fixture.query(
        db, """SELECT o.start_line || ':' || o.start_col || ' ' || o.written || ' ' || e.key || ' ' || o.roles
               FROM occurrence o JOIN entity e ON e.id = o.entity_id JOIN source_file f ON f.id = o.file_id
               WHERE f.path = '$path' AND e.kind = 'ATTRIBUTE' ORDER BY o.start_line, o.start_col"""
    )

    @Test
    fun `each attribute a CREATE TYPE writes is a child of its type, defined on its name, with its type as written`() {
        assertThat(Fixture.query(db, """SELECT e.key || ' ' || p.key FROM entity e JOIN entity p ON p.id = e.parent_id
            WHERE e.kind = 'ATTRIBUTE' AND p.key IN ('db:HR.TY_GAUGE', 'db:HR.TY_DIAL') ORDER BY 1"""))
            .containsExactly("db:HR.TY_DIAL.C db:HR.TY_DIAL", "db:HR.TY_GAUGE.A db:HR.TY_GAUGE", "db:HR.TY_GAUGE.B db:HR.TY_GAUGE")
        assertThat(attributes("hr/ty_gauge.typ")).containsExactly("2:2 a db:HR.TY_GAUGE.A DEFINITION", "3:2 b db:HR.TY_GAUGE.B DEFINITION")
        // The subtype lists only what it writes; the method BUMP is not an attribute.
        assertThat(attributes("hr/ty_dial.typ")).containsExactly("2:2 c db:HR.TY_DIAL.C DEFINITION")
        assertThat(Fixture.query(db, "SELECT d.datatype FROM declared_text d JOIN entity e ON e.id = d.entity_id WHERE e.key = 'db:HR.TY_GAUGE.B'"))
            .containsExactly("varchar2(20)")
    }

    @Test
    fun `self in a type body reads and writes the type's attributes`() {
        assertThat(attributes("hr/ty_gauge.tpb")).containsExactly(
            "4:12 a db:HR.TY_GAUGE.A READ",   // if self.a > 0
            "5:11 b db:HR.TY_GAUGE.B WRITE"   // self.b := 'up'
        )
    }

    @Test
    fun `a use through a subtype belongs to the supertype's attribute, and an attribute of an attribute is used too`() {
        assertThat(attributes("hr/use_gauge.prc")).containsExactly(
            "4:9 a db:HR.TY_GAUGE.A READ",    // n := o.a
            "5:4 b db:HR.TY_GAUGE.B WRITE",   // d.b := 'x': inherited by TY_DIAL
            "6:9 c db:HR.TY_DIAL.C READ",     // n := d.c.x
            "6:11 x db:HR.TY_POINT.X READ"
        )
    }

    @Test
    fun `a type an ALTER TYPE changes says its attributes may not be all, and its added one has no entity`() {
        assertThat(Fixture.query(db, "SELECT e.key || ' ' || g.reasons FROM members_gap g JOIN entity e ON e.id = g.entity_id"))
            .containsExactly("db:HR.TY_GROWN ALTER_TYPE")
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE key LIKE 'db:HR.TY_GROWN.%' ORDER BY key")).containsExactly("db:HR.TY_GROWN.K")
    }
}
