//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import java.sql.Connection
import java.sql.ResultSet

/** Entity search and the entity page, over one open connection. */
internal class EntityReader(private val connection: Connection) {
    private val forms = FormsLines(connection)

    /** [AtlasIndex.searchEntities] after its checks: [text] trimmed, split into [segments]. */
    fun searchEntities(text: String, segments: List<String>, kind: String?, includeExternal: Boolean, limit: Int): List<SearchHit> {
        fun search(joins: String, match: String, parameters: List<Any?>) = connection.query(
            """SELECT e.key, e.kind, e.display, e.external,
                      (SELECT count(*) FROM occurrence o WHERE o.entity_id = e.id AND o.roles NOT IN ('DEFINITION', 'DECLARATION')), e.name
               FROM entity e $joins
               WHERE $match ${if (kind != null) "AND e.kind = ?" else ""} ${if (includeExternal) "" else "AND e.external = 0"}
               ORDER BY CASE WHEN e.name = ? COLLATE NOCASE THEN 0 ELSE 1 END, e.external, e.name COLLATE NOCASE, e.display COLLATE NOCASE, e.key
               LIMIT ?""",
            parameters + listOfNotNull(kind, text, limit)
        ) { SearchHit(it.getString(1), it.getString(2), it.getString(3), it.getInt(4) == 1, it.getLong(5), it.getString(6)) }

        // A routine's own variables and constants are many and share names (`i`, `l_result`): a plain name finds them
        // only when the kind is asked for; a dotted name naming their routine finds them below.
        if (segments.size == 1) {
            val locals = if (kind == null) " AND e.kind NOT IN ('LOCAL_VARIABLE', 'LOCAL_CONSTANT')" else ""
            return search("", "e.name LIKE ? ESCAPE '\\'$locals", listOf("${escapeLike(text)}%"))
        }
        val structural = if (segments.size > OccurrenceQuerySql.MAX_SEGMENTS || segments.any { it.isEmpty() }) {
            emptyList()
        } else {
            // The last segment is a name prefix, the others name the parents exactly.
            val (joins, match, parameters) = OccurrenceQuerySql.parentChain("e", segments) { column, segment, last ->
                if (last) "$column LIKE ? ESCAPE '\\'" to listOf("${escapeLike(segment)}%") else "$column = ? COLLATE NOCASE" to listOf(segment)
            }
            search(joins, match, parameters)
        }
        return structural.ifEmpty { search("", "e.display LIKE ? ESCAPE '\\'", listOf("%${escapeLike(text)}%")) }
    }

