//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** The answers behind the home page and the top bar: the files that did not parse cleanly, and the most used packages. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HomeAnswersTest {
    private val temp: Path = Files.createTempDirectory("atlas-home-test")

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun `the run summary lists the files with a parse problem by path, cut at the cap with their total`() {
        val db = Fixture.index(temp.resolve("fixture.db"))
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            val all = runSummary(connection)
            assertThat(all.parseProblemTotal).isEqualTo(2)
            assertThat(all.parseProblems).hasSize(2).allMatch { it.parseStatus == "RECOVERED" }
            assertThat(all.parseProblems.map { it.path }).isSorted
            val cut = runSummary(connection, shown = 1)
            assertThat(cut.parseProblems).containsExactly(all.parseProblems.first())
            assertThat(cut.parseProblemTotal).isEqualTo(2)
        }
    }

    @Test
    fun `the most used packages count uses from outside the package, the project's own only, each with its top member`() {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(
            sources.resolve("pkg_alpha.pks"),
            "create or replace package pkg_alpha as\n  procedure first_one;\n  procedure second_one;\n  c_limit constant number := 3;\nend pkg_alpha;\n/\n"
        )
        Files.writeString(
            sources.resolve("pkg_beta.pks"),
            "create or replace package pkg_beta as\n  procedure only_one;\nend pkg_beta;\n/\n"
        )
        Files.writeString(
            sources.resolve("pkg_quiet.pks"),
            "create or replace package pkg_quiet as\n  procedure never_called;\nend pkg_quiet;\n/\n"
        )
        // Uses from its own body only: left out. Its body also calls a member of pkg_alpha, which counts for pkg_alpha.
        Files.writeString(
            sources.resolve("pkg_self.pks"),
            "create or replace package pkg_self as\n  procedure step;\n  procedure loop_all;\nend pkg_self;\n/\n"
        )
        Files.writeString(
            sources.resolve("pkg_self.pkb"),
            """
            create or replace package body pkg_self as
              procedure step is begin null; end;
              procedure loop_all is
                procedure inner is begin step; end;
              begin
                step; step; step; inner;
                pkg_alpha.second_one;
              end;
            end pkg_self;
            /
            """.trimIndent()
        )
        // pkg_alpha's body calls its own members: those uses do not count.
        Files.writeString(
            sources.resolve("pkg_alpha.pkb"),
            """
            create or replace package body pkg_alpha as
              procedure first_one is begin second_one; second_one; second_one; end;
              procedure second_one is begin null; end;
            end pkg_alpha;
            /
            """.trimIndent()
        )
        // A script in no unit: its uses count.
        Files.writeString(sources.resolve("demo.sql"), "begin\n  pkg_beta.only_one;\n  pkg_beta.only_one;\nend;\n/\n")
        Files.writeString(
            sources.resolve("run_all.prc"),
            """
            create or replace procedure run_all is
              v number;
            begin
              pkg_alpha.first_one;
              pkg_alpha.first_one;
              pkg_alpha.second_one;
              v := pkg_alpha.c_limit;
              pkg_beta.only_one;
              ext_pkg.called_often;
              ext_pkg.called_often;
              ext_pkg.called_often;
              ext_pkg.called_often;
              ext_pkg.called_often;
            end;
            /
            """.trimIndent()
        )
        val db = temp.resolve("home.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "HR", threads = 1))
        val packages = AtlasIndex(db, sources).topPackages().result
        assertThat(packages.map { "${it.entity.key} ${it.uses} ${it.topMember?.name} ${it.topMemberUses}" }).containsExactly(
            "db:HR.PKG_ALPHA 5 FIRST_ONE 2",
            "db:HR.PKG_BETA 3 ONLY_ONE 3"
        )
    }
}
