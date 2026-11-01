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
 * The types a package body declares at its top level, their fields and their uses: pack_img.pks and pack_img.pkb,
 * indexed without a compilation schema. The body redeclares the spec's T_DUP.
 */
class BodyTypesTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(sources.resolve("pack_img.pks"), """
            create or replace package pack_img is
              type t_dup is record (a number);
              procedure run;
            end pack_img;
            /
        """.trimIndent())
        Files.writeString(sources.resolve("pack_img.pkb"), """
            create or replace package body pack_img is
              type t_img is record (width number, height number, tag varchar2(10));
              type t_imgs is table of t_img index by pls_integer;
              type t_dup is record (b number);
              subtype t_code is varchar2(10);
              type t_cur is ref cursor;
              g_img t_img;
              procedure run is
                l_x t_img;
                l_tab t_imgs;
                l_n number;
                l_d t_dup;
                l_c t_code;
                l_k t_cur;
                type t_inner is record (q number);
                l_i t_inner;
              begin
                l_x.width := 1;
                l_n := l_x.height;
                l_tab(1).tag := 'a';
                g_img.width := l_tab(1).width;
                l_i.q := 1;
                l_d.b := 1;
              end;
            end pack_img;
            /
        """.trimIndent())
        val output = temp.resolve("body-types.db")
        Indexer().index(IndexOptions(sources, output, compilationSchema = null, dictionary = AtlasDictionary.NONE, threads = 1))
        output
    }

    private val img = "db:~.PACK_IMG.T_IMG@BODY"

    @Test
    fun `each top-level type of the body is a child of its package, and each field of a record a child of its type`() {
        assertThat(Fixture.query(db, """SELECT e.key || ' ' || e.kind || ' ' || p.key FROM entity e JOIN entity p ON p.id = e.parent_id
            WHERE e.key LIKE 'db:~.PACK_IMG.T%' ORDER BY 1""")).containsExactly(
            "db:~.PACK_IMG.T_CODE@BODY TYPE db:~.PACK_IMG",
            "db:~.PACK_IMG.T_CUR@BODY TYPE db:~.PACK_IMG",
            "db:~.PACK_IMG.T_DUP TYPE db:~.PACK_IMG",
            "db:~.PACK_IMG.T_DUP.A RECORD_FIELD db:~.PACK_IMG.T_DUP",
            "db:~.PACK_IMG.T_DUP@BODY TYPE db:~.PACK_IMG",
            "db:~.PACK_IMG.T_DUP@BODY.B RECORD_FIELD db:~.PACK_IMG.T_DUP@BODY",
            "$img TYPE db:~.PACK_IMG",
            "$img.HEIGHT RECORD_FIELD $img",
            "$img.TAG RECORD_FIELD $img",
            "$img.WIDTH RECORD_FIELD $img",
            "db:~.PACK_IMG.T_IMGS@BODY TYPE db:~.PACK_IMG"
        )
        // A type declared inside a subprogram has no entity.
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE name IN ('T_INNER', 'Q')")).isEmpty()
    }

    @Test
    fun `the fields are used through a variable, an element of a collection and a body global, and the types where a declaration names them`() {
        val rows = Fixture.query(
            db, """SELECT o.start_line || ':' || o.start_col || ' ' || o.written || ' ' || e.key || ' ' || o.roles || ' ' || o.status
                   FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id
                   WHERE f.path = 'pack_img.pkb' AND e.kind IN ('TYPE', 'RECORD_FIELD') AND o.roles <> 'DEFINITION'
                   ORDER BY o.start_line, o.start_col"""
        )
        assertThat(rows).containsExactly(
            "3:26 t_img $img TYPE_USE RESOLVED",            // the element type of a collection
            "7:8 t_img $img TYPE_USE RESOLVED",             // a body global
            "9:8 t_img $img TYPE_USE RESOLVED",             // a local
            "10:10 t_imgs db:~.PACK_IMG.T_IMGS@BODY TYPE_USE RESOLVED",
            "13:8 t_code db:~.PACK_IMG.T_CODE@BODY TYPE_USE RESOLVED",
            "14:8 t_cur db:~.PACK_IMG.T_CUR@BODY TYPE_USE RESOLVED",
            "18:8 width $img.WIDTH WRITE RESOLVED",
            "19:15 height $img.HEIGHT READ RESOLVED",
            "20:13 tag $img.TAG WRITE RESOLVED",            // a field of an element
            "21:10 width $img.WIDTH WRITE RESOLVED",
            "21:28 width $img.WIDTH READ RESOLVED"
            // T_DUP, declared in the spec and again in the body: zpa decides neither (line 12, line 23), so neither entity
            // is used. T_INNER and Q, declared in RUN: no entity, no use (lines 16, 22).
        )
    }

    @Test
    fun `a datatype qualified by the body's own package names the body's type, and its fields are used through it`() {
        val sources = Files.createDirectories(temp.resolve("qualified"))
        Files.writeString(sources.resolve("pk_w.pks"), "create or replace package pk_w is\n  type t_s is record (y number);\n  procedure run;\nend;\n/\n")
        Files.writeString(sources.resolve("pk_w.pkb"), """
            create or replace package body pk_w is
              type t_r is record (x number);
              type t_rs is table of pk_w.t_r index by pls_integer;
              g_r pk_w.t_r;
              procedure run is
                l_r pk_w.t_r;
                l_rs t_rs;
              begin
                l_r.x := 1;
                g_r.x := 2;
                l_rs(1).x := 3;
              end;
            end;
            /
        """.trimIndent())
        val output = temp.resolve("qualified.db")
        Indexer().index(IndexOptions(sources, output, compilationSchema = null, dictionary = AtlasDictionary.NONE, threads = 1))
        val r = "db:~.PK_W.T_R@BODY"
        assertThat(Fixture.query(
            output, """SELECT o.start_line || ':' || o.start_col || ' ' || o.written || ' ' || coalesce(e.key, '-') || ' ' || o.roles || ' ' || o.status
                       FROM occurrence o JOIN source_file f ON f.id = o.file_id LEFT JOIN entity e ON e.id = o.entity_id
                       WHERE f.path = 'pk_w.pkb' AND (o.roles = 'TYPE_USE' OR e.kind = 'RECORD_FIELD') AND o.roles <> 'DEFINITION'
                       ORDER BY o.start_line, o.start_col"""
        )).containsExactly(
            "3:24 pk_w.t_r $r TYPE_USE RESOLVED",
            "4:6 pk_w.t_r $r TYPE_USE RESOLVED",
            "6:8 pk_w.t_r $r TYPE_USE RESOLVED",
            "7:9 t_rs db:~.PK_W.T_RS@BODY TYPE_USE RESOLVED",
            "9:8 x $r.X WRITE RESOLVED",
            "10:8 x $r.X WRITE RESOLVED",
            "11:12 x $r.X WRITE RESOLVED"
        )
    }
}
