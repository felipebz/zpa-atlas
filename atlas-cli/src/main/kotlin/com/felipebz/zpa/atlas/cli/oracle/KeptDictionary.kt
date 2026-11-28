//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli.oracle

import com.felipebz.zpa.atlas.cli.CliExecutionException
import com.felipebz.zpa.atlas.cli.oracle.KeptDictionary.inputs
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.oracle.metadata.OracleMetadataAcquisition
import com.felipebz.zpa.squid.AstScanner
import java.io.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.*
import java.util.logging.Logger
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import javax.sql.DataSource

/**
 * The Oracle dictionary of an `index --incremental` run, kept beside the index (`<index>.dictionary`) as the calls the
 * load made to the database and what each gave ([JdbcRecording]), so that the next run can load it again from them,
 * without reading the database, while the database has not changed.
 *
 * The next run replays it when:
 * - it was kept with the same [inputs]: the connection (URL and user, never the password), the requested schemas and
 *   relations, the compilation schema, the JDBC driver and the zpa build;
 * - the database check ([DatabaseCheck]) gives what it gave when the load was kept, over the schemas the load read;
 * - the load makes the very calls recorded: one that asks for anything else (a synonym target a source names now, a
 *   query of another build) stops the replay, and the dictionary is loaded from the database.
 *
 * Otherwise the dictionary is loaded from the database, and kept when nothing it read changed while it was read. The
 * file is written whole and moved into place, and ends with a checksum of what it holds: a file cut short or altered,
 * or of another format, is never replayed.
 */
internal object KeptDictionary {
    private val LOG = Logger.getLogger(KeptDictionary::class.java.name)

    /** Raised when what the file holds, or how the calls are written, changes. */
    private const val FORMAT = 1
    private const val MAGIC = "zpa-atlas dictionary"
    private const val END = "end of the dictionary"

    fun pathFor(index: Path): Path = index.toAbsolutePath().resolveSibling(index.fileName.toString() + ".dictionary")

    /** The dictionary loaded, and how: what the phase notes say. */
    class Outcome(val loaded: LoadedMetadata, val note: String)

    /** The dictionary of [settings], replayed from the file kept beside [index] when it may, else loaded and kept. */
    fun load(settings: OracleSettings, compilationSchema: String?, database: OracleMetadataLoader.ConnectedDatabase, writtenNames: Set<String>, index: Path): Outcome {
        val live = database.dataSource
        val path = pathFor(index)
        val inputs = inputs(settings, compilationSchema, database.driver)
        val kept = read(path)
        val reason = when {
            kept == null -> if (Files.exists(path)) "the kept dictionary cannot be used" else "no kept dictionary yet"
            kept.inputs != inputs -> "the kept dictionary was loaded with another ${inputs.keys.first { kept.inputs[it] != inputs[it] }}"
            else -> {
                val started = System.nanoTime()
                val state = check(settings) { DatabaseCheck.state(live, kept.owners) }
                val checked = (System.nanoTime() - started) / 1_000_000
                if (state.fingerprint != kept.fingerprint) "the database changed (checked in $checked ms)"
                else replay(settings, compilationSchema, writtenNames, kept)?.let { (loaded, replayed) ->
                    return Outcome(loaded, "Oracle dictionary: the kept one, ${kept.callCount} calls replayed in $replayed ms after the database check in $checked ms " +
                        "(${kept.owners.size} schemas, ${Files.size(path) / 1024} KB kept)")
                } ?: "the sources ask the dictionary for something else"
            }
        }
        LOG.info("Loading the Oracle dictionary: $reason")
        return Outcome(loadAndKeep(settings, compilationSchema, writtenNames, live, path, inputs, kept?.owners.orEmpty()), "Oracle dictionary: loaded, as $reason")
    }

    /** The dictionary replayed from [kept], and how long it took; null when the load asked for anything else. */
    private fun replay(settings: OracleSettings, compilationSchema: String?, writtenNames: Set<String>, kept: Kept): Pair<LoadedMetadata, Long>? {
        val started = System.nanoTime()
        val replaying = ReplayingDataSource(InflaterInputStream(ByteArrayInputStream(kept.bytes)), kept.callCount)
        val loaded = try {
            OracleMetadataLoader.load(settings, compilationSchema, replaying, writtenNames)
        } catch (e: ReplayMismatch) {
            LOG.info("The kept dictionary does not answer this load: ${e.message}")
            return null
        }
        if (!replaying.complete) {
            LOG.info("The kept dictionary does not answer this load: it holds calls this load did not make")
            return null
        }
        return loaded to (System.nanoTime() - started) / 1_000_000
    }

