//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

import com.felipebz.zpa.atlas.model.*
import java.sql.Connection
import java.sql.PreparedStatement

/** The rows of the files, in path order, then the coverage notes; the ids count across files. */
internal class FileRows(private val connection: Connection) {
    /** Writes [sortedFiles] (sorted by path) with the ids of [entityIds], then the run's and the files' coverage notes. */
    fun write(sortedFiles: List<FileFacts>, entityIds: Map<String, Long>, globalCoverage: List<CoverageFact>) {
        val fileIds = HashMap<String, Long>()
        val fileInsert = prepare("INSERT INTO source_file(id, path, kind, sha256, size_bytes, compilation_schema, parse_status, recovered_regions, encoding) VALUES (?,?,?,?,?,?,?,?,?)")
        val unitInsert = prepare("INSERT INTO unit(id, file_id, entity_id, kind, name, start_line, start_col, end_line, end_col, origin_path, origin_line_offset) VALUES (?,?,?,?,?,?,?,?,?,NULL,NULL)")
        val statementInsert = prepare("INSERT INTO sql_statement(id, file_id, unit_id, container_entity_id, kind, start_line, start_col, end_line, end_col, normalized_hash) VALUES (?,?,?,?,?,?,?,?,?,?)")
        val occurrenceInsert = prepare("INSERT INTO occurrence(id, file_id, unit_id, container_entity_id, statement_id, start_line, start_col, end_line, end_col, written, entity_id, roles, status, origin, reason, name_start_line, name_start_col, name_end_line, name_end_col) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
        val candidateInsert = prepare("INSERT INTO occurrence_candidate(occurrence_id, entity_id) VALUES (?,?)")
        val writtenValueInsert = prepare("INSERT INTO written_value(occurrence_id, row_number, value_kind, value, start_line, start_col, end_line, end_col) VALUES (?,?,?,?,?,?,?,?)")
        val argumentInsert = prepare("INSERT INTO call_argument(occurrence_id, ordinal, label, parameter_entity_id, start_line, start_col, end_line, end_col, value_kind, value) VALUES (?,?,?,?,?,?,?,?,?,?)")
        val coverageInsert = prepare("INSERT INTO coverage_note(id, file_id, kind, message) VALUES (?,?,?,?)")
        val batches = Batches(fileInsert, unitInsert, statementInsert, occurrenceInsert, candidateInsert, argumentInsert, writtenValueInsert, coverageInsert)

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
                val nameSpan = occurrence.nameSpan?.takeIf { it != occurrence.span }
                occurrenceInsert.setNullableLong(16, nameSpan?.startLine?.toLong())
                occurrenceInsert.setNullableLong(17, nameSpan?.startCol?.toLong())
                occurrenceInsert.setNullableLong(18, nameSpan?.endLine?.toLong())
                occurrenceInsert.setNullableLong(19, nameSpan?.endCol?.toLong())
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
                    argumentInsert.setString(9, argument.valueKind.name)
                    argumentInsert.setNullableString(10, argument.value)
                    batches.add(argumentInsert)
                }
                for (written in occurrence.writtenValues) {
                    writtenValueInsert.setLong(1, occurrenceId)
                    writtenValueInsert.setInt(2, written.row)
                    writtenValueInsert.setString(3, written.valueKind.name)
                    writtenValueInsert.setNullableString(4, written.value)
                    writtenValueInsert.setSpan(5, written.span)
                    batches.add(writtenValueInsert)
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

    private fun prepare(sql: String): PreparedStatement = connection.prepareStatement(sql)
}

private val OCCURRENCE_ORDER = compareBy<OccurrenceFact>(
    { it.span }, { it.entityKey ?: "" }, { it.roles.encode() }, { it.status }, { it.reason ?: "" }, { it.written }
)
