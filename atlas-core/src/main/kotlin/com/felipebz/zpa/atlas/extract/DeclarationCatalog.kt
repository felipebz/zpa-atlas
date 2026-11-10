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
class DeclarationCatalog(snapshot: ProjectSnapshot, private val fileSchemas: FileSchemas) {
    val entities = ArrayList<EntityFact>()
    val relations = ArrayList<RelationFact>()
    val coverage = ArrayList<CoverageFact>()
    private val fileCoverage = HashMap<String, MutableList<CoverageFact>>()
    private val occurrences = HashMap<String, MutableList<DeclarationOccurrence>>()

    /** Subprogram entity by the start of its declaration (file id, line, column): the AST anchor for containers. */
    private val subprogramsByStart = HashMap<Triple<String, Int, Int>, String>()

    private val keysById = HashMap<ProjectDeclarationId, String>()

    /** Record field and attribute entity by the start of its declaration (file id, line, column), where zpa places it. */
    private val fieldsByStart = HashMap<Triple<String, Int, Int>, String>()

    /** The object types of each file whose listed attributes may not be all. */
    private val membersGaps = HashMap<String, MutableList<MembersGapFact>>()

    /** Parameter entity keys by owner key (subprogram or cursor) and normalized name: one shared instance per key. */
    private val parameterKeys = HashMap<String, HashMap<String, String>>()

    /** Package-body subprograms that [ProjectSnapshot.correlateSpecification] does not pair with one specification. */
    private val privateBodies = HashSet<ProjectDeclarationId>()

    /** Package or standalone subprogram by the start of its declaration (file id, line, column). */
    private val subprogramViewsByStart = HashMap<Triple<String, Int, Int>, ProjectSubprogramView>()

    /** The specification each package-body subprogram is paired with. */
    private val specifications = HashMap<ProjectDeclarationId, ProjectSubprogramView>()

    init {
        val declarations = snapshot.declarations
        val usedKeys = HashMap<String, Int>()
        for (declaration in declarations) {
            val key = register(declaration, usedKeys, snapshot) ?: continue
            keysById[declaration.id] = key
        }
        for (declaration in declarations) {
            if (declaration !is ProjectPackageSubprogramView || declaration.role != ProjectDeclarationRole.BODY) continue
            val bodyKey = keysById[declaration.id] ?: continue
            val correlation = snapshot.correlateSpecification(declaration.id)
            if (correlation.status == ProjectSpecificationCorrelation.Status.RESOLVED) {
                val specification = correlation.specification.get()
                keysById[specification.id]?.let { relations += RelationFact(bodyKey, RelationKind.IMPLEMENTS, it) }
                (specification as? ProjectSubprogramView)?.let { specifications[declaration.id] = it }
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

    /** The record field or attribute entity declared at [line] and [column] (the start of its declaration) of [fileId], or null. */
    fun fieldAt(fileId: String, line: Int, column: Int): String? = fieldsByStart[Triple(fileId, line, column)]

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
                membersGapsFor(fileId).forEach { append("members ").append(it.entityKey).append(' ').append(it.reasons).append('\n') }
            }
        }
    }

    fun coverageFor(fileId: String): List<CoverageFact> = synchronized(fileCoverage) { fileCoverage[fileId].orEmpty().toList() }

    /** The object types [fileId] declares whose listed attributes may not be all their attributes. */
    fun membersGapsFor(fileId: String): List<MembersGapFact> = membersGaps[fileId].orEmpty()

    fun subprogramAt(fileId: String, line: Int, column: Int): String? = subprogramsByStart[Triple(fileId, line, column)]

    /**
     * The subprogram a call bound to the declaration starting at [line] and [column] of [fileId] reaches, as a resolved
     * call of it does: the specification a body is paired with, the body itself otherwise; null when no package or
     * standalone subprogram starts there.
     */
    fun calledSubprogramAt(fileId: String, line: Int, column: Int): ProjectSubprogramView? =
        subprogramViewsByStart[Triple(fileId, line, column)]?.let { specifications[it.id] ?: it }

    /** The key of a package (or other schema object) named [segments] as written: its owner, else its file's schema. */
    fun objectKey(fileId: String, segments: List<String>): String {
        val (schema, rest) = schemaAndName(fileId, segments)
        return dbKey(schema, *rest.toTypedArray())
    }

    fun schemaAndName(fileId: String, segments: List<String>): Pair<String, List<String>> {
        if (segments.size >= 2) return segments.first() to segments.drop(1)
        fileSchemas.of(fileId)?.let { return it to segments }
        note(fileId, CoverageFact("SCHEMA_UNKNOWN", "No written owner, no --schema-paths pattern matching the file and no --compilation-schema: keys use the placeholder schema '$UNKNOWN_SCHEMA'"))
        return UNKNOWN_SCHEMA to segments
    }

