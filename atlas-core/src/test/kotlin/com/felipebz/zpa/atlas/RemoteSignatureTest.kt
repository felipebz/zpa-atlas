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

/** Subprogram keys keep the database link of a parameter or return type anchored behind it. */
class RemoteSignatureTest {
    @TempDir
    lateinit var temp: Path

    private fun index(files: Map<String, String>): Path {
        val sources = Files.createDirectories(temp.resolve("src").resolve("hr"))
        for ((name, text) in files) Files.writeString(sources.resolve(name), text.trimIndent() + "\n")
        val output = temp.resolve("index.db")
        Indexer().index(IndexOptions(sources.parent, output, compilationSchema = "HR", dictionary = AtlasDictionary.NONE, threads = 1))
        return output
    }

    private fun keys(db: Path, like: String) = Fixture.query(db, "SELECT key FROM entity WHERE kind IN ('PROCEDURE', 'FUNCTION') AND key LIKE '$like' ORDER BY key")

    @Test
    fun `the anchor text keeps a normalized link between the name and the anchor`() {
        val db = index(mapOf("pk_text.pks" to """
            create or replace package pk_text is
              procedure local_type(i t.c%type);
              procedure local_row(i t%rowtype);
              procedure remote_type(i t.c@far%type);
              procedure remote_row(i desenv.t@far%rowtype);
              procedure domain(i t.c@far.example.com%type);
              procedure quoted(i t.c@"far"%type);
              procedure mixed1(i t.c@Far%type);
              procedure mixed2(i t.c@FAR%type);
            end;
            /
        """))
        assertThat(keys(db, "db:HR.PK_TEXT.%")).containsExactly(
            "db:HR.PK_TEXT.DOMAIN(I IN T.C@FAR.EXAMPLE.COM%TYPE)",
            "db:HR.PK_TEXT.LOCAL_ROW(I IN T%ROWTYPE)",
            "db:HR.PK_TEXT.LOCAL_TYPE(I IN T.C%TYPE)",
            // Two spellings of one link give the same text; a quoted link keeps its case.
            "db:HR.PK_TEXT.MIXED1(I IN T.C@FAR%TYPE)",
            "db:HR.PK_TEXT.MIXED2(I IN T.C@FAR%TYPE)",
            "db:HR.PK_TEXT.QUOTED(I IN T.C@far%TYPE)",
            "db:HR.PK_TEXT.REMOTE_ROW(I IN DESENV.T@FAR%ROWTYPE)",
            "db:HR.PK_TEXT.REMOTE_TYPE(I IN T.C@FAR%TYPE)"
        )
    }

    @Test
    fun `overloads that differ only by link get distinct keys, and a body and its calls follow the spec key`() {
        val db = index(mapOf(
            "pk_over.pks" to """
                create or replace package pk_over is
                  procedure q(i t.c@a%type);
                  procedure q(i t.c@b%type);
                  procedure q(i t.c%type);
                  function f(p in t.c@far.example.com%type) return t.d@far.example.com%type;
                end;
                /
            """,
            "pk_over.pkb" to """
                create or replace package body pk_over is
                  procedure q(i t.c@a%type) is begin null; end;
                  procedure q(i t.c@b%type) is begin null; end;
                  procedure q(i t.c%type) is begin null; end;
                  function f(p in t.c@far.example.com%type) return t.d@far.example.com%type is begin return p; end;
                end;
                /
            """,
            "p_caller.prc" to """
                create or replace procedure p_caller is
                  x number;
                begin
                  x := pk_over.f(1);
                end;
                /
            """
        ))
        assertThat(keys(db, "db:HR.PK_OVER.Q(%")).containsExactly(
            "db:HR.PK_OVER.Q(I IN T.C%TYPE)", "db:HR.PK_OVER.Q(I IN T.C%TYPE)@BODY",
            "db:HR.PK_OVER.Q(I IN T.C@A%TYPE)", "db:HR.PK_OVER.Q(I IN T.C@A%TYPE)@BODY",
            "db:HR.PK_OVER.Q(I IN T.C@B%TYPE)", "db:HR.PK_OVER.Q(I IN T.C@B%TYPE)@BODY"
        )
        assertThat(Fixture.query(db, "SELECT kind FROM coverage_note WHERE kind = 'OVERLOAD_KEY_COLLISION'")).isEmpty()
        val spec = "db:HR.PK_OVER.F(P IN T.C@FAR.EXAMPLE.COM%TYPE)"
        assertThat(Fixture.query(db, "SELECT f.key || ' ' || t.key FROM relation r JOIN entity f ON f.id = r.from_id JOIN entity t ON t.id = r.to_id " +
            "WHERE r.kind = 'IMPLEMENTS' AND t.key LIKE 'db:HR.PK_OVER.F(%'")).containsExactly("$spec@BODY $spec")
        assertThat(Fixture.query(db, "SELECT e.key || ' ' || o.status FROM occurrence o JOIN entity e ON e.id = o.entity_id " +
            "WHERE o.roles = 'CALL' AND o.written = 'pk_over.f'")).containsExactly("$spec RESOLVED")
    }
}
