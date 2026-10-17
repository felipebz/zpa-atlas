//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.compare

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DependencyComparisonTest {
    private val temp: Path = Files.createTempDirectory("atlas-compare")
    private lateinit var result: ComparisonResult
    private lateinit var external: ComparisonResult
    private lateinit var types: ComparisonResult
    private lateinit var byName: ComparisonResult
    private lateinit var values: ComparisonResult

    private val golden = Path.of("src/test/resources/compare/golden")
    private val update = System.getProperty("atlas.updateGolden") == "true"

    @BeforeAll
    fun compare() {
        val index = Fixture.index(temp.resolve("fixture.db"))
        val facts = Files.newBufferedReader(Path.of("src/test/resources/compare/fixture-dependencies.csv")).use { DatabaseDependencies.read(it) }
        result = DependencyComparison(index, facts, Fixture.sources).compare()

        // A second, small index: calls into schemas the run does not know, and a guessed table.
        val corpus = Path.of("src/test/resources/compare/ext-corpus")
        val small = temp.resolve("ext.db")
        Indexer().index(IndexOptions(corpus, small, compilationSchema = "HR", dictionary = AtlasDictionary.NONE, threads = 1))
        val smallFacts = Files.newBufferedReader(Path.of("src/test/resources/compare/ext-dependencies.csv")).use { DatabaseDependencies.read(it) }
        external = DependencyComparison(small, smallFacts, corpus).compare()

        // Types of packages outside the project, and an anchor on a relation HR (complete in the fixture dictionary) lacks.
        val typesCorpus = Path.of("src/test/resources/compare/types-corpus")
        val typesIndex = temp.resolve("types.db")
        Indexer().index(IndexOptions(typesCorpus, typesIndex, compilationSchema = "HR", dictionary = Fixture.dictionary, threads = 1))
        val typesFacts = Files.newBufferedReader(Path.of("src/test/resources/compare/types-dependencies.csv")).use { DatabaseDependencies.read(it) }
        types = DependencyComparison(typesIndex, typesFacts, typesCorpus).compare()

        // `zz_ext.g_var.run` projects to a typeless ~.ZZ_EXT, `zz_ext.go` to ~.ZZ_EXT PACKAGE: one edge for one row.
        val byNameCorpus = Path.of("src/test/resources/compare/byname-corpus")
        val byNameIndex = temp.resolve("byname.db")
        Indexer().index(IndexOptions(byNameCorpus, byNameIndex, compilationSchema = "HR", dictionary = AtlasDictionary.NONE, threads = 1))
        val byNameFacts = Files.newBufferedReader(Path.of("src/test/resources/compare/byname-dependencies.csv")).use { DatabaseDependencies.read(it) }
        byName = DependencyComparison(byNameIndex, byNameFacts, byNameCorpus).compare()

        // A package value read, a handled package exception, a package cursor loop and a sequence the project does
        // not declare.
        val valuesCorpus = Path.of("src/test/resources/compare/values-corpus")
        val valuesIndex = temp.resolve("values.db")
        Indexer().index(IndexOptions(valuesCorpus, valuesIndex, compilationSchema = "HR", dictionary = AtlasDictionary.NONE, threads = 1))
        val valuesFacts = Files.newBufferedReader(Path.of("src/test/resources/compare/values-dependencies.csv")).use { DatabaseDependencies.read(it) }
        values = DependencyComparison(valuesIndex, valuesFacts, valuesCorpus).compare()
    }

    @AfterAll
    fun clean() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun `summary and CSV files match the golden output`() {
        val out = temp.resolve("out")
        ComparisonReport.writeCsv(result, out)
        val actual = mapOf("summary.txt" to ComparisonReport.summary(result)) +
            listOf("units.csv", "matched.csv", "db_only.csv", "index_only.csv").associateWith { Files.readString(out.resolve(it)) }
        if (update) {
            Files.createDirectories(golden)
            actual.forEach { (name, text) -> Files.writeString(golden.resolve(name), text) }
            return
        }
        for ((name, text) in actual) assertThat(text).describedAs(name).isEqualTo(Files.readString(golden.resolve(name)))
    }

    @Test
    fun `a name not in the text is transitive only through a matched first hop, on the shortest path`() {
        fun row(unit: String, name: String) = result.databaseOnly.single { it.unit.name == unit && it.reference.referencedName == name }.let { "${it.dbClass} ${it.detail}" }
        // One hop: the matched type TY_POINT depends on TY_COORD. Two hops: the matched public synonym, then SYS.DBMS_OUTPUT.
        assertThat(row("PACK_CALLS", "TY_COORD")).isEqualTo("TRANSITIVE via HR.TY_POINT TYPE (1)")
        assertThat(row("PACK_CALLS", "DBMSOUTPUT_LINESARRAY")).isEqualTo("TRANSITIVE via PUBLIC.DBMS_OUTPUT SYNONYM (2)")
        // The walk through the TY_POINT/TY_COORD cycle ends without reaching it.
        assertThat(row("PACK_CALLS", "T_NOWHERE")).isEqualTo("NAME_NOT_IN_TEXT ")
        // The only path starts with V_GHOST, a dependency the index does not match.
        assertThat(row("PROC_STANDALONE", "T_GHOST")).isEqualTo("NAME_NOT_IN_TEXT ")
    }

    @Test
    fun `every class is hit at least once`() {
        assertThat((result.databaseOnly + external.databaseOnly).map { it.dbClass }.toSet()).containsExactlyInAnyOrderElementsOf(ComparisonReport.DB_CLASSES)
        assertThat(result.indexOnly.map { it.indexClass }.toSet()).containsExactlyInAnyOrderElementsOf(ComparisonReport.INDEX_CLASSES)
        assertThat(result.matched).anyMatch { it.matchClass == "SYNONYM" && it.reference.referencedName == "DBMS_OUTPUT" }
        assertThat(result.collapsedOccurrences).isGreaterThan(0)
        assertThat(result.databaseOnly.single { it.dbClass == "RESOLVED_ELSEWHERE" }.reference.referencedOwner).isEqualTo("SCOTT")
    }

    @Test
    fun `unknown-schema names match by their first segment or as schema-qualified objects`() {
        assertThat(external.matched.map { "${if (it.byName) "BY_NAME" else "EXACT"} ${it.unit.name} ${it.reference.referencedOwner}.${it.reference.referencedName}" }).containsExactly(
            // `comentario comentario.texto%type` in the spec and the body: the parameter is not visible in its own
            // datatype, the anchor names the table (Oracle records it for both, verified on Oracle 26ai).
            "EXACT PK_DOC HR.COMENTARIO", "EXACT PK_DOC HR.COMENTARIO", "EXACT PK_DOC HR.PK_DOC", "EXACT PROC_EXT HR.PK_DOC",
            "BY_NAME PROC_EXT OTHER_S.EXT", "BY_NAME PROC_EXT OTHER_S.PROC", "BY_NAME PROC_EXT PUBLIC.PKG_EXT"
        )
        // Both other_s calls are one index edge (to ~.OTHER_S); only the guessed table is index-only.
        assertThat(external.indexOnly.map { "${it.indexClass} ${it.target}" }).containsExactly("INDEX_ONLY HR.EMP")
    }

    @Test
    fun `declarations are not evidence, evidence roles fit the type, NON-EXISTENT and DUAL are expected`() {
        assertThat(external.databaseOnly.map { "${it.dbClass} ${it.unit.name} ${it.reference.referencedOwner}.${it.reference.referencedName} ${it.detail}".trim() })
            .containsExactly(
                "BUILTIN PROC_EXT PUBLIC.DUAL",
                "GUESSED_ELSEWHERE PROC_EXT PUBLIC.EMP hr/proc_ext.prc:9->HR.EMP(UNVERIFIED)",
                "NON_EXISTENT PROC_EXT HR.PKG_EXT",
                // A call to a member named like the table: CALL evidence does not fit a TABLE.
                "NOT_EXTRACTED PROC_EXT HR.COMPANY"
            )
        assertThat(ComparisonReport.summary(external)).contains("overall: 77.8% (7/9)")
    }

    @Test
    fun `a type of an external package projects to the package, and a column of a missing relation is no edge`() {
        assertThat(types.matched.map { "${if (it.byName) "BY_NAME" else "EXACT"} ${it.reference.referencedOwner}.${it.reference.referencedName}" })
            .containsExactly("EXACT SYS.DBMS_AQ")
        assertThat(types.indexOnly).isEmpty()
        assertThat(types.databaseOnly.map { "${it.dbClass} ${it.reference.referencedName}" }).containsExactly("BUILTIN STANDARD")
    }

    @Test
    fun `by-name targets that differ only in type are matched together`() {
        val match = byName.matched.single()
        assertThat(match.byName).isTrue()
        assertThat(match.locations.map { it.toString() }).containsExactly("hr/proc_byname.prc:3", "hr/proc_byname.prc:4")
        assertThat(byName.indexOnly).isEmpty()
        assertThat(byName.databaseOnly).isEmpty()
    }

    @Test
    fun `package value reads, exception handlers and cursor loops are evidence for the package, a sequence matches by name`() {
        assertThat(values.matched.map { "${if (it.byName) "BY_NAME" else "EXACT"} ${it.unit.name} ${it.reference.referencedOwner}.${it.reference.referencedName}" })
            .containsExactlyInAnyOrder(
                "EXACT PROC_VAL HR.PK_VAL", "BY_NAME PROC_VAL HR.SEQ_EXT", "EXACT PROC_HANDLE HR.PK_VAL", "EXACT PROC_LOOP HR.PK_VAL"
            )
        assertThat(values.indexOnly).isEmpty()
        assertThat(values.databaseOnly).isEmpty()
    }

    @Test
    fun `the dependencies file round-trips`() {
        val original = Files.readString(Path.of("src/test/resources/compare/fixture-dependencies.csv"))
        val facts = DatabaseDependencies.read(StringReader(original))
        val written = StringWriter().also { facts.write(it) }.toString()
        assertThat(DatabaseDependencies.read(StringReader(written))).isEqualTo(facts)
        assertThat(written).isEqualTo(original)
    }

    @Test
    fun `stored source and file text compare after their headers`() {
        // Oracle keeps the object keyword, replaces `owner.` with blanks and keeps a quoted name as written.
        val file = "create or replace package hr.p_owned is\n  procedure x;\nend;\n/\n"
        assertThat(PlSqlText.firstDifference(file, "package    p_owned is\n  procedure x;\nend;")).isNull()
        assertThat(PlSqlText.firstDifference("create package \"HR\".\"P_QUOTED\" is procedure x; end;", "package       \"P_QUOTED\" is procedure x; end;")).isNull()
        assertThat(PlSqlText.firstDifference("create or replace\n  package   hr . p_spaced is x number; end;", "package \t    p_spaced is x number; end;")).isNull()
        assertThat(PlSqlText.firstDifference("create editionable package p is\r\n  x number;   \r\nend;", "package p is\n  x number;\nend;\n")).isNull()
        assertThat(PlSqlText.firstDifference(file, "package p_owned is\n  procedure y;\nend;")).isEqualTo(2)
        assertThat(PlSqlText.isWrapped("package body p_secret wrapped\na000000")).isTrue()
        assertThat(PlSqlText.isWrapped("package body p_secret is")).isFalse()
    }

    @Test
    fun `names in text skip comments and string literals`() {
        val names = PlSqlText.names("select x from t_a -- t_b\n/* t_c */ where y = 'T_D' and z = q'[T_E]' and \"Mixed\" = 1")
        assertThat(names).contains("SELECT", "X", "T_A", "Y", "Z", "Mixed").doesNotContain("T_B", "T_C", "T_D", "T_E")
    }
}
