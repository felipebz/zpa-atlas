//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.extract.exact
import com.felipebz.zpa.atlas.model.*
import java.util.*

/**
 * The places column values are read into ([ColumnPlaceFact]), decided once every file is scanned, since a target, its
 * type, an anchor or a cursor may be declared in another file: those of each file's own statements ([DirectPlaces]),
 * then those a value reaches through routines ([RoutineFlow]). A place's class ([PlaceClass], whose rules it follows)
 * comes from the target's type followed to where it ends ([PlaceTypes]); a character length written without a unit
 * counts in the unit the declaring unit was compiled with ([PlaceLengths]).
 */
internal object ColumnPlaces {
    /**
     * Where column values are read into PL/SQL, now that every target, type and cursor of every file is known: the places
     * by file path, and what is not followed, by file path and shape. What only the following uses (the entity kinds,
     * the declared shapes by key) is let go once it returns, before the index is written.
     */
    fun of(
        facts: List<FileFacts>, globalEntities: List<EntityFact>, resolved: Map<String, ProjectExternalRelation>, dictionary: AtlasDictionary,
        lengthSemantics: String
    ): Pair<Map<String, List<ColumnPlaceFact>>, Map<String, Map<String, Int>>> {
        val kinds = HashMap<String, EntityKind>()
        for (entity in globalEntities) kinds.putIfAbsent(entity.key, entity.kind)
        for (file in facts) for (entity in file.entities) kinds.putIfAbsent(entity.key, entity.kind)
        val relationColumns = resolved.mapValues { (key, relation) -> relation.rowFields.sortedBy { it.position.asInt }.map { "$key.${it.name.exact()}" } }
        val types = PlaceTypes(facts, kinds, relationColumns)
        val direct = DirectPlaces(types, PlaceLengths(facts, types, dictionary.lengthSemantics, lengthSemantics))
        val places = HashMap<String, List<ColumnPlaceFact>>()
        val placesLeftOut = HashMap<String, Map<String, Int>>()
        for (file in facts) {
            val (found, expressions) = direct.of(file)
            if (found.isNotEmpty()) places[file.path] = found
            val counts = TreeMap(file.placesLeftOut)
            expressions.forEach { (shape, count) -> counts.merge(shape, count, Int::plus) }
            if (counts.isNotEmpty()) placesLeftOut[file.path] = counts
        }
        // Through routines, across files: the targets inside them of the columns passed to their IN parameters, and what
        // comes back out of them through RETURN and OUT parameters to their callers.
        val (followed, followedLeftOut) = RoutineFlow(types, direct, facts, places).result()
        for ((path, found) in followed) places[path] = places[path].orEmpty() + found
        for ((path, counts) in followedLeftOut) placesLeftOut[path] = TreeMap(placesLeftOut[path].orEmpty()).apply { counts.forEach { (k, v) -> merge(k, v, Int::plus) } }
        return places to placesLeftOut
    }
}
