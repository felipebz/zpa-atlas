//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

class IndexSchemaPathsTest {
    @TempDir
    lateinit var directory: Path

    private lateinit var sources: Path
    private lateinit var out: Path

    @BeforeEach
    fun sources() {
        sources = directory.resolve("src")
        write("billing/pkg_invoice.sql", """
            create or replace package pkg_invoice is
              procedure post(p_id number);
            end;
            /
            create or replace package body pkg_invoice is
              procedure post(p_id number) is begin null; end;
            end;
            /
            """)
        write("shop/close_order.sql", """
            create or replace procedure close_order(p_id number) is
            begin
              billing_owner.pkg_invoice.post(p_id);
            end;
            /
            """)
        out = directory.resolve("atlas.db")
    }

    private fun write(path: String, text: String) {
        val file = sources.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text.trimIndent() + "\n")
    }

    /** The exit code of `zpa-atlas` with [args], and what it printed to stderr. */
    private fun run(vararg args: String): Pair<Int, String> {
        val stderr = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
        try {
            return execute(arrayOf(*args)) { null } to stderr.toString(Charsets.UTF_8)
        } finally {
            System.setErr(original)
        }
    }

    private fun index(vararg options: String) =
        run("index", "--sources", sources.toString(), "--out", out.toString(), "--threads", "1", *options)

    private fun query(sql: String): List<String> = DriverManager.getConnection("jdbc:sqlite:$out").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1) ?: "-") } }
        }
    }

    private val mapping = "billing/**=billing_owner,shop/**=SHOP"

    @Test
    fun `an invalid value is a usage error naming the option`() {
        val (code, stderr) = index("--schema-paths", "billing/**")

        assertThat(code).isEqualTo(2)
        assertThat(stderr).contains("--schema-paths").contains("pattern=SCHEMA")
        assertThat(out).doesNotExist()
    }

    @Test
    fun `an unowned unit takes the schema of the first pattern matching its file, and the run records the mapping`() {
        assertThat(index("--schema-paths", mapping, "--compilation-schema", "other").first).isEqualTo(0)

        assertThat(query("SELECT DISTINCT e.key FROM unit u JOIN entity e ON e.id = u.entity_id ORDER BY e.key")).containsExactly(
            "db:BILLING_OWNER.PKG_INVOICE", "db:SHOP.CLOSE_ORDER(P_ID IN NUMBER)"
        )
        assertThat(query("SELECT path || ' ' || compilation_schema FROM source_file ORDER BY path"))
            .containsExactly("billing/pkg_invoice.sql BILLING_OWNER", "shop/close_order.sql SHOP")
        assertThat(query("SELECT schema_paths || ' ' || compilation_schema FROM run"))
            .containsExactly("billing/**=billing_owner,shop/**=SHOP OTHER")
    }

    @Test
    fun `a name qualified by a mapped schema reaches the unowned unit that compiles in it`() {
        val call = "SELECT o.written || ' ' || coalesce(e.key, '-') || ' ' || o.status FROM occurrence o " +
            "LEFT JOIN entity e ON e.id = o.entity_id WHERE o.written = 'billing_owner.pkg_invoice.post'"
        index()
        assertThat(query(call)).singleElement().asString().doesNotContain("db:~.PKG_INVOICE")

        index("--schema-paths", mapping)
        assertThat(query(call)).containsExactly("billing_owner.pkg_invoice.post db:BILLING_OWNER.PKG_INVOICE.POST(P_ID IN NUMBER) RESOLVED")
    }

    @Test
    fun `a subtype knows the methods of a supertype that compiles in another schema`() {
        write("types/shape_t.sql", """
            create or replace type shape_t as object (id number, member procedure draw) not final;
            /
            create or replace type body shape_t is
              member procedure draw is begin null; end;
            end;
            /
            """)
        write("shapes/circle_t.sql", """
            create or replace type circle_t under shape_t (member procedure render);
            /
            create or replace type body circle_t is
              member procedure render is
              begin
                self.draw;
                draw;
                id := 1;
              end;
            end;
            /
            """)
        val uses = "SELECT o.written || ' ' || coalesce(e.key, '-') || ' ' || o.status || ' ' || coalesce(o.reason, '-') FROM occurrence o " +
            "JOIN source_file f ON f.id = o.file_id LEFT JOIN entity e ON e.id = o.entity_id " +
            "WHERE f.path = 'shapes/circle_t.sql' AND (o.start_line IN (6, 7, 8) OR o.written = 'shape_t') ORDER BY o.start_line, o.start_col"

        index()
        assertThat(query(uses)).containsExactly(
            "shape_t db:~.SHAPE_T RESOLVED -", "self.draw - UNRESOLVED LOCAL_SUBPROGRAM", "draw - UNRESOLVED LOCAL_SUBPROGRAM", "id db:~.SHAPE_T.ID RESOLVED -"
        )

        index("--schema-paths", "types/**=APP_A,shapes/**=APP_B")
        assertThat(query(uses)).containsExactly(
            "shape_t db:APP_A.SHAPE_T RESOLVED -", "self.draw - UNRESOLVED LOCAL_SUBPROGRAM", "draw - UNRESOLVED LOCAL_SUBPROGRAM",
            "id db:APP_A.SHAPE_T.ID RESOLVED -"
        )
    }

    @Test
    fun `an unqualified name in a unit takes the declaration of the unit's schema`() {
        for (dir in listOf("a", "b")) {
            write("$dir/base_t.sql", "create or replace type base_t as object (id number);\n/")
            write("$dir/pk_dup.sql", "create or replace package pk_dup is\n  procedure run;\nend;\n/")
        }
        write("b/use_dup.sql", """
            create or replace procedure use_dup is
              v base_t;
              w app_b.base_t;
            begin
              pk_dup.run;
              app_a.pk_dup.run;
            end;
            /
            """)
        val uses = "SELECT o.written || ' ' || coalesce(e.key, '-') || ' ' || o.status FROM occurrence o " +
            "JOIN source_file f ON f.id = o.file_id LEFT JOIN entity e ON e.id = o.entity_id " +
            "WHERE f.path = 'b/use_dup.sql' AND o.start_line BETWEEN 2 AND 6 AND o.written NOT IN ('v', 'w') ORDER BY o.start_line, o.start_col"

        assertThat(index("--schema-paths", "a/**=APP_A,b/**=APP_B").first).isEqualTo(0)
        assertThat(query(uses)).containsExactly(
            "base_t db:APP_B.BASE_T RESOLVED", "app_b.base_t db:APP_B.BASE_T RESOLVED",
            "pk_dup.run db:APP_B.PK_DUP.RUN() RESOLVED", "app_a.pk_dup.run db:APP_A.PK_DUP.RUN() RESOLVED"
        )
    }

    @Test
    fun `a name written with the unit's own schema reaches the unowned declaration`() {
        write("shop/ship_order.sql", """
            create or replace procedure ship_order(p_id number) is
            begin
              shop.close_order(p_id);
            end;
            /
            """)
        val call = "SELECT o.written || ' ' || coalesce(e.key, '-') || ' ' || o.status FROM occurrence o " +
            "LEFT JOIN entity e ON e.id = o.entity_id WHERE o.written = 'shop.close_order'"

        assertThat(index("--schema-paths", mapping).first).isEqualTo(0)
        assertThat(query(call)).containsExactly("shop.close_order db:SHOP.CLOSE_ORDER(P_ID IN NUMBER) RESOLVED")
    }

    @Test
    fun `an omitted callable that appears touches the files using its name`() {
        write("shop/use_broken.sql", "create or replace procedure use_broken is\nbegin\n  broken_proc(1);\nend;\n/")
        assertThat(index("--incremental").first).isEqualTo(0)

        // A heading zpa identifies but keeps no declaration of: the call cannot bind to it, but its file is walked again.
        write("shop/broken_proc.sql", "create or replace procedure broken_proc(p in out nocopy) is\nbegin\n  null;\nend;\n/")
        val (code, stderr) = index("--incremental")
        assertThat(code).isEqualTo(0)
        assertThat(stderr).contains("Incremental run: walked 2 of 4 files")
    }

    @Test
    fun `the project file key is the option`() {
        val project = directory.resolve("conf/billing.yaml")
        Files.createDirectories(project.parent)
        Files.writeString(project, "sources: ../src\nindex: ../atlas.db\nschemaPaths: \"$mapping\"\n")

        assertThat(run("index", "--project", project.toString(), "--threads", "1").first).isEqualTo(0)
        assertThat(query("SELECT schema_paths FROM run")).containsExactly(mapping)

        Files.writeString(project, "sources: ../src\nindex: ../atlas.db\nschemaPaths: billing\n")
        val (code, stderr) = run("index", "--project", project.toString())
        assertThat(code).isEqualTo(2)
        assertThat(stderr.replace('\\', '/')).contains("conf/billing.yaml: schemaPaths: ")
    }

    @Test
    fun `changing the mapping makes the next incremental run a full one that names the option`() {
        assertThat(index("--incremental", "--schema-paths", mapping).first).isEqualTo(0)
        assertThat(index("--incremental", "--schema-paths", mapping).second).contains("Incremental run: walked 0 of 2 files")

        val (code, stderr) = index("--incremental", "--schema-paths", "billing/**=BILLING_OWNER")
        assertThat(code).isEqualTo(0)
        assertThat(stderr).contains("Full run").contains("--schema-paths")
        assertThat(query("SELECT e.key FROM unit u JOIN entity e ON e.id = u.entity_id WHERE e.key LIKE '%CLOSE_ORDER%'"))
            .containsExactly("db:~.CLOSE_ORDER(P_ID IN NUMBER)")
    }
}
