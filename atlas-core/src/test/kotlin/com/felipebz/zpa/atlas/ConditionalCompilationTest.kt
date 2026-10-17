//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.index.DatabaseVersionOrigin
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.squid.ConditionalCompilationSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `hr/conditional.sql` indexed without settings, for 11.2 and for 19, without dictionary: the branches each database
 * compiles, and the package names of the conditions it evaluated.
 */
class ConditionalCompilationTest {
    @TempDir
    lateinit var temp: Path

    private fun index(version: String?): Path {
        val name = version ?: "none"
        val sources = Files.createDirectories(temp.resolve("src-$name").resolve("hr"))
        Files.copy(Fixture.sources.resolve("hr/conditional.sql"), sources.resolve("conditional.sql"))
        val output = temp.resolve("$name.db")
        Indexer().index(IndexOptions(sources.parent, output, compilationSchema = "HR", dictionary = AtlasDictionary.NONE, threads = 1,
            conditionalCompilation = ConditionalCompilationSettings.of(null, version),
            databaseVersionOrigin = version?.let { DatabaseVersionOrigin.OPTION }))
        return output
    }

    /** The SQL relation and column uses as `line written key roles`. */
    private fun sql(db: Path) = Fixture.query(db,
        """SELECT o.start_line || ' ' || o.written || ' ' || e.key || ' ' || o.roles FROM occurrence o JOIN entity e ON e.id = o.entity_id
           WHERE o.statement_id IS NOT NULL AND o.roles <> 'CALL' ORDER BY o.id""")

    /** The condition names: reads without statement, as `line:col written key`. */
    private fun conditions(db: Path) = Fixture.query(db,
        """SELECT o.start_line || ':' || o.start_col || ' ' || o.written || ' ' || e.key FROM occurrence o JOIN entity e ON e.id = o.entity_id
           WHERE o.statement_id IS NULL AND o.roles = 'READ' ORDER BY o.id""")

    /** The subprograms of PACK_COND the declarations and the body agree on. */
    private fun implemented(db: Path) = Fixture.query(db,
        """SELECT f.key FROM relation r JOIN entity f ON f.id = r.from_id WHERE r.kind = 'IMPLEMENTS' AND f.key LIKE 'db:HR.PACK_COND.%' ORDER BY 1""")

    private val alwaysRead = listOf(
        "11:4 dbms_db_version.version db:~.DBMS_DB_VERSION",
        "20:4 dbms_db_version.version db:~.DBMS_DB_VERSION",
        "28:8 dbms_db_version.ver_le_11_2 db:~.DBMS_DB_VERSION",
        // A static constant of a project package; ZPA cannot evaluate it, so its first branch stays.
        "33:8 cc_flags.debug db:HR.CC_FLAGS.DEBUG",
        "39:8 dbms_db_version.ver_le_10 db:~.DBMS_DB_VERSION"
    )

    // Every condition of the `$ERROR` selection (it keeps nothing) and of the last selection. Nothing from the nested
    // `$IF cc_nested.x` of a skipped branch, from the string, or from the `$ERROR` text.
    private val lastRead = listOf(
        "52:8 dbms_db_version.version db:~.DBMS_DB_VERSION",
        "55:8 dbms_db_version.version db:~.DBMS_DB_VERSION"
    )

    @Test
    fun `without settings the first branch of every selection is indexed`() {
        val db = index(null)
        assertThat(sql(db)).containsExactly(
            "21 send_queue db:HR.SEND_QUEUE SQL_DELETE", "21 id db:HR.SEND_QUEUE.ID READ",
            "29 company db:HR.COMPANY SQL_SELECT",
            "37 event_log db:HR.EVENT_LOG SQL_SELECT",
            "40 company db:HR.COMPANY SQL_UPDATE", "40 st_active db:HR.COMPANY.ST_ACTIVE SQL_UPDATE,WRITE"
        )
        // The `$ELSIF` condition is neither kept nor reached.
        assertThat(conditions(db)).containsExactlyElementsOf(alwaysRead + lastRead)
        assertThat(implemented(db)).containsExactly("db:HR.PACK_COND.RUN_NEW(P_ID IN NUMBER)@BODY", "db:HR.PACK_COND.WORK()@BODY")
        assertThat(Fixture.query(db, "SELECT coalesce(database_version, '-') || ' ' || coalesce(database_version_origin, '-') FROM run")).containsExactly("- -")
    }

    @Test
    fun `for 11_2 the 11g branches are indexed and an unset inquiry directive is NULL`() {
        val db = index("11.2")
        assertThat(sql(db)).containsExactly(
            "23 event_log db:HR.EVENT_LOG SQL_DELETE", "23 id db:HR.EVENT_LOG.ID READ",
            "29 company db:HR.COMPANY SQL_SELECT",
            "42 company db:HR.COMPANY SQL_UPDATE", "42 company_name db:HR.COMPANY.COMPANY_NAME SQL_UPDATE,WRITE"
        )
        // The `$ELSIF` branch is kept: its condition was evaluated.
        assertThat(conditions(db)).containsExactlyElementsOf(
            alwaysRead + "41:11 dbms_db_version.ver_le_11_2 db:~.DBMS_DB_VERSION" + lastRead
        )
        // The specification and the body agree on the branch: both passes got the settings.
        assertThat(implemented(db)).containsExactly("db:HR.PACK_COND.RUN_OLD(P_ID IN NUMBER)@BODY", "db:HR.PACK_COND.WORK()@BODY")
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE key LIKE 'db:HR.PACK_COND.RUN_NEW%'")).isEmpty()
        assertThat(Fixture.query(db, "SELECT database_version || ' ' || database_version_origin FROM run")).containsExactly("11.2 OPTION")
    }

    @Test
    fun `for 19 the 12c branches are indexed`() {
        val db = index("19")
        assertThat(sql(db)).containsExactly(
            "21 send_queue db:HR.SEND_QUEUE SQL_DELETE", "21 id db:HR.SEND_QUEUE.ID READ",
            "31 send_queue db:HR.SEND_QUEUE SQL_SELECT",
            "44 company db:HR.COMPANY SQL_UPDATE", "44 dt_changed db:HR.COMPANY.DT_CHANGED SQL_UPDATE,WRITE"
        )
        // The `$ELSE` branch is kept: both conditions before it were evaluated.
        assertThat(conditions(db)).containsExactlyElementsOf(
            alwaysRead + "41:11 dbms_db_version.ver_le_11_2 db:~.DBMS_DB_VERSION" + lastRead
        )
        assertThat(implemented(db)).containsExactly("db:HR.PACK_COND.RUN_NEW(P_ID IN NUMBER)@BODY", "db:HR.PACK_COND.WORK()@BODY")
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE key LIKE 'db:HR.PACK_COND.RUN_OLD%'")).isEmpty()
    }

    @Test
    fun `with the dictionary a condition name reaches the package behind the public synonym`() {
        val db = Fixture.index(temp.resolve("fixture.db"))
        assertThat(Fixture.query(db,
            """SELECT DISTINCT e.key FROM occurrence o JOIN entity e ON e.id = o.entity_id JOIN source_file f ON f.id = o.file_id
               WHERE f.path = 'hr/conditional.sql' AND o.written LIKE 'dbms_db_version.%'""")).containsExactly("db:SYS.DBMS_DB_VERSION")
    }
}