    private fun register(declaration: ProjectDeclarationView, usedKeys: MutableMap<String, Int>, snapshot: ProjectSnapshot): String? {
        val role = if (declaration.role == ProjectDeclarationRole.SPECIFICATION) Role.DECLARATION else Role.DEFINITION
        return when (declaration.kind) {
            ProjectDeclarationKind.PACKAGE, ProjectDeclarationKind.PACKAGE_BODY, ProjectDeclarationKind.SEQUENCE, ProjectDeclarationKind.STANDALONE_TYPE ->
                registerObject(declaration, role, snapshot)
            ProjectDeclarationKind.PACKAGE_TYPE, ProjectDeclarationKind.PACKAGE_SUBTYPE,
            ProjectDeclarationKind.PACKAGE_VARIABLE, ProjectDeclarationKind.PACKAGE_CONSTANT,
            ProjectDeclarationKind.PACKAGE_EXCEPTION, ProjectDeclarationKind.PACKAGE_CURSOR -> registerMember(declaration, role)
            ProjectDeclarationKind.PACKAGE_PROCEDURE, ProjectDeclarationKind.PACKAGE_FUNCTION,
            ProjectDeclarationKind.STANDALONE_PROCEDURE, ProjectDeclarationKind.STANDALONE_FUNCTION -> registerSubprogram(declaration, role, usedKeys)
        }
    }

    /** Registers a package, package body, sequence or standalone type, with a type's attributes; returns its key. */
    private fun registerObject(declaration: ProjectDeclarationView, role: Role, snapshot: ProjectSnapshot): String {
        val fileId = declaration.fileId
        val (schema, rest) = schemaAndName(fileId, namesOf(declaration))
        val key = dbKey(schema, *rest.toTypedArray())
        val kind = when (declaration.kind) {
            ProjectDeclarationKind.SEQUENCE -> EntityKind.SEQUENCE
            ProjectDeclarationKind.STANDALONE_TYPE -> EntityKind.TYPE
            else -> EntityKind.PACKAGE
        }
        entity(key, kind, schema, rest.last(), null)
        occurrence(fileId, declaration.sourceRange.toSpan(), declaration.qualifiedName.last.originalSpelling, key, role, null)
        if (declaration is ProjectStandaloneTypeView) attributes(declaration, key, schema, snapshot)
        return key
    }

    /**
     * Registers a package member (type, subtype, variable, constant, exception or cursor), with a record type's fields
     * and a cursor's parameters, and its package if not declared; returns its key.
     */
    private fun registerMember(declaration: ProjectDeclarationView, role: Role): String {
        val fileId = declaration.fileId
        val (schema, rest) = memberName(fileId, namesOf(declaration))
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
        occurrence(fileId, declaration.sourceRange.toSpan(), declaration.qualifiedName.last.originalSpelling, key, role, null)
        if (declaration is ProjectPackageTypeView) recordFields(declaration, key, schema, role)
        if (declaration is ProjectPackageCursorView) parameters(fileId, declaration.parameters, key, schema, role)
        return key
    }

    /** The fields of the package record type [type] (whose entity is [key]): each a `RECORD_FIELD` entity `key.NAME`. */
    private fun recordFields(type: ProjectPackageTypeView, key: String, schema: String, role: Role) {
        val fileId = type.fileId
        for (field in type.recordFields) {
            val fieldName = normalizeIdentifier(field.name.originalSpelling)
            val fieldKey = "$key.$fieldName"
            entity(fieldKey, EntityKind.RECORD_FIELD, schema, fieldName, key)
            val fieldSpan = field.sourceRange.toSpan()
            occurrence(fileId, fieldSpan, field.name.originalSpelling, fieldKey, role, null)
            fieldsByStart.putIfAbsent(Triple(fileId, fieldSpan.startLine, fieldSpan.startCol), fieldKey)
        }
    }

