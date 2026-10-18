//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.compare

import com.felipebz.zpa.atlas.Fixture
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.index.DatabaseVersionOrigin
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.squid.ConditionalCompilationSettings
import com.felipebz.zpa.squid.DatabaseVersion
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
            listOf("units.csv", "matched.csv", "db_only.csv", "index_only.csv", "trigger_columns.csv").associateWith { Files.readString(out.resolve(it)) }
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
    fun `the summary shows the index's conditional compilation settings against the database's`() {
        val cc = result.conditionalCompilation
        assertThat(cc.indexVersion).isNull()
        assertThat(cc.databaseVersion).isEqualTo("23.0")
        assertThat(cc.versionDiffers).isTrue()
        assertThat(cc.flagsChecked).isTrue()
        assertThat(cc.differentFlags).containsExactly(DbObjectId("HR", "PACK_CALLS", "PACKAGE BODY") to "debug:true")
        assertThat(cc.withoutSettings).isZero()
        assertThat(ComparisonReport.summary(result)).contains(
            "Conditional compilation\n" +
                "  database version: index (none), database 23.0 (DATABASE)\n" +
                "  WARNING: the index was built without a database version setting; the database is 23.0\n" +
                "  flags: index (none)\n" +
                "  compared PL/SQL units: same flags ${cc.sameFlags}, different flags 1, without settings row 0\n" +
                "    HR.PACK_CALLS PACKAGE BODY: debug:true\n\n"
        )
    }

    @Test
    fun `flags compare by meaning, and a file without the settings rows does not check them`() {
        val corpus = Path.of("src/test/resources/compare/ext-corpus")
        val index = temp.resolve("ext-cc.db")
        val settings = ConditionalCompilationSettings(ConditionalCompilationSettings.parseFlags("Debug:TRUE, lvl:2"), DatabaseVersion(11, 2))
        Indexer().index(IndexOptions(corpus, index, compilationSchema = "HR", dictionary = AtlasDictionary.NONE, threads = 1,
            conditionalCompilation = settings, databaseVersionOrigin = DatabaseVersionOrigin.OPTION))
        val old = Files.newBufferedReader(Path.of("src/test/resources/compare/ext-dependencies.csv")).use { DatabaseDependencies.read(it) }
        assertThat(old.ccflagsReadable).isFalse()
        assertThat(old.databaseVersion).isNull()
        val unchecked = DependencyComparison(index, old, corpus).compare()
        assertThat(ComparisonReport.summary(unchecked)).contains(
            "  database version: index 11.2 (OPTION), database (not read)\n" +
                "  flags: index DEBUG:TRUE, LVL:2\n" +
                "  flags not checked: ALL_PLSQL_OBJECT_SETTINGS not read\n\n"
        )

        val facts = old.copy(
            ccflagsReadable = true,
            ccflags = listOf(DbCcFlags("HR", "PK_DOC", "PACKAGE", " LVL : 02 ,debug:true"), DbCcFlags("HR", "PK_DOC", "PACKAGE BODY", "debug:maybe")),
            databaseVersion = "23.0",
            databaseVersionOrigin = "JDBC"
        )
        val checkedResult = DependencyComparison(index, facts, corpus).compare()
        val checked = checkedResult.conditionalCompilation
        assertThat(checked.sameFlags).isEqualTo(1)
        assertThat(checked.differentFlags).containsExactly(DbObjectId("HR", "PK_DOC", "PACKAGE BODY") to "debug:maybe")
        assertThat(checked.withoutSettings).isEqualTo(1)
        assertThat(ComparisonReport.summary(checkedResult)).contains(
            "  database version: index 11.2 (OPTION), database 23.0 (JDBC)\n" +
                "  WARNING: the index was built for database version 11.2; the database is 23.0\n" +
                "  flags: index DEBUG:TRUE, LVL:2\n" +
                "  compared PL/SQL units: same flags 1, different flags 1, without settings row 1\n" +
                "    HR.PK_DOC PACKAGE BODY: debug:maybe (not valid PLSQL_CCFLAGS)\n\n"
        )
    }

    @Test
    fun `PLSQL_CCFLAGS parse like zpa's flags`() {
        assertThat(CcFlags.parse(null)).isEmpty()
        assertThat(CcFlags.parse("  ")).isEmpty()
        assertThat(CcFlags.parse("Trace : TRUE ,  lvl:2")).isEqualTo(mapOf("TRACE" to "TRUE", "LVL" to "2"))
        assertThat(CcFlags.parse("a:1, A:null, b:-07, c:False")).isEqualTo(mapOf("A" to "NULL", "B" to "-7", "C" to "FALSE"))
        listOf("a", "a:", ":1", "a:yes", "a:1.5", "a:1:2", "a:1,,b:2", "1a:1", "a b:1", "a:99999999999").forEach {
            assertThat(CcFlags.parse(it)).describedAs(it).isNull()
        }
        assertThat(CcFlags.version("23")).isEqualTo("23.0")
        assertThat(CcFlags.version(" 11.2 ")).isEqualTo("11.2")
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
    fun `a view's query compares with ALL_VIEWS TEXT, stars matching their stored expansion`() {
        fun same(file: String, stored: String) = assertThat(PlSqlText.viewDifference(file, stored)).describedAs(file).isNull()
        // The forms Oracle stores (26ai): `*` as `"A","B"`, `q.*` as `q."A",q."B"`, quoted, without blanks.
        same("create view v_star as select * from t1;", "select \"ID\",\"X\",\"MixCase\" from t1")
        same("create view v_q as select t.* from t1 t\n/", "select t.\"ID\",t.\"X\",t.\"MixCase\" from t1 t")
        same("create view v_join_q as select a.*, b.y from t1 a join t2 b on b.k = a.id", "select a.\"ID\",a.\"X\",a.\"MixCase\", b.y from t1 a join t2 b on b.k = a.id")
        same("create view v_join_star as select * from t1 a join t2 b on b.k = a.id", "select \"ID\",\"X\",\"MixCase\",\"K\",\"Y\" from t1 a join t2 b on b.k = a.id")
        same("create view v_inline as select * from (select id, x from t1)", "select \"ID\",\"X\" from (select id, x from t1)")
        same("create view v_with as with w as (select k from t2) select * from w", "with w as (select k from t2) select \"K\" from w")
        // Leading comment and blanks dropped by Oracle, case and inner comments as written; `WITH READ ONLY` kept,
        // `WITH CHECK OPTION` kept without its constraint name.
        same("create view v_ro\n  as\n  -- a comment\n  SELECT Id, X /* c2 */ from t1 with read only;", "SELECT Id, X /* c2 */ from t1 with read only")
        same("create view v_co as select id from t1 where id > 0 with check option constraint v_co_ck", "select id from t1 where id > 0 with check option ")
        // A view that does not compile keeps its star; a view whose star source is gone keeps the old expansion.
        same("create force view v_force as select * from missing_t", "select * from missing_t")
        same("create view v_gone as select * from t3", "select \"A\",\"B\" from t3")
        // Stars outside the view's own select list stay as written (26ai), and an expanded one still matches.
        val nested = "select a.x from t a left join (select pf.*, row_number() over (partition by pf.k order by pf.d) rn from p pf) b on b.k = a.k"
        same("create view v1 as $nested", nested)
        same("create view v1 as $nested", nested.replace("pf.*", "pf.\"K\",pf.\"D\""))
        same("create view v2 as select a.x from t a left join (select * from p) b on b.k = a.k", "select a.x from t a left join (select * from p) b on b.k = a.k")
        same("create view v3 as select a.x from t a where exists (select * from u)", "select a.x from t a where exists (select * from u)")
        same("create view v4 as select a.x from t a where x in (select * from u)", "select a.x from t a where x in (select * from u)")
        same("create view v5 as select x from (select * from p)", "select x from (select * from p)")
        // A stored `q.*` with another qualifier is a difference.
        assertThat(PlSqlText.viewDifference("create view v1 as $nested", nested.replace("select pf.*", "select px.*"))).isEqualTo(1)
        // A column list: only the query is compared.
        same("create or replace view hr.v (a, b) as select id, x from t1", "select id, x from t1")

        val stored = "select id,\n  'A' code\n  from t1\n  where x = 1"
        assertThat(PlSqlText.viewDifference("create view v as select id, 'B' code from t1 where x = 1", stored)).isEqualTo(2)
        assertThat(PlSqlText.viewDifference("create view v as select id, 'A' code from t1 where y = 1", stored)).isEqualTo(4)
        assertThat(PlSqlText.viewDifference("create view v as select id, 'A' code from t1 join t2 on t2.k = t1.id where x = 1", stored)).isEqualTo(4)
        assertThat(PlSqlText.viewDifference("create view v as select id, 'A' code from t1 where x = 1 and 2 = 2", stored)).isEqualTo(4)
        // A quoted column right after a star is taken by the star's expansion: a known false difference.
        assertThat(PlSqlText.viewDifference("create view v as select *, \"Z\" from t1", "select \"ID\",\"X\", \"Z\" from t1")).isEqualTo(1)
    }

    @Test
    fun `views compare with their stored text, and a file without view rows keeps them without stored source`() {
        val corpus = Path.of("src/test/resources/compare/views-corpus")
        val index = temp.resolve("views.db")
        Indexer().index(IndexOptions(corpus, index, compilationSchema = "HR", dictionary = AtlasDictionary.NONE, threads = 1))
        val text = Files.readString(Path.of("src/test/resources/compare/views-dependencies.csv"))
        val facts = DatabaseDependencies.read(StringReader(text))
        // The multi-line view text round-trips through the SRC rows.
        val written = StringWriter().also { facts.write(it) }.toString()
        assertThat(DatabaseDependencies.read(StringReader(written))).isEqualTo(facts)
        assertThat(facts.sources.filter { it.name == "V_DIFF" }.joinToString("") { it.text }).isEqualTo("select id\n    from t_c")
        val result = DependencyComparison(index, facts, corpus).compare()
        fun status(name: String) = result.units.single { it.unit.name == name }.let { "${it.sourceStatus} ${it.firstDifferentLine}" }
        assertThat(status("V_SAME")).isEqualTo("SAME null")
        assertThat(status("V_DIFF")).isEqualTo("DIFFERS 2")
        assertThat(result.indexOnly.map { "${it.unit.name} ${it.target.name} ${it.indexClass}" }).containsExactly("V_DIFF T_B SOURCE_DIFFERS")

        val old = facts.copy(sources = facts.sources.filter { it.type != "VIEW" })
        val oldResult = DependencyComparison(index, old, corpus).compare()
        assertThat(oldResult.units.filter { it.unit.type == "VIEW" }.map { it.sourceStatus }.distinct()).containsExactly(SourceStatus.NO_STORED_SOURCE)
    }

    @Test
    fun `names in text skip comments and string literals`() {
        val names = PlSqlText.names("select x from t_a -- t_b\n/* t_c */ where y = 'T_D' and z = q'[T_E]' and \"Mixed\" = 1")
        assertThat(names).contains("SELECT", "X", "T_A", "Y", "Z", "Mixed").doesNotContain("T_B", "T_C", "T_D", "T_E")
    }
}
