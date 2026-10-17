//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli.oracle

import com.felipebz.zpa.api.project.ProjectExternalMetadata
import com.felipebz.zpa.atlas.cli.CliExecutionException
import com.felipebz.zpa.atlas.cli.CliValidationException
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import com.felipebz.zpa.oracle.metadata.OracleMetadataAcquisition
import com.felipebz.zpa.oracle.metadata.OracleMetadataException
import com.felipebz.zpa.oracle.metadata.OracleMetadataResult
import java.nio.file.Path
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

/** The acquired metadata and the synonyms of relations read with it. */
class LoadedMetadata(val result: OracleMetadataResult, val synonyms: List<DictionarySynonym>)

/**
 * Acquires Oracle metadata once per run, before scanning, exactly as zpa-cli does, plus the synonyms of relations
 * (public ones and those of the compilation and requested schemas), whose final targets are added to the request.
 * Failures abort the run with a message from which the URL and the password are removed: no partial metadata is ever
 * used. A schema that does not exist is rejected up front: the dictionary would otherwise return nothing for it,
 * silently.
 */
object OracleMetadataLoader {
    private val LOG = Logger.getLogger(OracleMetadataLoader::class.java.name)

    /**
     * Synonyms without database link whose target is a table, a view or a synonym the session can see (this keeps out
     * the public synonyms of packages, types and Java classes). Only the columns of `ALL_SYNONYMS` on 10g are read.
     */
    private const val SYNONYMS_SQL = """SELECT s.owner, s.synonym_name, s.table_owner, s.table_name
  FROM all_synonyms s
 WHERE s.owner IN (%s)
   AND s.db_link IS NULL
   AND EXISTS (SELECT 1 FROM all_objects o
                WHERE o.owner = s.table_owner AND o.object_name = s.table_name
                  AND o.object_type IN ('TABLE', 'VIEW', 'SYNONYM'))"""

    /**
     * @param compilationSchema checked for existence with the requested schemas; null when not configured
     * @param writtenNames the identifiers of the sources ([com.felipebz.zpa.atlas.index.SourceIdentifiers]): only the
     *   targets of synonyms named there are acquired (all of them would add seconds for thousands of public synonyms)
     */
    fun load(settings: OracleSettings, compilationSchema: String?, driversDirectory: Path?, writtenNames: Set<String>): LoadedMetadata {
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

        val owners = (listOf(AtlasDictionary.PUBLIC) + settings.schemas + listOfNotNull(compilationSchema)).distinct()
        val started = System.nanoTime()
        val synonyms = try {
            synonyms(dataSource, owners)
        } catch (e: SQLException) {
            throw CliExecutionException("Reading the synonyms failed${errorCode(e)}: ${redact(e.message, settings)}")
        } catch (e: RuntimeException) {
            throw CliExecutionException("Reading the synonyms failed: ${redact(e.message, settings)}")
        }
        val targets = synonymTargets(synonyms.filter { it.name in writtenNames }, synonyms, settings.schemas.toSet())
        LOG.info("Synonyms: ${synonyms.size} read in ${(System.nanoTime() - started) / 1_000_000} ms; ${targets.size} target relation(s) requested")

        LOG.info("Reading Oracle metadata as ${settings.user} (schemas ${settings.schemas}, relations ${settings.relations.map { "${it.first}.${it.second}" }})...")
        val acquisitionStarted = System.nanoTime()
        val result = try {
            OracleMetadataAcquisition.acquire(dataSource, settings.request(targets))
        } catch (e: OracleMetadataException) {
            throw CliExecutionException("Oracle metadata acquisition failed: ${redact(e.message, settings)}")
        } catch (e: SQLException) {
            throw CliExecutionException("Oracle metadata acquisition failed${errorCode(e)}: ${redact(e.message, settings)}")
        } catch (e: RuntimeException) {
            throw CliExecutionException("Oracle metadata acquisition failed: ${redact(e.message, settings)}")
        }
        val requested = settings.relations.toSet()
        val (notVisible, synonymTargetsNotVisible) = result.notVisible.partition { (it.schema to it.name) in requested }
        LOG.info("Oracle metadata: ${result.relationCount} relations, ${result.columnCount} columns in ${(System.nanoTime() - acquisitionStarted) / 1_000_000} ms; " +
            "complete schemas ${result.completeSchemas}" +
            (if (notVisible.isEmpty()) "" else "; not visible $notVisible") +
            (if (synonymTargetsNotVisible.isEmpty()) "" else "; ${synonymTargetsNotVisible.size} synonym target(s) not returned"))
        return LoadedMetadata(result, synonyms)
    }

    private fun synonyms(dataSource: DataSource, owners: List<String>): List<DictionarySynonym> {
        val result = ArrayList<DictionarySynonym>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(SYNONYMS_SQL.format(owners.joinToString(",") { "?" })).use { statement ->
                owners.forEachIndexed { index, owner -> statement.setString(index + 1, owner) }
                statement.executeQuery().use { rows ->
                    while (rows.next()) result += DictionarySynonym(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4))
                }
            }
        }
        return result
    }

    /**
     * The final targets of the [named] synonyms (chains followed through [all], cycles dropped) outside the
     * [requestedSchemas], which are read completely anyway.
     */
    private fun synonymTargets(named: List<DictionarySynonym>, all: List<DictionarySynonym>, requestedSchemas: Set<String>): Set<Pair<String, String>> {
        val table = AtlasDictionary.lookupOnly(ProjectExternalMetadata.none(), emptySet(), all)
        return named.mapNotNullTo(LinkedHashSet()) { synonym ->
            table.synonymChain(synonym.owner, synonym.name)?.last()?.let { it.targetOwner to it.targetName }
        }.filterTo(LinkedHashSet()) { it.first !in requestedSchemas }
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
