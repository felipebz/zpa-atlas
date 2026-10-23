//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.api.project.*
import com.felipebz.zpa.atlas.model.*

/** A declaration occurrence of the project snapshot, before it is placed in a unit. */
data class DeclarationOccurrence(
    val fileId: String,
    val span: SourceSpan,
    val written: String,
    val entityKey: String,
    val role: Role,
    /** The enclosing entity for parameters (their subprogram); null means the unit's entity. */
    val containerKey: String?
)

/**
 * Entities, relations and declaration occurrences derived once per run from [ProjectSnapshot.declarations].
 * Subprogram keys carry the written signature; spec and body declarations are distinct entities, the body one
 * suffixed with `@BODY` and linked by `IMPLEMENTS` when the snapshot correlates it with exactly one specification.
 */
class DeclarationCatalog(snapshot: ProjectSnapshot, private val compilationSchema: String?) {
    val entities = ArrayList<EntityFact>()
    val relations = ArrayList<RelationFact>()
    val coverage = ArrayList<CoverageFact>()
    private val fileCoverage = HashMap<String, MutableList<CoverageFact>>()
    private val occurrences = HashMap<String, MutableList<DeclarationOccurrence>>()

    /** Subprogram entity by the start of its declaration (file id, line, column): the AST anchor for containers. */
    private val subprogramsByStart = HashMap<Triple<String, Int, Int>, String>()

    private val keysById = HashMap<ProjectDeclarationId, String>()

    /** Parameter entity keys by owner key (subprogram or cursor) and normalized name: one shared instance per key. */
    private val parameterKeys = HashMap<String, HashMap<String, String>>()

    /** Package-body subprograms that [ProjectSnapshot.correlateSpecification] does not pair with one specification. */
    private val privateBodies = HashSet<ProjectDeclarationId>()

    init {
        val declarations = snapshot.declarations
        val usedKeys = HashMap<String, Int>()
        for (declaration in declarations) {
            val key = register(declaration, usedKeys) ?: continue
            keysById[declaration.id] = key
        }
        for (declaration in declarations) {
            if (declaration !is ProjectPackageSubprogramView || declaration.role != ProjectDeclarationRole.BODY) continue
            val bodyKey = keysById[declaration.id] ?: continue
            val correlation = snapshot.correlateSpecification(declaration.id)
            if (correlation.status == ProjectSpecificationCorrelation.Status.RESOLVED) {
                keysById[correlation.specification.get().id]?.let { relations += RelationFact(bodyKey, RelationKind.IMPLEMENTS, it) }
            } else {
                privateBodies += declaration.id
            }
        }
    }

    /**
     * The entity key of a declaration of this catalog's snapshot. Every subprogram, type, value, exception, cursor,
     * package and sequence has one, so a missing id is a bug (an id of another snapshot), not a coverage gap.
     */
    fun keyOf(id: ProjectDeclarationId): String = keysById[id] ?: error("No entity for declaration $id")

    fun isPrivate(id: ProjectDeclarationId): Boolean = id in privateBodies

    /**
     * The entity key of [parameter] of the subprogram or cursor whose key is [ownerKey]: the instance registered with
     * the entity, so that every binding shares it instead of holding a copy of the long signature.
     */
    fun parameterKey(ownerKey: String, parameter: ProjectParameterView): String {
        val name = normalizeIdentifier(parameter.name.originalSpelling)
        return parameterKeys[ownerKey]?.get(name) ?: error("No parameter entity $name of $ownerKey")
    }

    private val declaredKeys: Set<String> by lazy { entities.mapTo(HashSet()) { it.key } }

    /** Whether [key] is an entity declared in the sources (never an external one). */
    fun isDeclared(key: String): Boolean = key in declaredKeys

    /** The schemas of the declared entities, without the placeholder. */
    val schemas: Set<String> by lazy { entities.mapNotNull { it.schemaName }.filter { it != UNKNOWN_SCHEMA }.toSet() }

    fun occurrencesFor(fileId: String): List<DeclarationOccurrence> = occurrences[fileId].orEmpty()

    /**
     * What this catalog gives the walk of each of [fileIds], positions left out: the keys, roles and containers of its
     * declarations, the specifications its body subprograms implement, and the notes made for it so far.
     */
    fun digests(fileIds: Collection<String>): Map<String, String> {
        val implemented = relations.filter { it.kind == RelationKind.IMPLEMENTS }.groupBy { it.fromKey }
        return fileIds.associateWith { fileId ->
            buildString {
                val own = occurrencesFor(fileId)
                own.forEach { append(it.written).append('|').append(it.entityKey).append('|').append(it.role).append('|').append(it.containerKey).append('\n') }
                own.mapTo(LinkedHashSet()) { it.entityKey }.forEach { key ->
                    implemented[key]?.forEach { append("implements ").append(it.fromKey).append(" -> ").append(it.toKey).append('\n') }
                }
                coverageFor(fileId).forEach { append(it.kind).append(": ").append(it.message).append('\n') }
            }
        }
    }

