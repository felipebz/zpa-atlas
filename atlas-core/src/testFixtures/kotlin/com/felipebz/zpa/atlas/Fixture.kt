//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.api.project.ProjectExternalColumn
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionaryRelation
import com.felipebz.zpa.atlas.dictionary.DictionaryRelationKind
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.extract.FileExtractor
import com.felipebz.zpa.api.checks.PlSqlCheck
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

/**
 * The fixture corpus and a hand-built dictionary: schema HR complete, a few `SYS` dictionary views (`SYS` is not
 * complete), and synonyms: public ones for the views, private ones of HR, a chain and a cycle.
 */
object Fixture {
    /** A folder on disk (the indexer walks it), set by the build for every test task. */
    val sources: Path = Path.of(System.getProperty("atlas.fixture") ?: error("System property atlas.fixture is not set"))

    private fun relation(
        name: String, vararg columns: String, kind: DictionaryRelationKind = DictionaryRelationKind.TABLE, schema: String = "HR", complete: Boolean = true
    ) =
        DictionaryRelation(
            ProjectExternalRelation.of(schema, name, complete, columns.mapIndexed { i, c -> ProjectExternalColumn.of(c, i + 1, null) }),
            kind
        )

    val dictionary: AtlasDictionary = AtlasDictionary.of(
        "fixture", listOf("HR"),
        listOf(
            relation("COMPANY", "ID", "ST_ACTIVE", "COMPANY_NAME", "DT_CHANGED"),
            relation("SEND_QUEUE", "ID", "RECORD_TYPE", "PAYLOAD"),
            relation("EVENT_LOG", "ID", "MSG"),
            relation("V_COMPANY", "ID", "COMPANY_NAME", kind = DictionaryRelationKind.VIEW),
            // The views of hr/views.sql; V_COMPANY_CALC has a column its text does not name.
            relation("V_COMPANY_LIST", "COMPANY_ID", "COMPANY_LABEL", kind = DictionaryRelationKind.VIEW),
            relation("V_COMPANY_CALC", "ID", "LABEL", "NAME_LEN", "LEGACY_COL", kind = DictionaryRelationKind.VIEW),
            relation("V_SEND_ALL", "ID", "RECORD_TYPE", "PAYLOAD", kind = DictionaryRelationKind.VIEW),
            relation("V_SEND_Q", "ID", "RECORD_TYPE", "PAYLOAD", "TAG", kind = DictionaryRelationKind.VIEW),
            relation("V_LOG_UNION", "ID", "TEXT", kind = DictionaryRelationKind.VIEW),
            relation("V_ACTIVE_COMPANY", "ID", "COMPANY_NAME", kind = DictionaryRelationKind.VIEW),
            relation("V_COMPANY_NAMES", "COMPANY_ID", "COMPANY_LABEL", kind = DictionaryRelationKind.VIEW),
            // The star views of hr/views_star.sql, and a table whose columns the dictionary does not list completely.
            relation("PARTIAL_T", "ID", complete = false),
            relation("V_INLINE", "X", "COMPANY_NAME", kind = DictionaryRelationKind.VIEW),
            relation("V_INLINE_NESTED", "ID", "NOTE", kind = DictionaryRelationKind.VIEW),
            relation("V_INLINE_UNION", "ID", "MSG", kind = DictionaryRelationKind.VIEW),
            relation("V_TABLE_FN", "COL_A", "COL_B", kind = DictionaryRelationKind.VIEW),
            relation("V_PARTIAL", "ID", "EXTRA", "ONE", kind = DictionaryRelationKind.VIEW),
            // The views of hr/views_lineage.sql; V_LIN_SHORT has a column its column list does not name, in the middle.
            relation("V_LIN_SCALAR", "ID", "LAST_MSG", kind = DictionaryRelationKind.VIEW),
            relation("V_LIN_CASE", "LABEL", kind = DictionaryRelationKind.VIEW),
            relation("V_LIN_AGG", "ST_ACTIVE", "TOTAL", "LAST_CHANGE", kind = DictionaryRelationKind.VIEW),
            relation("V_LIN_ANALYTIC", "ID", "RN", kind = DictionaryRelationKind.VIEW),
            relation("V_LIN_NONE", "ONE", "RN", "TODAY", "NEXT_ID", "UP", kind = DictionaryRelationKind.VIEW),
            relation("V_LIN_INLINE", "A", "B", kind = DictionaryRelationKind.VIEW),
            relation("V_LIN_WITH", "K", "LABEL", kind = DictionaryRelationKind.VIEW),
            relation("V_LIN_UNION", "K", "V", kind = DictionaryRelationKind.VIEW),
            relation("V_LIN_SHORT", "A", "EXTRA", "B", kind = DictionaryRelationKind.VIEW),
            relation("V_LIN_WITH_STAR", "ID", "MSG", kind = DictionaryRelationKind.VIEW),
            relation("V_LIN_RECURSIVE", "N", kind = DictionaryRelationKind.VIEW),
            relation("V_LIN_FN", "COL_A", kind = DictionaryRelationKind.VIEW),
            relation("V_LIN_JOIN", "ID", "COMPANY_NAME", "MSG", kind = DictionaryRelationKind.VIEW),
            relation("V_CHAIN1", "ID", "COMPANY_NAME", kind = DictionaryRelationKind.VIEW),
            relation("V_CHAIN2", "ID", "LABEL", kind = DictionaryRelationKind.VIEW),
            relation("USER_TABLES", "TABLE_NAME", "NUM_ROWS", kind = DictionaryRelationKind.VIEW, schema = "SYS"),
            relation("USER_TAB_COLUMNS", "TABLE_NAME", "COLUMN_NAME", kind = DictionaryRelationKind.VIEW, schema = "SYS"),
            relation("V_\$SESSION", "SID", "USERNAME", kind = DictionaryRelationKind.VIEW, schema = "SYS")
        ),
        listOf(
            DictionarySynonym("PUBLIC", "USER_TABLES", "SYS", "USER_TABLES", "VIEW"),
            DictionarySynonym("PUBLIC", "USER_TAB_COLUMNS", "SYS", "USER_TAB_COLUMNS", "VIEW"),
            DictionarySynonym("PUBLIC", "V\$SESSION", "SYS", "V_\$SESSION", "VIEW"),
            // Named like an HR table: the own table wins.
            DictionarySynonym("PUBLIC", "EVENT_LOG", "SYS", "USER_TABLES", "VIEW"),
            // A target the dictionary lacks, in a schema that is not complete.
            DictionarySynonym("PUBLIC", "MISSING_VIEW", "SYS", "NO_SUCH_VIEW", "VIEW"),
            DictionarySynonym("HR", "SYN_COMPANY", "HR", "COMPANY", "TABLE"),
            DictionarySynonym("HR", "SYN_CHAIN1", "HR", "SYN_CHAIN2", "SYNONYM"),
            DictionarySynonym("HR", "SYN_CHAIN2", "HR", "SEND_QUEUE", "TABLE"),
            DictionarySynonym("HR", "SYN_LOOP_A", "HR", "SYN_LOOP_B", "SYNONYM"),
            DictionarySynonym("HR", "SYN_LOOP_B", "HR", "SYN_LOOP_A", "SYNONYM"),
            // Public synonyms of SYS packages, types, functions and sequences.
            DictionarySynonym("PUBLIC", "DBMS_OUTPUT", "SYS", "DBMS_OUTPUT", "PACKAGE"),
            DictionarySynonym("PUBLIC", "DBMS_UTILITY", "SYS", "DBMS_UTILITY", "PACKAGE"),
            DictionarySynonym("PUBLIC", "XMLTYPE", "SYS", "XMLTYPE", "TYPE"),
            DictionarySynonym("PUBLIC", "XMLAGG", "SYS", "SYS_IXMLAGG", "FUNCTION"),
            DictionarySynonym("PUBLIC", "XMLSEQUENCE", "SYS", "XMLSEQUENCE", "OPERATOR"),
            DictionarySynonym("PUBLIC", "GET_ENV", "SYS", "GET_ENV_FN", "FUNCTION"),
            DictionarySynonym("PUBLIC", "DBMS_DB_VERSION", "SYS", "DBMS_DB_VERSION", "PACKAGE"),
            DictionarySynonym("PUBLIC", "SYN_SEQ", "SYS", "GLOBAL_SEQ", "SEQUENCE"),
            // Private synonyms of HR: a package of another schema, and a project package.
            DictionarySynonym("HR", "SYN_EXT_PKG", "OTHER", "EXT_PKG", "PACKAGE"),
            DictionarySynonym("HR", "SYN_CALLS", "HR", "PACK_CALLS", "PACKAGE"),
            // Named like the HR package DBMS_RANDOM: the own package wins.
            DictionarySynonym("PUBLIC", "DBMS_RANDOM", "SYS", "DBMS_RANDOM", "PACKAGE")
        ),
        // The namespace of HR as the database lists it: its relations and synonyms, and objects the sources lack.
        mapOf(
            "HR" to mapOf(
                "COMPANY" to "TABLE", "SEND_QUEUE" to "TABLE", "EVENT_LOG" to "TABLE", "V_COMPANY" to "VIEW",
                "V_COMPANY_LIST" to "VIEW", "V_COMPANY_CALC" to "VIEW", "V_SEND_ALL" to "VIEW", "V_SEND_Q" to "VIEW",
                "V_LOG_UNION" to "VIEW", "V_ACTIVE_COMPANY" to "VIEW", "V_COMPANY_NAMES" to "VIEW",
                "PARTIAL_T" to "TABLE", "V_INLINE" to "VIEW", "V_INLINE_NESTED" to "VIEW", "V_INLINE_UNION" to "VIEW",
                "V_TABLE_FN" to "VIEW", "V_PARTIAL" to "VIEW",
                "V_LIN_SCALAR" to "VIEW", "V_LIN_CASE" to "VIEW", "V_LIN_AGG" to "VIEW", "V_LIN_ANALYTIC" to "VIEW", "V_LIN_NONE" to "VIEW",
                "V_LIN_INLINE" to "VIEW", "V_LIN_WITH" to "VIEW", "V_LIN_UNION" to "VIEW", "V_LIN_SHORT" to "VIEW", "V_LIN_WITH_STAR" to "VIEW",
                "V_LIN_RECURSIVE" to "VIEW", "V_LIN_FN" to "VIEW", "V_LIN_JOIN" to "VIEW", "V_CHAIN1" to "VIEW", "V_CHAIN2" to "VIEW",
                "SYN_COMPANY" to "SYNONYM", "SYN_CHAIN1" to "SYNONYM", "SYN_CHAIN2" to "SYNONYM", "SYN_LOOP_A" to "SYNONYM",
                "SYN_LOOP_B" to "SYNONYM", "SYN_EXT_PKG" to "SYNONYM", "SYN_CALLS" to "SYNONYM",
                "DBMS_RANDOM" to "PACKAGE", "TY_MISSING" to "TYPE", "SEQ_MISSING" to "SEQUENCE"
            )
        )
    )

