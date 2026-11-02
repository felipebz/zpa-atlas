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
    /**
     * Writes [sortedFiles] (sorted by path) with the ids of [entityIds], then the run's and the files' coverage notes,
     * then the counts of [placesLeftOut] as notes after them all (so that the other notes keep their ids). [places] are
     * the places column values are read into, by file path.
     */
    fun write(
        sortedFiles: List<FileFacts>, entityIds: Map<String, Long>, globalCoverage: List<CoverageFact>,
        places: Map<String, List<ColumnPlaceFact>> = emptyMap(), placesLeftOut: Map<String, Map<String, Int>> = emptyMap()
    ) {
        val fileIds = HashMap<String, Long>()
        val fileInsert = prepare("INSERT INTO source_file(id, path, kind, sha256, size_bytes, compilation_schema, parse_status, recovered_regions, encoding) VALUES (?,?,?,?,?,?,?,?,?)")
        val unitInsert = prepare("INSERT INTO unit(id, file_id, entity_id, kind, name, start_line, start_col, end_line, end_col, origin_path, origin_line_offset) VALUES (?,?,?,?,?,?,?,?,?,NULL,NULL)")
        val statementInsert = prepare("INSERT INTO sql_statement(id, file_id, unit_id, container_entity_id, kind, start_line, start_col, end_line, end_col, normalized_hash) VALUES (?,?,?,?,?,?,?,?,?,?)")
        val occurrenceInsert = prepare("INSERT INTO occurrence(id, file_id, unit_id, container_entity_id, statement_id, start_line, start_col, end_line, end_col, written, entity_id, roles, status, origin, reason, name_start_line, name_start_col, name_end_line, name_end_col) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
        val candidateInsert = prepare("INSERT INTO occurrence_candidate(occurrence_id, entity_id) VALUES (?,?)")
        val writtenValueInsert = prepare("INSERT INTO written_value(occurrence_id, row_number, value_kind, value, start_line, start_col, end_line, end_col) VALUES (?,?,?,?,?,?,?,?)")
        val argumentInsert = prepare("INSERT INTO call_argument(occurrence_id, ordinal, label, parameter_entity_id, start_line, start_col, end_line, end_col, value_kind, value) VALUES (?,?,?,?,?,?,?,?,?,?)")
        val coverageInsert = prepare("INSERT INTO coverage_note(id, file_id, kind, message) VALUES (?,?,?,?)")
        val docInsert = prepare("INSERT INTO doc_comment(entity_id, file_id, in_body, shape, start_line, text, text_cut) VALUES (?,?,?,?,?,?,?)")
        val placeInsert = prepare("""INSERT INTO column_place(id, file_id, unit_id, container_entity_id, statement_id, start_line, start_col, end_line,
            end_col, column_id, source, bulk, target_entity_id, target_written, field, class, declared_type, length, length_unit, type_source_id, reason,
            scale, parameter_mode, functions)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")
        val batches = Batches(fileInsert, unitInsert, statementInsert, occurrenceInsert, candidateInsert, argumentInsert, writtenValueInsert, coverageInsert,
            docInsert, placeInsert)
        var placeId = 0L

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
            for (doc in file.docComments.sortedWith(compareBy({ it.line }, { it.entityKey }))) {
                docInsert.setLong(1, entityIds.getValue(doc.entityKey))
                docInsert.setLong(2, fileId)
                docInsert.setInt(3, if (doc.inBody) 1 else 0)
                docInsert.setString(4, doc.shape.name)
                docInsert.setInt(5, doc.line)
                docInsert.setString(6, doc.text)
                docInsert.setInt(7, if (doc.textCut) 1 else 0)
                batches.add(docInsert)
            }
            // A key the run has no entity for (a column the index never mentioned) is left out of the place.
            for (place in places[file.path].orEmpty().sortedWith(PLACE_ORDER)) {
                val column = entityIds[place.columnKey] ?: continue
                placeInsert.setLong(1, ++placeId)
                placeInsert.setLong(2, fileId)
                placeInsert.setLong(3, unitIds[place.unitIndex])
                placeInsert.setNullableLong(4, place.containerKey?.let { entityIds[it] })
                placeInsert.setNullableLong(5, place.statementLocalId?.let { statementIds[it] })
                placeInsert.setSpan(6, place.span)
                placeInsert.setLong(10, column)
                placeInsert.setString(11, place.source.name)
                placeInsert.setInt(12, if (place.bulk) 1 else 0)
                placeInsert.setNullableLong(13, place.targetKey?.let { entityIds[it] })
                placeInsert.setString(14, place.targetWritten)
                placeInsert.setNullableString(15, place.field)
                placeInsert.setString(16, place.placeClass.name)
                placeInsert.setNullableString(17, place.declaredType)
                placeInsert.setNullableLong(18, place.length?.toLong())
                placeInsert.setNullableString(19, place.unit)
                placeInsert.setNullableLong(20, place.typeSourceKey?.let { entityIds[it] })
                placeInsert.setNullableString(21, place.reason)
                placeInsert.setNullableLong(22, place.scale?.toLong())
                placeInsert.setNullableString(23, place.parameterMode)
                placeInsert.setNullableString(24, place.functions.takeIf { it.isNotEmpty() }?.joinToString(","))
                batches.add(placeInsert)
            }
            // At a file boundary, so that the rows of one file go out together.
            batches.flushIfFull()
        }
        val notes = globalCoverage.distinct().sortedWith(compareBy({ it.kind }, { it.message })).map { null to it } +
            sortedFiles.flatMap { file -> file.coverage.distinct().sortedWith(compareBy({ it.kind }, { it.message })).map { file.path to it } } +
            sortedFiles.flatMap { file -> placesLeftOut[file.path].orEmpty().map { (shape, count) -> file.path to leftOutNote(shape, count) } }
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

    /** A count of what reads a column's value into PL/SQL without being followed: `COLUMN_VALUE_NOT_FOLLOWED`, its shape first. */
    private fun leftOutNote(shape: String, count: Int): CoverageFact {
        val kind = when {
            shape.startsWith("EXPRESSION_") -> "EXPRESSION"
            shape.startsWith("RECORD_ASSIGNMENT_EXPRESSION_") -> "RECORD_ASSIGNMENT_EXPRESSION"
            else -> shape
        }
        val what = LEFT_OUT[kind] ?: "read(s) of a column value into PL/SQL: not followed"
        return CoverageFact(COLUMN_VALUE_NOT_FOLLOWED, "$shape: $count $what")
    }

    companion object {
        const val COLUMN_VALUE_NOT_FOLLOWED = "COLUMN_VALUE_NOT_FOLLOWED"

        private val LEFT_OUT = mapOf(
            "EXPRESSION" to "select item(s) that are an expression of one column, read into a PL/SQL target: not followed",
            "DYNAMIC_INTO" to "target(s) of a dynamic INTO: not followed",
            "RECORD_ASSIGNMENT" to "assignment(s) of a field of a %ROWTYPE or cursor loop record: not followed",
            "RECORD_ASSIGNMENT_EXPRESSION" to "assigned value(s) that are an expression of a field of a %ROWTYPE or cursor loop record: not followed",
            "ARGUMENT" to "column value(s) passed to a routine: not followed",
            "FETCH_CURSOR_NOT_KNOWN" to "target(s) of a FETCH from a cursor whose query is not known: not followed"
        )
    }
}

private val OCCURRENCE_ORDER = compareBy<OccurrenceFact>(
    { it.span }, { it.entityKey ?: "" }, { it.roles.encode() }, { it.status }, { it.reason ?: "" }, { it.written }
)

private val PLACE_ORDER = compareBy<ColumnPlaceFact>({ it.span }, { it.columnKey }, { it.field ?: "" }, { it.source })
