//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.api.project.*
import com.felipebz.zpa.atlas.model.EntityFact
import com.felipebz.zpa.atlas.model.EntityKind
import com.felipebz.zpa.atlas.model.RelationFact
import com.felipebz.zpa.atlas.model.Status

/** How one datatype name resolves: the target of its `TYPE_USE` occurrence. */
data class TypeOutcome(val status: Status, val entityKey: String?, val reason: String?, val candidateKeys: List<String> = emptyList())

/**
 * Resolves the names of datatypes: `%TYPE`/`%ROWTYPE` anchors to the first object they name, never to the type
 * they end at, and named types to their declaration. Lexical capture (locals) is the caller's legacy-symbol check.
 * Package values and types use the spellings and the enclosing-package rule of [CallResolver].
 *
 * @param runSchemas the schemas known to the run, used to read `A.B` as `schema.type` when the project does not know it
 */
class TypeReferenceResolver(
    private val snapshot: ProjectSnapshot,
    private val catalog: DeclarationCatalog,
    private val values: PackageValues,
    private val relationResolver: RelationResolver,
    private val synonyms: SynonymResolver,
    private val entities: MutableList<EntityFact>,
    private val relations: MutableList<RelationFact>,
    private val runSchemas: Set<String>
) {
    /**
     * An anchor `written%TYPE` or `written%ROWTYPE`: a package value or cursor (a longer name is a value plus a record
     * field path), else a member of a project package that is not one of these, else a relation or a column.
     *
     * @param enclosingMember the first segment is bound to a declaration of the enclosing package specification that
     *   is not a package value (a cursor, a type): it is never read as a relation
     */
    fun anchored(written: List<String>, rowtype: Boolean, unitSchema: String?, enclosingPackage: List<String>?, enclosingMember: Boolean): TypeOutcome {
        val packageName = enclosingPackage?.let { qualifiedName(it) }
        values.packageMember(written, unitSchema, packageName)?.takeIf { it.kind != MemberKind.EXCEPTION }?.let { return it.toTypeOutcome() }
        if (enclosingMember || values.isPackageMember(written, unitSchema)) return UNKNOWN_MEMBER
        val names = written.map { normalizeIdentifier(it) }
        if (rowtype) {
            return when (names.size) {
                1 -> relation(null, names[0], unitSchema)
                2 -> relation(names[0], names[1], unitSchema)
                else -> NOT_IN_PROJECT
            }
        }
        val (relation, column) = when (names.size) {
            2 -> relationResolver.resolve(WrittenRelation(null, names[0], null), unitSchema) to names[1]
            3 -> relationResolver.resolve(WrittenRelation(names[0], names[1], null), unitSchema) to names[2]
            else -> return NOT_IN_PROJECT
        }
        val resolved = relationResolver.resolveColumn(relation, column)
        return TypeOutcome(resolved.status, resolved.entityKey, resolved.reason)
    }

    /**
     * A named type: a project type (package type, subtype or standalone type), else nothing for a built-in (null),
     * else a type a synonym names (`xmltype` is `SYS.XMLTYPE`, resolved as if written), else an unknown type with an
     * external entity under the key rules of unknown calls.
     *
     * @param synonymTarget the object [written] was rewritten to, a synonym target or an own object the project lacks (internal)
     */
    fun named(written: List<String>, unitSchema: String?, enclosingPackage: List<String>?, synonymTarget: NameTarget? = null): TypeOutcome? {
        val packageName = enclosingPackage?.let { qualifiedName(it) }
        for (names in nameSpellings(written, unitSchema)) {
            // A one-segment name is a member of the enclosing package first, then a standalone type.
            val owners = if (names.size == 1 && packageName != null) listOf(packageName, null) else listOf(null)
            for (owner in owners) {
                val resolution = snapshot.resolveNamedType(qualifiedName(names), owner)
                when (resolution.status) {
                    ProjectNamedTypeResolution.Status.RESOLVED ->
                        return TypeOutcome(Status.RESOLVED, catalog.keyOf(resolution.declaration.get().id), null)
                    ProjectNamedTypeResolution.Status.AMBIGUOUS ->
                        return TypeOutcome(Status.AMBIGUOUS, null, resolution.status.name, resolution.candidates.map { catalog.keyOf(it.id) }.distinct())
                    // Every lookup is incomplete once a source failed: only a known candidate makes it this name's answer.
                    ProjectNamedTypeResolution.Status.INCOMPLETE_INDEX -> if (resolution.candidates.isNotEmpty()) return INCOMPLETE_INDEX
                    else -> Unit
                }
            }
        }
        if (isBuiltIn(written, packageName)) return null
        if (values.isPackageMember(written, unitSchema)) return UNKNOWN_MEMBER
        val names = written.map { normalizeIdentifier(it) }
        if (synonymTarget == null) {
            val targetTypes = if (names.size == 1) SynonymResolver.TYPES else SynonymResolver.PACKAGES_AND_TYPES
            synonyms.follow(names[0], unitSchema, targetTypes)?.let { target ->
                return named(SynonymResolver.written(target, names.drop(1)), unitSchema, null, target)
            }
        }
        return unknownType(names, unitSchema, synonymTarget)
    }

    /** A built-in of zpa-core's classifier, or a type of `SYS.STANDARD` it does not list (written in one segment). */
    private fun isBuiltIn(written: List<String>, packageName: ProjectQualifiedName?): Boolean =
        (written.size == 1 && normalizeIdentifier(written[0]) in STANDARD_TYPES) ||
            snapshot.describeType(qualifiedName(written), packageName).status == ProjectTypeDescription.Status.BUILT_IN

    private fun relation(schema: String?, name: String, unitSchema: String?): TypeOutcome {
        val resolved = relationResolver.resolve(WrittenRelation(schema, name, null), unitSchema)
        return TypeOutcome(resolved.status, resolved.entityKey, resolved.reason)
    }

    /**
     * An external `TYPE` entity, with the key and parent rules of unknown calls ([ExternalNames]): a type of a package
     * belongs to that package. One segment (typically a public synonym such as `XMLTYPE` without dictionary) is
     * `db:~.X`, without parent; through a synonym, the target `db:O.T` (or `db:O.P.T` under the package `db:O.P`).
     */
    private fun unknownType(names: List<String>, unitSchema: String?, synonymTarget: NameTarget?): TypeOutcome {
        if (synonymTarget != null && names.size == 2) {
            val key = dbKey(names[0], names[1])
            if (catalog.isDeclared(key)) return UNKNOWN_MEMBER
            entities += EntityFact(key, EntityKind.TYPE, names[0], names[1], null, key.removePrefix("db:"), external = true, precedence = Precedence.MENTIONED)
            return NOT_IN_PROJECT.copy(entityKey = key)
        }
        if (names.size == 1) {
            val key = dbKey(UNKNOWN_SCHEMA, names[0])
            if (catalog.isDeclared(key)) return UNKNOWN_MEMBER
            entities += EntityFact(key, EntityKind.TYPE, null, names[0], null, key.removePrefix("db:"), external = true, precedence = Precedence.MENTIONED)
            return NOT_IN_PROJECT.copy(entityKey = key)
        }
        val schemas = if (synonymTarget != null) runSchemas + synonymTarget.owner else runSchemas
        return when (val result = ExternalNames.external(names, EntityKind.TYPE, unitSchema, schemas, catalog, entities, relations)) {
            ExternalNames.Result.UnknownMember -> UNKNOWN_MEMBER
            is ExternalNames.Result.External -> NOT_IN_PROJECT.copy(entityKey = result.key)
        }
    }

    private companion object {
        val NOT_IN_PROJECT = TypeOutcome(Status.UNRESOLVED, null, "NOT_IN_PROJECT")
        val UNKNOWN_MEMBER = TypeOutcome(Status.UNRESOLVED, null, "UNKNOWN_MEMBER")
        val INCOMPLETE_INDEX = TypeOutcome(Status.UNRESOLVED, null, "INCOMPLETE_INDEX")

        /**
         * Types declared in `SYS.STANDARD` that zpa-core's built-in classifier does not recognize. Units depend on
         * STANDARD as a whole (a built-in dependency), never on these names.
         */
        val STANDARD_TYPES = setOf(
            "SYS_REFCURSOR", "SIMPLE_INTEGER", "SIMPLE_FLOAT", "SIMPLE_DOUBLE", "MLSLABEL", "TIME_UNCONSTRAINED",
            "TIME_TZ_UNCONSTRAINED", "TIMESTAMP_UNCONSTRAINED", "TIMESTAMP_TZ_UNCONSTRAINED", "TIMESTAMP_LTZ_UNCONSTRAINED",
            "YMINTERVAL_UNCONSTRAINED", "DSINTERVAL_UNCONSTRAINED"
        )
    }
}
