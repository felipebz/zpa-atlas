//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli.oracle

import com.felipebz.zpa.api.project.ProjectExternalMetadata
import com.felipebz.zpa.atlas.cli.CliExecutionException
import com.felipebz.zpa.atlas.cli.CliValidationException
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionaryKey
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import com.felipebz.zpa.atlas.extract.SynonymResolver
import com.felipebz.zpa.atlas.model.CompiledUnit
import com.felipebz.zpa.atlas.model.KeyKind
import com.felipebz.zpa.oracle.metadata.OracleMetadataAcquisition
import com.felipebz.zpa.oracle.metadata.OracleMetadataException
import com.felipebz.zpa.oracle.metadata.OracleMetadataResult
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * The acquired metadata, the synonyms read with it, the object names of the compilation and requested schemas, by
 * schema, with their `OBJECT_TYPE`, the keys and `NOT NULL` columns of the relations read ([AtlasDictionary.keys],
 * [AtlasDictionary.notNull]), whether the user sees every table's constraints ([AtlasDictionary.seesEveryTable]), and
 * the version of the database.
 */
class LoadedMetadata(
    val result: OracleMetadataResult,
    val synonyms: List<DictionarySynonym>,
    val objects: Map<String, Map<String, String>>,
    val databaseVersion: DetectedDatabaseVersion,
    val keys: List<DictionaryKey> = emptyList(),
    val notNull: Map<Pair<String, String>, Set<String>> = emptyMap(),
    val seesEveryTable: Boolean = true,
    val lengthSemantics: Map<CompiledUnit, String> = emptyMap()
)

/**
 * Acquires Oracle metadata once per run, before scanning, exactly as zpa-cli does, plus the synonyms of relations,
 * packages, types, subprograms and sequences (public ones, except those named like a `STANDARD` subprogram, and those
 * of the compilation and requested schemas). The final targets that are relations are added to the request. The
 * database version ([DatabaseVersionReader]) is read once, after the schemas check.
 * Failures abort the run with a message from which the URL and the password are removed: no partial metadata is ever
 * used. A schema that does not exist is rejected up front: the dictionary would otherwise return nothing for it,
 * silently.
 */
object OracleMetadataLoader {
    private val LOG = Logger.getLogger(OracleMetadataLoader::class.java.name)

    /**
     * Synonyms without database link whose target is a table, a view, a package, a type, a subprogram, an operator, a
     * sequence or a synonym the session can see, with the target's object type (the scalar subquery gives one row per
     * synonym; an evolved type has several `ALL_OBJECTS` rows). Bodies are left out: they would duplicate rows. Only the
     * columns of `ALL_SYNONYMS` on 10g are read.
     */
    private const val SYNONYMS_SQL = """SELECT owner, synonym_name, table_owner, table_name, object_type FROM (
  SELECT s.owner, s.synonym_name, s.table_owner, s.table_name,
         (SELECT MIN(o.object_type) FROM all_objects o
           WHERE o.owner = s.table_owner AND o.object_name = s.table_name
             AND o.object_type IN ('TABLE', 'VIEW', 'MATERIALIZED VIEW', 'SYNONYM', 'PACKAGE', 'TYPE', 'FUNCTION', 'PROCEDURE', 'OPERATOR', 'SEQUENCE')) object_type
    FROM all_synonyms s
   WHERE s.owner IN (%s)
     AND s.db_link IS NULL)
 WHERE object_type IS NOT NULL"""

    /** The subprograms of `SYS.STANDARD`: a one-segment name of one of them is never a public synonym (STANDARD wins). */
    private const val STANDARD_SQL = "SELECT DISTINCT procedure_name FROM all_procedures WHERE owner = 'SYS' AND object_name = 'STANDARD' AND procedure_name IS NOT NULL"

    /**
     * The names in the namespace of schema objects Oracle searches before public synonyms. An evolved type has several
     * rows: one per name is kept. A materialized view has a `MATERIALIZED VIEW` and a `TABLE` row of the same name:
     * `MIN` keeps the first (`'MATERIALIZED VIEW' < 'TABLE'`), here and for synonym targets.
     */
    private const val OBJECTS_SQL = """SELECT owner, object_name, MIN(object_type) FROM all_objects
 WHERE owner IN (%s)
   AND object_type IN ('TABLE', 'VIEW', 'MATERIALIZED VIEW', 'SYNONYM', 'SEQUENCE', 'PACKAGE', 'TYPE', 'FUNCTION', 'PROCEDURE', 'OPERATOR')
 GROUP BY owner, object_name"""

