//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

import com.felipebz.zpa.atlas.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types

/** Run provenance written to the `run` table. */
data class RunInfo(
    val atlasVersion: String,
    val zpaVersion: String,
    val sourcesRoot: String,
    /** The `--encoding` option: `auto` or the forced charset name. */
    val sourceEncoding: String,
    val metadataOrigin: String?,
    val compilationSchema: String?,
    /** The target database version of conditional compilation (`11.2`), or null. */
    val databaseVersion: String? = null,
    /** `OPTION`, `DATABASE` or `JDBC` with a [databaseVersion], else null. */
    val databaseVersionOrigin: String? = null,
    /** The `PLSQL_CCFLAGS` of the run as `NAME:VALUE, …`, or null. */
    val ccflags: String? = null,
    val startedAt: String,
    val finishedAt: String
)

/**
 * The only owner of the SQLite connection, the DDL and every INSERT. The database is written to a temporary file next
 * to the target and moved over it at the end, so a reader never sees a partial index. There are no migrations: a
 * schema change means re-indexing. Ids are assigned in a sorted order (entity key; file path, then source position),
 * never in thread-completion order, so two runs over the same input produce identical rows.
 */
class AtlasWriter private constructor(private val connection: Connection) : AutoCloseable {

    /** Writes every row; the caller ([writeAtomically]) owns the transaction, the indexes and the commit. */
    fun write(run: RunInfo, files: List<FileFacts>, globalEntities: List<EntityFact>, globalRelations: List<RelationFact>, globalCoverage: List<CoverageFact>) {
        insertRun(run)
        val sortedFiles = files.sortedBy { it.path }
        val fileIds = HashMap<String, Long>()
        val entityIds = insertEntities(globalEntities + sortedFiles.flatMap { it.entities })
        insertRelations((globalRelations + sortedFiles.flatMap { it.relations }), entityIds)
        insertTriggerEvents(sortedFiles.flatMap { it.triggerEvents }, entityIds)

        val fileInsert = prepare("INSERT INTO source_file(id, path, kind, sha256, size_bytes, compilation_schema, parse_status, recovered_regions, encoding) VALUES (?,?,?,?,?,?,?,?,?)")
        val unitInsert = prepare("INSERT INTO unit(id, file_id, entity_id, kind, name, start_line, start_col, end_line, end_col, origin_path, origin_line_offset) VALUES (?,?,?,?,?,?,?,?,?,NULL,NULL)")
        val statementInsert = prepare("INSERT INTO sql_statement(id, file_id, unit_id, container_entity_id, kind, start_line, start_col, end_line, end_col, normalized_hash) VALUES (?,?,?,?,?,?,?,?,?,?)")
        val occurrenceInsert = prepare("INSERT INTO occurrence(id, file_id, unit_id, container_entity_id, statement_id, start_line, start_col, end_line, end_col, written, entity_id, roles, status, origin, reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
        val candidateInsert = prepare("INSERT INTO occurrence_candidate(occurrence_id, entity_id) VALUES (?,?)")
        val argumentInsert = prepare("INSERT INTO call_argument(occurrence_id, ordinal, label, parameter_entity_id, start_line, start_col, end_line, end_col) VALUES (?,?,?,?,?,?,?,?)")
        val coverageInsert = prepare("INSERT INTO coverage_note(id, file_id, kind, message) VALUES (?,?,?,?)")
        val batches = Batches(fileInsert, unitInsert, statementInsert, occurrenceInsert, candidateInsert, argumentInsert, coverageInsert)

        var unitId = 0L
        var statementId = 0L
        var occurrenceId = 0L
        var coverageId = 0L
        for ((index, file) in sortedFiles.withIndex()) {
            val fileId = index + 1L
            fileIds[file.path] = fileId
            fileInsert.setLong(1, fileId)
            fileInsert.setString(2, file.path)
            fileInsert.setString(3, FileKind.DB_SOURCE.name)
            fileInsert.setString(4, file.sha256)
            fileInsert.setLong(5, file.sizeBytes)
            fileInsert.setNullableString(6, file.compilationSchema)
            fileInsert.setString(7, file.parseStatus.name)
            fileInsert.setInt(8, file.recoveredRegions)
            fileInsert.setString(9, file.encoding)
            batches.add(fileInsert)

            val unitIds = LongArray(file.units.size)
            for ((u, unit) in file.units.withIndex()) {
                unitIds[u] = ++unitId
                unitInsert.setLong(1, unitId)
                unitInsert.setLong(2, fileId)
                unitInsert.setNullableLong(3, unit.entityKey?.let { entityIds.getValue(it) })
                unitInsert.setString(4, unit.kind.name)
                unitInsert.setString(5, unit.name)
                unitInsert.setSpan(6, unit.span)
                batches.add(unitInsert)
            }

            val statementIds = HashMap<Int, Long>()
            for (statement in file.statements.sortedWith(compareBy({ it.span }, { it.kind }))) {
                statementIds[statement.localId] = ++statementId
                statementInsert.setLong(1, statementId)
                statementInsert.setLong(2, fileId)
                statementInsert.setLong(3, unitIds[statement.unitIndex])
                statementInsert.setNullableLong(4, statement.containerKey?.let { entityIds.getValue(it) })
                statementInsert.setString(5, statement.kind.name)
                statementInsert.setSpan(6, statement.span)
                statementInsert.setString(10, statement.normalizedHash)
                batches.add(statementInsert)
            }

            for (occurrence in file.occurrences.sortedWith(OCCURRENCE_ORDER)) {
                occurrenceInsert.setLong(1, ++occurrenceId)
                occurrenceInsert.setLong(2, fileId)
                occurrenceInsert.setLong(3, unitIds[occurrence.unitIndex])
                occurrenceInsert.setNullableLong(4, occurrence.containerKey?.let { entityIds.getValue(it) })
                occurrenceInsert.setNullableLong(5, occurrence.statementLocalId?.let { statementIds.getValue(it) })
                occurrenceInsert.setSpan(6, occurrence.span)
                occurrenceInsert.setString(10, occurrence.written)
                occurrenceInsert.setNullableLong(11, occurrence.entityKey?.let { entityIds.getValue(it) })
                occurrenceInsert.setString(12, occurrence.roles.encode())
                occurrenceInsert.setString(13, occurrence.status.name)
                occurrenceInsert.setString(14, occurrence.origin.name)
                occurrenceInsert.setNullableString(15, occurrence.reason)
                batches.add(occurrenceInsert)
                for (candidate in occurrence.candidateKeys.map { entityIds.getValue(it) }.distinct().sorted()) {
                    candidateInsert.setLong(1, occurrenceId)
                    candidateInsert.setLong(2, candidate)
                    batches.add(candidateInsert)
                }
                for (argument in occurrence.arguments) {
                    argumentInsert.setLong(1, occurrenceId)
                    argumentInsert.setInt(2, argument.ordinal)
                    argumentInsert.setNullableString(3, argument.label)
                    argumentInsert.setNullableLong(4, argument.parameterKey?.let { entityIds.getValue(it) })
                    argumentInsert.setSpan(5, argument.span)
                    batches.add(argumentInsert)
                }
            }
            // At a file boundary, so that the rows of one file go out together.
            batches.flushIfFull()
        }
        val notes = globalCoverage.distinct().sortedWith(compareBy({ it.kind }, { it.message })).map { null to it } +
            sortedFiles.flatMap { file -> file.coverage.distinct().sortedWith(compareBy({ it.kind }, { it.message })).map { file.path to it } }
        for ((path, note) in notes) {
            coverageInsert.setLong(1, ++coverageId)
            coverageInsert.setNullableLong(2, path?.let { fileIds.getValue(it) })
            coverageInsert.setString(3, note.kind)
            coverageInsert.setString(4, note.message)
            batches.add(coverageInsert)
        }
        batches.flush()
        batches.close()
    }

    private fun insertRun(run: RunInfo) {
        prepare("INSERT INTO run(id, schema_version, atlas_version, zpa_version, sources_root, source_encoding, metadata_origin, compilation_schema, database_version, database_version_origin, ccflags, started_at, finished_at) VALUES (1,?,?,?,?,?,?,?,?,?,?,?,?)").use {
            it.setInt(1, SCHEMA_VERSION)
            it.setString(2, run.atlasVersion)
            it.setString(3, run.zpaVersion)
            it.setString(4, run.sourcesRoot)
            it.setString(5, run.sourceEncoding)
            it.setNullableString(6, run.metadataOrigin)
            it.setNullableString(7, run.compilationSchema)
            it.setNullableString(8, run.databaseVersion)
            it.setNullableString(9, run.databaseVersionOrigin)
            it.setNullableString(10, run.ccflags)
            it.setString(11, run.startedAt)
            it.setString(12, run.finishedAt)
            it.executeUpdate()
        }
    }

    /** Merges facts by key (the lowest precedence wins, then the smallest fact) and numbers them in key order. */
    private fun insertEntities(facts: List<EntityFact>): Map<String, Long> {
        val chosen = facts.groupBy { it.key }.mapValues { (_, same) ->
            same.minWith(compareBy<EntityFact>({ it.precedence }, { it.external }, { it.kind }, { it.display }, { it.parentKey ?: "" }))
        }
        val keys = chosen.keys.sorted()
        val ids = HashMap<String, Long>(keys.size * 2)
        keys.forEachIndexed { index, key -> ids[key] = index + 1L }
        prepare("INSERT INTO entity(id, key, kind, schema_name, name, parent_id, display, external) VALUES (?,?,?,?,?,?,?,?)").use { insert ->
            val batches = Batches(insert)
            for (key in keys) {
                val entity = chosen.getValue(key)
                insert.setLong(1, ids.getValue(key))
                insert.setString(2, key)
                insert.setString(3, entity.kind.name)
                insert.setNullableString(4, entity.schemaName)
                insert.setString(5, entity.name)
                insert.setNullableLong(6, entity.parentKey?.let { ids.getValue(it) })
                insert.setString(7, entity.display)
                insert.setInt(8, if (entity.external) 1 else 0)
                batches.add(insert)
                batches.flushIfFull()
            }
            batches.flush()
        }
        return ids
    }

    private fun insertRelations(facts: List<RelationFact>, ids: Map<String, Long>) {
        val rows = facts.map { Triple(ids.getValue(it.fromKey), it.kind, ids.getValue(it.toKey)) }.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
        prepare("INSERT INTO relation(from_id, kind, to_id) VALUES (?,?,?)").use { insert ->
            val batches = Batches(insert)
            for ((from, kind, to) in rows) {
                insert.setLong(1, from)
                insert.setString(2, kind.name)
                insert.setLong(3, to)
                batches.add(insert)
                batches.flushIfFull()
            }
            batches.flush()
        }
    }

    private fun insertTriggerEvents(facts: List<TriggerEventFact>, ids: Map<String, Long>) {
        val rows = facts.distinct().sortedWith(compareBy({ ids.getValue(it.triggerKey) }, { it.event }, { it.timing }, { it.level }, { it.columnKey?.let(ids::getValue) ?: 0L }))
        prepare("INSERT INTO trigger_event(trigger_id, timing, level, event, column_id) VALUES (?,?,?,?,?)").use { insert ->
            val batches = Batches(insert)
            for (row in rows) {
                insert.setLong(1, ids.getValue(row.triggerKey))
                insert.setString(2, row.timing.name)
                insert.setNullableString(3, row.level?.name)
                insert.setString(4, row.event)
                insert.setNullableLong(5, row.columnKey?.let { ids.getValue(it) })
                batches.add(insert)
                batches.flushIfFull()
            }
            batches.flush()
        }
    }

    private fun prepare(sql: String): PreparedStatement = connection.prepareStatement(sql)

    /**
     * Pending batches of some statements, executed once [FLUSH_ROWS] rows are pending: the driver keeps every bound
     * parameter of a pending row as an object, so batching a whole corpus duplicates the index in the heap. Each
     * statement keeps its own insertion order, so the rows and their order do not depend on when a batch runs.
     */
    private class Batches(private vararg val statements: PreparedStatement) : AutoCloseable {
        private var pending = 0

        fun add(statement: PreparedStatement) {
            statement.addBatch()
            pending++
        }

        fun flushIfFull() {
            if (pending >= FLUSH_ROWS) flush()
        }

        fun flush() {
            for (statement in statements) statement.executeBatch()
            pending = 0
        }

        override fun close() {
            for (statement in statements) statement.close()
        }
    }

    override fun close() = connection.close()

    companion object {
        const val SCHEMA_VERSION = 7

        /** Pending rows (all statements together) after which the batches are executed. */
        private const val FLUSH_ROWS = 20_000

        private val OCCURRENCE_ORDER = compareBy<OccurrenceFact>(
            { it.span }, { it.entityKey ?: "" }, { it.roles.encode() }, { it.status }, { it.reason ?: "" }, { it.written }
        )

        /**
         * Writes the index to a temporary file beside [target] and moves it over [target] when everything succeeded.
         */
        fun writeAtomically(target: Path, body: (AtlasWriter) -> Unit) {
            val absolute = target.toAbsolutePath()
            Files.createDirectories(absolute.parent)
            val temporary = Files.createTempFile(absolute.parent, absolute.fileName.toString(), ".tmp")
            try {
                Files.delete(temporary)
                DriverManager.getConnection("jdbc:sqlite:${temporary}").use { connection ->
                    // One transaction: the tables, the rows, then the secondary indexes (cheaper on loaded tables).
                    connection.autoCommit = false
                    execute(connection, tableDdl())
                    body(AtlasWriter(connection))
                    execute(connection, indexDdl())
                    connection.commit()
                }
                replace(temporary, absolute)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        /**
         * Moves [temporary] over [target]. On Windows the move fails while another process (the server between two
         * requests is the usual one) has the target open, so it is retried for about [REPLACE_WAIT_MILLIS].
         */
        private fun replace(temporary: Path, target: Path) {
            val deadline = System.nanoTime() + REPLACE_WAIT_MILLIS * 1_000_000
            while (true) {
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    return
                } catch (e: java.nio.file.FileSystemException) {
                    // AccessDeniedException is a FileSystemException.
                    if (System.nanoTime() > deadline) {
                        throw IllegalStateException("Cannot replace $target: the index is open in another process (${e.javaClass.simpleName})", e)
                    }
                    Thread.sleep(100)
                }
            }
        }

        private const val REPLACE_WAIT_MILLIS = 5_000L

        /** Creates the empty schema: the tables, then the secondary indexes. */
        fun createSchema(connection: Connection) {
            execute(connection, tableDdl())
            execute(connection, indexDdl())
        }

        private fun execute(connection: Connection, ddl: List<String>) {
            connection.createStatement().use { statement ->
                for (sql in ddl) statement.execute(sql)
            }
        }

        /**
         * An IN list in a CHECK made each batched insert several times slower when measured; a chain of equalities
         * accepts the same values without that cost.
         */
        private inline fun <reified E> check(column: String): String where E : Enum<E>, E : StoredEnum =
            "CHECK (${enumValues<E>().joinToString(" OR ") { "$column = '${it.name}'" }})"

        /** A comma-separated set of roles (empty for dynamic SQL): removing every role name and comma must leave nothing. */
        private fun rolesCheck(): String {
            val names = Role.entries.map { it.name }.sortedByDescending { it.length }
            val stripped = names.fold("roles") { expression, name -> "replace($expression,'$name','')" }
            return "CHECK (replace($stripped,',','') = '')"
        }

        /** The tables, with their inline constraints (`PRIMARY KEY`, `UNIQUE`), in creation order. */
        fun tableDdl(): List<String> = listOf(
            """CREATE TABLE run (
                id INTEGER PRIMARY KEY,
                schema_version INTEGER NOT NULL,
                atlas_version TEXT NOT NULL,
                zpa_version TEXT NOT NULL,
                sources_root TEXT NOT NULL,
                source_encoding TEXT NOT NULL,
                metadata_origin TEXT NULL,
                compilation_schema TEXT NULL,
                database_version TEXT NULL,
                database_version_origin TEXT NULL CHECK (database_version_origin IN ('OPTION', 'DATABASE', 'JDBC')),
                ccflags TEXT NULL,
                started_at TEXT NOT NULL,
                finished_at TEXT NOT NULL
            )""",
            """CREATE TABLE source_file (
                id INTEGER PRIMARY KEY,
                path TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL ${check<FileKind>("kind")},
                sha256 TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                compilation_schema TEXT NULL,
                parse_status TEXT NOT NULL ${check<ParseStatus>("parse_status")},
                recovered_regions INTEGER NOT NULL,
                encoding TEXT NOT NULL
            )""",
            """CREATE TABLE entity (
                id INTEGER PRIMARY KEY,
                key TEXT NOT NULL,
                kind TEXT NOT NULL ${check<EntityKind>("kind")},
                schema_name TEXT NULL,
                name TEXT NOT NULL,
                parent_id INTEGER NULL REFERENCES entity(id),
                display TEXT NOT NULL,
                external INTEGER NOT NULL CHECK (external IN (0, 1))
            )""",
            """CREATE TABLE unit (
                id INTEGER PRIMARY KEY,
                file_id INTEGER NOT NULL REFERENCES source_file(id),
                entity_id INTEGER NULL REFERENCES entity(id),
                kind TEXT NOT NULL ${check<UnitKind>("kind")},
                name TEXT NOT NULL,
                start_line INTEGER NOT NULL,
                start_col INTEGER NOT NULL,
                end_line INTEGER NOT NULL,
                end_col INTEGER NOT NULL,
                origin_path TEXT NULL,
                origin_line_offset INTEGER NULL
            )""",
            """CREATE TABLE relation (
                from_id INTEGER NOT NULL REFERENCES entity(id),
                kind TEXT NOT NULL ${check<RelationKind>("kind")},
                to_id INTEGER NOT NULL REFERENCES entity(id),
                PRIMARY KEY (from_id, kind, to_id)
            )""",
            """CREATE TABLE sql_statement (
                id INTEGER PRIMARY KEY,
                file_id INTEGER NOT NULL REFERENCES source_file(id),
                unit_id INTEGER NOT NULL REFERENCES unit(id),
                container_entity_id INTEGER NULL REFERENCES entity(id),
                kind TEXT NOT NULL ${check<StatementKind>("kind")},
                start_line INTEGER NOT NULL,
                start_col INTEGER NOT NULL,
                end_line INTEGER NOT NULL,
                end_col INTEGER NOT NULL,
                normalized_hash TEXT NOT NULL
            )""",
            """CREATE TABLE occurrence (
                id INTEGER PRIMARY KEY,
                file_id INTEGER NOT NULL REFERENCES source_file(id),
                unit_id INTEGER NOT NULL REFERENCES unit(id),
                container_entity_id INTEGER NULL REFERENCES entity(id),
                statement_id INTEGER NULL REFERENCES sql_statement(id),
                start_line INTEGER NOT NULL,
                start_col INTEGER NOT NULL,
                end_line INTEGER NOT NULL,
                end_col INTEGER NOT NULL,
                written TEXT NOT NULL,
                entity_id INTEGER NULL REFERENCES entity(id),
                roles TEXT NOT NULL ${rolesCheck()},
                status TEXT NOT NULL ${check<Status>("status")},
                origin TEXT NOT NULL ${check<Origin>("origin")},
                reason TEXT NULL
            )""",
            """CREATE TABLE occurrence_candidate (
                occurrence_id INTEGER NOT NULL REFERENCES occurrence(id),
                entity_id INTEGER NOT NULL REFERENCES entity(id),
                PRIMARY KEY (occurrence_id, entity_id)
            )""",
            """CREATE TABLE call_argument (
                occurrence_id INTEGER NOT NULL REFERENCES occurrence(id),
                ordinal INTEGER NOT NULL,
                label TEXT NULL,
                parameter_entity_id INTEGER NULL REFERENCES entity(id),
                start_line INTEGER NOT NULL,
                start_col INTEGER NOT NULL,
                end_line INTEGER NOT NULL,
                end_col INTEGER NOT NULL,
                PRIMARY KEY (occurrence_id, ordinal)
            )""",
            """CREATE TABLE coverage_note (
                id INTEGER PRIMARY KEY,
                file_id INTEGER NULL REFERENCES source_file(id),
                kind TEXT NOT NULL,
                message TEXT NOT NULL
            )""",
            // The event is free text: DML events, but also the DDL and database events of system triggers.
            """CREATE TABLE trigger_event (
                trigger_id INTEGER NOT NULL REFERENCES entity(id),
                timing TEXT NOT NULL ${check<TriggerTiming>("timing")},
                level TEXT NULL ${check<TriggerLevel>("level")},
                event TEXT NOT NULL,
                column_id INTEGER NULL REFERENCES entity(id)
            )"""
        ).map { it.trimIndent() }

        /** The secondary indexes, created after the rows are loaded. Readers do not depend on their creation order. */
        fun indexDdl(): List<String> = listOf(
            "CREATE UNIQUE INDEX entity_key ON entity(key)",
            "CREATE INDEX entity_parent ON entity(parent_id)",
            "CREATE INDEX occurrence_entity_roles ON occurrence(entity_id, roles)",
            "CREATE INDEX occurrence_file_line ON occurrence(file_id, start_line)",
            "CREATE INDEX occurrence_container ON occurrence(container_entity_id)",
            "CREATE INDEX occurrence_candidate_entity ON occurrence_candidate(entity_id)",
            // Case-insensitive name prefix search (`name LIKE 'abc%'`, LIKE folds ASCII case like NOCASE).
            "CREATE INDEX entity_name_nocase ON entity(name COLLATE NOCASE)",
            "CREATE INDEX relation_from ON relation(from_id)",
            "CREATE INDEX call_argument_parameter ON call_argument(parameter_entity_id)",
            "CREATE INDEX relation_to ON relation(to_id)",
            "CREATE INDEX trigger_event_trigger ON trigger_event(trigger_id)",
            "CREATE INDEX trigger_event_column ON trigger_event(column_id)"
        )
    }
}

private fun PreparedStatement.setNullableString(index: Int, value: String?) =
    if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)

private fun PreparedStatement.setNullableLong(index: Int, value: Long?) =
    if (value == null) setNull(index, Types.INTEGER) else setLong(index, value)

private fun PreparedStatement.setSpan(index: Int, span: SourceSpan) {
    setInt(index, span.startLine)
    setInt(index + 1, span.startCol)
    setInt(index + 2, span.endLine)
    setInt(index + 3, span.endCol)
}
