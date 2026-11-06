//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import java.sql.Connection
import java.sql.ResultSet

/**
 * What the index says of Forms modules beyond their entities: the module an object belongs to, the tree of a module's
 * objects in the order of its file, the own text of each piece of code (`forms_object`), and what a block and an item
 * do with their data (`forms_block`, `forms_item`).
 */
internal class FormsReader(private val connection: Connection) {
    /** The module of the Forms object [entityId], with its title and file; null when it is not a Forms object. */
    fun info(entityId: Long): FormsInfo? {
        val module = moduleOf(entityId) ?: return null
        val path = connection.query(
            "SELECT f.path FROM forms_object fo JOIN source_file f ON f.id = fo.file_id WHERE fo.entity_id = ? ORDER BY fo.position LIMIT 1", listOf(module)
        ) { it.getString(1) }.singleOrNull() ?: return null
        val summary = summary(connection, module)
        val internal = connection.query("SELECT value FROM declared_text WHERE entity_id = ?", listOf(module)) { it.getString(1) }.singleOrNull()
        return FormsInfo(summary, internal ?: summary.name, path)
    }

    /** The module the Forms object [entityId] is in (itself for a module), through its parents; null for another entity. */
    private fun moduleOf(entityId: Long): Long? {
        var id: Long? = entityId
        repeat(MAX_DEPTH) {
            val (kind, parent) = connection.query("SELECT kind, parent_id FROM entity WHERE id = ?", listOf(id)) {
                it.getString(1) to (it.getObject(2) as Number?)?.toLong()
            }.singleOrNull() ?: return null
            if (!kind.startsWith("FORMS_")) return null
            if (kind == "FORMS_MODULE") return id
            id = parent ?: return null
        }
        return null
    }

    /**
     * What the database block [entityId] does with its data ([FormsBlockData]): each operation's state follows the rules
     * the indexer followed for the block's `FORMS_IMPLICIT` uses, so `DONE` is where they are. Null for a non-database
     * block (Forms queries and writes nothing for it, and its items stand for no column) and for any other entity.
     */
    fun block(entityId: Long): FormsBlockData? {
        val (data, allowed) = connection.query(
            """SELECT b.source_type, b.source_name, b.target_type, b.target_name, b.query_allowed, b.insert_allowed,
                      b.update_allowed, b.delete_allowed, b.where_clause, b.order_by_clause,
                      s.key, s.kind, s.display, s.external, s.name, t.key, t.kind, t.display, t.external, t.name
               FROM forms_block b LEFT JOIN entity s ON s.id = b.source_entity_id LEFT JOIN entity t ON t.id = b.target_entity_id
               WHERE b.entity_id = ? AND b.database = 1""", listOf(entityId)
        ) { rs ->
            FormsBlockData(FormsDataSource(rs.getString(1), rs.getString(2), summaryAt(rs, 11)),
                FormsDataSource(rs.getString(3), rs.getString(4), summaryAt(rs, 16)), emptyList(), rs.getString(9), rs.getString(10)) to
                (5..8).map { rs.getInt(it) == 1 }
        }.singleOrNull() ?: return null
        // The block's own transactional triggers, else the module's, which fire for every block without its own.
        val triggers = connection.query(
            """SELECT upper(e.name), e.key, e.kind, e.display, e.external, e.name FROM entity e
               WHERE e.kind = 'FORMS_TRIGGER' AND upper(e.name) IN ('ON-SELECT', 'ON-INSERT', 'ON-UPDATE', 'ON-DELETE')
                 AND (e.parent_id = ? OR e.parent_id = (SELECT parent_id FROM entity WHERE id = ?))
               ORDER BY e.parent_id = ? DESC, e.key""", listOf(entityId, entityId, entityId)
        ) { it.getString(1) to summaryAt(it, 2)!! }.fold(HashMap<String, EntitySummary>()) { map, (name, trigger) -> map.apply { putIfAbsent(name, trigger) } }
        val operations = FormsBlockOperation.entries.mapIndexed { i, operation ->
            val trigger = triggers[REPLACING_TRIGGERS.getValue(operation)]
            val table = if (operation == FormsBlockOperation.QUERY) data.source.entity else data.target.entity
            val state = when {
                !allowed[i] -> FormsOperationState.NOT_ALLOWED
                trigger != null -> FormsOperationState.REPLACED
                table == null -> FormsOperationState.NOT_FOLLOWED
                else -> FormsOperationState.DONE
            }
            FormsOperation(operation.name, state.name, trigger.takeIf { state == FormsOperationState.REPLACED })
        }
        return data.copy(operations = operations)
    }

