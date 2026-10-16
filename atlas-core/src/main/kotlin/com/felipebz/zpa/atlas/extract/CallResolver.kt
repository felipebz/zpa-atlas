package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.api.project.*
import com.felipebz.zpa.atlas.model.EntityFact
import com.felipebz.zpa.atlas.model.EntityKind
import com.felipebz.zpa.atlas.model.RelationFact
import com.felipebz.zpa.atlas.model.RelationKind
import com.felipebz.zpa.atlas.model.Status

/**
 * The spellings a name is looked up with, in order: with the unit schema [schema] as first segment, as written then
 * without it; else, for one or two segments with a known schema, as written then prefixed with it; else as written.
 *
 * The project index keys each declaration by the name written in its CREATE, owner included, and never applies a
 * compilation schema, so every lookup by name tries both spellings; the first that the project knows wins.
 */
internal fun nameSpellings(written: List<String>, schema: String?): List<List<String>> = when {
    schema == null -> listOf(written)
    written.size >= 2 && normalizeIdentifier(written[0]) == schema -> listOf(written, written.drop(1))
    written.size <= 2 -> listOf(written, listOf("\"" + schema + "\"") + written)
    else -> listOf(written)
}

internal fun qualifiedName(names: List<String>): ProjectQualifiedName = ProjectQualifiedName.of(*names.toTypedArray())

/**
 * The package value lookups of a name, in order, as (segments, package owner): a one-segment name under each spelling,
 * with the enclosing package as owner; else the first segment as a value of the enclosing package (`g_tab.exists(1)`
 * in the body), then the full name and its prefixes of two or more segments under each spelling.
 */
internal fun valueLookups(written: List<String>, unitSchema: String?, packageName: ProjectQualifiedName?): Sequence<Pair<List<String>, ProjectQualifiedName?>> =
    sequence {
        if (written.size == 1) {
            nameSpellings(written, unitSchema).forEach { yield(it to if (it.size == 1) packageName else null) }
            return@sequence
        }
        if (packageName != null) yield(written.take(1) to packageName)
        for (length in written.size downTo 2) nameSpellings(written.take(length), unitSchema).forEach { yield(it to null) }
    }

/** A written actual: its label as written (named notation) or null (positional). */
data class WrittenArgument(val label: String?)

/**
 * How one call site resolves: [Value] for a package value, exception or cursor
 * (`pkg.g_tab(i)`, `pkg.g_tab.exists(i)`, `pkg.c_pending(1)`), [NotACall] for a
 * type the project does not identify.
 */
sealed class CallOutcome {
    data object NotACall : CallOutcome()

    /** The name is a package value, exception or cursor ([kind]): the entity is it, the first hop of the name. */
    data class Value(val kind: MemberKind, val status: Status, val entityKey: String?, val reason: String?, val candidateKeys: List<String> = emptyList()) : CallOutcome()

    data class Call(
        val status: Status,
        val entityKey: String?,
        val reason: String?,
        val candidateKeys: List<String> = emptyList(),
        /** Parameter entity key by 1-based actual ordinal, only for resolved calls. */
        val bindings: Map<Int, String> = emptyMap(),
        /** Mode of the bound parameter by 1-based actual ordinal, only for resolved calls. */
        val modes: Map<Int, ProjectParameterMode> = emptyMap()
    ) : CallOutcome()
}

/**
 * Resolves call sites through [ProjectSnapshot.resolveCall]: spelling variants for the unit schema,
 * package scope before schema scope inside a package, and the body-only subprograms the API never sees. Lexical scope
 * is the caller's legacy-symbol check; nothing here binds arguments against package-body declarations.
 *
 * The project index keys each declaration by the name written in its CREATE, owner included, and never applies a
 * compilation schema. So `S.x` and an unowned `x` are different targets, and every lookup by name tries both spellings
 * ([nameSpellings]); the first that the project knows wins.
 *
 * @param runSchemas the schemas known to the run (unit owners, compilation schema, requested dictionary schemas,
 *   `SYS`, `SYSTEM`), used to read `A.B` as `schema.object` when the project does not know it
 */
