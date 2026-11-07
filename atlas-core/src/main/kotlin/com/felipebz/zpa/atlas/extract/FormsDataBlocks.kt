//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.atlas.forms.FormsDataType
import com.felipebz.zpa.atlas.forms.FormsLayout
import com.felipebz.zpa.atlas.forms.FormsTriggerLevel
import com.felipebz.zpa.atlas.model.*
import java.util.*

/**
 * What Oracle Forms does with a module's data blocks without code. A block on a table (a database block whose query
 * data source is a table it names) queries its source and inserts, updates and deletes its DML target (a table it
 * names, by default its source), each when the block allows it and no transactional trigger replaces it (`ON-SELECT`,
 * `ON-INSERT`, `ON-UPDATE`, `ON-DELETE`: the block's own, else the module's, which fires for every block without one).
 * An item on a column (a database item that holds a value, in a block on a table; its column is its `ColumnName`, else
 * its own name) reads its column of the source when the block queries, and writes the target's column of that name in
 * the inserts and updates the item allows. `DUAL` (or `SYS.DUAL`) is no table, as in SQL: a block on it uses nothing.
 *
 * The tables and columns resolve as the module's own SQL does: in the run's compilation schema, through synonyms,
 * against the dictionary. Each table is a `FORMS_BLOCK_TABLE` relation of its block (the source, and the target when
 * it is another relation), each column a `FORMS_ITEM_COLUMN` relation of its item, and each use a [FormsUseFact] with
 * the roles of the SQL Forms runs, as a statement without a column list gives them, and `IMPLICIT`: the block's on
 * the table, the item's on the column. A database block whose source or target is a procedure, a query or
 * transactional triggers, or of a type the reader did not know, is counted in a `FORMS_BLOCK_SOURCE_NOT_FOLLOWED` note;
 * so is, by its path, one whose table source or target name is not a table name (a query text, a list of names).
 */
internal class FormsDataBlocks(layout: FormsLayout, entities: FormsEntities, private val resolver: RelationResolver, private val schema: String?) {
    val relations = ArrayList<RelationFact>()
    val uses = ArrayList<FormsUseFact>()
    val coverage = ArrayList<CoverageFact>()
    private val properties = HashMap<String, FormsObjectFact>()

    init {
        var notFollowed = 0
        var unknownType = 0
        // The blocks whose source or DML target name is not a table name (a query text, a list), by path.
        val notANames = ArrayList<String>()
        for ((index, block) in layout.blocks.withIndex()) {
            val blockKey = entities.blockKeys[index]
            val types = setOf(block.source.type, block.target.type)
            val counted = block.database && (types.any { it in NOT_FOLLOWED } || FormsDataType.UNKNOWN in types)
            if (block.database && types.any { it in NOT_FOLLOWED }) notFollowed++
            else if (counted) unknownType++
            var source: ResolvedRelation? = null
            var target: ResolvedRelation? = null
            if (block.database && block.source.type == FormsDataType.TABLE && block.source.name.isNotBlank()) {
                val sourceName = written(block.source.name)
                if (sourceName == null) {
                    if (!counted) notANames += displayOf(blockKey)
                } else if (!sourceName.isDual) {
                    source = resolver.resolve(sourceName, schema).also { relations += RelationFact(blockKey, RelationKind.FORMS_BLOCK_TABLE, it.entityKey) }
                    if (block.target.type == FormsDataType.TABLE && block.target.name.isNotBlank()) {
                        val targetName = written(block.target.name)
                        if (targetName == null && !counted) notANames += displayOf(blockKey) + " (DML target)"
                        target = when {
                            targetName == null || targetName.isDual -> null
                            targetName == sourceName -> source
                            else -> resolver.resolve(targetName, schema)
                        }
                        if (target != null && target.entityKey != source.entityKey) relations += RelationFact(blockKey, RelationKind.FORMS_BLOCK_TABLE, target.entityKey)
                    }
                }
            }
            // The block's own transactional triggers, then the module's, which fire for every block without its own.
            val replaced = layout.units.filter {
                it.kind == FormsLayout.Kind.TRIGGER && (it.level == FormsTriggerLevel.BLOCK && it.owner == listOf(block.name.value) || it.level == FormsTriggerLevel.MODULE)
            }.mapTo(HashSet()) { it.name.uppercase(Locale.ROOT) }
            val query = source != null && block.queryAllowed && "ON-SELECT" !in replaced
            val insert = target != null && block.insertAllowed && "ON-INSERT" !in replaced
            val update = target != null && block.updateAllowed && "ON-UPDATE" !in replaced
            val delete = target != null && block.deleteAllowed && "ON-DELETE" !in replaced
            if (query) use(blockKey, block.source.name, source, Role.SQL_SELECT)
            if (insert) use(blockKey, block.target.name, target, Role.SQL_INSERT)
            if (update) use(blockKey, block.target.name, target, Role.SQL_UPDATE)
            if (delete) use(blockKey, block.target.name, target, Role.SQL_DELETE)
            properties[blockKey] = FormsObjectFact(blockKey, block = FormsBlockFact(block.database, block.source.type, block.source.name, source?.entityKey,
                block.target.type, block.target.name, target?.entityKey, block.queryAllowed, block.insertAllowed, block.updateAllowed, block.deleteAllowed,
                block.where, block.orderBy))
            for ((position, item) in block.items.withIndex()) {
                val itemKey = entities.itemKeys[index][position]
                var column: ResolvedColumn? = null
                var targetColumn: ResolvedColumn? = null
                if (source != null && item.database && item.kind.holdsValue) {
                    val written = item.columnName.ifBlank { item.name.value }
                    val name = normalizeIdentifier(written)
                    column = resolver.resolveColumn(source, name).also { relations += RelationFact(itemKey, RelationKind.FORMS_ITEM_COLUMN, it.entityKey!!) }
                    if (target != null && target.entityKey != source.entityKey && (item.insertAllowed || item.updateAllowed)) {
                        targetColumn = resolver.resolveColumn(target, name).also { relations += RelationFact(itemKey, RelationKind.FORMS_ITEM_COLUMN, it.entityKey!!) }
                    }
                    val writtenColumn = targetColumn ?: column
                    if (query) use(itemKey, written, column, Role.READ)
                    if (insert && item.insertAllowed) use(itemKey, written, writtenColumn, Role.SQL_INSERT, Role.WRITE)
                    if (update && item.updateAllowed) use(itemKey, written, writtenColumn, Role.SQL_UPDATE, Role.WRITE)
                }
                properties[itemKey] = FormsObjectFact(itemKey, item = FormsItemFact(item.database, item.columnName, item.kind, item.insertAllowed,
                    item.updateAllowed, column?.entityKey, targetColumn?.entityKey))
            }
        }
        val parts = listOfNotNull(
            "$notFollowed a procedure, a FROM clause query or transactional triggers".takeIf { notFollowed > 0 },
            "$unknownType of a type not known".takeIf { unknownType > 0 },
            "${notANames.size} whose table name is not a name: ${notANames.joinToString(", ")}".takeIf { notANames.isNotEmpty() }
        )
        if (parts.isNotEmpty()) {
            coverage += CoverageFact("FORMS_BLOCK_SOURCE_NOT_FOLLOWED",
                "${notFollowed + unknownType + notANames.size} database block(s) whose data source or DML target is not a table: what Forms does with it, " +
                    "and with its items' columns, is not followed (${parts.joinToString("; ")})")
        }
    }

