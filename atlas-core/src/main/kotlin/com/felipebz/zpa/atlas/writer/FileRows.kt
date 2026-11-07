//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

import com.felipebz.zpa.atlas.model.*
import com.felipebz.zpa.atlas.writer.FileRows.Companion.FETCH_NOT_KNOWN
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
        val rows = Rows(entityIds)
        for ((index, file) in sortedFiles.withIndex()) rows.writeFile(index + 1L, file, places[file.path].orEmpty())
        rows.writeNotes(notesOf(sortedFiles, globalCoverage, placesLeftOut))
        rows.finish()
    }

    /** The coverage notes with the path of their file, in id order: the run's, the files', then the counts of [placesLeftOut]. */
    private fun notesOf(
        sortedFiles: List<FileFacts>, globalCoverage: List<CoverageFact>, placesLeftOut: Map<String, Map<String, Int>>
    ): List<Pair<String?, CoverageFact>> =
        globalCoverage.distinct().sortedWith(compareBy({ it.kind }, { it.message })).map { null to it } +
            sortedFiles.flatMap { file -> file.coverage.distinct().sortedWith(compareBy({ it.kind }, { it.message })).map { file.path to it } } +
            sortedFiles.flatMap { file -> placesLeftOut[file.path].orEmpty().map { (shape, count) -> file.path to leftOutNote(shape, count) } }

    /** The inserts of one [write] and the ids handed out so far; [entityIds] are the ids of the entities by key. */
    private inner class Rows(private val entityIds: Map<String, Long>) {
        private val fileIds = HashMap<String, Long>()
        private val fileInsert = prepare("INSERT INTO source_file(id, path, kind, sha256, size_bytes, compilation_schema, parse_status, recovered_regions, encoding) VALUES (?,?,?,?,?,?,?,?,?)")
        private val unitInsert = prepare("INSERT INTO unit(id, file_id, entity_id, kind, name, start_line, start_col, end_line, end_col, origin_path, origin_line_offset) VALUES (?,?,?,?,?,?,?,?,?,NULL,NULL)")
        private val statementInsert = prepare("INSERT INTO sql_statement(id, file_id, unit_id, container_entity_id, kind, start_line, start_col, end_line, end_col, normalized_hash) VALUES (?,?,?,?,?,?,?,?,?,?)")
        private val occurrenceInsert = prepare("INSERT INTO occurrence(id, file_id, unit_id, container_entity_id, statement_id, start_line, start_col, end_line, end_col, written, entity_id, roles, status, origin, reason, name_start_line, name_start_col, name_end_line, name_end_col) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
        private val candidateInsert = prepare("INSERT INTO occurrence_candidate(occurrence_id, entity_id) VALUES (?,?)")
        private val writtenValueInsert = prepare("INSERT INTO written_value(occurrence_id, row_number, value_kind, value, start_line, start_col, end_line, end_col) VALUES (?,?,?,?,?,?,?,?)")
        private val argumentInsert = prepare("INSERT INTO call_argument(occurrence_id, ordinal, label, parameter_entity_id, start_line, start_col, end_line, end_col, value_kind, value) VALUES (?,?,?,?,?,?,?,?,?,?)")
        private val coverageInsert = prepare("INSERT INTO coverage_note(id, file_id, kind, message) VALUES (?,?,?,?)")
        private val docInsert = prepare("INSERT INTO doc_comment(entity_id, file_id, in_body, shape, start_line, text, text_cut) VALUES (?,?,?,?,?,?,?)")
        private val placeInsert = prepare("""INSERT INTO column_place(id, file_id, unit_id, container_entity_id, statement_id, start_line, start_col, end_line,
            end_col, column_id, source, bulk, target_entity_id, target_written, field, class, declared_type, length, length_unit, type_source_id, reason,
            scale, parameter_mode, functions, argument_parameter_id, argument_routine_id, call_count, call_path, call_line, concatenated,
            unit_source, unit_compiled_owner, unit_compiled_name, unit_compiled_type)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")
        private val formsInsert = prepare("INSERT INTO forms_object(file_id, position, entity_id, code_first_line, code_last_line, code_kind) VALUES (?,?,?,?,?,?)")
        private val blockInsert = prepare("""INSERT INTO forms_block(entity_id, database, source_type, source_name, source_entity_id, target_type, target_name,
            target_entity_id, query_allowed, insert_allowed, update_allowed, delete_allowed, where_clause, order_by_clause) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")
        private val itemInsert = prepare("""INSERT INTO forms_item(entity_id, database, column_name, kind, insert_allowed, update_allowed, column_id, target_column_id)
            VALUES (?,?,?,?,?,?,?,?)""")
        private val batches = Batches(fileInsert, unitInsert, statementInsert, occurrenceInsert, candidateInsert, argumentInsert, writtenValueInsert,
            coverageInsert, docInsert, placeInsert, formsInsert, blockInsert, itemInsert)
        private var placeId = 0L
        private var unitId = 0L
        private var statementId = 0L
        private var occurrenceId = 0L
        private var coverageId = 0L

        /** Writes [file] as [fileId], then its units, statements, occurrences, Forms rows, doc comments and [places]. */
        fun writeFile(fileId: Long, file: FileFacts, places: List<ColumnPlaceFact>) {
            fileIds[file.path] = fileId
            fileInsert.setLong(1, fileId)
            fileInsert.setString(2, file.path)
            fileInsert.setString(3, file.kind.name)
            fileInsert.setString(4, file.sha256)
            fileInsert.setLong(5, file.sizeBytes)
            fileInsert.setNullableString(6, file.compilationSchema)
            fileInsert.setString(7, file.parseStatus.name)
            fileInsert.setInt(8, file.recoveredRegions)
            fileInsert.setString(9, file.encoding)
            batches.add(fileInsert)

            val unitIds = writeUnits(fileId, file.units)
            val statementIds = writeStatements(fileId, file.statements, unitIds)
            for (occurrence in file.occurrences.sortedWith(OCCURRENCE_ORDER)) writeOccurrence(fileId, occurrence, unitIds, statementIds)
            for (use in file.formsUses) writeFormsUse(fileId, use)
            for ((position, formsObject) in file.formsObjects.withIndex()) writeFormsObject(fileId, position, formsObject)
            for (doc in file.docComments.sortedWith(compareBy({ it.line }, { it.entityKey }))) writeDoc(fileId, doc)
            // A key the run has no entity for (a column the index never mentioned) is left out of the place.
            for (place in places.sortedWith(PLACE_ORDER)) {
                val column = entityIds[place.columnKey] ?: continue
                writePlace(fileId, place, column, unitIds, statementIds)
            }
            // At a file boundary, so that the rows of one file go out together.
            batches.flushIfFull()
        }

        /** Writes the coverage [notes], each with the id of its file when it has a path. */
        fun writeNotes(notes: List<Pair<String?, CoverageFact>>) {
            for ((path, note) in notes) {
                coverageInsert.setLong(1, ++coverageId)
                coverageInsert.setNullableLong(2, path?.let { fileIds.getValue(it) })
                coverageInsert.setString(3, note.kind)
                coverageInsert.setString(4, note.message)
                batches.add(coverageInsert)
            }
        }

        /** Executes the pending rows and closes the inserts. */
        fun finish() {
            batches.flush()
            batches.close()
        }

        /** Writes the [units] of the file [fileId]; returns their ids, by index. */
        private fun writeUnits(fileId: Long, units: List<UnitFact>): LongArray {
            val unitIds = LongArray(units.size)
            for ((u, unit) in units.withIndex()) {
                unitIds[u] = ++unitId
                unitInsert.setLong(1, unitId)
                unitInsert.setLong(2, fileId)
                unitInsert.setNullableLong(3, unit.entityKey?.let { entityIds.getValue(it) })
                unitInsert.setString(4, unit.kind.name)
                unitInsert.setString(5, unit.name)
                unitInsert.setSpan(6, unit.span)
                batches.add(unitInsert)
            }
            return unitIds
        }

        /** Writes the [statements] of the file [fileId] in span order; returns their ids, by local id. */
        private fun writeStatements(fileId: Long, statements: List<StatementFact>, unitIds: LongArray): Map<Int, Long> {
            val statementIds = HashMap<Int, Long>()
            for (statement in statements.sortedWith(compareBy({ it.span }, { it.kind }))) {
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
            return statementIds
        }

        /** Writes [occurrence] of the file [fileId], then its candidates, arguments and written values. */
        private fun writeOccurrence(fileId: Long, occurrence: OccurrenceFact, unitIds: LongArray, statementIds: Map<Int, Long>) {
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
            for (argument in occurrence.arguments) writeArgument(argument)
            for (written in occurrence.writtenValues) writeWrittenValue(written)
        }

        /** Writes [argument] of the last occurrence written. */
        private fun writeArgument(argument: CallArgumentFact) {
            argumentInsert.setLong(1, occurrenceId)
            argumentInsert.setInt(2, argument.ordinal)
            argumentInsert.setNullableString(3, argument.label)
            argumentInsert.setNullableLong(4, argument.parameterKey?.let { entityIds.getValue(it) })
            argumentInsert.setSpan(5, argument.span)
            argumentInsert.setString(9, argument.valueKind.name)
            argumentInsert.setNullableString(10, argument.value)
            batches.add(argumentInsert)
        }

        /** Writes [written], a value the last occurrence written writes. */
        private fun writeWrittenValue(written: WrittenValueFact) {
            writtenValueInsert.setLong(1, occurrenceId)
            writtenValueInsert.setInt(2, written.row)
            writtenValueInsert.setString(3, written.valueKind.name)
            writtenValueInsert.setNullableString(4, written.value)
            writtenValueInsert.setSpan(5, written.span)
            batches.add(writtenValueInsert)
        }

        /** Writes [use], a use Forms makes without code in the file [fileId]: no unit, no statement, no span. */
        private fun writeFormsUse(fileId: Long, use: FormsUseFact) {
            occurrenceInsert.setLong(1, ++occurrenceId)
            occurrenceInsert.setLong(2, fileId)
            for (parameter in listOf(3, 5, 6, 7, 8, 9, 16, 17, 18, 19)) occurrenceInsert.setNullableLong(parameter, null)
            occurrenceInsert.setLong(4, entityIds.getValue(use.containerKey))
            occurrenceInsert.setString(10, use.written)
            occurrenceInsert.setNullableLong(11, use.entityKey?.let { entityIds.getValue(it) })
            occurrenceInsert.setString(12, use.roles.encode())
            occurrenceInsert.setString(13, use.status.name)
            occurrenceInsert.setString(14, Origin.FORMS_IMPLICIT.name)
            occurrenceInsert.setNullableString(15, use.reason)
            batches.add(occurrenceInsert)
        }

        /** Writes [formsObject] at [position] in the file [fileId], then its block or item. */
        private fun writeFormsObject(fileId: Long, position: Int, formsObject: FormsObjectFact) {
            val entityId = entityIds.getValue(formsObject.entityKey)
            formsInsert.setLong(1, fileId)
            formsInsert.setInt(2, position)
            formsInsert.setLong(3, entityId)
            formsInsert.setNullableLong(4, formsObject.codeFirstLine?.toLong())
            formsInsert.setNullableLong(5, formsObject.codeLastLine?.toLong())
            formsInsert.setNullableString(6, formsObject.unitKind?.name)
            batches.add(formsInsert)
            formsObject.block?.let { writeBlock(entityId, it) }
            formsObject.item?.let { writeItem(entityId, it) }
        }

        /** Writes [block], the data block of the Forms object [entityId]. */
        private fun writeBlock(entityId: Long, block: FormsBlockFact) {
            blockInsert.setLong(1, entityId)
            blockInsert.setInt(2, if (block.database) 1 else 0)
            blockInsert.setString(3, block.sourceType.name)
            blockInsert.setString(4, block.sourceName)
            blockInsert.setNullableLong(5, block.sourceKey?.let { entityIds.getValue(it) })
            blockInsert.setString(6, block.targetType.name)
            blockInsert.setString(7, block.targetName)
            blockInsert.setNullableLong(8, block.targetKey?.let { entityIds.getValue(it) })
            blockInsert.setInt(9, if (block.queryAllowed) 1 else 0)
            blockInsert.setInt(10, if (block.insertAllowed) 1 else 0)
            blockInsert.setInt(11, if (block.updateAllowed) 1 else 0)
            blockInsert.setInt(12, if (block.deleteAllowed) 1 else 0)
            blockInsert.setString(13, block.where)
            blockInsert.setString(14, block.orderBy)
            batches.add(blockInsert)
        }

        /** Writes [item], the item of the Forms object [entityId]. */
        private fun writeItem(entityId: Long, item: FormsItemFact) {
            itemInsert.setLong(1, entityId)
            itemInsert.setInt(2, if (item.database) 1 else 0)
            itemInsert.setString(3, item.columnName)
            itemInsert.setString(4, item.kind.name)
            itemInsert.setInt(5, if (item.insertAllowed) 1 else 0)
            itemInsert.setInt(6, if (item.updateAllowed) 1 else 0)
            itemInsert.setNullableLong(7, item.columnKey?.let { entityIds.getValue(it) })
            itemInsert.setNullableLong(8, item.targetColumnKey?.let { entityIds.getValue(it) })
            batches.add(itemInsert)
        }

        /** Writes [doc], a doc comment of the file [fileId]. */
        private fun writeDoc(fileId: Long, doc: DocCommentFact) {
            docInsert.setLong(1, entityIds.getValue(doc.entityKey))
            docInsert.setLong(2, fileId)
            docInsert.setInt(3, if (doc.inBody) 1 else 0)
            docInsert.setString(4, doc.shape.name)
            docInsert.setInt(5, doc.line)
            docInsert.setString(6, doc.text)
            docInsert.setInt(7, if (doc.textCut) 1 else 0)
            batches.add(docInsert)
        }

        /** Writes [place] of the file [fileId], a place the value of the column [column] is read into. */
        private fun writePlace(fileId: Long, place: ColumnPlaceFact, column: Long, unitIds: LongArray, statementIds: Map<Int, Long>) {
            placeInsert.setLong(1, ++placeId)
            placeInsert.setLong(2, fileId)
            placeInsert.setNullableLong(3, place.unitIndex?.let { unitIds[it] })
            placeInsert.setNullableLong(4, place.containerKey?.let { entityIds[it] })
            placeInsert.setNullableLong(5, place.statementLocalId?.let { statementIds[it] })
            // A place in no code (a block's query): no lines.
            if (place.span != null) placeInsert.setSpan(6, place.span) else for (parameter in 6..9) placeInsert.setNullableLong(parameter, null)
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
            placeInsert.setNullableString(24, place.functions.filter { it != CONCATENATED }.takeIf { it.isNotEmpty() }?.joinToString(","))
            placeInsert.setNullableLong(25, place.argumentOf?.let { entityIds[it] })
            placeInsert.setNullableLong(26, place.argumentOf?.let { entityIds[it.substringBeforeLast('.')] })
            placeInsert.setNullableLong(27, place.callCount?.toLong())
            placeInsert.setNullableString(28, place.callPath)
            placeInsert.setNullableLong(29, place.callLine?.toLong())
            placeInsert.setInt(30, if (CONCATENATED in place.functions) 1 else 0)
            placeInsert.setNullableString(31, place.unitSource?.name)
            placeInsert.setNullableString(32, place.unitCompiledIn?.owner)
            placeInsert.setNullableString(33, place.unitCompiledIn?.name)
            placeInsert.setNullableString(34, place.unitCompiledIn?.type)
            batches.add(placeInsert)
        }
    }

    private fun prepare(sql: String): PreparedStatement = connection.prepareStatement(sql)

    /** A count of what reads a column's value into PL/SQL without being followed: `COLUMN_VALUE_NOT_FOLLOWED`, its shape first. */
    private fun leftOutNote(shape: String, count: Int): CoverageFact {
        val what = when {
            shape.startsWith("EXPRESSION_") -> LEFT_OUT.getValue("EXPRESSION")
            shape.startsWith("ARGUMENT_EXPRESSION_") -> LEFT_OUT.getValue("ARGUMENT_EXPRESSION")
            shape.startsWith("RETURNED_EXPRESSION_") -> LEFT_OUT.getValue("RETURNED_EXPRESSION")
            shape.startsWith("RECORD_ASSIGNMENT_EXPRESSION_") -> LEFT_OUT.getValue("RECORD_ASSIGNMENT_EXPRESSION")
            shape.startsWith(ARGUMENT_NOT_KNOWN) -> "field(s) of ${fieldOf(shape.removePrefix(ARGUMENT_NOT_KNOWN))}, passed to a routine: not followed"
            shape.startsWith(ASSIGNMENT_NOT_KNOWN) -> "assignment(s) of a field of ${fieldOf(shape.removePrefix(ASSIGNMENT_NOT_KNOWN))}: not followed"
            shape.startsWith(FETCH_NOT_KNOWN + OPENED_FOR_TEXT) ->
                "target(s) of a FETCH from a cursor variable the routine opens FOR a string whose text is not followed (${shape.removePrefix(FETCH_NOT_KNOWN + OPENED_FOR_TEXT)}): not followed"
            shape.startsWith(FETCH_NOT_KNOWN) ->
                "target(s) of a FETCH from ${FETCH_FROM[shape.removePrefix(FETCH_NOT_KNOWN)] ?: "a cursor"}, whose query is not known: not followed"
            else -> LEFT_OUT[shape]
        } ?: "read(s) of a column value into PL/SQL: not followed"
        return CoverageFact(COLUMN_VALUE_NOT_FOLLOWED, "$shape: $count $what")
    }

    /** The record whose field's column is not known, by why ([reason], the shape after its family's prefix). */
    private fun fieldOf(reason: String) =
        if (reason.startsWith("EXPRESSION_")) NOT_KNOWN.getValue("EXPRESSION") else NOT_KNOWN[reason] ?: NOT_KNOWN.getValue("OTHER")

    companion object {
        const val COLUMN_VALUE_NOT_FOLLOWED = "COLUMN_VALUE_NOT_FOLLOWED"

        private val LEFT_OUT = mapOf(
            "EXPRESSION" to "select item(s) that are an expression of one column, read into a PL/SQL target: not followed",
            "DYNAMIC_INTO" to "target(s) of a dynamic INTO: not followed",
            "RECORD_ASSIGNMENT_EXPRESSION" to "assigned value(s) that are an expression of a field of a %ROWTYPE or cursor loop record: not followed",
            "ARGUMENT_ROUTINE_UNDECIDED" to "column value(s) passed to a routine the index does not decide: not followed",
            "ARGUMENT_ROUTINE_OUTSIDE" to "column value(s) passed to a routine whose PL/SQL body is not in the index: not followed",
            "ARGUMENT_NOT_TIED" to "column value(s) passed to a routine, the argument not tied to a parameter: not followed",
            "ARGUMENT_CONSTRUCTOR" to "column value(s) passed to an object type's constructor: not followed",
            "ARGUMENT_LOCAL_SUBPROGRAM" to "column value(s) passed to an overloaded subprogram nested in a routine: not followed",
            "ARGUMENT_COLUMN_POSSIBLE" to "parameter(s) a column's value is passed to, named in SQL on a table whose columns are not known (a column may take the name): not followed",
            "ARGUMENT_FETCHED" to "parameter(s) a column's value is passed to, selected by a cursor: not followed",
            "ARGUMENT_INTO_NOT_FOLLOWED" to "parameter(s) a column's value is passed to, selected into targets that do not match the items: not followed",
            "RETURNED_EXPRESSION" to "results of a function that returns a column's value, written within an expression: not followed",
            "RETURNED_NOT_SCALAR" to "calls of a function that returns a column's value in a record, a collection or an object: not followed",
            "ARGUMENT_EXPRESSION" to "parameter(s) a column's value is passed to, written within an expression: not followed",
            "OUT_ARGUMENT_NOT_FOLLOWED" to "argument(s) for an OUT parameter a column's value is read into, not a name, field or element the index binds: not followed",
            "OUT_PARAMETER_NOT_FOLLOWED" to "OUT parameter(s) a column's value is read into whose callers the index cannot tell: not followed",
            "OUT_CALL_NOT_DECIDED" to "call(s) whose routine is not decided, among routines with an OUT parameter a column's value is read into: not followed",
            "FETCH_CURSOR_ITEMS_NOT_MATCHED" to "target(s) of a FETCH from a cursor variable that do not match the items of a query it reads: not followed",
            "FETCH_CURSOR_STAR_NOT_KNOWN" to "target(s) of a FETCH whose query's * is over a relation or collection whose columns are not known: not followed",
        )

        private const val ARGUMENT_NOT_KNOWN = "ARGUMENT_COLUMN_NOT_KNOWN_"
        private const val ASSIGNMENT_NOT_KNOWN = "RECORD_ASSIGNMENT_COLUMN_NOT_KNOWN_"
        private const val FETCH_NOT_KNOWN = "FETCH_CURSOR_NOT_KNOWN_"

        /** After [FETCH_NOT_KNOWN]: a string's text not followed, the reason of its `DYNAMIC` occurrence after it. */
        private const val OPENED_FOR_TEXT = "OPENED_DYNAMIC_"

        /** The record a field whose column is not known is of, by why. */
        private val NOT_KNOWN = mapOf(
            "EXPRESSION" to "a cursor record whose item is an expression",
            "ROWTYPE" to "a %ROWTYPE whose relation or cursor is not known",
            "CURSOR" to "a %ROWTYPE of a cursor whose query is not known",
            "REF_CURSOR" to "a %ROWTYPE of a cursor variable of a REF CURSOR type with RETURN",
            "STAR" to "a cursor record whose * is over a relation whose columns are not known",
            "NO_COLUMN" to "a cursor record whose item reads no column of a table or view",
            "COLUMN_UNRESOLVED" to "a cursor record whose item is a column the index cannot resolve",
            "INLINE_VIEW" to "a cursor record whose item is a column of an inline view",
            "OTHER" to "a %ROWTYPE or cursor loop record whose column is not known"
        )

        /** The cursor a FETCH whose query is not known reads from. */
        private val FETCH_FROM = mapOf(
            "STRONG" to "a cursor variable of a REF CURSOR type with RETURN",
            "OPENED_STATIC" to "a cursor variable the routine opens FOR a static query",
            "OPENED_DYNAMIC" to "a cursor variable the routine opens FOR a string",
            "PARAMETER" to "a cursor variable that comes in as a parameter",
            "TEXT_HOLE" to "a cursor variable the routine opens FOR a string whose hole in the FROM hides the column an item reads"
        )
    }
}

private val OCCURRENCE_ORDER = compareBy<OccurrenceFact>(
    { it.span }, { it.entityKey ?: "" }, { it.roles.encode() }, { it.status }, { it.reason ?: "" }, { it.written }
)

private val PLACE_ORDER = compareBy<ColumnPlaceFact>({ it.span }, { it.columnKey }, { it.field ?: "" }, { it.source }, { it.targetKey ?: "" })
