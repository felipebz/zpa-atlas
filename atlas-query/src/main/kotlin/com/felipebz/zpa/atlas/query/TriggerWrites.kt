//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.query.UsageReader.Companion.ViewWrite
import java.sql.Connection

/**
 * A write that fires [trigger]: the target occurrence [occurrence] of an INSERT, UPDATE, DELETE or MERGE, in [container]
 * (null for code outside any). [fires] names the trigger's events it fires, such as `INSERT` or `INSERT,UPDATE`.
 * [sure] is false when the hop may not happen though the write is RESOLVED: the table is reached only through some of
 * the columns of a view, or an `UPDATE OF` trigger meets an UPDATE whose column list is not known.
 */
internal class FiringWrite(
    val trigger: Long,
    val occurrence: Long,
    val container: Long?,
    val status: String,
    val candidate: Boolean,
    val path: String,
    val line: Int,
    val sure: Boolean,
    val fires: String
)

/**
 * The writes that fire DML triggers, read from the trigger's events (`trigger_event`) against each write's statement.
 * A write fires a trigger of its table, or of the base table of a view it writes through ([UsageReader.writtenThroughViews]:
 * one base, and no INSTEAD OF trigger on the view for the write's event: such a trigger takes the write, and only it
 * fires). A MERGE into a view with an INSTEAD OF trigger for the event of one of its clauses is refused by Oracle and
 * fires nothing; any other MERGE goes through every view to the base table and never fires an INSTEAD OF trigger, not
 * even one of a view below its target. TRUNCATE (a DDL role) and remote tables (another entity) never fire one.
 *
 * As Oracle 26ai runs them: INSERT and the insert clause of MERGE fire INSERT; UPDATE and the update clause of MERGE fire
 * UPDATE, and `UPDATE OF` only when they set one of its columns; DELETE and the DELETE WHERE of MERGE fire DELETE. A
 * statement-level trigger fires even when no row is touched, so every such statement counts.
 */
internal class TriggerWrites(private val connection: Connection) {

    /** The triggers among [ids] that are DML triggers (they have a table), with that table. */
    fun tables(ids: Collection<Long>): Map<Long, Long> {
        if (ids.isEmpty()) return emptyMap()
        return connection.query(
            "SELECT from_id, to_id FROM relation WHERE kind = 'TRIGGER_ON' AND from_id IN (SELECT value FROM json_each(?)) ORDER BY from_id",
            listOf(json(ids))
        ) { it.getLong(1) to it.getLong(2) }.toMap()
    }

    private class Events {
        var insert = false
        var delete = false
        var updateAll = false
        var insteadOf = false
        val updateColumns = HashSet<Long>()
        val update get() = updateAll || updateColumns.isNotEmpty()
    }

    private class Write(
        val occurrence: Long, val relation: Long, val roles: Set<String>, val status: String, val candidate: Boolean,
        val container: Long?, val path: String, val line: Int, val statement: Long?
    )

    /** A written column of a statement: [entity] and its [parent] when resolved. */
    private class Column(val entity: Long?, val parent: Long?, val roles: Set<String>)