    /** [fact] with its block's or item's properties, when it is a block or an item. */
    fun withProperties(fact: FormsObjectFact): FormsObjectFact = properties[fact.entityKey] ?: fact

    private fun use(containerKey: String, written: String, relation: ResolvedRelation, role: Role) {
        uses += FormsUseFact(containerKey, written, relation.entityKey, setOf(role, Role.IMPLICIT), relation.status, relation.reason)
    }

    private fun use(containerKey: String, written: String, column: ResolvedColumn, vararg roles: Role) {
        uses += FormsUseFact(containerKey, written, column.entityKey, roles.toSet() + Role.IMPLICIT, column.status, column.reason)
    }

    companion object {
        /** The types of a data source or target whose data the index does not follow. */
        private val NOT_FOLLOWED = setOf(FormsDataType.PROCEDURE, FormsDataType.QUERY, FormsDataType.TRANSACTIONAL_TRIGGERS)

        private val SIMPLE_NAME = Regex("[A-Za-z][A-Za-z0-9_$#]*")

        /** A relation name as a block writes it, `[schema.]name[@link]`, each part normalized; null when [text] is not one. */
        fun written(text: String): WrittenRelation? {
            val parts = ArrayList<String>()
            var link: String? = null
            val part = StringBuilder()
            var quoted = false
            var linkStart = -1
            for ((index, char) in text.trim().withIndex()) {
                when {
                    char == '"' -> {
                        quoted = !quoted
                        part.append(char)
                    }
                    quoted -> part.append(char)
                    char == '.' -> {
                        parts += part.toString()
                        part.clear()
                    }
                    char == '@' -> {
                        linkStart = index + 1
                        break
                    }
                    else -> part.append(char)
                }
            }
            if (quoted) return null
            parts += part.toString()
            if (linkStart >= 0) {
                val linkParts = text.trim().substring(linkStart).split('.')
                if (linkParts.any { !it.matches(SIMPLE_NAME) }) return null
                link = linkParts.joinToString(".") { normalizeIdentifier(it) }
            }
            if (parts.size !in 1..2 || parts.any { !isName(it) }) return null
            return WrittenRelation(parts.getOrNull(parts.size - 2)?.let(::normalizeIdentifier), normalizeIdentifier(parts.last()), link)
        }

        private fun isName(part: String) =
            part.matches(SIMPLE_NAME) || part.length > 2 && part.startsWith('"') && part.endsWith('"') && part.indexOf('"', 1) == part.length - 1
    }
}
