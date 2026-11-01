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

/**
 * `self` in a type body is the object, declared as a parameter or not, and when no schema is known:
 * hr/ty_peg.typ, hr/ty_tray.typ and hr/ty_tray.tpb indexed alone, without a compilation schema.
 */
class ImplicitSelfTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy {
        val sources = Files.createDirectories(temp.resolve("src"))
        for (name in listOf("ty_peg.typ", "ty_tray.typ", "ty_tray.tpb")) Files.copy(Fixture.sources.resolve("hr/$name"), sources.resolve(name))
        val output = temp.resolve("self.db")
        Indexer().index(IndexOptions(sources, output, compilationSchema = null, dictionary = AtlasDictionary.NONE, threads = 1))
        output
    }

    /** The occurrences of [line] of the type body but its type uses: written text, entity and roles. */
    private fun line(line: Int): List<String> = Fixture.query(
        db, """SELECT o.written || ' ' || coalesce(e.key, '-') || ' ' || o.roles FROM occurrence o JOIN source_file f ON f.id = o.file_id
               LEFT JOIN entity e ON e.id = o.entity_id
               WHERE f.path = 'ty_tray.tpb' AND o.start_line = $line AND coalesce(e.kind, '-') <> 'TYPE' ORDER BY o.start_col"""
    )

    private val items = "db:~.TY_TRAY.ITEMS"
    private val slots = "db:~.TY_TRAY.SLOTS"
    private val tag = "db:~.TY_PEG.TAG"

    @Test
    fun `an attribute written through the implicit self is a write, with no call`() {
        for (number in listOf(5, 6, 13)) assertThat(line(number)).describedAs("line $number").containsExactly("items $items WRITE")
        assertThat(line(14)).containsExactly("items $items WRITE", "items $items READ")
        for (number in listOf(8, 15)) assertThat(line(number)).describedAs("line $number").containsExactly("slots $slots WRITE", "tag $tag WRITE")
        assertThat(line(25)).containsExactly("slots $slots READ", "tag $tag READ")
    }

    @Test
    fun `the implicit self gives the rows of an explicit one`() {
        assertThat(line(19)).isEqualTo(line(13))
        assertThat(line(20)).isEqualTo(line(14))
        assertThat(line(21)).isEqualTo(line(15))
    }

    @Test
    fun `an attribute named without self is a use of the attribute`() {
        val sources = Files.createDirectories(temp.resolve("bare"))
        Files.writeString(sources.resolve("ty_crate.typ"), """
            create or replace type ty_crate_base as object (base_n number) not final;
            /
            create or replace type ty_nums as table of number;
            /
            create or replace type ty_crate under ty_crate_base (
              items ty_nums, tag varchar2(10),
              member procedure fill, member function label return varchar2,
              member procedure clash(items ty_nums), member procedure shadow
            );
            /
        """.trimIndent())
        Files.writeString(sources.resolve("ty_crate.tpb"), """
            create or replace type body ty_crate is
              member procedure fill is
              begin
                items.extend;
                items(1) := 2;
                tag := 'x';
                base_n := 3;
                select 'y' into tag from dual;
              end;
              member function label return varchar2 is
              begin
                return tag;
              end;
              member procedure clash(items ty_nums) is
              begin
                items.extend;
              end;
              member procedure shadow is
                tag varchar2(10);
              begin
                tag := 'z';
              end;
            end;
            /
        """.trimIndent())
        // The same rows with a compilation schema, under its keys.
        for (schema in listOf(null, "APP")) {
            val output = temp.resolve("bare-${schema ?: "none"}.db")
            Indexer().index(IndexOptions(sources, output, compilationSchema = schema, dictionary = AtlasDictionary.NONE, threads = 1))
            val rows = Fixture.query(
                output, """SELECT o.start_line || ' ' || o.written || ' ' || e.key || ' ' || o.roles || ' ' || o.status FROM occurrence o
                           JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id
                           WHERE f.path = 'ty_crate.tpb' AND e.kind = 'ATTRIBUTE' ORDER BY o.start_line, o.start_col"""
            )
            val s = schema ?: "~"
            assertThat(rows).describedAs("schema $schema").containsExactly(
                "4 items db:$s.TY_CRATE.ITEMS WRITE RESOLVED",
                "5 items db:$s.TY_CRATE.ITEMS WRITE RESOLVED",
                "6 tag db:$s.TY_CRATE.TAG WRITE RESOLVED",
                "7 base_n db:$s.TY_CRATE_BASE.BASE_N WRITE RESOLVED",
                "8 tag db:$s.TY_CRATE.TAG WRITE RESOLVED",
                "12 tag db:$s.TY_CRATE.TAG READ RESOLVED"
                // Lines 16 and 21: the parameter and the local take the name first.
            )
        }
    }

    @Test
    fun `a method of the type called through self or alone is a local subprogram, with or without a schema`() {
        val sources = Files.createDirectories(temp.resolve("box"))
        Files.writeString(sources.resolve("ty_box.typ"), """
            create or replace type ty_box as object (
              n number,
              member procedure a5,
              member procedure show,
              member function label return varchar2
            );
            /
        """.trimIndent())
        Files.writeString(sources.resolve("ty_box.tpb"), """
            create or replace type body ty_box is
              member procedure a5 is
                l varchar2(10);
              begin
                self.show;
                self.show();
                show;
                l := self.label();
                l := label;
              end;
              member procedure show is
              begin
                null;
              end;
              member function label return varchar2 is
              begin
                return 'x';
              end;
            end;
            /
        """.trimIndent())
        fun rows(schema: String?): List<String> {
            val output = temp.resolve("box-${schema ?: "none"}.db")
            Indexer().index(IndexOptions(sources, output, compilationSchema = schema, dictionary = AtlasDictionary.NONE, threads = 1))
            assertThat(Fixture.query(output, "SELECT key FROM entity WHERE key LIKE 'db:%.SELF%'")).describedAs("schema $schema").isEmpty()
            return Fixture.query(
                output, """SELECT o.start_line || ' ' || o.written || ' ' || coalesce(e.key, '-') || ' ' || o.roles || ' ' || o.status || ' ' ||
                                  coalesce(o.reason, '-') FROM occurrence o JOIN source_file f ON f.id = o.file_id
                           LEFT JOIN entity e ON e.id = o.entity_id
                           WHERE f.path = 'ty_box.tpb' AND o.start_line BETWEEN 5 AND 9 ORDER BY o.start_line, o.start_col"""
            ).map { it.replace("db:${schema ?: "~"}.", "db:S.") }
        }
        val local = rows(null)
        assertThat(local).containsExactly(
            "5 self.show - CALL UNRESOLVED LOCAL_SUBPROGRAM",
            "6 self.show - CALL UNRESOLVED LOCAL_SUBPROGRAM",
            "7 show - CALL UNRESOLVED LOCAL_SUBPROGRAM",
            "8 self.label - CALL UNRESOLVED LOCAL_SUBPROGRAM"
            // Line 9, `label` without parentheses: no row in either mode.
        )
        assertThat(rows("HR")).isEqualTo(local)
    }
}
