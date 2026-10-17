//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.api.project.ProjectDeclarationKind
import com.felipebz.zpa.api.project.ProjectDeclarationView
import com.felipebz.zpa.api.project.ProjectPackageCursorView
import com.felipebz.zpa.api.project.ProjectPackageExceptionView
import com.felipebz.zpa.api.project.ProjectPackageValueView
import com.felipebz.zpa.api.project.ProjectQualifiedName
import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.api.project.ProjectValueResolution
import com.felipebz.zpa.atlas.model.Status

/** What a package member found by [PackageValues.packageMember] is, so that callers can pick the role. */
enum class MemberKind { VALUE, EXCEPTION, CURSOR }

data class MemberOutcome(val kind: MemberKind, val status: Status, val entityKey: String?, val reason: String?, val candidateKeys: List<String> = emptyList()) {
    fun toTypeOutcome() = TypeOutcome(status, entityKey, reason, candidateKeys)
}

/**
 * Package values (variables and constants of package specifications), package exceptions and cursors, and project
 * packages by written name, shared by anchors, calls and value references so that they use the same spellings and
 * owners ([valueLookups]).
 */
class PackageValues(private val snapshot: ProjectSnapshot, private val catalog: DeclarationCatalog) {
    /**
     * The first lookup that identifies a value, exception or cursor of a package specification (or several), or null.
     * A value comes from the resolution itself; an exception or cursor is a candidate of a `NOT_FOUND_IN_PROJECT`
     * result: one gives RESOLVED, several (the same specification in two files) AMBIGUOUS. INCOMPLETE_INDEX applies
     * only when a candidate is one of these.
     */
    fun packageMember(written: List<String>, unitSchema: String?, packageName: ProjectQualifiedName?): MemberOutcome? {
        for ((segments, owner) in valueLookups(written, unitSchema, packageName)) {
            val resolution = snapshot.resolvePackageValue(qualifiedName(segments), owner)
            when (resolution.status) {
                ProjectValueResolution.Status.RESOLVED ->
                    return MemberOutcome(MemberKind.VALUE, Status.RESOLVED, catalog.keyOf(resolution.value.get().id), null)
                ProjectValueResolution.Status.AMBIGUOUS -> return MemberOutcome(MemberKind.VALUE, Status.AMBIGUOUS, null, resolution.status.name,
                    resolution.candidates.map { catalog.keyOf(it.id) }.distinct())
                ProjectValueResolution.Status.NOT_FOUND_IN_PROJECT -> {
                    val members = resolution.candidates.filter { kindOf(it) != null }
                    val keys = members.map { catalog.keyOf(it.id) }.distinct()
                    when {
                        members.isEmpty() -> Unit
                        members.size == 1 -> return MemberOutcome(kindOf(members.single())!!, Status.RESOLVED, keys.single(), null)
                        else -> return MemberOutcome(kindOf(members.first())!!, Status.AMBIGUOUS, null, ProjectValueResolution.Status.AMBIGUOUS.name, keys)
                    }
                }
                ProjectValueResolution.Status.INCOMPLETE_INDEX ->
                    resolution.candidates.firstNotNullOfOrNull { kindOf(it) }?.let { return MemberOutcome(it, Status.UNRESOLVED, null, "INCOMPLETE_INDEX") }
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

    /** Whether a proper prefix of [written], under some spelling, is a package of the project. */
    fun isPackageMember(written: List<String>, unitSchema: String?): Boolean =
        (1 until written.size).any { length ->
            nameSpellings(written.take(length), unitSchema).any { names ->
                snapshot.findDeclarations(qualifiedName(names)).declarations.any {
                    it.kind == ProjectDeclarationKind.PACKAGE || it.kind == ProjectDeclarationKind.PACKAGE_BODY
                }
            }
        }

    /** The project sequence named by [written] under some spelling, as an entity key, or null. */
    fun sequence(written: List<String>, unitSchema: String?): String? {
        for (names in nameSpellings(written, unitSchema)) {
            val declaration = snapshot.findDeclarations(qualifiedName(names)).declarations.firstOrNull { it.kind == ProjectDeclarationKind.SEQUENCE }
            if (declaration != null) return catalog.keyOf(declaration.id)
        }
        return null
    }
}
