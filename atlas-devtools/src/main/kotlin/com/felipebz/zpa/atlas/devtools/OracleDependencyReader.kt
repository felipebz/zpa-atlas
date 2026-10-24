//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.devtools

import com.felipebz.zpa.atlas.cli.CliExecutionException
import com.felipebz.zpa.atlas.cli.oracle.JdbcDriverDataSource
import com.felipebz.zpa.atlas.cli.oracle.DatabaseVersionReader
import com.felipebz.zpa.atlas.cli.oracle.JdbcDrivers
import com.felipebz.zpa.atlas.cli.oracle.OracleMetadataLoader
import com.felipebz.zpa.atlas.cli.oracle.OracleSettings
import com.felipebz.zpa.atlas.compare.DatabaseDependencies
import com.felipebz.zpa.atlas.compare.DbCcFlags
import com.felipebz.zpa.atlas.compare.DbDependency
import com.felipebz.zpa.atlas.compare.DbObject
import com.felipebz.zpa.atlas.compare.DbSourceLine
import com.felipebz.zpa.atlas.compare.DbTriggerColumn
import com.felipebz.zpa.atlas.compare.DbTriggerSetting
import com.felipebz.zpa.atlas.index.ConditionalCompilationText
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger

/**
 * Reads `ALL_DEPENDENCIES`, `ALL_OBJECTS`, `ALL_SOURCE`, `ALL_VIEWS` (the text of views), `ALL_MVIEWS` (the query of
 * materialized views), `ALL_TRIGGER_COLS`, `ALL_PLSQL_OBJECT_SETTINGS` (the triggers'
 * `PLSQL_DEBUG` and every unit's `PLSQL_CCFLAGS`) and the database version (as the indexer reads it) for the requested
 * owners, in one read-only transaction. Only the seven `ALL_DEPENDENCIES` columns that exist on 10.2 are used. A failure
 * to read `ALL_SOURCE` is not an error: the comparison then skips the source classes; nor is a failure to read
 * `ALL_PLSQL_OBJECT_SETTINGS`: the flags are then not checked. Messages never contain the URL or the password.
 */
object OracleDependencyReader {
    private val LOG = Logger.getLogger(OracleDependencyReader::class.java.name)

    private const val OBJECT_TYPES = "'PACKAGE','PACKAGE BODY','PROCEDURE','FUNCTION','TRIGGER','TYPE','TYPE BODY','VIEW','MATERIALIZED VIEW'"

