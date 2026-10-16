package com.felipebz.zpa.atlas.cli.oracle

import com.felipebz.zpa.atlas.cli.CliExecutionException
import com.felipebz.zpa.atlas.cli.CliValidationException
import com.felipebz.zpa.oracle.metadata.OracleMetadataAcquisition
import com.felipebz.zpa.oracle.metadata.OracleMetadataException
import com.felipebz.zpa.oracle.metadata.OracleMetadataResult
import java.nio.file.Path
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Acquires Oracle metadata once per run, before scanning, exactly as zpa-cli does. Failures abort the run with a
 * message from which the URL and the password are removed: no partial metadata is ever used. A schema that does not
 * exist is rejected up front: the dictionary would otherwise return nothing for it, silently.
 */
object OracleMetadataLoader {
    private val LOG = Logger.getLogger(OracleMetadataLoader::class.java.name)

    /** @param compilationSchema checked for existence with the requested schemas; null when not configured */
    fun load(settings: OracleSettings, compilationSchema: String?, driversDirectory: Path?): OracleMetadataResult {
        val driver = try {
            JdbcDrivers.find(settings.url, driversDirectory)
        } catch (e: JdbcDrivers.NoDriverException) {
            throw CliExecutionException(e.message!!)
        } catch (e: SQLException) {
            throw CliExecutionException("JDBC driver lookup failed${errorCode(e)}: ${redact(e.message, settings)}")
        } catch (e: RuntimeException) {
            throw CliExecutionException("JDBC driver lookup failed: ${redact(e.message, settings)}")
        }
        val dataSource = JdbcDriverDataSource(driver, settings.url, settings.user, settings.password)
        val schemas = (settings.schemas + settings.relations.map { it.first } + listOfNotNull(compilationSchema)).distinct()
        val missing = try {
            missingSchemas(dataSource, schemas)
        } catch (e: SQLException) {
            throw CliExecutionException("Checking the requested schemas failed${errorCode(e)}: ${redact(e.message, settings)}")
        }
        if (missing.isNotEmpty()) {
            throw CliValidationException("Schema(s) not found in the database: ${missing.joinToString()}. " +
                "Unquoted names are case-insensitive; a schema created with a quoted name needs the exact case, e.g. \"Name\"")
        }
        LOG.info("Reading Oracle metadata as ${settings.user} (schemas ${settings.schemas}, relations ${settings.relations.map { "${it.first}.${it.second}" }})...")
        val result = try {
            OracleMetadataAcquisition.acquire(dataSource, settings.request())
        } catch (e: OracleMetadataException) {
            throw CliExecutionException("Oracle metadata acquisition failed: ${redact(e.message, settings)}")
        } catch (e: SQLException) {
            throw CliExecutionException("Oracle metadata acquisition failed${errorCode(e)}: ${redact(e.message, settings)}")
        } catch (e: RuntimeException) {
            throw CliExecutionException("Oracle metadata acquisition failed: ${redact(e.message, settings)}")
        }
        LOG.info("Oracle metadata: ${result.relationCount} relations, ${result.columnCount} columns; complete schemas ${result.completeSchemas}" +
            (if (result.notVisible.isEmpty()) "" else "; not visible ${result.notVisible}"))
        return result
    }

    /** The [schemas] without a user in `ALL_USERS`, which lists every user whatever the session privileges. */
    private fun missingSchemas(dataSource: DataSource, schemas: List<String>): List<String> {
        val placeholders = schemas.joinToString(",") { "?" }
        val existing = HashSet<String>()
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT username FROM all_users WHERE username IN ($placeholders)").use { statement ->
                schemas.forEachIndexed { index, schema -> statement.setString(index + 1, schema) }
                statement.executeQuery().use { rows -> while (rows.next()) existing.add(rows.getString(1)) }
            }
        }
        return schemas.filter { it !in existing }
    }

    private fun errorCode(e: SQLException) = if (e.errorCode != 0) " (error ${e.errorCode})" else ""

    /** Removes the URL, the password and any `user/password@` fragment from a driver message. */
    fun redact(message: String?, settings: OracleSettings): String {
        var text = message ?: "unknown error"
        text = text.replace(settings.url, "<oracle-url>")
        if (settings.password.isNotEmpty()) text = text.replace(settings.password, "***")
        return text.replace(Regex("[^\\s/@:]+/[^\\s@]+@"), "***@")
    }
}
