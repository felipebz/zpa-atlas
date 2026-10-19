//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.dictionary

import com.felipebz.zpa.api.project.ProjectExternalMetadata
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.extract.exact
import com.felipebz.zpa.atlas.model.EntityKind

/** The dictionary kind of a relation. */
enum class DictionaryRelationKind(val entityKind: EntityKind) {
    TABLE(EntityKind.TABLE),
    VIEW(EntityKind.VIEW),
    MATERIALIZED_VIEW(EntityKind.MATERIALIZED_VIEW),
    OBJECT_TABLE(EntityKind.OBJECT_TABLE)
}

data class DictionaryRelation(val relation: ProjectExternalRelation, val kind: DictionaryRelationKind)

/**
 * A synonym without database link, exact dictionary names: `owner.name` (owner `PUBLIC` for a public one) for `target`,
 * whose `ALL_OBJECTS.OBJECT_TYPE` is [targetType] (`SYNONYM` for a hop of a chain).
 */
data class DictionarySynonym(val owner: String, val name: String, val targetOwner: String, val targetName: String, val targetType: String)

/**
 * The Oracle dictionary as the indexer sees it: the [metadata] used for every lookup, the relations it can enumerate,
 * and the [synonyms] of relations, packages, types, subprograms and sequences (zpa leaves synonyms to its consumers).
 *
 * [ProjectExternalMetadata] has no public way to list its relations. When [relations] is not [enumerable] (an
 * [OracleMetadataAcquisition] result), a relation becomes an entity only when code names it, and its kind comes from
 * [kindOf].
 */
class AtlasDictionary(
    val metadata: ProjectExternalMetadata,
    val relations: List<DictionaryRelation>,
    val enumerable: Boolean,
    /** Exact names of the schemas the dictionary was requested for. */
    val schemas: Set<String> = emptySet(),
    val synonyms: List<DictionarySynonym> = emptyList(),
    /**
     * The names of the objects of the schemas whose namespace was read (tables, views, synonyms, sequences, packages,
     * types, subprograms), by schema, with their `OBJECT_TYPE`. A schema absent here has an unknown namespace.
     */
    val objects: Map<String, Map<String, String>> = emptyMap()
) {
    private val kinds = relations.associate { (it.relation.schema to it.relation.name) to it.kind }
    private val synonymsByName = synonyms.associateBy { it.owner to it.name }
    /** The `OBJECT_TYPE` of the synonym targets, which may lie in schemas whose namespace was not read. */
    private val synonymTargetTypes = synonyms.associate { (it.targetOwner to it.targetName) to it.targetType }

    /** The synonym `owner.name` itself (owner `PUBLIC` for a public one), or null. */
    fun synonym(owner: String, name: String): DictionarySynonym? = synonymsByName[owner to name]

    /** Whether the object names of [schema] were read: only then is a name it lacks known to be absent. */
    fun knowsObjects(schema: String): Boolean = schema in objects

    /** The `OBJECT_TYPE` of the object [name] of [schema], or null (absent, or not read). */
    fun objectType(schema: String, name: String): String? = objects[schema]?.get(name)

    /**
     * The kind of [relation]: the enumerated kind, else a `VIEW` or `MATERIALIZED_VIEW` when the dictionary's object
     * types say so (the `OBJECT_TYPE` of the read schemas, or the target type of a synonym that reaches it), else `VIEW`
     * for a relation read from `ALL_VIEWS` ([ProjectExternalRelation.isView], with or without a row type), else
     * `OBJECT_TABLE` when it has a row object type, else `TABLE`. A materialized view is not in `ALL_VIEWS`, and it also
     * has a `TABLE` row of the same name; the loader keeps the `MATERIALIZED VIEW` one.
     */
    fun kindOf(relation: ProjectExternalRelation): DictionaryRelationKind {
        kinds[relation.schema to relation.name]?.let { return it }
        val (schema, name) = relation.schema.exact() to relation.name.exact()
        val type = objectType(schema, name) ?: synonymTargetTypes[schema to name]
        return when {
            type == "VIEW" -> DictionaryRelationKind.VIEW
            type == "MATERIALIZED VIEW" -> DictionaryRelationKind.MATERIALIZED_VIEW
            relation.isView -> DictionaryRelationKind.VIEW
            relation.rowObjectType.isPresent -> DictionaryRelationKind.OBJECT_TABLE
            else -> DictionaryRelationKind.TABLE
        }
    }

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
            synonyms: List<DictionarySynonym> = emptyList(),
            objects: Map<String, Map<String, String>> = emptyMap()
        ): AtlasDictionary {
            val builder = ProjectExternalMetadata.builder(origin)
            completeSchemas.forEach { builder.completeSchema(it) }
            relations.forEach { builder.relation(it.relation) }
            val schemas = completeSchemas.toSet() + relations.map { it.relation.schema.originalSpelling.removeSurrounding("\"") }
            return AtlasDictionary(builder.build(), relations, enumerable = true, schemas = schemas, synonyms = synonyms, objects = objects)
        }

        /** An acquired dictionary for the requested [schemas]: lookups only, no enumeration. */
        fun lookupOnly(
            metadata: ProjectExternalMetadata,
            schemas: Set<String>,
            synonyms: List<DictionarySynonym> = emptyList(),
            objects: Map<String, Map<String, String>> = emptyMap()
        ): AtlasDictionary = AtlasDictionary(metadata, emptyList(), enumerable = false, schemas = schemas, synonyms = synonyms, objects = objects)
    }
}
