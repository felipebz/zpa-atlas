//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionaryKeys
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import com.felipebz.zpa.atlas.extract.RelationResolver
import com.felipebz.zpa.atlas.model.*
import java.util.*

/**
 * What the dictionary adds to a run once every file is walked: the dictionary relations that are entities, their
 * declared texts and keys; [merge] adds their entities and relations, the synonyms the files followed and the notes of
 * what the dictionary cannot say to the run's global lists.
 */
internal class DictionaryFacts private constructor(
    /** The dictionary relations that are entities, by key, in key order. */
    val resolved: Map<String, ProjectExternalRelation>,
    val declaredTexts: List<DeclaredTextFact>,
    val keys: List<KeyFact>
) {
    companion object {
        /**
         * Adds the entities and relations of [dictionary]'s relations and synonyms the [facts] of every file name (every
         * relation for an enumerable dictionary) to [globalEntities] and [globalRelations], and its notes to [globalCoverage].
         */
        fun merge(
            dictionary: AtlasDictionary,
            facts: List<FileFacts>,
            globalEntities: MutableList<EntityFact>,
            globalRelations: MutableList<RelationFact>,
            globalCoverage: MutableList<CoverageFact>
        ): DictionaryFacts {
            // The dictionary relations the files resolved, once each, in key order (an enumerable dictionary has them all).
            val resolved = TreeMap<String, ProjectExternalRelation>()
            for (file in facts) for (relation in file.dictionaryRelations) resolved.putIfAbsent(
                RelationResolver.dictionaryKey(
                    relation
                ), relation
            )
            if (dictionary.enumerable) dictionary.relations.forEach {
                resolved[RelationResolver.dictionaryKey(it.relation)] = it.relation
            }
            // The relations at the other end of their keys, so that both ends of a key are entities.
            resolved += DictionaryKeys.linked(dictionary, resolved)
            val globalDeclaredTexts = ArrayList<DeclaredTextFact>()
            resolved.values.forEach {
                RelationResolver.addDictionaryRelation(
                    dictionary,
                    it,
                    globalEntities,
                    globalRelations,
                    globalDeclaredTexts
                )
            }
            val keys = DictionaryKeys.facts(dictionary, resolved)
            // The synonyms the files followed, once each, in key order.
            facts.flatMapTo(
                java.util.TreeSet(
                    compareBy<DictionarySynonym>(
                        { it.owner },
                        { it.name })
                )
            ) { it.dictionarySynonyms }
                .forEach { RelationResolver.addDictionarySynonym(it, globalEntities, globalRelations) }
            if (!dictionary.enumerable && dictionary.isAvailable) {
                globalCoverage += CoverageFact(
                    "DICTIONARY_NOT_ENUMERABLE",
                    "The acquired metadata cannot list its relations: only relations named by code are entities, and views are recorded as TABLE"
                )
            }
            if (dictionary.isAvailable && !dictionary.seesEveryTable) {
                globalCoverage += CoverageFact(
                    DictionaryKeys.REFERENCED_BY_INCOMPLETE,
                    "${DictionaryKeys.TABLES_NOT_VISIBLE}: the loading user has none of ${AtlasDictionary.EVERY_TABLE_PRIVILEGES.joinToString()}, " +
                        "and ALL_CONSTRAINTS lists only the constraints of the tables it owns or was granted a privilege on: " +
                        "the foreign keys referencing a key may be more than those recorded"
                )
            }
            return DictionaryFacts(resolved, globalDeclaredTexts, keys)
        }
    }
}
