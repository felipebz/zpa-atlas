//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

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
                    UNION ALL SELECT 'source_encoding', source_encoding FROM run
                    UNION ALL SELECT 'database_version', coalesce(database_version, '(none)') FROM run
                    UNION ALL SELECT 'database_version_origin', coalesce(database_version_origin, '(none)') FROM run
                    UNION ALL SELECT 'ccflags', coalesce(ccflags, '(none)') FROM run""")
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
                section(connection, "Occurrences by origin",
                    "SELECT origin, count(*) FROM occurrence GROUP BY origin ORDER BY origin")
                // The occurrence of a dynamic statement is the one on its DYNAMIC statement row (its status is
                // PARTIAL_SOURCE in a recovered region); the occurrences inside its text have statements of their own.
                section(connection, "Dynamic SQL by reason",
                    "SELECT o.reason, count(*) FROM occurrence o JOIN sql_statement s ON s.id = o.statement_id WHERE s.kind = 'DYNAMIC' GROUP BY o.reason ORDER BY o.reason")
                section(connection, "Call arguments by value kind",
                    "SELECT value_kind || CASE WHEN parameter_entity_id IS NULL THEN ' unbound' ELSE ' bound' END, count(*) FROM call_argument GROUP BY 1 ORDER BY 1")
                section(connection, "DDL targets by role and status", """
                    SELECT roles || ' ' || status || ' ' || coalesce(reason, '-'), count(*) FROM occurrence
                    WHERE roles LIKE 'DDL\_%' ESCAPE '\' GROUP BY roles, status, reason ORDER BY roles, status, reason""")
                section(connection, "Coverage notes by kind",
                    "SELECT kind, count(*) FROM coverage_note GROUP BY kind ORDER BY kind")
                section(connection, "View column lineage", """
                    WITH base AS (SELECT from_id, count(*) AS n FROM relation WHERE kind = 'VIEW_BASE' GROUP BY from_id)
                    SELECT '1 base column', count(*) FROM base WHERE n = 1
                    UNION ALL SELECT '2 base columns', count(*) FROM base WHERE n = 2
                    UNION ALL SELECT '3 or more base columns', count(*) FROM base WHERE n >= 3
                    UNION ALL SELECT 'no base column, no note', count(*) FROM entity c JOIN entity v ON v.id = c.parent_id
                    WHERE c.kind = 'COLUMN' AND v.kind IN ('VIEW', 'MATERIALIZED_VIEW')
                      AND EXISTS (SELECT 1 FROM occurrence o WHERE o.entity_id = c.id AND (',' || o.roles || ',') LIKE '%,DEFINITION,%')
                      AND c.id NOT IN (SELECT from_id FROM base)
                      AND v.key || ': column ' || substr(c.key, length(v.key) + 2) || ' ' NOT IN (
                        SELECT substr(message, 1, instr(message, ' comes from ')) FROM coverage_note WHERE kind = 'VIEW_LINEAGE_UNKNOWN')
                    UNION ALL SELECT 'VIEW_LINEAGE_UNKNOWN notes', count(*) FROM coverage_note WHERE kind = 'VIEW_LINEAGE_UNKNOWN'
                    UNION ALL SELECT 'VIEW_BASE rows', count(*) FROM relation WHERE kind = 'VIEW_BASE'""")
                section(connection, "Top 10 view columns by base columns", """
                    SELECT e.key, count(*) AS n FROM relation r JOIN entity e ON e.id = r.from_id
                    WHERE r.kind = 'VIEW_BASE' GROUP BY e.key ORDER BY n DESC, e.key LIMIT 10""")
                section(connection, "Top 20 relations by writers", """
                    SELECT e.key, count(DISTINCT coalesce(o.container_entity_id, -o.unit_id)) AS writers
                    FROM occurrence o JOIN entity e ON e.id = o.entity_id
                    WHERE e.kind IN ('TABLE', 'VIEW', 'MATERIALIZED_VIEW', 'OBJECT_TABLE', 'UNKNOWN_RELATION')
                      AND (o.roles LIKE '%SQL_INSERT%' OR o.roles LIKE '%SQL_UPDATE%' OR o.roles LIKE '%SQL_DELETE%' OR o.roles LIKE '%SQL_MERGE%')
                    GROUP BY e.key ORDER BY writers DESC, e.key LIMIT 20""")
                section(connection, "Column reads by status and reason", """
                    SELECT o.status || ' ' || coalesce(o.reason, '-'), count(*) FROM occurrence o LEFT JOIN entity e ON e.id = o.entity_id
                    WHERE $COLUMN_READ GROUP BY o.status, o.reason ORDER BY o.status, o.reason""")
                section(connection, "Names in static SQL not excluded as columns, by roles", """
                    SELECT roles, count(*) FROM occurrence WHERE reason = 'COLUMN_NOT_EXCLUDED' GROUP BY roles ORDER BY roles""")
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

    /** A read of a column in static SQL: named, by `*`, ambiguous, or of a relation whose columns are unknown. */
    private const val COLUMN_READ = "o.statement_id IS NOT NULL AND o.roles IN ('READ', 'IMPLICIT,READ') AND " +
        "(e.kind = 'COLUMN' OR o.reason IN ('AMBIGUOUS_COLUMN', 'COLUMNS_UNKNOWN'))"

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