    /**
     * What the item [entityId] says of its data ([FormsItemData]), with what Forms does with its column read from the
     * item's `FORMS_IMPLICIT` uses; null for any other entity.
     */
    fun item(entityId: Long): FormsItemData? = connection.query(
        """SELECT i.database, i.kind, i.column_name, c.key, c.kind, c.display, c.external, c.name, t.key, t.kind, t.display, t.external, t.name,
                  ${implicitUse("i.entity_id", "READ")}, ${implicitUse("i.entity_id", "SQL_INSERT")}, ${implicitUse("i.entity_id", "SQL_UPDATE")}
           FROM forms_item i LEFT JOIN entity c ON c.id = i.column_id LEFT JOIN entity t ON t.id = i.target_column_id
           WHERE i.entity_id = ?""", listOf(entityId)
    ) { rs ->
        FormsItemData(rs.getInt(1) == 1, rs.getString(2), rs.getString(3), summaryAt(rs, 4), summaryAt(rs, 9),
            rs.getInt(14) == 1, rs.getInt(15) == 1, rs.getInt(16) == 1)
    }.singleOrNull()

    /** Whether the item [itemColumn] has a `FORMS_IMPLICIT` use with [role]: an SQL expression giving 0 or 1. */
    private fun implicitUse(itemColumn: String, role: String) =
        """EXISTS (SELECT 1 FROM occurrence o WHERE o.container_entity_id = $itemColumn AND o.origin = 'FORMS_IMPLICIT'
                   AND instr(',' || o.roles || ',', ',$role,') > 0)"""

    /** The [EntitySummary] in the five columns from [first] (key, kind, display, external, name); null for a null key. */
    private fun summaryAt(rs: ResultSet, first: Int): EntitySummary? =
        rs.getString(first)?.let { EntitySummary(it, rs.getString(first + 1), rs.getString(first + 2), rs.getInt(first + 3) == 1, rs.getString(first + 4)) }

    /** The tree of the Forms module, block or item [key] ([FormsTree]); null when it is none of these. */
    fun tree(key: String): FormsTree? {
        val (id, kind) = connection.query("SELECT id, kind FROM entity WHERE key = ?", listOf(key)) { it.getLong(1) to it.getString(2) }.singleOrNull() ?: return null
        if (kind !in TREE_KINDS) return null
        val module = moduleOf(id) ?: return null
        val rows = connection.query(
            """SELECT e.id, e.parent_id, e.key, e.kind, e.display, e.external, e.name, fo.code_kind FROM forms_object fo JOIN entity e ON e.id = fo.entity_id
               WHERE fo.file_id = (SELECT file_id FROM forms_object WHERE entity_id = ? ORDER BY position LIMIT 1) ORDER BY fo.position""",
            listOf(module)
        ) { TreeRow(it.getLong(1), (it.getObject(2) as Number?)?.toLong(), EntitySummary(it.getString(3), it.getString(4), it.getString(5), it.getInt(6) == 1, it.getString(7)), it.getString(8)) }
            .distinctBy { it.id to it.unitKind }
        val children = rows.groupBy { it.parent }
        fun groups(id: Long, kind: String): List<FormsGroup> {
            val under = children[id].orEmpty()
            return GROUPS.getValue(kind).mapNotNull { (group, childKind) ->
                under.filter { it.entity.kind == childKind }
                    .map { FormsNode(it.entity, if (childKind in TREE_KINDS) groups(it.id, childKind) else emptyList(), it.unitKind) }
                    .takeIf { it.isNotEmpty() }?.let { FormsGroup(group, it) }
            }
        }
        return FormsTree(summary(connection, id), groups(id, kind))
    }