    fun read(settings: OracleSettings, driversDirectory: Path?): DatabaseDependencies {
        val owners = settings.schemas
        if (owners.isEmpty()) throw CliExecutionException("compare-deps needs --oracle-schemas: the owners of the indexed code")
        val driver = try {
            JdbcDrivers.find(settings.url, driversDirectory)
        } catch (e: JdbcDrivers.NoDriverException) {
            throw CliExecutionException(e.message!!)
        }
        val marks = owners.joinToString(",") { "?" }
        try {
            JdbcDriverDataSource(driver, settings.url, settings.user, settings.password).connection.use { connection ->
                connection.isReadOnly = true
                connection.autoCommit = false
                try {
                    val dependencies = connection.rows(
                        """SELECT owner, name, type, referenced_owner, referenced_name, referenced_type, referenced_link_name
                           FROM all_dependencies WHERE owner IN ($marks)""", owners
                    ) { DbDependency(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getString(7)) }
                    val objects = connection.rows(
                        "SELECT owner, object_name, object_type, status FROM all_objects WHERE owner IN ($marks) AND object_type IN ($OBJECT_TYPES)", owners
                    ) { DbObject(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
                    var sourceReadable = true
                    val sources = try {
                        connection.rows(
                            "SELECT owner, name, type, line, text FROM all_source WHERE owner IN ($marks) AND type IN ($OBJECT_TYPES)", owners
                        ) { DbSourceLine(it.getString(1), it.getString(2), it.getString(3), it.getInt(4), it.getString(5) ?: "") }
                    } catch (e: SQLException) {
                        LOG.warning("ALL_SOURCE is not readable (error ${e.errorCode}); source comparison skipped")
                        sourceReadable = false
                        emptyList()
                    } + if (sourceReadable) viewTexts(connection, marks, owners) + materializedViewQueries(connection, marks, owners) else emptyList()
                    val triggerColumns = connection.rows(
                        """SELECT trigger_owner, trigger_name, table_owner, table_name, column_name, column_list, column_usage
                           FROM all_trigger_cols WHERE trigger_owner IN ($marks)""", owners
                    ) { DbTriggerColumn(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6) == "YES", it.getString(7) ?: "NONE") }
                    // PLSQL_DEBUG of the triggers, PLSQL_CCFLAGS of every unit.
                    var ccflagsReadable = true
                    val settings = try {
                        connection.rows(
                            "SELECT owner, name, type, plsql_debug, plsql_ccflags FROM all_plsql_object_settings WHERE owner IN ($marks) AND type IN ($OBJECT_TYPES)", owners
                        ) { DbCcFlags(it.getString(1), it.getString(2), it.getString(3), it.getString(5)) to (it.getString(4) == "TRUE") }
                    } catch (e: SQLException) {
                        LOG.warning("ALL_PLSQL_OBJECT_SETTINGS is not readable (error ${e.errorCode}); every trigger counts as compiled without debug, PLSQL_CCFLAGS not checked")
                        ccflagsReadable = false
                        emptyList()
                    }
                    val triggerSettings = settings.filter { it.first.type == "TRIGGER" }.map { (unit, debug) -> DbTriggerSetting(unit.owner, unit.name, debug) }
                    val ccflags = settings.map { it.first }
                    val version = DatabaseVersionReader.read(connection)
                    version.failure?.let { LOG.warning("DBMS_DB_VERSION is not readable (error ${it.errorCode}); using the JDBC driver's database version") }
                    LOG.info("Read ${dependencies.size} dependencies, ${objects.size} objects, ${sources.size} source lines, ${triggerColumns.size} trigger columns, " +
                        "${ccflags.size} unit settings for $owners; database version ${ConditionalCompilationText.version(version.version)} (${version.origin})")
                    return DatabaseDependencies(dependencies, objects, sources, sourceReadable, triggerColumns, triggerSettings, ccflagsReadable, ccflags,
                        ConditionalCompilationText.version(version.version), version.origin.name)
                } finally {
                    connection.rollback()
                }
            }
        } catch (e: SQLException) {
            throw CliExecutionException("Reading the dependencies failed (error ${e.errorCode}): ${OracleMetadataLoader.redact(e.message, settings)}")
        }
    }

    /**
     * The stored text of every view (`ALL_VIEWS.TEXT`), as `VIEW` source lines. Empty, with a warning, when the view
     * is not readable; `ALL_SOURCE` is not affected.
     */
    private fun viewTexts(connection: Connection, marks: String, owners: List<String>): List<DbSourceLine> = try {
        storedQueries(connection, "SELECT owner, view_name, text FROM all_views WHERE owner IN ($marks)", owners, "VIEW")
    } catch (e: SQLException) {
        LOG.warning("ALL_VIEWS is not readable (error ${e.errorCode}); views are compared as without stored source")
        emptyList()
    }

    /**
     * The stored query of every materialized view (`ALL_MVIEWS.QUERY`), as `MATERIALIZED VIEW` source lines. Empty,
     * with a warning, when the view is not readable; `ALL_SOURCE` and `ALL_VIEWS` are not affected.
     */
    private fun materializedViewQueries(connection: Connection, marks: String, owners: List<String>): List<DbSourceLine> = try {
        storedQueries(connection, "SELECT owner, mview_name, query FROM all_mviews WHERE owner IN ($marks)", owners, "MATERIALIZED VIEW")
    } catch (e: SQLException) {
        LOG.warning("ALL_MVIEWS is not readable (error ${e.errorCode}); materialized views are compared as without stored source")
        emptyList()
    }

    /**
     * The owner, name and `LONG` text read by [sql], as [type] source lines: split after each line end, numbered from 1,
     * so that joining them gives the text back. The `LONG` is last in the select list (10.2 has no `TEXT_VC`): the
     * driver returns it whole, but it can be read only once and only in column order.
     */
    private fun storedQueries(connection: Connection, sql: String, owners: List<String>, type: String): List<DbSourceLine> =
        connection.rows(sql, owners) { rs ->
            val owner = rs.getString(1)
            val name = rs.getString(2)
            val text = rs.getString(3) ?: ""
            Regex("(?:[^\n]*\n)|(?:[^\n]+$)").findAll(text).mapIndexed { i, line -> DbSourceLine(owner, name, type, i + 1, line.value) }.toList()
        }.flatten()

    private fun <T> Connection.rows(sql: String, parameters: List<String>, map: (java.sql.ResultSet) -> T): List<T> =
        prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { i, value -> statement.setString(i + 1, value) }
            statement.fetchSize = 1000
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
}