    /**
     * Loads the dictionary from the database, recording the calls, and keeps it when nothing it read changed while it
     * was read: the [provisional] schemas (those known before: the requested ones, `PUBLIC`, those of the previous
     * file) are checked before and after the load, and no object or key of any schema it read changed after the load
     * started.
     */
    private fun loadAndKeep(
        settings: OracleSettings, compilationSchema: String?, writtenNames: Set<String>, live: DataSource, path: Path, inputs: Map<String, String>,
        previous: Set<String>
    ): LoadedMetadata {
        val provisional = (settings.schemas + settings.relations.map { it.first } + listOfNotNull(compilationSchema, AtlasDictionary.PUBLIC) + previous).toSortedSet()
        val before = check(settings) { DatabaseCheck.state(live, provisional) }
        val recorder = JdbcRecorder()
        val loaded = OracleMetadataLoader.load(settings, compilationSchema, RecordingDataSource(live, recorder), writtenNames)
        val calls = recorder.finish()
        recorder.unusable?.let {
            LOG.info("The Oracle dictionary is not kept: $it")
            return loaded
        }
        val owners = check(settings) { DatabaseCheck.owners(live, recorder.strings) } + provisional
        val again = check(settings) { DatabaseCheck.state(live, provisional) }
        val after = check(settings) { DatabaseCheck.state(live, owners) }
        when {
            again.fingerprint != before.fingerprint -> LOG.info("The Oracle dictionary is not kept: the database changed while it was read")
            after.latest >= before.now -> LOG.info("The Oracle dictionary is not kept: an object it read changed at ${after.latest}, while it was read")
            else -> write(path, Kept(inputs, after.fingerprint, owners.toSortedSet(), recorder.calls, calls))
        }
        return loaded
    }

    private fun <T> check(settings: OracleSettings, read: () -> T): T = try {
        read()
    } catch (e: SQLException) {
        throw CliExecutionException("Checking the Oracle dictionary failed${if (e.errorCode != 0) " (error ${e.errorCode})" else ""}: " +
            OracleMetadataLoader.redact(e.message, settings))
    }

    /** What the load depends on besides the database: the password is never one of them. */
    private fun inputs(settings: OracleSettings, compilationSchema: String?, driver: Driver): Map<String, String> = linkedMapOf(
        "format" to FORMAT.toString(),
        "URL" to settings.url,
        "user" to settings.user,
        "schemas" to settings.schemas.joinToString(","),
        "relations" to settings.relations.joinToString(",") { "${it.first}.${it.second}" },
        "compilation schema" to compilationSchema.orEmpty(),
        "JDBC driver" to "${driver.javaClass.name} ${driver.majorVersion}.${driver.minorVersion} ${jarDigest(driver.javaClass)}",
        "ZPA build" to listOf(AstScanner::class.java, OracleMetadataAcquisition::class.java).joinToString(" ") { jarDigest(it) }
    )

    private fun jarDigest(type: Class<*>): String {
        val location = type.protectionDomain?.codeSource?.location ?: return "unknown"
        val path = Path.of(location.toURI())
        if (!Files.isRegularFile(path)) return "classes"
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
    }

    /** A kept dictionary: its inputs, the database check it gave, the schemas the load read, and the calls, compressed. */
    private class Kept(val inputs: Map<String, String>, val fingerprint: String, val owners: Set<String>, val callCount: Long, val bytes: ByteArray)