    fun index(
        output: Path, dictionary: AtlasDictionary = this.dictionary, compilationSchema: String? = "HR", threads: Int = 4,
        visitors: (String, FileExtractor) -> List<PlSqlCheck> = { _, extractor -> listOf(extractor) }
    ): Path {
        var tick = 0L
        Indexer({ Instant.ofEpochSecond(tick++) }, visitors).index(
            IndexOptions(sources, output, compilationSchema = compilationSchema, dictionary = dictionary, threads = threads)
        )
        return output
    }

    /** Rows of [sql] as tab-separated text lines. */
    fun query(database: Path, sql: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    val columns = rows.metaData.columnCount
                    buildList {
                        while (rows.next()) add((1..columns).joinToString("\t") { rows.getString(it) ?: "NULL" })
                    }
                }
            }
        }

    /** Every occurrence with its file, entity key and container key, in id order. */
    const val OCCURRENCES = """
        SELECT f.path, o.start_line || ':' || o.start_col || '-' || o.end_line || ':' || o.end_col, o.written,
               coalesce(e.key, '-'), o.roles, o.status, coalesce(o.reason, '-'), coalesce(c.key, '-'),
               coalesce(s.kind, '-')
        FROM occurrence o JOIN source_file f ON f.id = o.file_id
        LEFT JOIN entity e ON e.id = o.entity_id LEFT JOIN entity c ON c.id = o.container_entity_id
        LEFT JOIN sql_statement s ON s.id = o.statement_id
        ORDER BY o.id"""
}
