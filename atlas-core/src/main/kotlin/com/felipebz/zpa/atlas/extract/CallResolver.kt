//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.api.project.*
import com.felipebz.zpa.api.semantics.CallBinding
import com.felipebz.zpa.api.semantics.LocalCallStatus
import com.felipebz.zpa.api.semantics.NameDeclaration
import com.felipebz.zpa.atlas.model.EntityFact
import com.felipebz.zpa.atlas.model.EntityKind
import com.felipebz.zpa.atlas.model.RelationFact
import com.felipebz.zpa.atlas.model.Status

internal fun qualifiedName(names: List<String>): ProjectQualifiedName = ProjectQualifiedName.of(*names.toTypedArray())

/**
 * The package value lookups of a name, in order, as (segments, package owner): a one-segment name with the enclosing
 * package as owner; else the first segment as a value of the enclosing package (`g_tab.exists(1)` in the body), then
 * the full name and its prefixes of two or more segments. The enclosing package first is PL/SQL scope; the schema is
 * the preferred snapshot's ([UnitSnapshots]).
 */
internal fun valueLookups(written: List<String>, packageName: ProjectQualifiedName?): Sequence<Pair<List<String>, ProjectQualifiedName?>> =
    sequence {
        if (written.size == 1) {
            yield(written to packageName)
            return@sequence
        }
        if (packageName != null) yield(written.take(1) to packageName)
        for (length in written.size downTo 2) yield(written.take(length) to null)
    }

/** A written actual: its label as written (named notation) or null (positional). */
data class WrittenArgument(val label: String?)

/**
 * What zpa's local analysis decided for a call ([binding]), with [subprogramAt], the package or standalone subprogram
 * a declaration of the file's own subprograms reaches when called (null for one without entity, a nested subprogram).
 */
class CallEvidence(val binding: CallBinding, val subprogramAt: (NameDeclaration) -> ProjectSubprogramView?)

/**
 * How one call site resolves: [Value] for a package value, exception or cursor
 * (`pkg.g_tab(i)`, `pkg.g_tab.exists(i)`, `pkg.c_pending(1)`), [NotACall] for a
 * type the project does not identify.
 */
sealed class CallOutcome {
    data object NotACall : CallOutcome()

