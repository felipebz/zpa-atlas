//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.dictionary

import com.felipebz.zpa.api.project.ProjectExternalMetadata
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.model.EntityKind

/** The dictionary kind of a relation. */
enum class DictionaryRelationKind(val entityKind: EntityKind) {
    TABLE(EntityKind.TABLE),
    VIEW(EntityKind.VIEW),
    OBJECT_TABLE(EntityKind.OBJECT_TABLE)
}

data class DictionaryRelation(val relation: ProjectExternalRelation, val kind: DictionaryRelationKind)

/** A synonym without database link, exact dictionary names: `owner.name` (owner `PUBLIC` for a public one) for `target`. */
data class DictionarySynonym(val owner: String, val name: String, val targetOwner: String, val targetName: String)

/**
 * The Oracle dictionary as the indexer sees it: the [metadata] used for every lookup, the relations it can enumerate,
 * and the [synonyms] of relations (zpa leaves synonyms to its consumers).
 *
 * [ProjectExternalMetadata] has no public way to list its relations, and it does not record whether a relation is a
 * table or a view (see the report). When [relations] is not [enumerable] (an [OracleMetadataAcquisition] result), a
 * relation becomes an entity only when code names it, and its kind is inferred from the only public signal
 * ([ProjectExternalRelation.rowObjectType]): `OBJECT_TABLE`, else `TABLE`.
 */
class AtlasDictionary(
    val metadata: ProjectExternalMetadata,
    val relations: List<DictionaryRelation>,
    val enumerable: Boolean,
    /** Exact names of the schemas the dictionary was requested for. */
    val schemas: Set<String> = emptySet(),
    val synonyms: List<DictionarySynonym> = emptyList()
) {
    private val kinds = relations.associate { (it.relation.schema to it.relation.name) to it.kind }
    private val synonymsByName = synonyms.associateBy { it.owner to it.name }

    fun kindOf(relation: ProjectExternalRelation): DictionaryRelationKind =
        kinds[relation.schema to relation.name]
            ?: if (relation.rowObjectType.isPresent) DictionaryRelationKind.OBJECT_TABLE else DictionaryRelationKind.TABLE

    val isAvailable: Boolean get() = metadata.origin.isPresent

    /**
     * The synonyms followed from `owner.name` to the final target, in order; empty when it is not a synonym, null for
     * a chain longer than [MAX_HOPS] or with a cycle (Oracle rejects it). The target is the last hop's target.
     */
    fun synonymChain(owner: String, name: String): List<DictionarySynonym>? {
        val chain = ArrayList<DictionarySynonym>()
        var current = synonymsByName[owner to name] ?: return chain
        while (true) {
            if (chain.size == MAX_HOPS || chain.any { it.owner == current.owner && it.name == current.name }) return null
            chain += current
            current = synonymsByName[current.targetOwner to current.targetName] ?: return chain
        }
    }

    companion object {
        const val MAX_HOPS = 10
        const val PUBLIC = "PUBLIC"

        val NONE = AtlasDictionary(ProjectExternalMetadata.none(), emptyList(), enumerable = true)

        /** A dictionary whose relations are all known (for example built by hand); [metadata] is built from them. */
        fun of(
            origin: String,
            completeSchemas: Collection<String>,
            relations: List<DictionaryRelation>,
            synonyms: List<DictionarySynonym> = emptyList()
        ): AtlasDictionary {
            val builder = ProjectExternalMetadata.builder(origin)
            completeSchemas.forEach { builder.completeSchema(it) }
            relations.forEach { builder.relation(it.relation) }
            val schemas = completeSchemas.toSet() + relations.map { it.relation.schema.originalSpelling.removeSurrounding("\"") }
            return AtlasDictionary(builder.build(), relations, enumerable = true, schemas = schemas, synonyms = synonyms)
        }

        /** An acquired dictionary for the requested [schemas]: lookups only, no enumeration. */
        fun lookupOnly(metadata: ProjectExternalMetadata, schemas: Set<String>, synonyms: List<DictionarySynonym> = emptyList()): AtlasDictionary =
            AtlasDictionary(metadata, emptyList(), enumerable = false, schemas = schemas, synonyms = synonyms)
    }
}
