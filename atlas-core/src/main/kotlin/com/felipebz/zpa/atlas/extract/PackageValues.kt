//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.api.project.*
import com.felipebz.zpa.atlas.model.Status

/** What a package member found by [PackageValues.packageMember] is, so that callers can pick the role. */
enum class MemberKind { VALUE, EXCEPTION, CURSOR }

/** A package member found by name; [named] is how many written segments name it, those after it its record fields (`code` of `pkg.g_rec.code`). */
data class MemberOutcome(
    val kind: MemberKind, val status: Status, val entityKey: String?, val reason: String?, val candidateKeys: List<String>, val named: Int
) {
    /** The outcome as an anchor's: the written [fields] after the member are followed from it. */
    fun toTypeOutcome(fields: List<String>) = TypeOutcome(status, entityKey, reason, candidateKeys, fields)
}

/**
 * Package values (variables and constants of package specifications), package exceptions and cursors, and project
 * packages by written name, shared by anchors, calls and value references so that they use the same owners
 * ([valueLookups]). Every lookup reads the snapshot preferring the unit's schema ([UnitSnapshots]).
 */
class PackageValues(private val snapshots: UnitSnapshots, private val catalog: DeclarationCatalog) {
    /**
     * The first lookup that identifies a value, exception or cursor of a package specification (or several), or null.
     * A value comes from the resolution itself; an exception or cursor is a candidate of a `NOT_FOUND_IN_PROJECT`
     * result: one gives RESOLVED, several (the same specification in two files) AMBIGUOUS. INCOMPLETE_INDEX applies
     * only when a candidate is one of these.
     */
    fun packageMember(written: List<String>, unitSchema: String?, packageName: ProjectQualifiedName?): MemberOutcome? {
        val snapshot = snapshots.of(unitSchema)
        for ((segments, owner) in valueLookups(written, packageName)) {
            val resolution = snapshot.resolvePackageValue(qualifiedName(segments), owner)
            when (resolution.status) {
                ProjectValueResolution.Status.RESOLVED ->
                    return MemberOutcome(MemberKind.VALUE, Status.RESOLVED, catalog.keyOf(resolution.value.get().id), null, emptyList(), segments.size)
                ProjectValueResolution.Status.AMBIGUOUS -> return MemberOutcome(MemberKind.VALUE, Status.AMBIGUOUS, null, resolution.status.name,
                    resolution.candidates.map { catalog.keyOf(it.id) }.distinct(), segments.size)
                ProjectValueResolution.Status.NOT_FOUND_IN_PROJECT -> {
                    val members = resolution.candidates.filter { kindOf(it) != null }
                    val keys = members.map { catalog.keyOf(it.id) }.distinct()
                    when {
                        members.isEmpty() -> Unit
                        members.size == 1 -> return MemberOutcome(kindOf(members.single())!!, Status.RESOLVED, keys.single(), null, emptyList(), segments.size)
                        else -> return MemberOutcome(kindOf(members.first())!!, Status.AMBIGUOUS, null, ProjectValueResolution.Status.AMBIGUOUS.name, keys, segments.size)
                    }
                }
                ProjectValueResolution.Status.INCOMPLETE_INDEX ->
                    resolution.candidates.firstNotNullOfOrNull { kindOf(it) }?.let { return MemberOutcome(it, Status.UNRESOLVED, null, "INCOMPLETE_INDEX", emptyList(), segments.size) }
                else -> Unit
            }
        }
        return null
    }

    private fun kindOf(declaration: ProjectDeclarationView): MemberKind? = when (declaration) {
        is ProjectPackageValueView -> MemberKind.VALUE
        is ProjectPackageExceptionView -> MemberKind.EXCEPTION
        is ProjectPackageCursorView -> MemberKind.CURSOR
        else -> null
    }

    /** Whether a proper prefix of [written] is a package of the project. */
    fun isPackageMember(written: List<String>, unitSchema: String?): Boolean {
        val snapshot = snapshots.of(unitSchema)
        return (1 until written.size).any { length ->
            snapshot.findDeclarations(qualifiedName(written.take(length))).declarations.any {
                it.kind == ProjectDeclarationKind.PACKAGE || it.kind == ProjectDeclarationKind.PACKAGE_BODY
            }
        }
    }

    /**
     * The schema-level declarations of the project (packages and their bodies, types, standalone subprograms,
     * sequences) named by [written].
     */
    fun schemaObjects(written: List<String>, unitSchema: String?): List<ProjectDeclarationView> =
        snapshots.of(unitSchema).findDeclarations(qualifiedName(written)).declarations.filter { it.kind in SCHEMA_OBJECTS }

    /** The project sequence named by [written], as an entity key, or null. */
    fun sequence(written: List<String>, unitSchema: String?): String? =
        snapshots.of(unitSchema).findDeclarations(qualifiedName(written)).declarations
            .firstOrNull { it.kind == ProjectDeclarationKind.SEQUENCE }?.let { catalog.keyOf(it.id) }
}

private val SCHEMA_OBJECTS = setOf(
    ProjectDeclarationKind.PACKAGE, ProjectDeclarationKind.PACKAGE_BODY, ProjectDeclarationKind.STANDALONE_TYPE,
    ProjectDeclarationKind.STANDALONE_PROCEDURE, ProjectDeclarationKind.STANDALONE_FUNCTION, ProjectDeclarationKind.SEQUENCE
)
