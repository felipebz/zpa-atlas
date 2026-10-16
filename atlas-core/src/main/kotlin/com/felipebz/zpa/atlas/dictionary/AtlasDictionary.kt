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

/**
 * The Oracle dictionary as the indexer sees it: the [metadata] used for every lookup, plus the relations it can
 * enumerate.
 *
 * [ProjectExternalMetadata] has no public way to list its relations, and it does not record whether a relation is a
 * table or a view (see the report). When [relations] is not [enumerable] (an [OracleMetadataAcquisition] result), a
 * relation becomes an entity only when code names it, and its kind is inferred from the only public signal
 * ([ProjectExternalRelation.rowObjectType]): `OBJECT_TABLE`, else `TABLE`.
 */
class AtlasDictionary(
    val metadata: ProjectExternalMetadata,
    val relations: List<DictionaryRelation>,
    val enumerable: Boolean
) {
    private val kinds = relations.associate { (it.relation.schema to it.relation.name) to it.kind }

    fun kindOf(relation: ProjectExternalRelation): DictionaryRelationKind =
        kinds[relation.schema to relation.name]
            ?: if (relation.rowObjectType.isPresent) DictionaryRelationKind.OBJECT_TABLE else DictionaryRelationKind.TABLE

    val isAvailable: Boolean get() = metadata.origin.isPresent

    companion object {
        val NONE = AtlasDictionary(ProjectExternalMetadata.none(), emptyList(), enumerable = true)

        /** A dictionary whose relations are all known (for example built by hand); [metadata] is built from them. */
        fun of(origin: String, completeSchemas: Collection<String>, relations: List<DictionaryRelation>): AtlasDictionary {
            val builder = ProjectExternalMetadata.builder(origin)
            completeSchemas.forEach { builder.completeSchema(it) }
            relations.forEach { builder.relation(it.relation) }
            return AtlasDictionary(builder.build(), relations, enumerable = true)
        }

        /** An acquired dictionary: lookups only, no enumeration. */
        fun lookupOnly(metadata: ProjectExternalMetadata): AtlasDictionary = AtlasDictionary(metadata, emptyList(), enumerable = false)
    }
}
