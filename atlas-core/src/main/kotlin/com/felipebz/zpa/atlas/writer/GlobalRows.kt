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
        prepare("INSERT INTO run(id, schema_version, atlas_version, zpa_version, sources_root, source_encoding, metadata_origin, compilation_schema, schema_paths, database_version, database_version_origin, ccflags, nls_length_semantics, started_at, finished_at) VALUES (1,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use {
            it.setInt(1, AtlasWriter.SCHEMA_VERSION)
            it.setString(2, run.atlasVersion)
            it.setString(3, run.zpaVersion)
            it.setString(4, run.sourcesRoot)
            it.setString(5, run.sourceEncoding)
            it.setNullableString(6, run.metadataOrigin)
            it.setNullableString(7, run.compilationSchema)
            it.setNullableString(8, run.schemaPaths)
            it.setNullableString(9, run.databaseVersion)
            it.setNullableString(10, run.databaseVersionOrigin)
            it.setNullableString(11, run.ccflags)
            it.setString(12, run.nlsLengthSemantics)
            it.setString(13, run.startedAt)
            it.setString(14, run.finishedAt)
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

    /**
     * The declared texts, one row per entity: when several files declare the same entity (a package cursor's spec and
     * body), the first file in path order that has a type gives it, and the first that has a value gives it.
     */
    fun insertDeclaredTexts(facts: List<DeclaredTextFact>, ids: Map<String, Long>) {
        // A column of a `CREATE TABLE` the run never mentioned has no entity: its datatype is left out.
        val byEntity = facts.filter { it.entityKey in ids }.groupBy { ids.getValue(it.entityKey) }.toSortedMap()
        prepare("INSERT INTO declared_text(entity_id, datatype, value, value_cut) VALUES (?,?,?,?)").use { insert ->
            val batches = Batches(insert)
            for ((id, same) in byEntity) {
                val valued = same.firstOrNull { it.value != null }
                insert.setLong(1, id)
                insert.setNullableString(2, same.firstNotNullOfOrNull { it.datatype })
                insert.setNullableString(3, valued?.value)
                insert.setInt(4, if (valued?.valueCut == true) 1 else 0)
                batches.add(insert)
                batches.flushIfFull()
            }
            batches.flush()
        }
    }

    /** The body globals whose uses may be incomplete: one row per entity, its reasons comma-separated in order. */
    fun insertUsesGaps(facts: List<UsesGapFact>, ids: Map<String, Long>) {
        prepare("INSERT INTO uses_gap(entity_id, reasons) VALUES (?,?)").use { insert ->
            val batches = Batches(insert)
            for ((id, fact) in facts.associateBy { ids.getValue(it.entityKey) }.toSortedMap()) {
                insert.setLong(1, id)
                insert.setString(2, fact.reasons.joinToString(","))
                batches.add(insert)
                batches.flushIfFull()
            }
            batches.flush()
        }
    }

    /** The types whose listed attributes may not be all: one row per entity, its reasons comma-separated. */
    fun insertMembersGaps(facts: List<MembersGapFact>, ids: Map<String, Long>) {
        prepare("INSERT INTO members_gap(entity_id, reasons) VALUES (?,?)").use { insert ->
            val batches = Batches(insert)
            for ((id, fact) in facts.associateBy { ids.getValue(it.entityKey) }.toSortedMap()) {
                insert.setLong(1, id)
                insert.setString(2, fact.reasons.joinToString(","))
                batches.add(insert)
                batches.flushIfFull()
            }
            batches.flush()
        }
    }

    /**
     * The dictionary keys, numbered in their order (owner, then name), and their columns: a foreign key's referenced
     * key by its id when it is among them, its name always.
     */
    fun insertKeys(facts: List<KeyFact>, ids: Map<String, Long>) {
        val keyIds = HashMap<Pair<String, String>, Long>()
        facts.forEachIndexed { index, key -> keyIds[key.owner to key.name] = index + 1L }
        prepare("""INSERT INTO dictionary_key(id, owner, name, kind, table_owner, table_name, relation_id, enabled, delete_rule,
                   referenced_owner, referenced_name, referenced_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""").use { insert ->
            val batches = Batches(insert)
            facts.forEachIndexed { index, key ->
                insert.setLong(1, index + 1L)
                insert.setString(2, key.owner)
                insert.setString(3, key.name)
                insert.setString(4, key.kind.name)
                insert.setString(5, key.tableOwner)
                insert.setString(6, key.tableName)
                insert.setNullableLong(7, key.relationKey?.let(ids::getValue))
                insert.setInt(8, if (key.enabled) 1 else 0)
                insert.setNullableString(9, key.deleteRule)
                insert.setNullableString(10, key.referencedOwner)
                insert.setNullableString(11, key.referencedName)
                insert.setNullableLong(12, key.referencedName?.let { keyIds[key.referencedOwner to it] })
                batches.add(insert)
                batches.flushIfFull()
            }
            batches.flush()
        }
        prepare("INSERT INTO dictionary_key_column(key_id, position, column_name, column_id) VALUES (?,?,?,?)").use { insert ->
            val batches = Batches(insert)
            facts.forEachIndexed { index, key ->
                key.columns.forEachIndexed { position, (name, columnKey) ->
                    insert.setLong(1, index + 1L)
                    insert.setInt(2, position + 1)
                    insert.setString(3, name)
                    insert.setNullableLong(4, columnKey?.let(ids::getValue))
                    batches.add(insert)
                    batches.flushIfFull()
                }
            }
            batches.flush()
        }
    }

    private fun prepare(sql: String): PreparedStatement = connection.prepareStatement(sql)
}
