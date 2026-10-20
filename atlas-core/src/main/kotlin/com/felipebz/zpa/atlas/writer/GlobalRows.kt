//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.writer

import com.felipebz.zpa.atlas.model.*
import java.sql.Connection
import java.sql.PreparedStatement

/** The rows that belong to the whole run: the run, the entities, the relations and the trigger events. */
internal class GlobalRows(private val connection: Connection) {
    fun insertRun(run: RunInfo) {
        prepare("INSERT INTO run(id, schema_version, atlas_version, zpa_version, sources_root, source_encoding, metadata_origin, compilation_schema, database_version, database_version_origin, ccflags, started_at, finished_at) VALUES (1,?,?,?,?,?,?,?,?,?,?,?,?)").use {
            it.setInt(1, AtlasWriter.SCHEMA_VERSION)
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
    fun insertEntities(facts: List<EntityFact>): Map<String, Long> {
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

    fun insertRelations(facts: List<RelationFact>, ids: Map<String, Long>) {
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

    fun insertTriggerEvents(facts: List<TriggerEventFact>, ids: Map<String, Long>) {
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
}
