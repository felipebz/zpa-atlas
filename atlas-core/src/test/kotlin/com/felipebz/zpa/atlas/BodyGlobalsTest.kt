//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** A package body's own variables and constants: their entities, declarations and uses (hr/pack_globals.pkb). */
class BodyGlobalsTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy { Fixture.index(temp.resolve("fixture.db")) }

    private val pack = "db:HR.PACK_GLOBALS"

    /** The occurrences of [key] in the body, but its definition: position, written text, roles, status and reason. */
    private fun uses(key: String): List<String> = Fixture.query(
        db, """SELECT o.start_line || ':' || o.start_col || ' ' || o.written || ' ' || o.roles || ' ' || o.status || coalesce(' ' || o.reason, '')
               FROM occurrence o JOIN entity e ON e.id = o.entity_id JOIN source_file f ON f.id = o.file_id
               WHERE e.key = '$key' AND f.path = 'hr/pack_globals.pkb' AND o.roles <> 'DEFINITION' ORDER BY o.start_line, o.start_col"""
    )

    @Test
    fun `each variable and constant of the body's declare section is a private child of its package, exceptions left out`() {
        assertThat(Fixture.query(db, """SELECT e.key || ' ' || e.kind || ' ' || p.key FROM entity e JOIN entity p ON p.id = e.parent_id
            WHERE e.key LIKE '$pack.%' AND e.kind IN ('PACKAGE_VARIABLE', 'PACKAGE_CONSTANT', 'EXCEPTION') ORDER BY e.key""")).containsExactly(
            "$pack.C_LIMIT@BODY PACKAGE_CONSTANT $pack", "$pack.G_IDS@BODY PACKAGE_VARIABLE $pack", "$pack.G_LABEL@BODY PACKAGE_VARIABLE $pack",
            "$pack.G_PAIR@BODY PACKAGE_VARIABLE $pack", "$pack.G_SHARED PACKAGE_VARIABLE $pack", "$pack.G_SHARED@BODY PACKAGE_VARIABLE $pack",
            "$pack.G_TOTAL@BODY PACKAGE_VARIABLE $pack"
        )
        assertThat(Fixture.query(db, "SELECT count(*) FROM relation r JOIN entity t ON t.id = r.to_id WHERE r.kind = 'CONTAINS' AND t.key LIKE '$pack.%@BODY' AND t.kind LIKE 'PACKAGE_%'"))
            .containsExactly("6")
    }

    @Test
    fun `a global carries its definition, what its declaration says and its documentation`() {
        assertThat(Fixture.query(db, """SELECT o.start_line || ':' || o.start_col || ' ' || o.roles || ' ' || o.name_start_line || ':' || o.name_start_col || ' ' || c.key
            FROM occurrence o JOIN entity e ON e.id = o.entity_id JOIN entity c ON c.id = o.container_entity_id WHERE e.key = '$pack.G_TOTAL@BODY' AND o.roles = 'DEFINITION'"""))
            .containsExactly("3:2 DEFINITION 3:2 $pack")
        assertThat(Fixture.query(db, "SELECT d.datatype || ' ' || d.value FROM declared_text d JOIN entity e ON e.id = d.entity_id WHERE e.key = '$pack.G_TOTAL@BODY'"))
            .containsExactly("number 0")
        assertThat(Fixture.query(db, "SELECT d.in_body || ' ' || d.shape || ' ' || d.text FROM doc_comment d JOIN entity e ON e.id = d.entity_id WHERE e.key IN ('$pack.G_TOTAL@BODY', '$pack.C_LIMIT@BODY') ORDER BY e.key"))
            .containsExactly("1 END_OF_LINE the most one run takes", "1 ABOVE the running total of the session")
    }

    @Test
    fun `reads, writes, elements and qualified uses bind the global, a local that hides it takes its own`() {
        assertThat(uses("$pack.G_TOTAL@BODY")).containsExactly(
            "19:15 g_total READ RESOLVED",           // plain read
            "20:4 g_total WRITE RESOLVED",           // assignment target
            "20:15 g_total READ RESOLVED",
            "21:25 g_total WRITE RESOLVED",          // INTO target
            "22:9 g_total WRITE RESOLVED",           // OUT argument
            "27:4 pack_globals.g_total WRITE RESOLVED" // qualified, in its own body
            // 37:4: hide's own g_total, not the global.
        )
        assertThat(uses("$pack.G_IDS@BODY")).containsExactly("23:4 g_ids WRITE RESOLVED", "24:15 g_ids READ RESOLVED")
        assertThat(uses("$pack.C_LIMIT@BODY")).containsExactly(
            "20:25 c_limit READ RESOLVED", "28:15 pack_globals.c_limit READ RESOLVED",
            // In SQL, no column of company takes the name.
            "29:57 c_limit READ RESOLVED"
        )
    }

    @Test
    fun `in SQL a source with unknown columns leaves the name undecided, as for any SQL name`() {
        assertThat(uses("$pack.G_LABEL@BODY")).containsExactly("30:62 g_label READ UNVERIFIED COLUMN_NOT_EXCLUDED")
    }

    @Test
    fun `what zpa does not bind is left out - the start of a chain, and a name the body redeclares from its spec`() {
        // g_pair.a, g_ids.count: zpa answers Unknown for a chain.
        assertThat(uses("$pack.G_PAIR@BODY")).isEmpty()
        assertThat(uses("$pack.G_IDS@BODY")).noneMatch { it.startsWith("26:") }
        // g_shared: declared in the spec and again in the body; zpa does not decide which one the name takes.
        assertThat(uses("$pack.G_SHARED@BODY")).isEmpty()
        assertThat(uses("$pack.G_SHARED")).isEmpty()
    }
}