    fun coverageFor(fileId: String): List<CoverageFact> = synchronized(fileCoverage) { fileCoverage[fileId].orEmpty().toList() }

    fun subprogramAt(fileId: String, line: Int, column: Int): String? = subprogramsByStart[Triple(fileId, line, column)]

    /** The key of a package (or other schema object) named [segments] as written, with the schema rule of [nameSpellings]. */
    fun objectKey(fileId: String, segments: List<String>): String {
        val (schema, rest) = schemaAndName(fileId, segments)
        return dbKey(schema, *rest.toTypedArray())
    }

    fun schemaAndName(fileId: String, segments: List<String>): Pair<String, List<String>> {
        if (segments.size >= 2) return segments.first() to segments.drop(1)
        if (compilationSchema != null) return compilationSchema to segments
        note(fileId, CoverageFact("SCHEMA_UNKNOWN", "No written owner and no --compilation-schema: keys use the placeholder schema '$UNKNOWN_SCHEMA'"))
        return UNKNOWN_SCHEMA to segments
    }

    private fun register(declaration: ProjectDeclarationView, usedKeys: MutableMap<String, Int>): String? {
        val fileId = declaration.fileId
        val role = if (declaration.role == ProjectDeclarationRole.SPECIFICATION) Role.DECLARATION else Role.DEFINITION
        val span = declaration.sourceRange.toSpan()
        val names = declaration.qualifiedName.segments.map { normalizeIdentifier(it.originalSpelling) }
        val local = declaration.qualifiedName.last.originalSpelling
        return when (declaration.kind) {
            ProjectDeclarationKind.PACKAGE, ProjectDeclarationKind.PACKAGE_BODY, ProjectDeclarationKind.SEQUENCE, ProjectDeclarationKind.STANDALONE_TYPE -> {
                val (schema, rest) = schemaAndName(fileId, names)
                val key = dbKey(schema, *rest.toTypedArray())
                val kind = when (declaration.kind) {
                    ProjectDeclarationKind.SEQUENCE -> EntityKind.SEQUENCE
                    ProjectDeclarationKind.STANDALONE_TYPE -> EntityKind.TYPE
                    else -> EntityKind.PACKAGE
                }
                entity(key, kind, schema, rest.last(), null)
                occurrence(fileId, span, local, key, role, null)
                key
            }
            ProjectDeclarationKind.PACKAGE_TYPE, ProjectDeclarationKind.PACKAGE_SUBTYPE,
            ProjectDeclarationKind.PACKAGE_VARIABLE, ProjectDeclarationKind.PACKAGE_CONSTANT,
            ProjectDeclarationKind.PACKAGE_EXCEPTION, ProjectDeclarationKind.PACKAGE_CURSOR -> {
                val (schema, rest) = memberName(fileId, names)
                val packageKey = dbKey(schema, *rest.dropLast(1).toTypedArray())
                val key = dbKey(schema, *rest.toTypedArray())
                val kind = when (declaration.kind) {
                    ProjectDeclarationKind.PACKAGE_VARIABLE -> EntityKind.PACKAGE_VARIABLE
                    ProjectDeclarationKind.PACKAGE_CONSTANT -> EntityKind.PACKAGE_CONSTANT
                    ProjectDeclarationKind.PACKAGE_EXCEPTION -> EntityKind.EXCEPTION
                    ProjectDeclarationKind.PACKAGE_CURSOR -> EntityKind.CURSOR
                    else -> EntityKind.TYPE
                }
                ensurePackage(packageKey, schema, rest.dropLast(1).last())
                entity(key, kind, schema, rest.last(), packageKey)
                occurrence(fileId, span, local, key, role, null)
                if (declaration is ProjectPackageTypeView) {
                    for (field in declaration.recordFields) {
                        val fieldName = normalizeIdentifier(field.name.originalSpelling)
                        val fieldKey = "$key.$fieldName"
                        entity(fieldKey, EntityKind.RECORD_FIELD, schema, fieldName, key)
                        occurrence(fileId, field.sourceRange.toSpan(), field.name.originalSpelling, fieldKey, role, null)
                    }
                }
                if (declaration is ProjectPackageCursorView) {
                    for (parameter in declaration.parameters) {
                        val parameterName = normalizeIdentifier(parameter.name.originalSpelling)
                        val parameterKey = "$key.$parameterName"
                        parameterKeys.getOrPut(key) { HashMap() }.putIfAbsent(parameterName, parameterKey)
                        entity(parameterKey, EntityKind.PARAMETER, schema, parameterName, key)
                        occurrence(fileId, parameter.sourceRange.toSpan(), parameter.name.originalSpelling, parameterKey, role, key)
                    }
                }
                key
            }
            ProjectDeclarationKind.PACKAGE_PROCEDURE, ProjectDeclarationKind.PACKAGE_FUNCTION,
            ProjectDeclarationKind.STANDALONE_PROCEDURE, ProjectDeclarationKind.STANDALONE_FUNCTION -> {
                val subprogram = declaration as? ProjectSubprogramView ?: return null
                val (schema, rest) = if (declaration is ProjectPackageSubprogramView) memberName(fileId, names) else schemaAndName(fileId, names)
                val packageKey = if (declaration is ProjectPackageSubprogramView) dbKey(schema, *rest.dropLast(1).toTypedArray()) else null
                if (packageKey != null) ensurePackage(packageKey, schema, rest.dropLast(1).last())
                val signature = subprogram.parameters.joinToString(",", "(", ")") { parameter ->
                    "${normalizeIdentifier(parameter.name.originalSpelling)} ${mode(parameter.mode)} ${typeText(parameter.typeRef)}"
                }
                val body = if (declaration.role == ProjectDeclarationRole.BODY) "@BODY" else ""
                val base = dbKey(schema, *rest.toTypedArray()) + signature + body
                val count = (usedKeys[base] ?: 0) + 1
                usedKeys[base] = count
                val key = if (count == 1) base else "$base#$count"
                if (count > 1) {
                    coverage += CoverageFact("OVERLOAD_KEY_COLLISION", "Declarations with the same written signature: $base (this one is #$count, ${declaration.sourceRange})")
                }
                val kind = if (declaration.kind == ProjectDeclarationKind.PACKAGE_FUNCTION || declaration.kind == ProjectDeclarationKind.STANDALONE_FUNCTION) EntityKind.FUNCTION else EntityKind.PROCEDURE
                entity(key, kind, schema, rest.last(), packageKey)
                occurrence(fileId, span, local, key, role, null)
                subprogramsByStart[Triple(fileId, span.startLine, span.startCol)] = key
                for (parameter in subprogram.parameters) {
                    val parameterName = normalizeIdentifier(parameter.name.originalSpelling)
                    val parameterKey = "$key.$parameterName"
                    parameterKeys.getOrPut(key) { HashMap() }.putIfAbsent(parameterName, parameterKey)
                    entity(parameterKey, EntityKind.PARAMETER, schema, parameterName, key)
                    occurrence(fileId, parameter.sourceRange.toSpan(), parameter.name.originalSpelling, parameterKey, role, key)
                }
                key
            }
        }
    }

