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

/** The variables, constants and types a routine declares, and their uses: pk_loc.pks, pk_loc.pkb and a script, no schema. */
class RoutineLocalsTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(sources.resolve("pk_loc.pks"), "create or replace package pk_loc is\n  procedure run(p_a number);\n  procedure other;\nend;\n/\n")
        Files.writeString(sources.resolve("pk_loc.pkb"), """
            create or replace package body pk_loc is
              g_n number;
              procedure run(p_a number) is
                l_total number := 0;
                c_max constant number := 10;
                type t_pair is record (a number, b number);
                l_pair t_pair;
                g_n varchar2(10);
                procedure helper is
                  l_h number;
                begin
                  l_h := l_total;
                end;
              begin
                l_total := l_total + c_max;
                l_pair.a := p_a;
                g_n := 'x';
                declare
                  l_total number;
                begin
                  l_total := 2;
                end;
                for i in 1 .. 3 loop null; end loop;
                helper;
              end;
              procedure other is
              begin
                g_n := 5;
              end;
            end;
            /
        """.trimIndent())
        Files.writeString(sources.resolve("pk_cc.pks"), "create or replace package pk_cc is\n  procedure undecided;\nend;\n/\n")
        Files.writeString(sources.resolve("pk_cc.pkb"), """
            create or replace package body pk_cc is
              procedure undecided is
                l_u number;
              begin
                ${'$'}if ${'$'}${'$'}flag ${'$'}then l_u := 1; ${'$'}end
                l_u := 2;
              end;
            end;
            /
        """.trimIndent())
        Files.writeString(sources.resolve("script.sql"), "declare\n  l_anon number;\nbegin\n  l_anon := 1;\nend;\n/\n")
        val output = temp.resolve("locals.db")
        Indexer().index(IndexOptions(sources, output, compilationSchema = null, dictionary = AtlasDictionary.NONE, threads = 1))
        output
    }

    private val run = "db:~.PK_LOC.RUN(P_A IN NUMBER)@BODY"

    @Test
    fun `a routine's variables, constants and types are its children, a nested subprogram's and an inner block's included`() {
        assertThat(Fixture.query(db, """SELECT e.key || ' ' || e.kind || ' ' || p.key FROM entity e JOIN entity p ON p.id = e.parent_id
            WHERE e.kind IN ('LOCAL_VARIABLE', 'LOCAL_CONSTANT') OR p.kind IN ('PROCEDURE', 'TYPE') AND e.kind <> 'PARAMETER' ORDER BY 1""")).containsExactly(
            "db:~.PK_CC.UNDECIDED()@BODY.L_U LOCAL_VARIABLE db:~.PK_CC.UNDECIDED()@BODY",
            "$run.C_MAX LOCAL_CONSTANT $run",
            "$run.G_N LOCAL_VARIABLE $run",
            // A nested subprogram has no entity: its local hangs on the routine, its key names the subprogram.
            "$run.HELPER().L_H LOCAL_VARIABLE $run",
            "$run.L_PAIR LOCAL_VARIABLE $run",
            "$run.L_TOTAL LOCAL_VARIABLE $run",
            // The same name in an inner block.
            "$run.L_TOTAL#2 LOCAL_VARIABLE $run",
            "$run.T_PAIR TYPE $run",
            "$run.T_PAIR.A RECORD_FIELD $run.T_PAIR",
            "$run.T_PAIR.B RECORD_FIELD $run.T_PAIR"
        )
        // An anonymous block's variable and a loop index are not entities.
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE name IN ('L_ANON', 'I')")).isEmpty()
    }

    @Test
    fun `each name zpa binds to a local is its use, a hiding local takes its own, a hidden global keeps the others`() {
        val rows = Fixture.query(db, """SELECT o.start_line || ':' || o.start_col || ' ' || o.written || ' ' || e.key || ' ' || o.roles || ' ' || o.status
            FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id
            WHERE f.path = 'pk_loc.pkb' AND o.roles <> 'DEFINITION' AND e.kind <> 'PARAMETER' ORDER BY o.start_line, o.start_col""")
        assertThat(rows).containsExactly(
            "7:11 t_pair $run.T_PAIR TYPE_USE RESOLVED",
            "12:6 l_h $run.HELPER().L_H WRITE RESOLVED",
            "12:13 l_total $run.L_TOTAL READ RESOLVED",
            "15:4 l_total $run.L_TOTAL WRITE RESOLVED",
            "15:15 l_total $run.L_TOTAL READ RESOLVED",
            "15:25 c_max $run.C_MAX READ RESOLVED",
            "16:4 l_pair.a $run.L_PAIR WRITE RESOLVED",
            "16:11 a $run.T_PAIR.A WRITE RESOLVED",
            "17:4 g_n $run.G_N WRITE RESOLVED",
            "21:6 l_total $run.L_TOTAL#2 WRITE RESOLVED",
            "28:4 g_n db:~.PK_LOC.G_N@BODY WRITE RESOLVED"
        )
    }

    @Test
    fun `a routine with names zpa could not decide marks its locals' uses as possibly incomplete`() {
        assertThat(Fixture.query(db, "SELECT e.key || ' ' || g.reasons FROM uses_gap g JOIN entity e ON e.id = g.entity_id ORDER BY 1"))
            .containsExactly("db:~.PK_CC.UNDECIDED()@BODY.L_U CONDITIONAL_COMPILATION")
    }

    /** The two overloads of a type method and of a nested procedure, written one way or, with [swapped], the other. */
    private fun overloads(swapped: Boolean): Path {
        val sources = Files.createDirectories(temp.resolve(if (swapped) "swapped" else "overloads"))
        fun two(first: String, second: String) = if (swapped) second + first else first + second
        Files.writeString(sources.resolve("ty_shelf.typ"),
            "create or replace type ty_shelf as object (n number, member procedure put(p number), member procedure put(p varchar2));\n/\n")
        Files.writeString(sources.resolve("ty_shelf.tpb"), "create or replace type body ty_shelf is\n" + two(
            "  member procedure put(p number) is\n    l_x number;\n  begin\n    l_x := p;\n  end;\n",
            "  member procedure put(p varchar2) is\n    l_x varchar2(10);\n  begin\n    l_x := p;\n    l_x := l_x || p;\n  end;\n"
        ) + "end;\n/\n")
        Files.writeString(sources.resolve("proc_shelf.prc"), "create or replace procedure proc_shelf is\n" + two(
            "  procedure fill(p number) is\n    l_y number;\n  begin\n    l_y := p;\n  end;\n",
            "  procedure fill(p varchar2) is\n    l_y varchar2(10);\n  begin\n    l_y := p;\n    l_y := l_y || p;\n  end;\n"
        ) + "begin\n  fill(1);\nend;\n/\n")
        val output = temp.resolve(if (swapped) "swapped.db" else "overloads.db")
        Indexer().index(IndexOptions(sources, output, compilationSchema = null, dictionary = AtlasDictionary.NONE, threads = 1))
        return output
    }

    /** Each local of [db]: its key, its count of declarations and of uses. */
    private fun locals(db: Path) = Fixture.query(db, """SELECT e.key || ' ' || sum(o.roles = 'DEFINITION') || ' ' || sum(o.roles <> 'DEFINITION')
        FROM entity e JOIN occurrence o ON o.entity_id = e.id WHERE e.kind = 'LOCAL_VARIABLE' GROUP BY e.key ORDER BY 1""")

    @Test
    fun `overloads of a method or a nested subprogram each have their own locals, keyed by signature whatever their order`() {
        val written = locals(overloads(swapped = false))
        assertThat(written).containsExactly(
            "db:~.PROC_SHELF().FILL(P IN NUMBER).L_Y 1 1",
            "db:~.PROC_SHELF().FILL(P IN VARCHAR2).L_Y 1 3",
            "db:~.TY_SHELF.PUT(P IN NUMBER).L_X 1 1",
            "db:~.TY_SHELF.PUT(P IN VARCHAR2).L_X 1 3"
        )
        // The overloads written the other way round: the same keys, each with its own uses.
        assertThat(locals(overloads(swapped = true))).isEqualTo(written)
    }
}
