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
    val metadataOrigin: String?,
    val compilationSchema: String?,
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

    fun write(run: RunInfo, files: List<FileFacts>, globalEntities: List<EntityFact>, globalRelations: List<RelationFact>, globalCoverage: List<CoverageFact>) {
        connection.autoCommit = false
        insertRun(run)
        val sortedFiles = files.sortedBy { it.path }
        val fileIds = HashMap<String, Long>()
        val entityIds = insertEntities(globalEntities + sortedFiles.flatMap { it.entities })
        insertRelations((globalRelations + sortedFiles.flatMap { it.relations }), entityIds)

        val fileInsert = prepare("INSERT INTO source_file(id, path, kind, sha256, size_bytes, compilation_schema, parse_status, recovered_regions) VALUES (?,?,?,?,?,?,?,?)")
        val unitInsert = prepare("INSERT INTO unit(id, file_id, entity_id, kind, name, start_line, start_col, end_line, end_col, origin_path, origin_line_offset) VALUES (?,?,?,?,?,?,?,?,?,NULL,NULL)")
        val statementInsert = prepare("INSERT INTO sql_statement(id, file_id, unit_id, container_entity_id, kind, start_line, start_col, end_line, end_col, normalized_hash) VALUES (?,?,?,?,?,?,?,?,?,?)")
        val occurrenceInsert = prepare("INSERT INTO occurrence(id, file_id, unit_id, container_entity_id, statement_id, start_line, start_col, end_line, end_col, written, entity_id, roles, status, origin, reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
        val coverageInsert = prepare("INSERT INTO coverage_note(id, file_id, kind, message) VALUES (?,?,?,?)")

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
            fileInsert.addBatch()

            val unitIds = LongArray(file.units.size)
            for ((u, unit) in file.units.withIndex()) {
                unitIds[u] = ++unitId
                unitInsert.setLong(1, unitId)
                unitInsert.setLong(2, fileId)
                unitInsert.setNullableLong(3, unit.entityKey?.let { entityIds.getValue(it) })
                unitInsert.setString(4, unit.kind.name)
                unitInsert.setString(5, unit.name)
                unitInsert.setSpan(6, unit.span)
                unitInsert.addBatch()
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
                statementInsert.addBatch()
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
                occurrenceInsert.addBatch()
            }
        }
        val notes = globalCoverage.distinct().sortedWith(compareBy({ it.kind }, { it.message })).map { null to it } +
            sortedFiles.flatMap { file -> file.coverage.distinct().sortedWith(compareBy({ it.kind }, { it.message })).map { file.path to it } }
        for ((path, note) in notes) {
            coverageInsert.setLong(1, ++coverageId)
            coverageInsert.setNullableLong(2, path?.let { fileIds.getValue(it) })
            coverageInsert.setString(3, note.kind)
            coverageInsert.setString(4, note.message)
            coverageInsert.addBatch()
        }
        for (statement in listOf(fileInsert, unitInsert, statementInsert, occurrenceInsert, coverageInsert)) {
            statement.executeBatch()
            statement.close()
        }
        connection.commit()
    }

    private fun insertRun(run: RunInfo) {
        prepare("INSERT INTO run(id, schema_version, atlas_version, zpa_version, sources_root, metadata_origin, compilation_schema, started_at, finished_at) VALUES (1,?,?,?,?,?,?,?,?)").use {
            it.setInt(1, SCHEMA_VERSION)
            it.setString(2, run.atlasVersion)
            it.setString(3, run.zpaVersion)
            it.setString(4, run.sourcesRoot)
            it.setNullableString(5, run.metadataOrigin)
            it.setNullableString(6, run.compilationSchema)
            it.setString(7, run.startedAt)
            it.setString(8, run.finishedAt)
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
                insert.addBatch()
            }
            insert.executeBatch()
        }
        return ids
    }

    private fun insertRelations(facts: List<RelationFact>, ids: Map<String, Long>) {
        val rows = facts.map { Triple(ids.getValue(it.fromKey), it.kind, ids.getValue(it.toKey)) }.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
        prepare("INSERT INTO relation(from_id, kind, to_id) VALUES (?,?,?)").use { insert ->
            for ((from, kind, to) in rows) {
                insert.setLong(1, from)
                insert.setString(2, kind.name)
                insert.setLong(3, to)
                insert.addBatch()
            }
            insert.executeBatch()
        }
    }

    private fun prepare(sql: String): PreparedStatement = connection.prepareStatement(sql)

    override fun close() = connection.close()

    companion object {
        const val SCHEMA_VERSION = 1

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
                    createSchema(connection)
                    body(AtlasWriter(connection))
                }
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        fun createSchema(connection: Connection) {
            connection.createStatement().use { statement ->
                for (ddl in ddl()) statement.execute(ddl)
            }
        }

        private inline fun <reified E> check(column: String): String where E : Enum<E>, E : StoredEnum =
            "CHECK ($column IN (${enumValues<E>().joinToString(",") { "'${it.name}'" }}))"

        /** A comma-separated set of roles (empty for dynamic SQL): removing every role name and comma must leave nothing. */
        private fun rolesCheck(): String {
            val names = Role.entries.map { it.name }.sortedByDescending { it.length }
            val stripped = names.fold("roles") { expression, name -> "replace($expression,'$name','')" }
            return "CHECK (replace($stripped,',','') = '')"
        }

        /** The complete DDL, in creation order. */
        fun ddl(): List<String> = listOf(
            """CREATE TABLE run (
                id INTEGER PRIMARY KEY,
                schema_version INTEGER NOT NULL,
                atlas_version TEXT NOT NULL,
                zpa_version TEXT NOT NULL,
                sources_root TEXT NOT NULL,
                metadata_origin TEXT NULL,
                compilation_schema TEXT NULL,
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
                recovered_regions INTEGER NOT NULL
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
            """CREATE TABLE coverage_note (
                id INTEGER PRIMARY KEY,
                file_id INTEGER NULL REFERENCES source_file(id),
                kind TEXT NOT NULL,
                message TEXT NOT NULL
            )""",
            "CREATE UNIQUE INDEX entity_key ON entity(key)",
            "CREATE INDEX entity_parent ON entity(parent_id)",
            "CREATE INDEX occurrence_entity_roles ON occurrence(entity_id, roles)",
            "CREATE INDEX occurrence_file_line ON occurrence(file_id, start_line)",
            "CREATE INDEX occurrence_container ON occurrence(container_entity_id)",
            "CREATE INDEX relation_from ON relation(from_id)",
            "CREATE INDEX relation_to ON relation(to_id)"
        ).map { it.trimIndent() }
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
