package com.felipebz.zpa.atlas

import com.felipebz.zpa.api.project.ProjectExternalColumn
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionaryRelation
import com.felipebz.zpa.atlas.dictionary.DictionaryRelationKind
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.extract.FileExtractor
import com.felipebz.zpa.api.checks.PlSqlCheck
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

/** The fixture corpus and a hand-built dictionary: schema HR complete, nothing else. */
object Fixture {
    /** A folder on disk (the indexer walks it), set by the build for every test task. */
    val sources: Path = Path.of(System.getProperty("atlas.fixture") ?: error("System property atlas.fixture is not set"))

    private fun relation(name: String, vararg columns: String, kind: DictionaryRelationKind = DictionaryRelationKind.TABLE) =
        DictionaryRelation(
            ProjectExternalRelation.of("HR", name, true, columns.mapIndexed { i, c -> ProjectExternalColumn.of(c, i + 1, null) }),
            kind
        )

    val dictionary: AtlasDictionary = AtlasDictionary.of(
        "fixture", listOf("HR"),
        listOf(
            relation("COMPANY", "ID", "ST_ACTIVE", "COMPANY_NAME", "DT_CHANGED"),
            relation("SEND_QUEUE", "ID", "RECORD_TYPE", "PAYLOAD"),
            relation("EVENT_LOG", "ID", "MSG"),
            relation("V_COMPANY", "ID", "COMPANY_NAME", kind = DictionaryRelationKind.VIEW)
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
