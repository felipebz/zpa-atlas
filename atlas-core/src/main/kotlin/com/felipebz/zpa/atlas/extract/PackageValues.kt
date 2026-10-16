package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.api.project.ProjectDeclarationKind
import com.felipebz.zpa.api.project.ProjectPackageValueView
import com.felipebz.zpa.api.project.ProjectQualifiedName
import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.api.project.ProjectValueResolution
import com.felipebz.zpa.atlas.model.Status

/**
 * Package values (variables and constants of package specifications) and project packages by written name, shared by
 * anchors, calls and value references so that they use the same spellings and owners ([valueLookups]).
 */
class PackageValues(private val snapshot: ProjectSnapshot, private val catalog: DeclarationCatalog) {
    /**
     * The first package value lookup that identifies a value (or several), or null: RESOLVED gives the value,
     * AMBIGUOUS the candidates, INCOMPLETE_INDEX only when a candidate is a value.
     */
    fun packageValue(written: List<String>, unitSchema: String?, packageName: ProjectQualifiedName?): TypeOutcome? {
        for ((segments, owner) in valueLookups(written, unitSchema, packageName)) {
            val resolution = snapshot.resolvePackageValue(qualifiedName(segments), owner)
            when (resolution.status) {
                ProjectValueResolution.Status.RESOLVED -> return TypeOutcome(Status.RESOLVED, catalog.keyOf(resolution.value.get().id), null)
                ProjectValueResolution.Status.AMBIGUOUS ->
                    return TypeOutcome(Status.AMBIGUOUS, null, resolution.status.name, resolution.candidates.map { catalog.keyOf(it.id) }.distinct())
                ProjectValueResolution.Status.INCOMPLETE_INDEX ->
                    if (resolution.candidates.any { it is ProjectPackageValueView }) return INCOMPLETE_INDEX
                else -> Unit
            }
        }
        return null
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

    private companion object {
        val INCOMPLETE_INDEX = TypeOutcome(Status.UNRESOLVED, null, "INCOMPLETE_INDEX")
    }
}
