//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.devtools

import com.felipebz.zpa.atlas.cli.CliExecutionException
import com.felipebz.zpa.atlas.cli.oracle.JdbcDriverDataSource
import com.felipebz.zpa.atlas.cli.oracle.JdbcDrivers
import com.felipebz.zpa.atlas.cli.oracle.OracleMetadataLoader
import com.felipebz.zpa.atlas.cli.oracle.OracleSettings
import com.felipebz.zpa.atlas.compare.DatabaseDependencies
import com.felipebz.zpa.atlas.compare.DbDependency
import com.felipebz.zpa.atlas.compare.DbObject
import com.felipebz.zpa.atlas.compare.DbSourceLine
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger

/**
 * Reads `ALL_DEPENDENCIES`, `ALL_OBJECTS` and `ALL_SOURCE` for the requested owners, in one read-only transaction.
 * Only the seven `ALL_DEPENDENCIES` columns that exist on 10.2 are used. A failure to read `ALL_SOURCE` is not an
 * error: the comparison then skips the source classes. Messages never contain the URL or the password.
 */
object OracleDependencyReader {
    private val LOG = Logger.getLogger(OracleDependencyReader::class.java.name)

    private const val OBJECT_TYPES = "'PACKAGE','PACKAGE BODY','PROCEDURE','FUNCTION','TRIGGER','TYPE','TYPE BODY','VIEW'"

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
                    }
                    LOG.info("Read ${dependencies.size} dependencies, ${objects.size} objects, ${sources.size} source lines for $owners")
                    return DatabaseDependencies(dependencies, objects, sources, sourceReadable)
                } finally {
                    connection.rollback()
                }
            }
        } catch (e: SQLException) {
            throw CliExecutionException("Reading the dependencies failed (error ${e.errorCode}): ${OracleMetadataLoader.redact(e.message, settings)}")
        }
    }

    private fun <T> Connection.rows(sql: String, parameters: List<String>, map: (java.sql.ResultSet) -> T): List<T> =
        prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { i, value -> statement.setString(i + 1, value) }
            statement.fetchSize = 1000
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
}
