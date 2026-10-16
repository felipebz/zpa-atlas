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
    fun `every class is hit at least once`() {
        assertThat((result.databaseOnly + external.databaseOnly).map { it.dbClass }.toSet()).containsExactlyInAnyOrderElementsOf(ComparisonReport.DB_CLASSES)
        assertThat(result.indexOnly.map { it.indexClass }.toSet()).containsExactlyInAnyOrderElementsOf(ComparisonReport.INDEX_CLASSES)
        assertThat(result.matched).anyMatch { it.byName && it.reference.referencedName == "DBMS_OUTPUT" }
        assertThat(result.collapsedOccurrences).isGreaterThan(0)
        assertThat(result.databaseOnly.single { it.dbClass == "RESOLVED_ELSEWHERE" }.reference.referencedOwner).isEqualTo("SCOTT")
    }

    @Test
    fun `unknown-schema names match by their first segment or as schema-qualified objects`() {
        assertThat(external.matched.map { it.byName to "${it.reference.referencedOwner}.${it.reference.referencedName}" }).containsExactly(
            true to "OTHER_S.EXT", true to "OTHER_S.PROC", true to "PUBLIC.PKG_EXT"
        )
        // Both other_s calls are one index edge (to ~.OTHER_S); only the guessed table is index-only.
        assertThat(external.indexOnly.map { "${it.indexClass} ${it.target}" }).containsExactly("INDEX_ONLY HR.EMP")
        val guessed = external.databaseOnly.single()
        assertThat(guessed.dbClass).isEqualTo("GUESSED_ELSEWHERE")
        assertThat(guessed.detail).isEqualTo("hr/proc_ext.prc:9->HR.EMP(UNVERIFIED)")
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
        // Oracle keeps the object keyword, replaces `owner.` with blanks and keeps a quoted name as written (F0.4 report).
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