    fun entity(key: String): EntityDetail? {
        val row = connection.query(
            "SELECT id, key, kind, schema_name, name, display, external, parent_id FROM entity WHERE key = ?", listOf(key)
        ) { Entity(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6), it.getInt(7) == 1, it.getObject(8) as Number?) }
            .singleOrNull() ?: return null
        val parent = row.parentId?.let { summary(connection, it.toLong()) }
        val ancestors = connection.query(
            """WITH RECURSIVE up(id, depth) AS (
                   SELECT parent_id, 1 FROM entity WHERE id = ? AND parent_id IS NOT NULL
                   UNION ALL
                   SELECT e.parent_id, up.depth + 1 FROM up JOIN entity e ON e.id = up.id WHERE e.parent_id IS NOT NULL)
               SELECT e.key, e.kind, e.display, e.external, e.name FROM up JOIN entity e ON e.id = up.id ORDER BY up.depth DESC""",
            listOf(row.id), ::entitySummary
        )
        val childRows = connection.query(
            """SELECT c.key, c.kind, c.display, c.external, c.name,
                      (SELECT count(*) FROM occurrence o WHERE o.entity_id = c.id AND o.roles NOT IN ('DEFINITION', 'DECLARATION')), c.id
               FROM relation r JOIN entity c ON c.id = r.to_id
               WHERE r.from_id = ? AND r.kind = 'CONTAINS' ORDER BY c.kind, c.name COLLATE NOCASE, c.key""", listOf(row.id)
        ) { Triple(entitySummary(it), it.getLong(6), it.getLong(7)) }
        val declared = declared(childRows.map { it.third } + row.id)
        val documentation = documentation(childRows.associate { it.third to it.first.key } + (row.id to row.key))
        val gaps = usesGaps(childRows.map { it.third } + row.id)
        val children = childRows.map { (entity, usages, id) -> Member(entity, usages, declared[id], documentation[id].orEmpty(), gaps[id]) }
        // VIEW_BASE edges have their own lists (baseColumns, viewColumns).
        val relations = connection.query(
            """SELECT r.kind, 'OUT', t.key, t.kind, t.display, t.external, t.name FROM relation r JOIN entity t ON t.id = r.to_id
               WHERE r.from_id = ? AND r.kind NOT IN ('CONTAINS', 'VIEW_BASE')
               UNION ALL
               SELECT r.kind, 'IN', f.key, f.kind, f.display, f.external, f.name FROM relation r JOIN entity f ON f.id = r.from_id
               WHERE r.to_id = ? AND r.kind NOT IN ('CONTAINS', 'VIEW_BASE')
               ORDER BY 1, 2, 3""", listOf(row.id, row.id)
        ) { RelationLink(it.getString(1), it.getString(2), EntitySummary(it.getString(3), it.getString(4), it.getString(5), it.getInt(6) == 1, it.getString(7))) }
        val definitions = definitions("o.entity_id = ?", row.id)
        val partners = relations.filter { it.kind == "IMPLEMENTS" }.map { Partner(it.entity, it.direction, definitions(it.entity.key)) }
        val overloads = if (row.kind == "PROCEDURE" || row.kind == "FUNCTION") {
            connection.query(
                """SELECT key, kind, display, external, name FROM entity
                   WHERE parent_id IS ? AND name = ? AND kind = ? ORDER BY key""", listOf(row.parentId, row.name, row.kind), ::entitySummary
            ).map { Overload(it, isBody(it.key), it.key == row.key) }
        } else {
            emptyList()
        }
        val usages = connection.query(
            "SELECT roles, status FROM occurrence WHERE entity_id = ? AND roles NOT IN ('DEFINITION', 'DECLARATION')", listOf(row.id)
        ) { it.getString(1) to it.getString(2) }
        val asCandidate = connection.query("SELECT count(*) FROM occurrence_candidate WHERE entity_id = ?", listOf(row.id)) { it.getLong(1) }.single()
        val ownKeys = ownKeys(row.id, row.kind)
        val formsReader = if (row.key.startsWith("forms:")) FormsReader(connection) else null
        return EntityDetail(
            row.key, row.kind, row.schema, row.name, row.display, row.external, parent, ancestors, children, relations, definitions, overloads, partners,
            UsageSummary(tally(usages.flatMap { roles(it.first) }), tally(usages.map { it.second }), asCandidate),
            triggerEvents(row.id, row.kind, row.parentId?.toLong()),
            if (row.kind in VIEW_KINDS) viewReads(row.id) else emptyList(),
            if (row.kind in VIEWED_KINDS) viewsReading(row.id) else emptyList(),
            if (row.kind == "COLUMN") viewBase(row.id, outgoing = true) else emptyList(),
            if (row.kind == "COLUMN") viewBase(row.id, outgoing = false) else emptyList(),
            declared[row.id],
            documentation[row.id].orEmpty(),
            ownAndPartnerGaps(row, gaps[row.id]),
            connection.query("SELECT reasons FROM members_gap WHERE entity_id = ?", listOf(row.id)) { it.getString(1).split(',') }.singleOrNull(),
            keys(ownKeys),
            referencing(ownKeys, 0, null).second,
            referencedByIncomplete(ownKeys),
            if (row.kind == "COLUMN") PlaceReader(connection).counts(row.id, concatenated = false) else emptyList(),
            if (row.kind == "COLUMN") PlaceReader(connection).total(row.id, concatenated = true) else 0,
            PlaceReader(connection).receives(row.id),
            formsReader?.info(row.id),
            if (row.kind == "FORMS_BLOCK") formsReader?.block(row.id) else null,
            if (row.kind == "FORMS_ITEM") formsReader?.item(row.id) else null
        )
    }

    /**
     * The documentation of the entities [keys] (by id) shows ([Documentation.shown]): each one's own rows, and the other
     * half's. A package or a cursor holds both halves (`in_body`); a routine's other half is the spec it `IMPLEMENTS` or
     * the body implementing it; a parameter's, the parameter of the same name of that routine. A key with `@BODY` is a
     * body's page.
     */
    private fun documentation(keys: Map<Long, String>): Map<Long, List<Documentation>> {
        if (keys.isEmpty()) return emptyMap()
        val rows = connection.query(
            """WITH ids(id) AS (SELECT value FROM json_each(?)),
               halves(id, other) AS (
                   SELECT id, id FROM ids
                   UNION SELECT i.id, r.to_id FROM ids i JOIN relation r ON r.from_id = i.id AND r.kind = 'IMPLEMENTS'
                   UNION SELECT i.id, r.from_id FROM ids i JOIN relation r ON r.to_id = i.id AND r.kind = 'IMPLEMENTS'
                   UNION SELECT i.id, p.id FROM ids i JOIN entity e ON e.id = i.id AND e.kind = 'PARAMETER'
                         JOIN relation r ON r.kind = 'IMPLEMENTS' AND (r.from_id = e.parent_id OR r.to_id = e.parent_id)
                         JOIN entity p ON p.parent_id = CASE WHEN r.from_id = e.parent_id THEN r.to_id ELSE r.from_id END
                                      AND p.kind = 'PARAMETER' AND p.name = e.name)
               SELECT h.id, d.in_body, d.shape, d.text, d.text_cut, f.path, d.start_line
               FROM halves h JOIN doc_comment d ON d.entity_id = h.other JOIN source_file f ON f.id = d.file_id
               ORDER BY h.id, f.path, d.start_line""",
            listOf(keys.keys.joinToString(",", "[", "]"))
        ) { it.getLong(1) to ((it.getInt(2) == 1) to Documentation(it.getString(4), it.getInt(5) == 1, it.getString(3), it.getString(6), it.getInt(7), null)) }
            .map { (id, doc) -> id to (doc.first to doc.second.copy(code = forms.codeAt(doc.second.path, doc.second.line))) }
        // A body routine's parameter is on the body's side too.
        fun onBody(key: String) = isBody(key) || isBody(key.substringBeforeLast('.'))
        return rows.groupBy({ it.first }, { it.second }).mapValues { (id, docs) ->
            Documentation.shown(docs.firstOrNull { !it.first }?.second, docs.firstOrNull { it.first }?.second, onBody(keys.getValue(id)))
        }
    }

    /** Why the recorded uses of [ids] may be incomplete ([EntityDetail.usesIncomplete]), for those that have a reason. */
    private fun usesGaps(ids: List<Long>): Map<Long, List<String>> = connection.query(
        "SELECT entity_id, reasons FROM uses_gap WHERE entity_id IN (SELECT value FROM json_each(?))", listOf(ids.joinToString(",", "[", "]"))
    ) { it.getLong(1) to it.getString(2).split(',') }.toMap()

    /**
     * [own], and for a parameter the reasons of its partners ([PARAMETER_PARTNERS]): the page and "who uses" show a
     * parameter with the uses of its spec's or body's, so they say when those may be incomplete.
     */
    private fun ownAndPartnerGaps(row: Entity, own: List<String>?): List<String>? {
        if (row.kind != "PARAMETER") return own
        val partners = connection.query(
            "SELECT g.reasons FROM entity e $PARAMETER_PARTNERS JOIN uses_gap g ON g.entity_id = p.id WHERE e.id = ?", listOf(row.id)
        ) { it.getString(1).split(',') }.flatten()
        return (own.orEmpty() + partners).distinct().ifEmpty { null }
    }

    /**
     * What the declarations of [ids] say ([Declared.of]), with the spec's text for a body: the spec a routine body
     * `IMPLEMENTS`, or for a body's parameter, the parameter of the same name of that spec.
     */
    private fun declared(ids: List<Long>): Map<Long, Declared> = connection.query(
        """SELECT e.id, d.datatype, d.value, d.value_cut, sd.entity_id, sd.datatype, sd.value, sd.value_cut
           FROM entity e
           LEFT JOIN declared_text d ON d.entity_id = e.id
           LEFT JOIN declared_text sd ON sd.entity_id = CASE WHEN e.kind = 'PARAMETER'
               THEN (SELECT p.id FROM relation r JOIN entity p ON p.parent_id = r.to_id AND p.kind = 'PARAMETER' AND p.name = e.name
                     WHERE r.from_id = e.parent_id AND r.kind = 'IMPLEMENTS' ORDER BY p.id LIMIT 1)
               ELSE (SELECT r.to_id FROM relation r WHERE r.from_id = e.id AND r.kind = 'IMPLEMENTS' ORDER BY r.to_id LIMIT 1) END
           WHERE e.id IN (SELECT value FROM json_each(?)) AND (d.entity_id IS NOT NULL OR sd.entity_id IS NOT NULL)""",
        listOf(ids.joinToString(",", "[", "]"))
    ) { rows ->
        val own = DeclaredText(rows.getString(2), rows.getString(3), rows.getInt(4) == 1)
        val spec = rows.getObject(5)?.let { DeclaredText(rows.getString(6), rows.getString(7), rows.getInt(8) == 1) }
        rows.getLong(1) to Declared.of(own, spec)
    }.mapNotNull { (id, declared) -> declared?.let { id to it } }.toMap()

    /** The definitions and declarations of the entity [key], alone; empty for an unknown key. */
    fun definitions(key: String): List<Location> = definitions("o.entity_id = (SELECT id FROM entity WHERE key = ?)", key)

    private fun definitions(entity: String, parameter: Any): List<Location> = connection.query(
        """SELECT f.path, o.start_line, o.start_col, o.end_line, o.end_col, o.roles, o.status, o.written, u.kind,
                  ${FormsReader.codeAtSql("o.file_id", "o.start_line")}
           FROM occurrence o JOIN source_file f ON f.id = o.file_id LEFT JOIN unit u ON u.id = o.unit_id
           WHERE $entity AND o.roles IN ('DEFINITION', 'DECLARATION') ORDER BY o.id""", listOf(parameter)
    ) { Location(it.getString(1), span(it, 2), roles(it.getString(6)), it.getString(7), it.getString(8), it.getString(9), FormsReader.codeAt(it.getString(10))) }

    private class Entity(val id: Long, val key: String, val kind: String, val schema: String?, val name: String, val display: String,
                         val external: Boolean, val parentId: Number?)

    /** One hop of `VIEW_BASE` edges of a column: the columns it comes from ([outgoing]), or the view columns coming from it. */
    private fun viewBase(columnId: Long, outgoing: Boolean): List<EntitySummary> {
        val (near, far) = if (outgoing) "from_id" to "to_id" else "to_id" to "from_id"
        return connection.query(
            """SELECT e.key, e.kind, e.display, e.external, e.name FROM relation r JOIN entity e ON e.id = r.$far
               WHERE r.$near = ? AND r.kind = 'VIEW_BASE' ORDER BY e.key""", listOf(columnId), ::entitySummary
        )
    }

    // Both derive from the SQL_SELECT occurrences: no relation rows are stored for them.
    private fun viewReads(viewId: Long): List<EntitySummary> = connection.query(
        """SELECT DISTINCT e.key, e.kind, e.display, e.external, e.name FROM occurrence o JOIN entity e ON e.id = o.entity_id
           WHERE o.container_entity_id = ? AND (',' || o.roles || ',') LIKE '%,SQL_SELECT,%' ORDER BY e.key""", listOf(viewId), ::entitySummary
    )

    private fun viewsReading(relationId: Long): List<EntitySummary> = connection.query(
        """SELECT DISTINCT v.key, v.kind, v.display, v.external, v.name FROM occurrence o JOIN entity v ON v.id = o.container_entity_id
           WHERE o.entity_id = ? AND v.kind IN ($VIEW_KINDS_SQL) AND (',' || o.roles || ',') LIKE '%,SQL_SELECT,%' ORDER BY v.key""", listOf(relationId), ::entitySummary
    )

    /** The trigger events [EntityDetail.triggerEvents] lists for an entity of [kind]. */
    private fun triggerEvents(id: Long, kind: String, parentId: Long?): List<TriggerEvent> {
        val select = """SELECT t.key, t.kind, t.display, t.external, e.timing, e.level, e.event, c.key, c.kind, c.display, c.external, t.name, c.name
               FROM trigger_event e JOIN entity t ON t.id = e.trigger_id LEFT JOIN entity c ON c.id = e.column_id"""
        val order = " ORDER BY t.key, e.event, e.timing, c.key"
        val map = { rs: ResultSet ->
            TriggerEvent(EntitySummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1, rs.getString(12)), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8)?.let { EntitySummary(it, rs.getString(9), rs.getString(10), rs.getInt(11) == 1, rs.getString(13)) })
        }
        return when (kind) {
            "TRIGGER" -> connection.query("$select WHERE e.trigger_id = ?$order", listOf(id), map)
            "TABLE", "VIEW", "MATERIALIZED_VIEW", "OBJECT_TABLE", "UNKNOWN_RELATION" -> connection.query(
                "$select WHERE e.trigger_id IN (SELECT from_id FROM relation WHERE kind = 'TRIGGER_ON' AND to_id = ?)$order", listOf(id), map)
            // UPDATE OF this column, or an UPDATE without column list of a trigger on the column's relation. A NULL column
            // is also an unresolved UPDATE OF column: a trigger with a resolved one has a column list.
            "COLUMN" -> if (parentId == null) emptyList() else connection.query(
                """$select WHERE e.event = 'UPDATE' AND (e.column_id = ? OR (e.column_id IS NULL
                     AND e.trigger_id IN (SELECT from_id FROM relation WHERE kind = 'TRIGGER_ON' AND to_id = ?)
                     AND NOT EXISTS (SELECT 1 FROM trigger_event o WHERE o.trigger_id = e.trigger_id AND o.event = 'UPDATE' AND o.column_id IS NOT NULL)))$order""",
                listOf(id, parentId), map)
            else -> emptyList()
        }
    }

    /** The ids of the dictionary keys of the entity [id]: a column's are the keys it is a column of, any other entity's those on it. */
    private fun ownKeys(id: Long, kind: String): List<Long> = connection.query(
        if (kind == "COLUMN") "SELECT DISTINCT key_id FROM dictionary_key_column WHERE column_id = ?" else "SELECT id FROM dictionary_key WHERE relation_id = ?",
        listOf(id)
    ) { it.getLong(1) }

    /** The dictionary keys [ids] ([EntityDetail.keys]), primary first, then unique, then foreign, by name; a foreign key with the key it references. */
    private fun keys(ids: List<Long>): List<TableKey> {
        if (ids.isEmpty()) return emptyList()
        val json = ids.joinToString(",", "[", "]")
        val rows = connection.query(
            """SELECT k.id, k.owner, k.name, k.kind, k.table_owner, k.table_name, k.enabled, k.delete_rule, k.referenced_owner, k.referenced_name,
                      k.referenced_id, e.key, e.kind, e.display, e.external, e.name
               FROM dictionary_key k LEFT JOIN entity e ON e.id = k.relation_id
               WHERE k.id IN (SELECT value FROM json_each(?)) OR k.id IN (SELECT referenced_id FROM dictionary_key WHERE id IN (SELECT value FROM json_each(?)))""",
            listOf(json, json)
        ) { rs ->
            KeyRow(rs.getLong(1), rs.getObject(11) as Number?, TableKey(
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5) + "." + rs.getString(6),
                rs.getString(12)?.let { EntitySummary(it, rs.getString(13), rs.getString(14), rs.getInt(15) == 1, rs.getString(16)) },
                emptyList(), rs.getInt(7) == 1, rs.getString(8), rs.getString(10)?.let { rs.getString(9) + "." + it }, null
            ))
        }
        val columns = keyColumns(rows.map { it.id })
        val plain = rows.associate { it.id to it.key.copy(columns = columns[it.id].orEmpty()) }
        val kindOrder = listOf("PRIMARY_KEY", "UNIQUE", "FOREIGN_KEY")
        return rows.filter { it.id in ids }.map { row -> plain.getValue(row.id).copy(references = row.referencedId?.toLong()?.let(plain::get)) }
            .sortedWith(compareBy({ kindOrder.indexOf(it.kind) }, { it.name }, { it.owner }))
    }

    /** The columns of the keys [ids] in key order, by key id. */
    private fun keyColumns(ids: Collection<Long>): Map<Long, List<KeyColumn>> = connection.query(
        """SELECT c.key_id, c.column_name, e.key, e.kind, e.display, e.external, e.name
           FROM dictionary_key_column c LEFT JOIN entity e ON e.id = c.column_id
           WHERE c.key_id IN (SELECT value FROM json_each(?)) ORDER BY c.key_id, c.position""",
        listOf(ids.joinToString(",", "[", "]"))
    ) { rs -> rs.getLong(1) to KeyColumn(rs.getString(2), rs.getString(3)?.let { EntitySummary(it, rs.getString(4), rs.getString(5), rs.getInt(6) == 1, rs.getString(7)) }) }
        .groupBy({ it.first }, { it.second })

    /**
     * The foreign keys referencing the keys [own], by table then name: [limit] of them from [offset] (all with a null
     * [limit]), each column with the column it references; and how many there are.
     */
    private fun referencing(own: List<Long>, offset: Int, limit: Int?): Pair<Int, List<ReferencingKey>> {
        if (own.isEmpty()) return 0 to emptyList()
        val json = own.joinToString(",", "[", "]")
        val total = connection.query("SELECT count(*) FROM dictionary_key WHERE referenced_id IN (SELECT value FROM json_each(?))", listOf(json)) { it.getInt(1) }.single()
        val rows = connection.query(
            """SELECT k.id, k.referenced_id, k.owner, k.name, k.table_owner || '.' || k.table_name, k.enabled, k.delete_rule,
                      k.referenced_owner || '.' || k.referenced_name, e.key, e.kind, e.display, e.external, e.name
               FROM dictionary_key k LEFT JOIN entity e ON e.id = k.relation_id
               WHERE k.referenced_id IN (SELECT value FROM json_each(?))
               ORDER BY k.table_owner, k.table_name, k.name, k.owner LIMIT ? OFFSET ?""",
            listOf(json, limit ?: -1, offset)
        ) { rs ->
            Triple(rs.getLong(1), rs.getLong(2), ReferencingKey(
                rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(9)?.let { EntitySummary(it, rs.getString(10), rs.getString(11), rs.getInt(12) == 1, rs.getString(13)) },
                emptyList(), rs.getInt(6) == 1, rs.getString(7), rs.getString(8)
            ))
        }
        val columns = keyColumns(rows.flatMap { listOf(it.first, it.second) }.toSet())
        return total to rows.map { (id, referenced, key) ->
            val targets = columns[referenced].orEmpty()
            key.copy(columns = columns[id].orEmpty().mapIndexed { i, c -> ReferencingColumn(c.name, c.column, targets.getOrNull(i)?.name) })
        }
    }

    /**
     * Why the foreign keys referencing the keys [own] may be more than those recorded: the run's
     * `REFERENCED_BY_INCOMPLETE` coverage note, whose message starts with the reason. Null when there is none, or when
     * none of [own] is a primary or unique key (only those are referenced).
     */
    private fun referencedByIncomplete(own: List<Long>): List<String>? {
        if (own.isEmpty()) return null
        val referenceable = connection.query(
            "SELECT count(*) FROM dictionary_key WHERE kind <> 'FOREIGN_KEY' AND id IN (SELECT value FROM json_each(?))", listOf(own.joinToString(",", "[", "]"))
        ) { it.getInt(1) }.single()
        if (referenceable == 0) return null
        return connection.query("SELECT message FROM coverage_note WHERE kind = 'REFERENCED_BY_INCOMPLETE' ORDER BY id", emptyList()) {
            it.getString(1).substringBefore(':')
        }.distinct().ifEmpty { null }
    }

    /**
     * A page of the foreign keys that reference the keys of the entity [key] ([EntityDetail.referencedBy]): [limit] from
     * [offset]. Null when there is no such entity.
     */
    fun referencingKeys(key: String, offset: Int, limit: Int): ReferencingKeyPage? {
        val row = connection.query("SELECT id, kind FROM entity WHERE key = ?", listOf(key)) { it.getLong(1) to it.getString(2) }.singleOrNull() ?: return null
        val own = ownKeys(row.first, row.second)
        val (total, rows) = referencing(own, offset, limit)
        return ReferencingKeyPage(total, offset, rows, (offset + rows.size).takeIf { it < total }, referencedByIncomplete(own))
    }

    private class KeyRow(val id: Long, val referencedId: Number?, val key: TableKey)

    private fun escapeLike(text: String) = OccurrenceQuerySql.escapeLike(text)
}
