package com.felipebz.zpa.atlas.stats

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** The `stats` report: plain text, every section in a deterministic order. */
object Stats {
    fun report(database: Path): String {
        require(Files.isRegularFile(database)) { "Index file does not exist: $database" }
        DriverManager.getConnection("jdbc:sqlite:file:${database.toAbsolutePath()}?mode=ro").use { connection ->
            return buildString {
                section(connection, "Run", """
                    SELECT 'schema_version', schema_version FROM run
                    UNION ALL SELECT 'zpa_version', zpa_version FROM run
                    UNION ALL SELECT 'metadata', coalesce(metadata_origin, '(none)') FROM run
                    UNION ALL SELECT 'compilation_schema', coalesce(compilation_schema, '(none)') FROM run
                    UNION ALL SELECT 'source_encoding', source_encoding FROM run""")
                section(connection, "Files by parse status",
                    "SELECT parse_status, count(*) FROM source_file GROUP BY parse_status ORDER BY parse_status")
                section(connection, "Files by encoding",
                    "SELECT encoding, count(*) FROM source_file GROUP BY encoding ORDER BY encoding")
                section(connection, "Entities by kind",
                    "SELECT kind, count(*) FROM entity WHERE external = 0 GROUP BY kind ORDER BY kind")
                section(connection, "External entities by kind",
                    "SELECT kind, count(*) FROM entity WHERE external = 1 GROUP BY kind ORDER BY kind")
                section(connection, "Occurrences by roles",
                    "SELECT CASE roles WHEN '' THEN '(none)' ELSE roles END, count(*) FROM occurrence GROUP BY roles ORDER BY roles")
                section(connection, "Occurrences by status",
                    "SELECT status, count(*) FROM occurrence GROUP BY status ORDER BY status")
                section(connection, "Dynamic SQL by reason",
                    "SELECT reason, count(*) FROM occurrence WHERE status = 'DYNAMIC' OR (status = 'PARTIAL_SOURCE' AND entity_id IS NULL AND reason IN ('LITERAL', 'EXPRESSION')) GROUP BY reason ORDER BY reason")
                section(connection, "Coverage notes by kind",
                    "SELECT kind, count(*) FROM coverage_note GROUP BY kind ORDER BY kind")
                section(connection, "Top 20 relations by writers", """
                    SELECT e.key, count(DISTINCT coalesce(o.container_entity_id, -o.unit_id)) AS writers
                    FROM occurrence o JOIN entity e ON e.id = o.entity_id
                    WHERE e.kind IN ('TABLE', 'VIEW', 'OBJECT_TABLE', 'UNKNOWN_RELATION')
                      AND (o.roles LIKE '%SQL_INSERT%' OR o.roles LIKE '%SQL_UPDATE%' OR o.roles LIKE '%SQL_DELETE%' OR o.roles LIKE '%SQL_MERGE%')
                    GROUP BY e.key ORDER BY writers DESC, e.key LIMIT 20""")
                section(connection, "Calls by status",
                    "SELECT status, count(*) FROM occurrence WHERE $CALL GROUP BY status ORDER BY status")
                section(connection, "Calls by reason",
                    "SELECT coalesce(reason, '(none)'), count(*) FROM occurrence WHERE $CALL GROUP BY reason ORDER BY reason")
                section(connection, "Top 20 called entities by callers", """
                    SELECT e.key, count(DISTINCT coalesce(o.container_entity_id, -o.unit_id)) AS callers
                    FROM occurrence o JOIN entity e ON e.id = o.entity_id WHERE $CALL
                    GROUP BY e.key ORDER BY callers DESC, e.key LIMIT 20""")
                section(connection, "Cross-schema calls without a schema prefix", """
                    SELECT 'calls', count(*) FROM occurrence o
                    JOIN entity e ON e.id = o.entity_id JOIN unit u ON u.id = o.unit_id JOIN source_file f ON f.id = o.file_id
                    WHERE $CALL AND o.status = 'RESOLVED' AND e.schema_name IS NOT NULL
                      AND e.schema_name <> coalesce(CASE WHEN instr(u.name, '.') > 0 THEN substr(u.name, 1, instr(u.name, '.') - 1) END, f.compilation_schema, '')
                      AND upper(o.written) NOT LIKE e.schema_name || '.%'""")
                section(connection, "Packages declaring a name as both procedure and function", """
                    SELECT 'packages', count(*) FROM (
                      SELECT parent_id FROM entity WHERE kind IN ('PROCEDURE', 'FUNCTION') AND parent_id IS NOT NULL
                      GROUP BY parent_id, name HAVING count(DISTINCT kind) = 2)
                    UNION ALL
                    SELECT * FROM (
                      SELECT p.display || '.' || e.name, count(*) FROM entity e JOIN entity p ON p.id = e.parent_id
                      WHERE e.kind IN ('PROCEDURE', 'FUNCTION')
                      GROUP BY e.parent_id, e.name HAVING count(DISTINCT e.kind) = 2
                      ORDER BY 1 LIMIT 20)""")
                section(connection, "Top 20 unresolved one-segment call names", """
                    SELECT upper(written) AS name, count(*) AS calls FROM occurrence
                    WHERE $CALL AND status = 'UNRESOLVED' AND reason = 'NOT_IN_PROJECT' AND instr(written, '.') = 0
                    GROUP BY upper(written) ORDER BY calls DESC, name LIMIT 20""")
            }
        }
    }

    private const val CALL = "(',' || roles || ',') LIKE '%,CALL,%'"

    private fun StringBuilder.section(connection: Connection, title: String, sql: String) {
        append(title).append('\n')
        connection.createStatement().use { statement ->
            statement.executeQuery(sql.trimIndent()).use { rows ->
                var any = false
                while (rows.next()) {
                    any = true
                    append("  ").append(rows.getString(1)).append(": ").append(rows.getString(2)).append('\n')
                }
                if (!any) append("  (none)\n")
            }
        }
        append('\n')
    }
}