    /**
     * The code page of the Forms module file [path] ([FormsFile]): its tree, and the code [key] ([unitKind] telling a
     * package's halves apart) read from the module's text by [source], else the first code in the tree's order. Null
     * when [path] is not a Forms module or its text cannot be read.
     */
    fun file(path: String, key: String?, unitKind: String?, source: SourceReader, colorer: CodeColorer?): FormsFile? {
        val module = connection.query(
            "SELECT fo.entity_id FROM forms_object fo JOIN source_file f ON f.id = fo.file_id WHERE f.path = ? AND fo.position = 0", listOf(path)
        ) { it.getLong(1) }.singleOrNull() ?: return null
        val forms = info(module) ?: return null
        val tree = tree(forms.module.key) ?: return null
        val pieces = connection.query(
            """SELECT e.key, fo.code_kind, fo.code_first_line, fo.code_last_line FROM forms_object fo JOIN entity e ON e.id = fo.entity_id
               WHERE fo.file_id = (SELECT id FROM source_file WHERE path = ?) AND fo.code_first_line IS NOT NULL ORDER BY fo.position""", listOf(path)
        ) { Piece(it.getString(1), it.getString(2), it.getInt(3), it.getInt(4)) }
        val file = source.file(path, colorer) ?: return null
        // Without its kind, a package shows its specification, whatever the file's order.
        val asked = key?.let { pieces.firstOrNull { it.key == key && it.unitKind == unitKind } ?: pieces.filter { it.key == key }.minByOrNull { it.unitKind == "PACKAGE_BODY" } }
        val shown = asked ?: firstCode(tree.groups)?.let { node -> pieces.firstOrNull { it.key == node.entity.key && it.unitKind == node.unitKind } }
        return FormsFile(forms, tree, shown?.let { FormsCode(it.key, it.unitKind, slice(file, it.firstLine, it.lastLine)) })
    }

    /** The first code (a trigger, a program unit, a record group) in the order of [groups], depth first. */
    private fun firstCode(groups: List<FormsGroup>): FormsNode? {
        for (group in groups) {
            for (node in group.nodes) {
                if (node.entity.kind in CODE_KINDS) return node
                firstCode(node.groups)?.let { return it }
            }
        }
        return null
    }

    private class TreeRow(val id: Long, val parent: Long?, val entity: EntitySummary, val unitKind: String?)

    private class Piece(val key: String, val unitKind: String?, val firstLine: Int, val lastLine: Int)

    /** Lines [first] to [last] of [file] as a view of their own, numbered from 1: the occurrences and colors inside them, moved. */
    private fun slice(file: FileView, first: Int, last: Int): FileView {
        val shift = first - 1
        val lines = file.lines.subList((first - 1).coerceAtMost(file.lines.size), last.coerceAtMost(file.lines.size))
        val used = LinkedHashMap<Int, Int>()
        fun indexOf(entity: Int) = used.getOrPut(entity) { used.size }
        val occurrences = file.occurrences.filter { it.at[0] >= first && it.at[2] <= last }.map { occurrence ->
            occurrence.copy(at = listOf(occurrence.at[0] - shift, occurrence.at[1], occurrence.at[2] - shift, occurrence.at[3]),
                entity = occurrence.entity?.let(::indexOf), candidates = occurrence.candidates.map(::indexOf))
        }
        val colors = file.colors?.mapNotNull { span ->
            val (startLine, startCol, endLine, endCol, color) = span
            if (endLine < first || startLine > last) return@mapNotNull null
            val from = if (startLine < first) listOf(first, 0) else listOf(startLine, startCol)
            val to = if (endLine > last) listOf(last, file.lines.getOrNull(last - 1)?.length ?: 0) else listOf(endLine, endCol)
            listOf(from[0] - shift, from[1], to[0] - shift, to[1], color)
        }
        return FileView(file.path, file.stale, lines, used.keys.map { file.entities[it] }, occurrences, file.parseStatus, emptyList(), colors, file.kind)
    }

