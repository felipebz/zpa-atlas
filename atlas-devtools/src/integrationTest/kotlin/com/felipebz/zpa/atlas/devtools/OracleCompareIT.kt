//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.devtools

import com.felipebz.zpa.atlas.compare.Csv
import com.felipebz.zpa.atlas.cli.execute as atlas
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.name

/**
 * Deploys a small corpus to the `ZPA_PROBE` schema, indexes it with metadata from the same database and compares it
 * with `ALL_DEPENDENCIES`. Opt-in: it runs only when `ATLAS_IT_ORACLE_URL`, `ATLAS_IT_ORACLE_USER` and
 * `ATLAS_IT_ORACLE_PASSWORD` are set. It resets the schema before and after: never run it with the probe at the same time.
 */
class OracleCompareIT {
    private val url: String? = System.getenv("ATLAS_IT_ORACLE_URL")
    private val user: String? = System.getenv("ATLAS_IT_ORACLE_USER")
    private val password: String? = System.getenv("ATLAS_IT_ORACLE_PASSWORD")

    private fun resource(path: String): Path = Path.of(javaClass.getResource("/oracle-corpus/$path")!!.toURI())

    @Test
    fun `compare-deps classifies the dependencies of a deployed corpus`() {
        assumeTrue(url != null && user != null && password != null,
            "Oracle integration test skipped: set ATLAS_IT_ORACLE_URL, ATLAS_IT_ORACLE_USER and ATLAS_IT_ORACLE_PASSWORD")
        val temp = Files.createTempDirectory("atlas-it")
        DriverManager.getConnection(url, user, password).use { connection ->
            try {
                reset(connection)
                deploy(connection)
                val schema = user!!.uppercase()
                val sources = temp.resolve("sources")
                resource("sources").toFile().copyRecursively(sources.toFile())
                val oracle = arrayOf("--oracle-url", url!!, "--oracle-user", user, "--oracle-password-env", "ATLAS_IT_ORACLE_PASSWORD", "--oracle-schemas", schema)
                val index = temp.resolve("index.db")
                assertThat(atlas(arrayOf("index", "--sources", sources.toString(), "--out", index.toString(), "--compilation-schema", schema) + oracle)).isEqualTo(0)

                val out = temp.resolve("out")
                val saved = temp.resolve("deps.csv")
                val (code, summary) = captured {
                    execute(arrayOf("compare-deps", "--db", index.toString(), "--out", out.toString(), "--save-dependencies", saved.toString()) + oracle)
                }
                println(summary)
                assertThat(code).isEqualTo(0)

                assertThat(classes(out.resolve("db_only.csv"))).isEqualTo(EXPECTED_DB_ONLY)
                assertThat(classes(out.resolve("index_only.csv"))).isEqualTo(EXPECTED_INDEX_ONLY)
                assertThat(classes(out.resolve("matched.csv"))).isEqualTo(EXPECTED_MATCHED)

                // The offline mode gives the same result from the saved file.
                val offline = temp.resolve("offline")
                val (offlineCode, offlineSummary) = captured {
                    execute(arrayOf("compare-deps", "--db", index.toString(), "--out", offline.toString(), "--dependencies", saved.toString()))
                }
                assertThat(offlineCode).isEqualTo(0)
                assertThat(offlineSummary).isEqualTo(summary)
                for (file in listOf("units.csv", "matched.csv", "db_only.csv", "index_only.csv")) {
                    assertThat(Files.readString(offline.resolve(file))).describedAs(file).isEqualTo(Files.readString(out.resolve(file)))
                }
            } finally {
                reset(connection)
                temp.toFile().deleteRecursively()
            }
        }
    }

    private fun classes(file: Path): Map<String, Int> =
        Csv.parse(Files.readString(file)).drop(1).groupingBy { it[0] }.eachCount().toSortedMap()

    private fun captured(block: () -> Int): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            return block() to buffer.toString(Charsets.UTF_8)
        } finally {
            System.setOut(original)
        }
    }

    private fun reset(connection: Connection) {
        connection.createStatement().use { it.execute(Files.readString(resource("reset.plsql"))) }
    }

    /**
     * Runs every `/`-terminated statement of the source files in name order. A file of `deployed/` replaces the source
     * file of the same name on purpose; one without a source file is an object the database has and the sources lack.
     */
    private fun deploy(connection: Connection) {
        // Specifications before bodies, then a recompilation of whatever is still invalid.
        val rank = listOf("sql", "typ", "pks", "prc", "pkb", "trg")
        val sources = Files.list(resource("sources")).use { it.toList() }
        val databaseOnly = Files.list(resource("deployed")).use { it.toList() }.filter { deployed -> sources.none { it.name == deployed.name } }
        val files = (sources + databaseOnly).sortedWith(compareBy({ rank.indexOf(it.name.substringAfterLast('.')) }, { it.name }))
        for (file in files) {
            val deployed = resource("deployed").resolve(file.name).takeIf { Files.exists(it) } ?: file
            for (chunk in Files.readString(deployed).replace("\r\n", "\n").split(Regex("(?m)^/\\s*$"))) {
                val text = chunk.trim()
                if (text.isEmpty()) continue
                val plsql = Regex("(?is)^create\\s+(or\\s+replace\\s+)?(package|procedure|function|trigger|type)\\b").containsMatchIn(text)
                connection.createStatement().use { it.execute(if (plsql) text else text.removeSuffix(";")) }
            }
        }
        connection.createStatement().use { it.execute("begin dbms_utility.compile_schema(user, false); end;") }
    }

    companion object {
        // Oracle 26ai: STANDARD, DBMS_STANDARD, PLITBLM and SYS_STUB_FOR_PURITY_ANALYSIS rows; P_CHANGED is deployed
        // differently. Oracle records a synonym, not its target: the private SYN_CUSTOMER, the public USER_TABLES and
        // USER_TAB_COLUMNS, and the public DBMS_OUTPUT (twice), XMLTYPE and DBMS_UTILITY match through the index edge to
        // the target. DBMS_RANDOM is a package of the schema the sources lack: P_OWN_RANDOM's call matches it exactly,
        // not the public synonym of the same name. TY_CIRCLE depends on its supertype, its body on TY_CIRCLE, and
        // P_XMLSEQ on the public synonym XMLSEQUENCE and its operator SYS.XMLSEQUENCE (Oracle records both).
        val EXPECTED_DB_ONLY = mapOf("BUILTIN" to 24, "SOURCE_DIFFERS" to 1)
        val EXPECTED_INDEX_ONLY = mapOf("SOURCE_DIFFERS" to 1)
        val EXPECTED_MATCHED = mapOf("EXACT" to 19, "SYNONYM" to 9)
    }
}
