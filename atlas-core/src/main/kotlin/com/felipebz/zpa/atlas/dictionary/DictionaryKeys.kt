//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.dictionary

import com.felipebz.zpa.api.project.ProjectExternalLookup
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.extract.RelationResolver
import com.felipebz.zpa.atlas.extract.dbKey
import com.felipebz.zpa.atlas.extract.exact
import com.felipebz.zpa.atlas.model.KeyFact
import com.felipebz.zpa.atlas.model.KeyKind

/**
 * The keys the index records, around the dictionary relations that are entities. A key links two relations: so that a
 * key's both ends can be followed from a page, the relation at the other end of a key of an entity relation becomes an
 * entity too, when the dictionary read it ([linked]), one hop only. A key whose relation the dictionary did not read is
 * still recorded when it is the other end of a key of an entity relation: by name, without entities.
 */
object DictionaryKeys {
    /**
     * The coverage note of a run whose loading user may not see every table ([AtlasDictionary.seesEveryTable]): its
     * message starts with the reason, [TABLES_NOT_VISIBLE]. The keys' "Referenced by" lists may then be incomplete.
     */
    const val REFERENCED_BY_INCOMPLETE = "REFERENCED_BY_INCOMPLETE"
    const val TABLES_NOT_VISIBLE = "TABLES_NOT_VISIBLE"

    /**
     * The dictionary relations, by entity key, at the other end of a key of [relations] (the relation a foreign key
     * references, the relations whose foreign keys reference one of its keys) that are not in [relations] and that the
     * dictionary read.
     */
    fun linked(dictionary: AtlasDictionary, relations: Map<String, ProjectExternalRelation>): Map<String, ProjectExternalRelation> {
        val result = sortedMapOf<String, ProjectExternalRelation>()
        for (key in around(dictionary, relations)) {
            val table = dbKey(key.tableOwner, key.tableName)
            if (table in relations || table in result) continue
            val lookup = dictionary.metadata.findRelation(RelationResolver.identifier(key.tableOwner), RelationResolver.identifier(key.tableName))
            if (lookup.status == ProjectExternalLookup.Status.FOUND) result[table] = lookup.relation.get()
        }
        return result
    }

    /**
     * The keys of [relations] (every entity relation, [linked] ones included), the keys their foreign keys reference and
     * the foreign keys that reference theirs, in name order, each with the entities of its relation and columns.
     */
    fun facts(dictionary: AtlasDictionary, relations: Map<String, ProjectExternalRelation>): List<KeyFact> =
        around(dictionary, relations).sortedWith(compareBy({ it.owner }, { it.name })).map { key ->
            val relationKey = dbKey(key.tableOwner, key.tableName)
            val relation = relations[relationKey]
            val columns = relation?.columns.orEmpty().mapTo(HashSet()) { it.name.exact() }
            KeyFact(
                key.owner, key.name, key.kind, key.tableOwner, key.tableName, relationKey.takeIf { relation != null },
                key.columns.map { it to "$relationKey.$it".takeIf { _ -> it in columns } },
                key.enabled, key.deleteRule, key.referencedOwner, key.referencedName
            )
        }

    /** The keys of [relations], with the keys their foreign keys reference and the foreign keys referencing theirs, once each. */
    private fun around(dictionary: AtlasDictionary, relations: Map<String, ProjectExternalRelation>): Collection<DictionaryKey> {
        if (dictionary.keys.isEmpty()) return emptyList()
        val result = LinkedHashMap<Pair<String, String>, DictionaryKey>()
        for (relation in relations.values) {
            for (key in dictionary.keysOf(relation.schema.exact(), relation.name.exact())) {
                result.putIfAbsent(key.owner to key.name, key)
                if (key.kind == KeyKind.FOREIGN_KEY) {
                    val referenced = dictionary.key(key.referencedOwner ?: continue, key.referencedName ?: continue) ?: continue
                    result.putIfAbsent(referenced.owner to referenced.name, referenced)
                } else {
                    dictionary.referencing(key).forEach { result.putIfAbsent(it.owner to it.name, it) }
                }
            }
        }
        return result.values
    }
}