    private fun write(target: Path, kept: Kept) {
        val temporary = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        try {
            DataOutputStream(Files.newOutputStream(temporary).buffered(1 shl 16)).use { out ->
                out.writeUTF(MAGIC)
                out.writeInt(FORMAT)
                out.writeInt(kept.inputs.size)
                kept.inputs.forEach { (key, value) -> text(out, key); text(out, value) }
                text(out, kept.fingerprint)
                out.writeInt(kept.owners.size)
                kept.owners.forEach { text(out, it) }
                out.writeLong(kept.callCount)
                val bytes = kept.bytes
                out.writeInt(bytes.size)
                out.writeLong(CRC32().apply { update(bytes) }.value)
                out.write(bytes)
                out.writeUTF(END)
            }
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** The kept dictionary at [source]; null when there is none, or it is not a whole file of this format. */
    private fun read(source: Path): Kept? {
        if (!Files.isRegularFile(source)) return null
        return try {
            DataInputStream(Files.newInputStream(source).buffered(1 shl 16)).use { input ->
                if (input.readUTF() != MAGIC || input.readInt() != FORMAT) return null
                val inputs = LinkedHashMap<String, String>()
                repeat(count(input.readInt())) { inputs[text(input)] = text(input) }
                val fingerprint = text(input)
                val owners = List(count(input.readInt())) { text(input) }.toSortedSet()
                val calls = input.readLong()
                val size = input.readInt()
                if (size < 0) return null
                val crc = input.readLong()
                val bytes = ByteArray(size).also { input.readFully(it) }
                if (CRC32().apply { update(bytes) }.value != crc || input.readUTF() != END || input.read() != -1) return null
                Kept(inputs, fingerprint, owners, calls, bytes)
            }
        } catch (e: IOException) {
            LOG.info("The kept dictionary cannot be read: ${e.message}")
            null
        }
    }

    private fun count(value: Int): Int = if (value in 0..MAX_COUNT) value else throw IOException("a count of $value")

    private fun text(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun text(input: DataInputStream): String {
        val size = input.readInt()
        if (size !in 0..MAX_TEXT) throw IOException("a text of $size bytes")
        return try {
            String(ByteArray(size).also { input.readFully(it) }, Charsets.UTF_8)
        } catch (e: EOFException) {
            throw IOException("it ends early", e)
        }
    }

    private const val MAX_COUNT = 1_000_000
    private const val MAX_TEXT = 1 shl 26
}

/**
 * The state of what a dictionary load reads, as one text that changes when any of it changes: for each schema, every
 * object it holds (name, type, status, the time of its last DDL, id), the primary, unique and foreign keys of the
 * schemas and those that reference them (name, type, table, status, referenced key, delete rule, time of their last
 * change), and the privileges of the session (system privileges, roles, object grants received), which decide what the
 * `ALL_` views show. Only columns that exist on 10g are read.
 */
internal object DatabaseCheck {
    class State(val fingerprint: String, val latest: String, val now: String)

    private const val TIME = "'YYYYMMDDHH24MISS'"

    private val OBJECTS = """SELECT owner, COUNT(*),
       SUM(ORA_HASH(object_name || '/' || object_type || '/' || status || '/' || TO_CHAR(last_ddl_time, $TIME) || '/' || object_id)),
       TO_CHAR(MAX(last_ddl_time), $TIME)
  FROM all_objects WHERE owner IN (%s) GROUP BY owner ORDER BY owner"""

    private val KEYS = """SELECT COUNT(*),
       SUM(ORA_HASH(owner || '/' || constraint_name || '/' || constraint_type || '/' || table_name || '/' || status || '/' || r_owner || '/' ||
           r_constraint_name || '/' || delete_rule || '/' || TO_CHAR(last_change, $TIME))),
       TO_CHAR(MAX(last_change), $TIME)
  FROM all_constraints WHERE constraint_type IN ('P', 'U', 'R') AND (owner IN (%s) OR r_owner IN (%s))"""

    private val PRIVILEGES = listOf(
        "SELECT COUNT(*), SUM(ORA_HASH(privilege)) FROM session_privs",
        "SELECT COUNT(*), SUM(ORA_HASH(role)) FROM session_roles",
        "SELECT COUNT(*), SUM(ORA_HASH(grantee || '/' || owner || '/' || table_name || '/' || grantor || '/' || privilege)) FROM all_tab_privs_recd"
    )

    /** Owners in one `IN` list: Oracle takes at most 1000 expressions in a list. */
    private const val OWNERS_PER_QUERY = 500

    /** The users among [strings]: the schemas a load may have read, whatever column named them. */
    fun owners(dataSource: DataSource, strings: Set<String>): Set<String> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT username FROM all_users").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.filterTo(HashSet()) { it in strings }
            }
        }
    }

    fun state(dataSource: DataSource, owners: Collection<String>): State = dataSource.connection.use { connection ->
        val lines = ArrayList<String>()
        var latest = ""
        val sorted = owners.sorted()
        for (chunk in sorted.chunked(OWNERS_PER_QUERY)) {
            query(connection, OBJECTS.format(placeholders(chunk.size)), chunk) { rows ->
                lines += "objects ${rows.getString(1)} ${rows.getString(2)} ${rows.getString(3)}"
                rows.getString(4)?.let { if (it > latest) latest = it }
            }
            query(connection, KEYS.format(placeholders(chunk.size), placeholders(chunk.size)), chunk + chunk) { rows ->
                lines += "keys ${rows.getString(1)} ${rows.getString(2)}"
                rows.getString(3)?.let { if (it > latest) latest = it }
            }
        }
        for (sql in PRIVILEGES) query(connection, sql, emptyList()) { rows -> lines += "privileges ${rows.getString(1)} ${rows.getString(2)}" }
        var now = ""
        query(connection, "SELECT TO_CHAR(SYSDATE, $TIME) FROM dual", emptyList()) { now = it.getString(1) }
        State(lines.joinToString("\n"), latest, now)
    }

    private fun placeholders(count: Int) = List(count) { "?" }.joinToString(",")

    private fun query(connection: Connection, sql: String, parameters: List<String>, row: (java.sql.ResultSet) -> Unit) {
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { rows -> while (rows.next()) row(rows) }
        }
    }
}