    /**
     * A member of an external package or type read without parentheses (`dbms_utility.format_error_backtrace`): its
     * kind is unknown (a function, a variable, a constant), so the entity is the owner [entityKey].
     */
    data class ExternalMember(val entityKey: String) : CallOutcome()

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
 * Resolves call sites through [ProjectSnapshot.resolveCall]: package scope before schema scope inside a package, and
 * the body-only subprograms the API never sees. Lexical scope is the caller's legacy-symbol check; nothing here binds
 * arguments against package-body declarations.
 *
 * Every lookup reads the snapshot preferring the unit's schema ([UnitSnapshots]): zpa reads an unqualified name in that
 * schema first, and `S.x` reaches an unowned `x` that compiles in `S`, so the name is looked up as written.
 *
 * @param runSchemas the schemas known to the run (unit owners, compilation schema, requested dictionary schemas,
 *   `SYS`, `SYSTEM`), used to read `A.B` as `schema.object` when the project does not know it
 */
class CallResolver(
    private val snapshots: UnitSnapshots,
    private val catalog: DeclarationCatalog,
    private val values: PackageValues,
    private val synonyms: SynonymResolver,
    private val entities: MutableList<EntityFact>,
    private val relations: MutableList<RelationFact>,
    private val runSchemas: Set<String>
) {
    /**
     * @param written the callee segments as written
     * @param unitSchema the schema of the unit (written owner, else the compilation schema), or null
     * @param enclosingPackage the package spec or body containing the call, as written in its CREATE, or null
     * @param withoutParentheses a name read as a function call without parentheses: constructors do not apply,
     *   and a name the project does not know is `NOT_IN_PROJECT` without external entity (it may be anything), except a
     *   member of a package or type a synonym names ([CallOutcome.ExternalMember])
     * @param evidence what zpa decided for the call, asked after package values and constructors and before the
     *   structural resolution ([decided])
     */
    fun resolve(
        written: List<String>,
        kind: ProjectCallKind,
        arguments: List<WrittenArgument>,
        unitSchema: String?,
        enclosingPackage: List<String>?,
        withoutParentheses: Boolean = false,
        evidence: CallEvidence? = null
    ): CallOutcome =
        valueOrConstructor(written, unitSchema, enclosingPackage, withoutParentheses)
            ?: evidence?.let { decided(it, arguments) }
            ?: structural(written, kind, arguments, unitSchema, enclosingPackage, withoutParentheses, null)

    /** [written] as a package value, exception or cursor, or as a constructor; null when it is neither. */
    private fun valueOrConstructor(written: List<String>, unitSchema: String?, enclosingPackage: List<String>?, withoutParentheses: Boolean): CallOutcome? {
        val packageName = enclosingPackage?.let { ProjectQualifiedName.of(*it.toTypedArray()) }
        values.packageMember(written, unitSchema, packageName)?.let { return CallOutcome.Value(it.kind, it.status, it.entityKey, it.reason, it.candidateKeys) }
        if (!withoutParentheses) constructorType(snapshots.of(unitSchema), written, packageName)?.let { return constructor(it) ?: CallOutcome.NotACall }
        return null
    }

    /**
     * The resolution of the number, names and modes of the actuals, then the synonym and external-name paths of a name
     * the project does not know.
     *
     * @param synonymTarget the object [written] was rewritten to, a synonym target or an own object the project lacks
     */
    private fun structural(
        written: List<String>,
        kind: ProjectCallKind,
        arguments: List<WrittenArgument>,
        unitSchema: String?,
        enclosingPackage: List<String>?,
        withoutParentheses: Boolean,
        synonymTarget: NameTarget?
    ): CallOutcome {
        val snapshot = snapshots.of(unitSchema)
        val packageName = enclosingPackage?.let { ProjectQualifiedName.of(*it.toTypedArray()) }
        val callArguments = ProjectCallArguments.of(arguments.map { argument ->
            argument.label?.let { ProjectCallArgument.named(ProjectIdentifier.fromSource(it)) } ?: ProjectCallArgument.positional()
        })
        if (enclosingPackage != null && packageName != null) {
            enclosingPackageMember(snapshot, written, enclosingPackage, packageName, kind, callArguments, arguments)?.let { return it }
        }
        val resolution = snapshot.resolveCall(ProjectCallTarget.written(qualifiedName(written)), kind, callArguments)
        if (resolution.status != ProjectCallResolution.Status.NOT_FOUND_IN_PROJECT &&
            resolution.status != ProjectCallResolution.Status.UNKNOWN_PACKAGE) {
            return map(resolution)
        }
        // A name the project does not know may be an object of the unit's schema the sources lack, or a synonym: a
        // one-segment call of a function, a procedure or a type (a constructor), or the owner of a member. The object
        // (a synonym's final target) is then resolved as if written, project first.
        val names = written.map { normalizeIdentifier(it) }
        if (synonymTarget == null && !(withoutParentheses && names.size == 1)) {
            val targetTypes = if (names.size == 1) SynonymResolver.CALLABLES else SynonymResolver.PACKAGES_AND_TYPES
            synonyms.follow(names[0], unitSchema, targetTypes)?.let { target ->
                val rewritten = SynonymResolver.written(target, names.drop(1))
                return valueOrConstructor(rewritten, unitSchema, enclosingPackage, withoutParentheses)
                    ?: structural(rewritten, kind, arguments, unitSchema, enclosingPackage, withoutParentheses, target)
            }
        }
        if (withoutParentheses) {
            return synonymTarget?.let { externalOwner(it) }?.let { CallOutcome.ExternalMember(it) }
                ?: CallOutcome.Call(Status.UNRESOLVED, null, "NOT_IN_PROJECT")
        }
        return notInProject(names, kind, unitSchema, synonymTarget)
    }

    /**
     * A member of the enclosing package [packageName]: the name, one segment or qualified with the package's own name,
     * when the package declares it; null otherwise.
     */
    private fun enclosingPackageMember(
        snapshot: ProjectSnapshot,
        names: List<String>,
        enclosingPackage: List<String>,
        packageName: ProjectQualifiedName,
        kind: ProjectCallKind,
        callArguments: ProjectCallArguments,
        arguments: List<WrittenArgument>
    ): CallOutcome? {
        if (names.size != 1 && !(names.size == 2 && normalizeIdentifier(names[0]) == normalizeIdentifier(enclosingPackage.last()))) return null
        val member = ProjectIdentifier.fromSource(names.last())
        val inventory = snapshot.findPackageSubprograms(packageName, member).declarations
        if (inventory.isEmpty()) return null
        val resolution = snapshot.resolveCall(ProjectCallTarget.packageMember(packageName, member), kind, callArguments)
        return packageMember(inventory, resolution, arguments)
    }

    /**
     * A constructor: the named-type resolution of the name when its declarations are all types (the name as written,
     * or a one-segment name as a member of the enclosing package); null when it is not a type.
     */
    private fun constructorType(snapshot: ProjectSnapshot, names: List<String>, packageName: ProjectQualifiedName?): ProjectNamedTypeResolution? {
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
        if (declarations.isEmpty()) return null
        return if (declarations.all { it is ProjectTypeDeclarationView }) snapshot.resolveNamedType(name, owner) else null
    }

    /** A constructor depends on its type; null keeps the skip when no type is identified. */
    private fun constructor(resolution: ProjectNamedTypeResolution): CallOutcome.Call? = when (resolution.status) {
        ProjectNamedTypeResolution.Status.RESOLVED -> CallOutcome.Call(Status.RESOLVED, catalog.keyOf(resolution.declaration.get().id), null)
        ProjectNamedTypeResolution.Status.AMBIGUOUS ->
            CallOutcome.Call(Status.AMBIGUOUS, null, resolution.status.name, resolution.candidates.map { catalog.keyOf(it.id) }.distinct())
        ProjectNamedTypeResolution.Status.INCOMPLETE_INDEX -> CallOutcome.Call(Status.UNRESOLVED, null, resolution.status.name)
        else -> null
    }

    /**
     * The call as zpa decided it, narrowed by the types of the actuals: a project resolution that selects a candidate or
     * keeps several, or a call of the file's own subprograms that binds one or keeps several, each with an entity. Null
     * for any other answer, which keeps the structural resolution.
     */
    private fun decided(evidence: CallEvidence, arguments: List<WrittenArgument>): CallOutcome.Call? = when (val binding = evidence.binding) {
        is CallBinding.Project -> typed(binding.resolution)
        is CallBinding.Local -> local(binding, evidence.subprogramAt, arguments)
        else -> null
    }

    private fun typed(resolution: ProjectTypedCallResolution): CallOutcome.Call? = when (resolution.status) {
        ProjectTypedCallResolution.Status.RESOLVED -> resolved(resolution.selected.get())
        ProjectTypedCallResolution.Status.AMBIGUOUS -> CallOutcome.Call(Status.AMBIGUOUS, null, ProjectCallResolution.Status.AMBIGUOUS.name,
            resolution.evaluations.filter { it.outcome == ProjectCandidateCompatibility.Outcome.RETAINED }.map { catalog.keyOf(it.candidate.declaration.id) }.distinct())
        else -> null
    }

    /** Each actual is bound by its label or its position, as for a body-only subprogram ([packageMember]). */
    private fun local(binding: CallBinding.Local, subprogramAt: (NameDeclaration) -> ProjectSubprogramView?, arguments: List<WrittenArgument>): CallOutcome.Call? =
        when (binding.status) {
            LocalCallStatus.RESOLVED -> subprogramAt(binding.selected!!)?.let { subprogram ->
                val key = catalog.keyOf(subprogram.id)
                val bound = bindByLabelOrPosition(subprogram, arguments)
                // A body-only subprogram without overloads keeps the reason a call of it has had.
                val reason = "PRIVATE_SINGLE".takeIf { binding.candidates.size == 1 && catalog.isPrivate(subprogram.id) }
                CallOutcome.Call(Status.RESOLVED, key, reason,
                    bindings = bound.mapValues { catalog.parameterKey(key, it.value) }, modes = bound.mapValues { it.value.mode })
            }
            LocalCallStatus.AMBIGUOUS -> binding.candidates.map { subprogramAt(it)?.let { subprogram -> catalog.keyOf(subprogram.id) } ?: return null }
                .distinct().let { CallOutcome.Call(Status.AMBIGUOUS, null, ProjectCallResolution.Status.AMBIGUOUS.name, it) }
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
                bindings = bound.mapValues { catalog.parameterKey(key, it.value) }, modes = bound.mapValues { it.value.mode })
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
            ProjectCallResolution.Status.RESOLVED -> resolved(resolution.selected.get())
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

    private fun resolved(selected: ProjectCallCandidate): CallOutcome.Call {
        val key = catalog.keyOf(selected.declaration.id)
        return CallOutcome.Call(Status.RESOLVED, key, null, bindings = selected.bindings.associate {
            it.argumentOrdinal to catalog.parameterKey(key, it.parameter)
        }, modes = selected.bindings.associate { it.argumentOrdinal to it.parameter.mode })
    }

    /**
     * A name the project does not find. A missing member of a declared owner (or a declared full key) is `UNKNOWN_MEMBER`
     * without entity; otherwise multi-segment names get an external entity. When `A` is a schema of the run:
     * `db:A.B` without parent, `db:A.B.C` under package `db:A.B`. Otherwise: `db:~.A.B` under package `db:~.A`
     * (a package, a synonym or a schema: not known), and `db:~.A.B.C` without parent.
     */
    private fun notInProject(names: List<String>, kind: ProjectCallKind, unitSchema: String?, synonymTarget: NameTarget?): CallOutcome.Call {
        val notFound = CallOutcome.Call(Status.UNRESOLVED, null, "NOT_IN_PROJECT")
        if (names.size < 2) return notFound
        val entityKind = if (kind == ProjectCallKind.FUNCTION) EntityKind.FUNCTION else EntityKind.PROCEDURE
        if (synonymTarget != null && names.size == 2) {
            // A one-segment call through a synonym: the function, procedure, operator or type (a constructor) itself.
            // An operator is a FUNCTION entity: it is called like one, and a new entity kind would change the schema.
            val key = dbKey(synonymTarget.owner, synonymTarget.name)
            val targetKind = when (synonymTarget.type) {
                "TYPE" -> EntityKind.TYPE
                "FUNCTION", "OPERATOR" -> EntityKind.FUNCTION
                else -> EntityKind.PROCEDURE
            }
            entities += EntityFact(key, targetKind, synonymTarget.owner, synonymTarget.name, null, key.removePrefix("db:"),
                external = true, precedence = Precedence.MENTIONED)
            return notFound.copy(entityKey = key)
        }
        val schemas = if (synonymTarget != null) runSchemas + synonymTarget.owner else runSchemas
        val parentKind = if (synonymTarget?.type == "TYPE") EntityKind.TYPE else EntityKind.PACKAGE
        return when (val result = ExternalNames.external(names, entityKind, unitSchema, schemas, catalog, entities, relations, parentKind)) {
            ExternalNames.Result.UnknownMember -> CallOutcome.Call(Status.UNRESOLVED, null, "UNKNOWN_MEMBER")
            is ExternalNames.Result.External -> notFound.copy(entityKey = result.key)
        }
    }

    /** The external package or type a synonym names, as an entity: the owner of a member read without parentheses. */
    private fun externalOwner(target: NameTarget): String? {
        val key = dbKey(target.owner, target.name)
        if (catalog.isDeclared(key)) return null
        val kind = when (target.type) {
            "PACKAGE" -> EntityKind.PACKAGE
            "TYPE" -> EntityKind.TYPE
            else -> return null
        }
        entities += EntityFact(key, kind, target.owner, target.name, null, key.removePrefix("db:"), external = true, precedence = Precedence.MENTIONED)
        return key
    }
}