    /**
     * Registers a package or standalone subprogram, with its parameters and its package if not declared; returns its
     * key, or null when the declaration is not a subprogram view.
     */
    private fun registerSubprogram(declaration: ProjectDeclarationView, role: Role, usedKeys: MutableMap<String, Int>): String? {
        val subprogram = declaration as? ProjectSubprogramView ?: return null
        val fileId = declaration.fileId
        val names = namesOf(declaration)
        val member = declaration is ProjectPackageSubprogramView
        val (schema, rest) = if (member) memberName(fileId, names) else schemaAndName(fileId, names)
        val packageKey = if (member) dbKey(schema, *rest.dropLast(1).toTypedArray()) else null
        if (packageKey != null) ensurePackage(packageKey, schema, rest.dropLast(1).last())
        val key = subprogramKey(subprogram, schema, rest, usedKeys)
        val kind = if (declaration.kind == ProjectDeclarationKind.PACKAGE_FUNCTION || declaration.kind == ProjectDeclarationKind.STANDALONE_FUNCTION) EntityKind.FUNCTION else EntityKind.PROCEDURE
        val span = declaration.sourceRange.toSpan()
        entity(key, kind, schema, rest.last(), packageKey)
        occurrence(fileId, span, declaration.qualifiedName.last.originalSpelling, key, role, null)
        subprogramsByStart[Triple(fileId, span.startLine, span.startCol)] = key
        subprogramViewsByStart[Triple(fileId, span.startLine, span.startCol)] = subprogram
        parameters(fileId, subprogram.parameters, key, schema, role)
        return key
    }

    /**
     * The key of [subprogram], named [rest] in [schema]: its written signature, `@BODY` for a body, and `#2` on for a
     * key already in [usedKeys], which is noted as a collision.
     */
    private fun subprogramKey(subprogram: ProjectSubprogramView, schema: String, rest: List<String>, usedKeys: MutableMap<String, Int>): String {
        val signature = subprogram.parameters.joinToString(",", "(", ")") { parameter ->
            "${normalizeIdentifier(parameter.name.originalSpelling)} ${mode(parameter.mode)} ${typeText(parameter.typeRef)}"
        }
        val body = if (subprogram.role == ProjectDeclarationRole.BODY) "@BODY" else ""
        val base = dbKey(schema, *rest.toTypedArray()) + signature + body
        val count = (usedKeys[base] ?: 0) + 1
        usedKeys[base] = count
        if (count == 1) return base
        coverage += CoverageFact("OVERLOAD_KEY_COLLISION", "Declarations with the same written signature: $base (this one is #$count, ${subprogram.sourceRange})")
        return "$base#$count"
    }

    /** The [parameters] of the subprogram or cursor [ownerKey]: each a `PARAMETER` entity `ownerKey.NAME`, its occurrence contained by the owner. */
    private fun parameters(fileId: String, parameters: List<ProjectParameterView>, ownerKey: String, schema: String, role: Role) {
        for (parameter in parameters) {
            val parameterName = normalizeIdentifier(parameter.name.originalSpelling)
            val parameterKey = "$ownerKey.$parameterName"
            parameterKeys.getOrPut(ownerKey) { HashMap() }.putIfAbsent(parameterName, parameterKey)
            entity(parameterKey, EntityKind.PARAMETER, schema, parameterName, ownerKey)
            occurrence(fileId, parameter.sourceRange.toSpan(), parameter.name.originalSpelling, parameterKey, role, ownerKey)
        }
    }

    /** The segments of the name of [declaration], normalized. */
    private fun namesOf(declaration: ProjectDeclarationView): List<String> = declaration.qualifiedName.segments.map { normalizeIdentifier(it.originalSpelling) }

    /**
     * The attributes [type] (whose entity is [key]) writes in its `CREATE TYPE`, as zpa extracts them: each an
     * `ATTRIBUTE` entity `key.NAME`, a child of the type, defined where zpa places it. An attribute a subtype inherits
     * is its supertype's. When zpa could not read them all, or an `ALTER TYPE` changes them, the type says so.
     */
    private fun attributes(type: ProjectStandaloneTypeView, key: String, schema: String, snapshot: ProjectSnapshot) {
        val fileId = type.fileId
        for (attribute in type.attributes) {
            val name = normalizeIdentifier(attribute.name.originalSpelling)
            val attributeKey = "$key.$name"
            entity(attributeKey, EntityKind.ATTRIBUTE, schema, name, key)
            val span = attribute.sourceRange.toSpan()
            occurrence(fileId, span, attribute.name.originalSpelling, attributeKey, Role.DEFINITION, null)
            fieldsByStart.putIfAbsent(Triple(fileId, span.startLine, span.startCol), attributeKey)
        }
        val reasons = listOfNotNull(
            "NOT_EXTRACTED".takeIf { !type.attributesComplete },
            "ALTER_TYPE".takeIf { snapshot.isAttributeListAltered(type.qualifiedName) }
        )
        if (reasons.isNotEmpty()) membersGaps.getOrPut(fileId) { ArrayList() } += MembersGapFact(key, reasons)
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