class CallResolver(
    private val snapshot: ProjectSnapshot,
    private val catalog: DeclarationCatalog,
    private val values: PackageValues,
    private val entities: MutableList<EntityFact>,
    private val relations: MutableList<RelationFact>,
    private val runSchemas: Set<String>
) {
    /**
     * @param written the callee segments as written
     * @param unitSchema the schema of the unit (written owner, else the compilation schema), or null
     * @param enclosingPackage the package spec or body containing the call, as written in its CREATE, or null
     * @param withoutParentheses a name read as a function call without parentheses: constructors do not apply,
     *   and a name the project does not know is `NOT_IN_PROJECT` without external entity (it may be anything)
     */
    fun resolve(
        written: List<String>,
        kind: ProjectCallKind,
        arguments: List<WrittenArgument>,
        unitSchema: String?,
        enclosingPackage: List<String>?,
        withoutParentheses: Boolean = false
    ): CallOutcome {
        val variants = nameSpellings(written, unitSchema)
        val packageName = enclosingPackage?.let { ProjectQualifiedName.of(*it.toTypedArray()) }
        values.packageMember(written, unitSchema, packageName)?.let { return CallOutcome.Value(it.kind, it.status, it.entityKey, it.reason, it.candidateKeys) }
        if (!withoutParentheses) constructorType(variants, packageName)?.let { return constructor(it) ?: CallOutcome.NotACall }

        val callArguments = ProjectCallArguments.of(arguments.map { argument ->
            argument.label?.let { ProjectCallArgument.named(ProjectIdentifier.fromSource(it)) } ?: ProjectCallArgument.positional()
        })
        if (packageName != null) {
            for (names in variants) {
                if (names.size != 1 && !(names.size == 2 && normalizeIdentifier(names[0]) == normalizeIdentifier(enclosingPackage.last()))) continue
                val member = ProjectIdentifier.fromSource(names.last())
                val inventory = snapshot.findPackageSubprograms(packageName, member).declarations
                if (inventory.isNotEmpty()) {
                    val resolution = snapshot.resolveCall(ProjectCallTarget.packageMember(packageName, member), kind, callArguments)
                    return packageMember(inventory, resolution, arguments)
                }
            }
        }
        for (names in variants) {
            val resolution = snapshot.resolveCall(ProjectCallTarget.written(qualifiedName(names)), kind, callArguments)
            if (resolution.status != ProjectCallResolution.Status.NOT_FOUND_IN_PROJECT &&
                resolution.status != ProjectCallResolution.Status.UNKNOWN_PACKAGE) {
                return map(resolution)
            }
        }
        if (withoutParentheses) return CallOutcome.Call(Status.UNRESOLVED, null, "NOT_IN_PROJECT")
        return notInProject(written.map { normalizeIdentifier(it) }, kind, unitSchema)
    }

    /**
     * A constructor: the named-type resolution of the first spelling whose declarations are all types (the name as
     * written, or a one-segment name as a member of the enclosing package); null when that spelling is not a type.
     */
    private fun constructorType(variants: List<List<String>>, packageName: ProjectQualifiedName?): ProjectNamedTypeResolution? {
        for (names in variants) {
            val name = qualifiedName(names)
            var owner: ProjectQualifiedName? = null
            val declarations = snapshot.findDeclarations(name).declarations.ifEmpty {
                if (names.size == 1 && packageName != null) {
                    owner = packageName
                    snapshot.findDeclarations(ProjectQualifiedName.ofIdentifiers(packageName.segments + name.last)).declarations
                } else {
                    emptyList()
                }
            }
            if (declarations.isNotEmpty()) {
                return if (declarations.all { it is ProjectTypeDeclarationView }) snapshot.resolveNamedType(name, owner) else null
            }
        }
        return null
    }

    /** A constructor depends on its type; null keeps the skip when no type is identified. */
    private fun constructor(resolution: ProjectNamedTypeResolution): CallOutcome.Call? = when (resolution.status) {
        ProjectNamedTypeResolution.Status.RESOLVED -> CallOutcome.Call(Status.RESOLVED, catalog.keyOf(resolution.declaration.get().id), null)
        ProjectNamedTypeResolution.Status.AMBIGUOUS ->
            CallOutcome.Call(Status.AMBIGUOUS, null, resolution.status.name, resolution.candidates.map { catalog.keyOf(it.id) }.distinct())
        ProjectNamedTypeResolution.Status.INCOMPLETE_INDEX -> CallOutcome.Call(Status.UNRESOLVED, null, resolution.status.name)
        else -> null
    }

    /** The public result of [resolution], combined with the private body declarations of the inventory. */
    private fun packageMember(
        inventory: List<ProjectPackageSubprogramView>,
        resolution: ProjectCallResolution,
        arguments: List<WrittenArgument>
    ): CallOutcome.Call {
        val privates = inventory.filter { it.role == ProjectDeclarationRole.BODY && catalog.isPrivate(it.id) }
        if (privates.isEmpty()) return map(resolution)
        val hasPublic = inventory.any { it.role == ProjectDeclarationRole.SPECIFICATION }
        if (!hasPublic && privates.size == 1) {
            val body = privates.single()
            val key = catalog.keyOf(body.id)
            val bound = bindByLabelOrPosition(body, arguments)
            return CallOutcome.Call(Status.RESOLVED, key, "PRIVATE_SINGLE",
                bindings = bound.mapValues { key + "." + normalizeIdentifier(it.value.name.originalSpelling) }, modes = bound.mapValues { it.value.mode })
        }
        val publicCandidates = if (hasPublic) applicable(resolution) else emptyList()
        return CallOutcome.Call(Status.AMBIGUOUS, null, "PRIVATE_OVERLOADS", (publicCandidates + privates.map { catalog.keyOf(it.id) }).distinct())
    }

    /** Exact bindings only: a label naming a formal, or a position while every earlier actual is positional. */
    private fun bindByLabelOrPosition(body: ProjectSubprogramView, arguments: List<WrittenArgument>): Map<Int, ProjectParameterView> {
        val bindings = HashMap<Int, ProjectParameterView>()
        var positional = true
        arguments.forEachIndexed { index, argument ->
            val parameter = if (argument.label != null) {
                positional = false
                val label = normalizeIdentifier(argument.label)
                body.parameters.firstOrNull { normalizeIdentifier(it.name.originalSpelling) == label }
            } else if (positional) {
                body.parameters.getOrNull(index)
            } else {
                null
            }
            if (parameter != null) bindings[index + 1] = parameter
        }
        return bindings
    }

    private fun applicable(resolution: ProjectCallResolution): List<String> =
        resolution.candidates.filter { it.applicability == ProjectCallCandidate.Applicability.APPLICABLE }.map { catalog.keyOf(it.declaration.id) }

    /** The outcome for every status but a name the project does not know ([notInProject]). */
    private fun map(resolution: ProjectCallResolution): CallOutcome.Call {
        val status = resolution.status
        return when (status) {
            ProjectCallResolution.Status.RESOLVED -> {
                val selected = resolution.selected.get()
                val key = catalog.keyOf(selected.declaration.id)
                CallOutcome.Call(Status.RESOLVED, key, null, bindings = selected.bindings.associate {
                    it.argumentOrdinal to key + "." + normalizeIdentifier(it.parameter.name.originalSpelling)
                }, modes = selected.bindings.associate { it.argumentOrdinal to it.parameter.mode })
            }
            ProjectCallResolution.Status.AMBIGUOUS -> CallOutcome.Call(Status.AMBIGUOUS, null, status.name, applicable(resolution))
            ProjectCallResolution.Status.AMBIGUOUS_TARGET ->
                CallOutcome.Call(Status.AMBIGUOUS, null, status.name, resolution.candidates.map { catalog.keyOf(it.declaration.id) }.distinct())
            ProjectCallResolution.Status.NO_APPLICABLE_CANDIDATE, ProjectCallResolution.Status.WRONG_KIND,
            ProjectCallResolution.Status.INVALID_NOTATION -> CallOutcome.Call(Status.UNRESOLVED, null, status.name)
            ProjectCallResolution.Status.NOT_FOUND_IN_PROJECT, ProjectCallResolution.Status.UNKNOWN_PACKAGE ->
                CallOutcome.Call(Status.UNRESOLVED, null, "NOT_IN_PROJECT")
            ProjectCallResolution.Status.PARTIAL_DECLARATIONS, ProjectCallResolution.Status.INCOMPLETE_INDEX ->
                CallOutcome.Call(Status.UNVERIFIED, resolution.selected.map { catalog.keyOf(it.declaration.id) }.orElse(null), status.name)
            ProjectCallResolution.Status.NOT_PREPARED -> error("Call resolution is NOT_PREPARED in a prepared run")
        }
    }

    /**
     * A name no spelling finds. A missing member of a declared owner (or a declared full key) is `UNKNOWN_MEMBER`
     * without entity; otherwise multi-segment names get an external entity. When `A` is a schema of the run:
     * `db:A.B` without parent, `db:A.B.C` under package `db:A.B`. Otherwise: `db:~.A.B` under package `db:~.A`
     * (a package, a synonym or a schema: not known), and `db:~.A.B.C` without parent.
     */
    private fun notInProject(names: List<String>, kind: ProjectCallKind, unitSchema: String?): CallOutcome.Call {
        val notFound = CallOutcome.Call(Status.UNRESOLVED, null, "NOT_IN_PROJECT")
        if (names.size < 2) return notFound
        val entityKind = if (kind == ProjectCallKind.FUNCTION) EntityKind.FUNCTION else EntityKind.PROCEDURE
        return when (val result = ExternalNames.external(names, entityKind, unitSchema, runSchemas, catalog, entities, relations)) {
            ExternalNames.Result.UnknownMember -> CallOutcome.Call(Status.UNRESOLVED, null, "UNKNOWN_MEMBER")
            is ExternalNames.Result.External -> notFound.copy(entityKey = result.key)
        }
    }
}
