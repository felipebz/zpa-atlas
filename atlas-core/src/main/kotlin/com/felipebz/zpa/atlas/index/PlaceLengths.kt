//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.atlas.extract.FORMS_PREFIX
import com.felipebz.zpa.atlas.extract.TypeShapes
import com.felipebz.zpa.atlas.model.*

/**
 * The unit of the character length of a place ([unitOf]). One written without a unit counts in the
 * `NLS_LENGTH_SEMANTICS` the unit declaring it was compiled with, as the dictionary gives it ([compiledSemantics]): the
 * package body for a variable of the body, the package spec for a field of a record type of the spec, wherever the
 * place is. Without it, in [defaultSemantics].
 */
internal class PlaceLengths(
    facts: List<FileFacts>,
    types: PlaceTypes,
    /** The `NLS_LENGTH_SEMANTICS` each PL/SQL unit was compiled with (`ALL_PLSQL_OBJECT_SETTINGS`). */
    private val compiledSemantics: Map<CompiledUnit, String>,
    /** `BYTE` or `CHAR`: the unit of a length written without one that [compiledSemantics] does not decide. */
    private val defaultSemantics: String
) {
    /** The units of each file, by path. */
    private val units = HashMap<String, List<UnitFact>>()

    /** The unit that declares each entity with a type, whose compilation decides a length written there without a unit. */
    private val declaredIn = HashMap<String, UnitFact>()

    init {
        for (file in facts) units[file.path] = file.units
        for (file in facts) for (occurrence in file.occurrences) {
            val key = occurrence.entityKey ?: continue
            if (Role.DEFINITION !in occurrence.roles && Role.DECLARATION !in occurrence.roles) continue
            if (types.shapeOf(key) != null || types.kinds[key] == EntityKind.RECORD_FIELD) {
                file.units.getOrNull(occurrence.unitIndex)?.let { declaredIn.putIfAbsent(key, it) }
            }
        }
    }

    /** A character length's [unit], where it comes from ([source]) and the unit whose compilation gave it ([compiledIn]). */
    class LengthUnit(val unit: String?, val source: UnitSource? = null, val compiledIn: CompiledUnit? = null)

    /** The unit of [decision]'s length, at a place in the unit [unitIndex] of the file [path]: one written without a unit, [lengthUnit]'s. */
    fun unitOf(decision: PlaceTypes.Decision, path: String, unitIndex: Int?): LengthUnit =
        when (val written = decision.unit) {
            TypeShapes.NLS_LENGTH_SEMANTICS -> lengthUnit(decision.source, path, unitIndex)
            "BYTE", "CHAR" -> LengthUnit(written, UnitSource.WRITTEN)
            else -> LengthUnit(written)
        }

    /**
     * The unit of a length written without one in the declaration of [owner] (or, for one without an entity, in the unit
     * [unitIndex] of [path]): the `NLS_LENGTH_SEMANTICS` the unit declaring it was compiled with, else [defaultSemantics].
     * A Forms item's or parameter's is the one its module was compiled with, which the dictionary does not tell.
     */
    private fun lengthUnit(owner: String?, path: String, unitIndex: Int?): LengthUnit {
        if (owner?.startsWith(FORMS_PREFIX) == true) return LengthUnit(defaultSemantics, UnitSource.OPTION)
        val declaring = owner?.let { declaredIn[it] } ?: unitIndex?.let { units[path]?.getOrNull(it) }
        val compiled = declaring?.let { unit ->
            COMPILED_TYPES[unit.kind]?.let { type ->
                unit.compilationSchema?.let {
                    CompiledUnit(
                        it,
                        unit.name,
                        type
                    )
                }
            }
        }
        val setting = compiled?.let { compiledSemantics[it] }
        return if (setting != null) LengthUnit(setting, UnitSource.COMPILED, compiled) else LengthUnit(
            defaultSemantics,
            UnitSource.OPTION
        )
    }

    private companion object {
        /** The `TYPE` of `ALL_PLSQL_OBJECT_SETTINGS` of each unit kind that has one. */
        val COMPILED_TYPES = mapOf(
            UnitKind.PACKAGE to "PACKAGE",
            UnitKind.PACKAGE_BODY to "PACKAGE BODY",
            UnitKind.PROCEDURE to "PROCEDURE",
            UnitKind.FUNCTION to "FUNCTION",
            UnitKind.TRIGGER to "TRIGGER",
            UnitKind.TYPE to "TYPE",
            UnitKind.TYPE_BODY to "TYPE BODY"
        )
    }
}