    /** The writes that fire each of [triggerIds]. */
    fun firing(triggerIds: Collection<Long>): List<FiringWrite> {
        val tableOf = tables(triggerIds)
        if (tableOf.isEmpty()) return emptyList()
        val events = HashMap<Long, Events>()
        connection.query(
            "SELECT trigger_id, event, column_id, timing FROM trigger_event WHERE trigger_id IN (SELECT value FROM json_each(?))",
            listOf(json(tableOf.keys))
        ) { listOf(it.getLong(1), it.getString(2), (it.getObject(3) as Number?)?.toLong(), it.getString(4)) }.forEach { (trigger, event, column, timing) ->
            val its = events.getOrPut(trigger as Long) { Events() }
            if (timing == "INSTEAD_OF") its.insteadOf = true
            when (event) {
                "INSERT" -> its.insert = true
                "DELETE" -> its.delete = true
                "UPDATE" -> if (column == null) its.updateAll = true else its.updateColumns += column as Long
            }
        }
        val tables = tableOf.values.toSet()
        // By kind of write, each view column whose writes of that kind reach a column of the tables: the base column, its
        // table and the view.
        val chains = listOf(ViewWrite.INSERT, ViewWrite.UPDATE, ViewWrite.DELETE, ViewWrite.MERGE).associateWith { kind ->
            connection.query(
                """WITH RECURSIVE reach(id, base) AS (
                     SELECT id, id FROM entity WHERE parent_id IN (SELECT value FROM json_each(?)) AND kind = 'COLUMN'
                     UNION ${UsageReader.writtenThroughViews("reach", kind)})
                   SELECT reach.id, reach.base, b.parent_id, c.parent_id FROM reach
                   JOIN entity b ON b.id = reach.base JOIN entity c ON c.id = reach.id WHERE reach.id <> reach.base""",
                listOf(json(tables))
            ) { it.getLong(1) to Triple(it.getLong(2), it.getLong(3), it.getLong(4)) }.toMap()
        }
        val views = chains.values.flatMap { chain -> chain.values.map { it.third } }.toSet()
        val writes = connection.query(
            """WITH r(id) AS (SELECT value FROM json_each(?))
               SELECT o.id, o.entity_id, o.roles, o.status, 0, o.container_entity_id, f.path, o.start_line, o.statement_id
               FROM occurrence o JOIN source_file f ON f.id = o.file_id WHERE o.entity_id IN (SELECT id FROM r)
               UNION ALL
               SELECT o.id, c.entity_id, o.roles, o.status, 1, o.container_entity_id, f.path, o.start_line, o.statement_id
               FROM occurrence_candidate c JOIN occurrence o ON o.id = c.occurrence_id JOIN source_file f ON f.id = o.file_id
               WHERE c.entity_id IN (SELECT id FROM r)""",
            listOf(json(tables + views))
        ) {
            Write(it.getLong(1), it.getLong(2), roles(it.getString(3)).toSet(), it.getString(4), it.getInt(5) == 1,
                (it.getObject(6) as Number?)?.toLong(), it.getString(7), it.getInt(8), (it.getObject(9) as Number?)?.toLong())
        }.filter { w -> w.roles.any { it in DML_ROLES } }
        if (writes.isEmpty()) return emptyList()
        val columns = connection.query(
            """SELECT o.statement_id, o.entity_id, e.parent_id, o.roles FROM occurrence o LEFT JOIN entity e ON e.id = o.entity_id
               WHERE o.statement_id IN (SELECT value FROM json_each(?)) AND (',' || o.roles || ',') LIKE '%,WRITE,%'""",
            listOf(json(writes.mapNotNull { it.statement }.toSet()))
        ) { it.getLong(1) to Column((it.getObject(2) as Number?)?.toLong(), (it.getObject(3) as Number?)?.toLong(), roles(it.getString(4)).toSet()) }
            .groupBy({ it.first }, { it.second })
        // A DELETE through a view writes no column: the view's columns tell which table it reaches.
        val viewColumns = connection.query(
            "SELECT id, parent_id FROM entity WHERE kind = 'COLUMN' AND parent_id IN (SELECT value FROM json_each(?))",
            listOf(json(writes.filter { it.relation in views }.map { it.relation }.toSet()))
        ) { it.getLong(2) to Column(it.getLong(1), it.getLong(2), emptySet()) }.groupBy({ it.first }, { it.second })

        // The events each written relation has an INSTEAD OF trigger for: a MERGE with a clause of one of them is refused.
        val insteadOf = connection.query(
            """SELECT r.to_id, e.event FROM relation r JOIN trigger_event e ON e.trigger_id = r.from_id
               WHERE r.kind = 'TRIGGER_ON' AND e.timing = 'INSTEAD_OF' AND r.to_id IN (SELECT value FROM json_each(?))""",
            listOf(json(writes.map { it.relation }.toSet()))
        ) { it.getLong(1) to it.getString(2) }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        val refused = { write: Write -> "SQL_MERGE" in write.roles && insteadOf[write.relation].orEmpty().any { "SQL_$it" in write.roles } }

        val byRelation = writes.filterNot(refused).groupBy { it.relation }
        val viewsOf = chains.values.flatMap { it.values }.groupBy({ it.second }, { it.third }).mapValues { it.value.toSet() }
        val found = ArrayList<FiringWrite>()
        for ((trigger, table) in tableOf) {
            val its = events[trigger] ?: continue
            for (write in (setOf(table) + viewsOf[table].orEmpty()).flatMap { byRelation[it].orEmpty() }) {
                val merge = "SQL_MERGE" in write.roles
                // A MERGE never runs an INSTEAD OF trigger: it is refused, or goes on to the base table.
                if (merge && its.insteadOf) continue
                val written = columns[write.statement].orEmpty().filter { it.parent == null || it.parent == write.relation }

                /** The columns of [table] that [cols] write by [event] (or as a MERGE), and whether every one reaches it. */
                fun reach(cols: List<Column>, event: ViewWrite): Pair<Set<Long>, Boolean> {
                    if (write.relation == table) return cols.mapNotNull { it.entity }.toSet() to true
                    val chain = chains.getValue(if (merge) ViewWrite.MERGE else event)
                    val bases = cols.mapNotNull { c -> c.entity?.let { chain[it] }?.takeIf { it.second == table }?.first }
                    return bases.toSet() to (bases.size == cols.size)
                }

                val fired = ArrayList<Pair<String, Boolean>>()
                if (its.insert && "SQL_INSERT" in write.roles) {
                    val (bases, all) = reach(written.filter { "SQL_INSERT" in it.roles }, ViewWrite.INSERT)
                    if (write.relation == table || bases.isNotEmpty()) fired += "INSERT" to all
                }
                if (its.delete && "SQL_DELETE" in write.roles) {
                    val (bases, all) = reach(viewColumns[write.relation].orEmpty(), ViewWrite.DELETE)
                    if (write.relation == table || bases.isNotEmpty()) fired += "DELETE" to all
                }
                if (its.update && "SQL_UPDATE" in write.roles) {
                    val set = written.filter { "SQL_UPDATE" in it.roles }
                    val (bases, all) = reach(set, ViewWrite.UPDATE)
                    if (write.relation == table || bases.isNotEmpty()) {
                        // `UPDATE OF`: a known column list sets one of its columns or does not; `SET ROW`, an unresolved
                        // column or a column outside the chain leaves it unknown, and the hop is not sure.
                        val known = set.isNotEmpty() && set.none { "IMPLICIT" in it.roles || it.entity == null } && all
                        when {
                            its.updateAll -> fired += "UPDATE" to all
                            !known -> fired += "UPDATE" to false
                            bases.any { it in its.updateColumns } -> fired += "UPDATE" to true
                        }
                    }
                }
                if (fired.isEmpty()) continue
                found += FiringWrite(
                    trigger, write.occurrence, write.container, write.status, write.candidate, write.path, write.line,
                    fired.any { it.second }, fired.map { it.first }.sorted().joinToString(",")
                )
            }
        }
        return found
    }

    private fun json(ids: Collection<Long>) = ids.joinToString(",", "[", "]")

    companion object {
        private val DML_ROLES = setOf("SQL_INSERT", "SQL_UPDATE", "SQL_DELETE")
    }
}