    companion object {
        private const val MAX_DEPTH = 10

        /** The block trigger whose code Forms runs instead of each operation. */
        private val REPLACING_TRIGGERS = mapOf(
            FormsBlockOperation.QUERY to "ON-SELECT", FormsBlockOperation.INSERT to "ON-INSERT",
            FormsBlockOperation.UPDATE to "ON-UPDATE", FormsBlockOperation.DELETE to "ON-DELETE"
        )

        private val TREE_KINDS = setOf("FORMS_MODULE", "FORMS_BLOCK", "FORMS_ITEM")

        private val CODE_KINDS = setOf("FORMS_TRIGGER", "FORMS_PROGRAM_UNIT", "FORMS_RECORD_GROUP")

        /** The groups of each kind of node, in the Object Navigator's order, each with the kind of entity it holds. */
        private val GROUPS = mapOf(
            "FORMS_MODULE" to listOf("TRIGGERS" to "FORMS_TRIGGER", "LIBRARIES" to "FORMS_LIBRARY", "BLOCKS" to "FORMS_BLOCK",
                "PROGRAM_UNITS" to "FORMS_PROGRAM_UNIT", "RECORD_GROUPS" to "FORMS_RECORD_GROUP"),
            "FORMS_BLOCK" to listOf("TRIGGERS" to "FORMS_TRIGGER", "ITEMS" to "FORMS_ITEM"),
            "FORMS_ITEM" to listOf("TRIGGERS" to "FORMS_TRIGGER")
        )

        /**
         * An SQL expression for the code a line of a Forms module is in ([CodeAt]), as `key<TAB>unit kind<TAB>line`: the line
         * [lineColumn] of the file [fileColumn], in a piece of code or on the header just before it; null elsewhere ([codeAt]).
         */
        fun codeAtSql(fileColumn: String, lineColumn: String) =
            """(SELECT ce.key || char(9) || coalesce(fo.code_kind, '') || char(9) || max(1, $lineColumn - fo.code_first_line + 1)
                FROM forms_object fo JOIN entity ce ON ce.id = fo.entity_id
                WHERE fo.file_id = $fileColumn AND fo.code_first_line IS NOT NULL AND fo.code_first_line - 2 <= $lineColumn
                  AND $lineColumn <= fo.code_last_line ORDER BY fo.code_first_line DESC LIMIT 1)"""

        /** The [CodeAt] a column of [codeAtSql] gives; null for null. */
        fun codeAt(value: String?): CodeAt? = value?.split('\t')?.let { (key, kind, line) -> CodeAt(key, line.toInt(), kind.ifEmpty { null }) }
    }
}

/**
 * The code each line of a Forms module's text is in ([CodeAt]), as [FormsReader.codeAtSql] gives it, for answers that
 * carry a path and a line: each file's pieces of code read once over [connection]; nothing for a database file.
 */
internal class FormsLines(private val connection: Connection) {
    private val pieces = HashMap<String, List<Piece>>()

    /** The code line [line] of the file [path] is in, and its line there; null outside Forms code. */
    fun codeAt(path: String, line: Int): CodeAt? {
        val piece = pieces.getOrPut(path) {
            connection.query(
                """SELECT e.key, fo.code_kind, fo.code_first_line, fo.code_last_line FROM forms_object fo JOIN entity e ON e.id = fo.entity_id
                   WHERE fo.file_id = (SELECT id FROM source_file WHERE path = ?) AND fo.code_first_line IS NOT NULL ORDER BY fo.code_first_line DESC""",
                listOf(path)
            ) { Piece(it.getString(1), it.getString(2), it.getInt(3), it.getInt(4)) }
        }.firstOrNull { it.firstLine - 2 <= line && line <= it.lastLine } ?: return null
        return CodeAt(piece.key, maxOf(1, line - piece.firstLine + 1), piece.unitKind)
    }

    private class Piece(val key: String, val unitKind: String?, val firstLine: Int, val lastLine: Int)
}
