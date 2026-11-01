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

/**
 * Names qualified by an enclosing unit (`hr/pack_scoped.pkb`) and the object's own members in a type body
 * (`hr/ty_bag.tpb`) are not external names.
 */
class OwnNamesTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy { Fixture.index(temp.resolve("fixture.db")) }

    /** The non-definition occurrences of [path] at [line] as `written key roles reason`. */
    private fun line(path: String, line: Int) = Fixture.query(db,
        """SELECT o.written || ' ' || coalesce(e.key, '-') || ' ' || o.roles || ' ' || coalesce(o.reason, '-')
           FROM occurrence o JOIN source_file f ON f.id = o.file_id LEFT JOIN entity e ON e.id = o.entity_id
           WHERE f.path = '$path' AND o.start_line = $line AND o.roles NOT IN ('DEFINITION', 'DECLARATION') ORDER BY o.id""")

    private fun body(line: Int) = line("hr/pack_scoped.pkb", line)

    @Test
    fun `a local subprogram qualified by its enclosing units is a local subprogram`() {
        assertThat(body(9)).containsExactly("add_entry - CALL LOCAL_SUBPROGRAM")
        assertThat(body(10)).containsExactly("check_items.add_entry - CALL LOCAL_SUBPROGRAM")
        assertThat(body(11)).containsExactly("pack_scoped.check_items.add_entry - CALL LOCAL_SUBPROGRAM")
        assertThat(body(12)).containsExactly("hr.pack_scoped.check_items.add_entry - CALL LOCAL_SUBPROGRAM")
        assertThat(body(19)).containsExactly("pack_scoped.recheck.add_reason - CALL LOCAL_SUBPROGRAM")
    }

    @Test
    fun `a local or private value qualified by its enclosing units is never an external name`() {
        // `check_items.l_items.delete`, `.count`: a subprogram's local, nothing; `o :=` writes the OUT parameter.
        assertThat(body(13)).isEmpty()
        assertThat(body(14)).containsExactly("o db:HR.PACK_SCOPED.CHECK_ITEMS(O OUT NUMBER)@BODY.O WRITE -")
        // `pack_scoped.g_items.delete`, `(1) :=`, `.exists(1)`: the body's own global, as zpa binds it.
        assertThat(body(20)).containsExactly("pack_scoped.g_items.delete db:HR.PACK_SCOPED.G_ITEMS@BODY WRITE -")
        assertThat(body(21)).containsExactly("pack_scoped.g_items db:HR.PACK_SCOPED.G_ITEMS@BODY WRITE -")
        assertThat(body(22)).containsExactly("pack_scoped.g_items.exists db:HR.PACK_SCOPED.G_ITEMS@BODY READ -")
        assertThat(Fixture.query(db,
            "SELECT key FROM entity WHERE key LIKE 'db:~.CHECK_ITEMS%' OR key LIKE 'db:~.PACK_SCOPED%' OR " +
                "(key LIKE 'db:HR.PACK_SCOPED.G_ITEMS%' AND key <> 'db:HR.PACK_SCOPED.G_ITEMS@BODY') " +
                "OR key LIKE 'db:HR.PACK_SCOPED.CHECK_ITEMS.%' OR key LIKE 'db:HR.PACK_SCOPED.RECHECK.%'")).isEmpty()
    }

    @Test
    fun `a public member and a qualifier that is not an enclosing unit keep their resolution`() {
        assertThat(body(23)).containsExactly("pack_scoped.pub db:HR.PACK_SCOPED.PUB(P IN NUMBER) CALL -")
        assertThat(body(24)).containsExactly("pack_other.g_items.delete db:~.PACK_OTHER.G_ITEMS.DELETE CALL NOT_IN_PROJECT")
    }

    @Test
    fun `in a type body SELF and the type's attributes are the object, its methods local subprograms`() {
        fun typeBody(line: Int) = line("hr/ty_bag.tpb", line)
        // `entries.next(i)` reads the attribute through the implicit SELF, `entries.trim(1)` writes it; neither is a call.
        assertThat(typeBody(6)).containsExactly("entries db:HR.TY_BAG.ENTRIES READ -")
        assertThat(typeBody(7)).containsExactly("entries db:HR.TY_BAG.ENTRIES WRITE -")
        assertThat(typeBody(11)).containsExactly("entries db:HR.TY_BAG.ENTRIES READ -", "entries db:HR.TY_BAG.ENTRIES READ -")
        assertThat(typeBody(8)).containsExactly("g - CALL LOCAL_SUBPROGRAM")
        assertThat(typeBody(9)).containsExactly("self.g - CALL LOCAL_SUBPROGRAM")
        // A project function in the arguments of an attribute still resolves.
        assertThat(typeBody(10)).containsExactly("entries db:HR.TY_BAG.ENTRIES READ -", "pack_methods.f_x db:HR.PACK_METHODS.F_X(P IN NUMBER) CALL -")
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE key LIKE 'db:~.SELF%' OR key LIKE 'db:~.ENTRIES%'")).isEmpty()
    }

    @Test
    fun `a REF CURSOR type of a package body, qualified by the package in the body, is not looked up in the project`() {
        val sources = Files.createDirectories(temp.resolve("cursor"))
        Files.writeString(sources.resolve("pack_feed.pks"), "create or replace package pack_feed is\n  procedure open_feed;\nend;\n/\n")
        Files.writeString(sources.resolve("pack_feed.pkb"), """
            create or replace package body pack_feed is
              type t_cursor is ref cursor;
              procedure open_feed is
              begin
                if pack_feed.t_cursor is null then null; end if;
              end;
            end;
            /
            """.trimIndent() + "\n")
        val index = temp.resolve("cursor.db")
        Indexer().index(IndexOptions(sources, index, compilationSchema = "HR", threads = 1))
        // The body's own type, like its variables: its definition, and no occurrence guessing a member of the package.
        assertThat(Fixture.query(index, "SELECT written || ' ' || roles FROM occurrence WHERE lower(written) LIKE '%t_cursor%'")).containsExactly("t_cursor DEFINITION")
        assertThat(Fixture.query(index, "SELECT key FROM entity WHERE key LIKE '%T_CURSOR%'")).containsExactly("db:HR.PACK_FEED.T_CURSOR@BODY")
    }
}