    /**
     * The primary, unique and foreign keys matching `%s`, with their columns in key order. Only the columns of
     * `ALL_CONSTRAINTS` and `ALL_CONS_COLUMNS` on 10g are read. The constraints of a table in the recycle bin are left
     * out.
     */
    private const val KEYS_SQL = """SELECT c.owner, c.constraint_name, c.constraint_type, c.table_name, c.status, c.delete_rule,
       c.r_owner, c.r_constraint_name, cc.column_name
  FROM all_constraints c
  JOIN all_cons_columns cc ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name AND cc.table_name = c.table_name
 WHERE c.constraint_type IN ('P', 'U', 'R')
   AND c.table_name NOT LIKE 'BIN${'$'}%%'
   AND (%s)
 ORDER BY c.owner, c.constraint_name, cc.position"""

    /** The `NOT NULL` columns matching `%s` (`NULLABLE` is on 10g). */
    private const val NOT_NULL_SQL = "SELECT owner, table_name, column_name FROM all_tab_cols WHERE nullable = 'N' AND (%s)"

    /** The `NLS_LENGTH_SEMANTICS` each PL/SQL unit of the owners `%s` was compiled with. */
    private const val LENGTH_SEMANTICS_SQL = "SELECT owner, name, type, nls_length_semantics FROM all_plsql_object_settings WHERE owner IN (%s)"

    /** Pairs in one `IN` list: Oracle takes at most 1000 expressions in a list. */
    private const val PAIRS_PER_QUERY = 500

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

        val databaseVersion = try {
            dataSource.connection.use { DatabaseVersionReader.read(it) }
        } catch (e: SQLException) {
            throw CliExecutionException("Reading the database version failed${errorCode(e)}: ${redact(e.message, settings)}")
        }