    /** A package member: the schema rule applies to the owning package, the member name is appended. */
    private fun memberName(fileId: String, names: List<String>): Pair<String, List<String>> {
        val (schema, owner) = schemaAndName(fileId, names.dropLast(1))
        return schema to owner + names.last()
    }

    private fun ensurePackage(key: String, schema: String, name: String) {
        // A member without its package declaration (a body-only file) still has a package parent.
        entities += EntityFact(key, EntityKind.PACKAGE, schema, name, null, key.removePrefix("db:"), external = false, precedence = Precedence.SOURCE)
    }

    private fun entity(key: String, kind: EntityKind, schema: String, name: String, parentKey: String?) {
        entities += EntityFact(key, kind, schema, name, parentKey, key.removePrefix("db:"), external = false, precedence = Precedence.DECLARED)
        if (parentKey != null) relations += RelationFact(parentKey, RelationKind.CONTAINS, key)
    }

    private fun occurrence(fileId: String, span: SourceSpan, written: String, key: String, role: Role, containerKey: String?) {
        occurrences.getOrPut(fileId) { ArrayList() } += DeclarationOccurrence(fileId, span, written, key, role, containerKey)
    }

    private fun note(fileId: String, fact: CoverageFact) = synchronized(fileCoverage) {
        val notes = fileCoverage.getOrPut(fileId) { ArrayList() }
        if (fact !in notes) notes += fact
    }

    companion object {
        fun mode(mode: ProjectParameterMode): String = when (mode) {
            ProjectParameterMode.IN -> "IN"
            ProjectParameterMode.OUT -> "OUT"
            ProjectParameterMode.IN_OUT -> "IN OUT"
        }

        fun typeText(reference: ProjectTypeReference): String {
            val name = reference.name.segments.joinToString(".") { normalizeIdentifier(it.originalSpelling) }
            return when (reference.kind) {
                ProjectTypeReference.Kind.NAMED -> name
                ProjectTypeReference.Kind.REF -> "REF $name"
                // A remote anchor keeps its link, spelled as remote relation keys spell it: `T.C@FAR.EXAMPLE.COM%TYPE`.
                ProjectTypeReference.Kind.ANCHORED -> name +
                    reference.link.map { link -> "@" + link.segments.joinToString(".") { normalizeIdentifier(it.originalSpelling) } }.orElse("") +
                    if (reference.anchor.get() == ProjectTypeReference.Anchor.ROWTYPE) "%ROWTYPE" else "%TYPE"
            }
        }

        fun ProjectSourceRange.toSpan() = SourceSpan(startLine, startColumn, endLine, endColumn)
    }
}