        val owners = (listOf(AtlasDictionary.PUBLIC) + settings.schemas + listOfNotNull(compilationSchema)).distinct()
        val started = System.nanoTime()
        val synonyms = try {
            val standard = standardNames(dataSource)
            synonyms(dataSource, owners).filterNot { it.owner == AtlasDictionary.PUBLIC && it.name in standard }
        } catch (e: SQLException) {
            throw CliExecutionException("Reading the synonyms failed${errorCode(e)}: ${redact(e.message, settings)}")
        } catch (e: RuntimeException) {
            throw CliExecutionException("Reading the synonyms failed: ${redact(e.message, settings)}")
        }
        val objectsStarted = System.nanoTime()
        val objects = try {
            objects(dataSource, (settings.schemas + listOfNotNull(compilationSchema)).distinct())
        } catch (e: SQLException) {
            throw CliExecutionException("Reading the schema objects failed${errorCode(e)}: ${redact(e.message, settings)}")
        }
        val targets = synonymTargets(synonyms.filter { it.name in writtenNames }, synonyms, settings.schemas.toSet())
        LOG.info("Synonyms: ${synonyms.size} read in ${(objectsStarted - started) / 1_000_000} ms; ${targets.size} target relation(s) requested; " +
            "${objects.values.sumOf { it.size }} object name(s) of ${objects.keys} read in ${(System.nanoTime() - objectsStarted) / 1_000_000} ms")

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
        val pairs = (settings.relations + targets).distinct()
        val (keys, notNull, seesEveryTable) = try {
            dataSource.connection.use { connection ->
                Triple(keys(connection, settings.schemas, pairs), notNull(connection, settings.schemas, pairs), seesEveryTable(connection))
            }
        } catch (e: SQLException) {
            throw CliExecutionException("Reading the keys and NOT NULL columns failed${errorCode(e)}: ${redact(e.message, settings)}")
        }
        LOG.info("Keys: ${keys.groupingBy { it.kind }.eachCount()}, ${notNull.values.sumOf { it.size }} NOT NULL column(s)" +
            (if (seesEveryTable) "" else "; ${settings.user} has none of ${AtlasDictionary.EVERY_TABLE_PRIVILEGES}: foreign keys of tables it cannot see are not read"))
        val lengthSemantics = try {
            dataSource.connection.use { connection -> lengthSemantics(connection, schemas) }
        } catch (e: SQLException) {
            throw CliExecutionException("Reading the PL/SQL compilation settings failed${errorCode(e)}: ${redact(e.message, settings)}")
        }
        return LoadedMetadata(result, synonyms, objects, databaseVersion, keys, notNull, seesEveryTable, lengthSemantics)
    }

    /** Runs [read] and logs how long it took and how many [rows] it read, as [what]. */
    private inline fun <T> timed(what: String, rows: (T) -> Int, read: () -> T): T {
        val started = System.nanoTime()
        val result = read()
        LOG.info("$what: ${rows(result)} row(s) in ${(System.nanoTime() - started) / 1_000_000} ms")
        return result
    }

    /** The `NLS_LENGTH_SEMANTICS` of each PL/SQL unit of [owners] (`ALL_PLSQL_OBJECT_SETTINGS`), by unit. */
    private fun lengthSemantics(connection: Connection, owners: List<String>): Map<CompiledUnit, String> {
        if (owners.isEmpty()) return emptyMap()
        val result = HashMap<CompiledUnit, String>()
        timed("PL/SQL length semantics", { it }) {
            connection.prepareStatement(LENGTH_SEMANTICS_SQL.format(owners.joinToString(",") { "?" })).use { statement ->
                owners.forEachIndexed { index, owner -> statement.setString(index + 1, owner) }
                statement.executeQuery().use { rows ->
                    while (rows.next()) rows.getString(4)?.let { result[CompiledUnit(rows.getString(1), rows.getString(2), rows.getString(3))] = it }
                }
            }
            result.size
        }
        return result
    }

    /**
     * Whether the session has a system privilege with which `ALL_CONSTRAINTS` lists every table's constraints
     * ([AtlasDictionary.EVERY_TABLE_PRIVILEGES], from `SESSION_PRIVS`, its roles' included). Without one, it lists only
     * those of the tables the user owns or was granted a privilege on.
     */
    private fun seesEveryTable(connection: Connection): Boolean = timed("Privileges that see every table", { it.size }) {
        val privileges = AtlasDictionary.EVERY_TABLE_PRIVILEGES
        connection.prepareStatement("SELECT privilege FROM session_privs WHERE privilege IN (${privileges.joinToString(",") { "?" }})").use { statement ->
            privileges.forEachIndexed { index, privilege -> statement.setString(index + 1, privilege) }
            statement.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
        }
    }.isNotEmpty()

    /**
     * The keys of the relations the dictionary reads (those of [schemas], and the [pairs] `owner to table`), the keys
     * their foreign keys reference, whatever their relation, and the foreign keys of any relation the session sees that
     * reference one of their primary or unique keys; in owner and name order.
     *
     * Parsing a statement over `ALL_CONSTRAINTS` takes about a second on 26ai (running it again takes nothing): one
     * statement reads the own keys and the foreign keys of other owners that may reference them, and a second one, only
     * when a foreign key references a key not read yet, reads those keys.
     */
    private fun keys(connection: Connection, schemas: List<String>, pairs: List<Pair<String, String>>): List<DictionaryKey> {
        val read = ArrayList<DictionaryKey>()
        fun query(what: String, filter: String, parameters: List<String>) = timed(what, { it }) {
            var count = 0
            connection.prepareStatement(KEYS_SQL.format(filter)).use { statement ->
                parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { rows ->
                    var current: DictionaryKey? = null
                    while (rows.next()) {
                        count++
                        val (owner, name, column) = Triple(rows.getString(1), rows.getString(2), rows.getString(9))
                        val key = current
                        if (key != null && key.owner == owner && key.name == name) {
                            current = key.copy(columns = key.columns + column)
                            continue
                        }
                        key?.let { read += it }
                        val kind = KeyKind.of(rows.getString(3))
                        current = DictionaryKey(owner, name, kind, owner, rows.getString(4), listOf(column), rows.getString(5) == "ENABLED",
                            rows.getString(6).takeIf { kind == KeyKind.FOREIGN_KEY }, rows.getString(7), rows.getString(8))
                    }
                    current?.let { read += it }
                }
            }
            count
        }
        fun placeholders(count: Int) = List(count) { "?" }.joinToString(",")
        fun pairList(chunk: List<Pair<String, String>>) = chunk.joinToString(", ") { "(?, ?)" }
        val owners = (schemas + pairs.map { it.first }).distinct()
        if (owners.isEmpty()) return emptyList()
        val chunks = pairs.chunked(PAIRS_PER_QUERY)
        val first = listOfNotNull(
            "c.owner IN (${placeholders(schemas.size)})".takeIf { schemas.isNotEmpty() },
            chunks.firstOrNull()?.let { "(c.owner, c.table_name) IN (${pairList(it)})" },
            "(c.constraint_type = 'R' AND c.r_owner IN (${placeholders(owners.size)}))"
        )
        query("Keys of the relations read, and foreign keys of other owners", first.joinToString(" OR "),
            schemas + chunks.firstOrNull().orEmpty().flatMap { listOf(it.first, it.second) } + owners)
        chunks.drop(1).forEach { chunk -> query("Keys of more relations read", "(c.owner, c.table_name) IN (${pairList(chunk)})", chunk.flatMap { listOf(it.first, it.second) }) }
        // The keys of the relations read; then the foreign keys of other relations that reference one of them.
        val schemaSet = schemas.toSet()
        val pairSet = pairs.toSet()
        val result = LinkedHashMap<Pair<String, String>, DictionaryKey>()
        read.filter { it.owner in schemaSet || (it.tableOwner to it.tableName) in pairSet }.forEach { result.putIfAbsent(it.owner to it.name, it) }
        val referenced = result.values.filter { it.kind != KeyKind.FOREIGN_KEY }.mapTo(HashSet()) { it.owner to it.name }
        read.filter { it.kind == KeyKind.FOREIGN_KEY && (it.referencedOwner to it.referencedName) in referenced }.forEach { result.putIfAbsent(it.owner to it.name, it) }
        // The keys their foreign keys reference that are not read yet: those of relations the dictionary does not read.
        val missing = result.values.mapNotNull { key -> key.referencedName?.let { key.referencedOwner!! to it } }.filter { it !in result }.distinct()
        read.clear()
        missing.chunked(PAIRS_PER_QUERY).forEach { chunk ->
            query("Keys referenced outside the relations read", "(c.owner, c.constraint_name) IN (${pairList(chunk)})", chunk.flatMap { listOf(it.first, it.second) })
        }
        read.forEach { result.putIfAbsent(it.owner to it.name, it) }
        return result.values.sortedWith(compareBy({ it.owner }, { it.name }))
    }

    /** The `NOT NULL` columns of the relations of [schemas] and of the [pairs], by `owner to table`, in one statement as a rule. */
    private fun notNull(connection: Connection, schemas: List<String>, pairs: List<Pair<String, String>>): Map<Pair<String, String>, Set<String>> {
        val result = HashMap<Pair<String, String>, MutableSet<String>>()
        fun read(filter: String, parameters: List<String>) = timed("NOT NULL columns", { it }) {
            var count = 0
            connection.prepareStatement(NOT_NULL_SQL.format(filter)).use { statement ->
                parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        count++
                        result.getOrPut(rows.getString(1) to rows.getString(2)) { HashSet() } += rows.getString(3)
                    }
                }
            }
            count
        }
        fun pairs(chunk: List<Pair<String, String>>) = "(owner, table_name) IN (${chunk.joinToString(", ") { "(?, ?)" }})"
        val chunks = pairs.chunked(PAIRS_PER_QUERY)
        val first = listOfNotNull("owner IN (${schemas.joinToString(",") { "?" }})".takeIf { schemas.isNotEmpty() }, chunks.firstOrNull()?.let(::pairs))
        if (first.isNotEmpty()) read(first.joinToString(" OR "), schemas + chunks.firstOrNull().orEmpty().flatMap { listOf(it.first, it.second) })
        chunks.drop(1).forEach { chunk -> read(pairs(chunk), chunk.flatMap { listOf(it.first, it.second) }) }
        return result
    }

    private fun synonyms(dataSource: DataSource, owners: List<String>): List<DictionarySynonym> {
        val result = ArrayList<DictionarySynonym>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(SYNONYMS_SQL.format(owners.joinToString(",") { "?" })).use { statement ->
                owners.forEachIndexed { index, owner -> statement.setString(index + 1, owner) }
                statement.executeQuery().use { rows ->
                    while (rows.next()) result += DictionarySynonym(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5))
                }
            }
        }
        return result
    }

    /** Every schema of [owners] gets an entry, even without objects: its namespace is known. */
    private fun objects(dataSource: DataSource, owners: List<String>): Map<String, Map<String, String>> {
        val result = owners.associateWithTo(LinkedHashMap()) { HashMap<String, String>() }
        dataSource.connection.use { connection ->
            connection.prepareStatement(OBJECTS_SQL.format(owners.joinToString(",") { "?" })).use { statement ->
                owners.forEachIndexed { index, owner -> statement.setString(index + 1, owner) }
                statement.executeQuery().use { rows -> while (rows.next()) result.getValue(rows.getString(1))[rows.getString(2)] = rows.getString(3) }
            }
        }
        return result
    }

    private fun standardNames(dataSource: DataSource): Set<String> {
        val result = HashSet<String>()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(STANDARD_SQL).use { rows -> while (rows.next()) result += rows.getString(1) }
            }
        }
        return result
    }

    /**
     * The final targets that are relations of the [named] synonyms (chains followed through [all], cycles dropped)
     * outside the [requestedSchemas], which are read completely anyway. Other targets are not acquired.
     */
    internal fun synonymTargets(named: List<DictionarySynonym>, all: List<DictionarySynonym>, requestedSchemas: Set<String>): Set<Pair<String, String>> {
        val table = AtlasDictionary.lookupOnly(ProjectExternalMetadata.none(), emptySet(), all)
        return named.mapNotNullTo(LinkedHashSet()) { synonym ->
            table.synonymChain(synonym.owner, synonym.name)?.last()?.takeIf { it.targetType in SynonymResolver.RELATIONS }
                ?.let { it.targetOwner to it.targetName }
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
