//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.compare

/** The database rows the index matches, the index edges they match, and the rows left unmatched in their order. */
internal class Matching(
    val matched: ArrayList<MatchedEdge>,
    val matchedIndex: HashSet<Pair<DbObjectId, Target>>,
    val unmatched: ArrayList<DbDependency>
)

/** Matches each of [dbEdges] with the unit's index [edges]: exactly, by name with its readings, or through a synonym. */
internal fun matchEdges(
    dbEdges: List<DbDependency>,
    edges: Map<DbObjectId, Map<Target, IndexEdge>>,
    synonymTargets: Map<Pair<String, String>, Target>
): Matching {
    val matched = ArrayList<MatchedEdge>()
    val matchedIndex = HashSet<Pair<DbObjectId, Target>>()
    val unmatched = ArrayList<DbDependency>()
    for (dep in dbEdges) {
        val obj = DbObjectId(dep.owner, dep.name, dep.type)
        val unitEdges = edges[obj].orEmpty()
        val exact = unitEdges.values.firstOrNull { it.target.owner == dep.referencedOwner && it.target.name == dep.referencedName && it.target.link == dep.referencedLink }
        val byNameEdges = if (exact != null) emptyList() else unitEdges.values.filter {
            it.target.byName && it.target.link == dep.referencedLink &&
                (it.target.name == dep.referencedName || (dep.referencedOwner to dep.referencedName) in it.readings)
        }
        val byName = byNameEdges.minByOrNull { it.target }
        // A dependency on an object that does not exist is never an index edge (Oracle records it to invalidate the
        // unit when such an object appears, typically next to a synonym of the same name).
        val hit = if (dep.referencedType == "NON-EXISTENT") null else exact ?: byName
        if (hit != null) {
            matchedIndex += obj to hit.target
            if (exact != null) {
                matched += MatchedEdge(obj, dep, false, hit.fromCollapsedOnly, hit.locations.toList())
            } else {
                // By-name targets that differ only in type (`~.X` typeless and `~.X` PACKAGE) are one edge: every one
                // with this name and link is matched, and their locations are merged.
                val same = byNameEdges.filter { it.target.name == hit.target.name && it.target.link == hit.target.link }
                same.forEach { matchedIndex += obj to it.target }
                matched += MatchedEdge(obj, dep, true, same.all { it.fromCollapsedOnly },
                    same.flatMap { it.locations }.toSortedSet().toList())
            }
            continue
        }
        // Oracle records the synonym, not its target: the unit's edge to the target is the evidence.
        val synonymEdge = if (dep.referencedType == "SYNONYM" && dep.referencedLink == null) {
            synonymTargets[dep.referencedOwner to dep.referencedName]?.let { unitEdges[it] }
        } else {
            null
        }
        if (synonymEdge != null) {
            matchedIndex += obj to synonymEdge.target
            matched += MatchedEdge(obj, dep, false, synonymEdge.fromCollapsedOnly, synonymEdge.locations.toList(), synonym = true)
            continue
        }
        unmatched += dep
    }
    return Matching(matched, matchedIndex, unmatched)
}

/**
 * The class of each database row the index does not match, the first reason that applies; [dependencies] are all the
 * database's rows, for the transitive paths.
 */
internal fun databaseOnlyClasses(
    unmatched: List<DbDependency>,
    matched: List<MatchedEdge>,
    dependencies: List<DbDependency>,
    evidence: Map<DbObjectId, List<Evidence>>,
    names: Map<DbObjectId, Set<String>>,
    sourceStatus: Map<DbObjectId, Pair<SourceStatus, Int?>>,
    builtins: Set<String>
): ArrayList<DatabaseOnlyEdge> {
    val databaseOnly = ArrayList<DatabaseOnlyEdge>()
    val paths = TransitivePaths(dependencies)
    // The first hops of each unit: the objects of its matched rows, in dependency order.
    val firstHops = matched.sortedWith { a, b -> DatabaseDependencies.DEPENDENCY_ORDER.compare(a.reference, b.reference) }
        .groupBy({ it.unit }, { DbObjectId(it.reference.referencedOwner ?: "", it.reference.referencedName, it.reference.referencedType) })
    for (dep in unmatched) {
        val obj = DbObjectId(dep.owner, dep.name, dep.type)
        val unitEvidence = evidence[obj].orEmpty().filter { dep.referencedName in it.segments && fits(dep.referencedType, it.roles) }
        val unresolved = unitEvidence.filter { it.status in UNRESOLVED_STATUSES }
        val elsewhere = unitEvidence.filter { it.target != null && !(it.target.owner == dep.referencedOwner && it.target.name == dep.referencedName) &&
            !(it.target.byName && it.target.name == dep.referencedName) }
        // Only RESOLVED evidence is a likely resolution bug; UNVERIFIED, EXTERNAL and collapsed guesses are guesses.
        val (resolvedElsewhere, guessedElsewhere) = elsewhere.partition { it.status == "RESOLVED" }
        fun describe(list: List<Evidence>) = list.map { "${it.location}->${it.target}(${it.status})" }.distinct().joinToString(" ")
        val nameNotInText = names[obj] != null && dep.referencedName !in names.getValue(obj)
        val path = if (nameNotInText && dep.referencedOwner != null && dep.referencedLink == null) {
            paths.shortest(firstHops[obj].orEmpty(), DbObjectId(dep.referencedOwner, dep.referencedName, dep.referencedType))
        } else {
            null
        }
        val (dbClass, detail) = when {
            dep.referencedOwner in DependencyComparison.BUILTIN_OWNERS && dep.referencedName in builtins -> "BUILTIN" to ""
            dep.referencedLink != null -> "REMOTE" to ""
            dep.referencedType == "NON-EXISTENT" -> "NON_EXISTENT" to ""
            // A materialized view depends on its own container table, a `TABLE` of the same owner and name.
            dep.type == "MATERIALIZED VIEW" && dep.referencedOwner == dep.owner && dep.referencedName == dep.name &&
                dep.referencedType == "TABLE" -> "MVIEW_CONTAINER" to ""
            sourceStatus[obj]?.first == SourceStatus.DIFFERS -> "SOURCE_DIFFERS" to ""
            path != null -> "TRANSITIVE" to "via ${path.first} (${path.second})"
            nameNotInText -> "NAME_NOT_IN_TEXT" to ""
            unresolved.isNotEmpty() -> "UNRESOLVED_IN_INDEX" to unresolved.joinToString(" ") { "${it.location}(${it.status})" }
            resolvedElsewhere.isNotEmpty() -> "RESOLVED_ELSEWHERE" to describe(resolvedElsewhere)
            guessedElsewhere.isNotEmpty() -> "GUESSED_ELSEWHERE" to describe(guessedElsewhere)
            else -> "NOT_EXTRACTED" to ""
        }
        databaseOnly += DatabaseOnlyEdge(dbClass, obj, dep, detail, path?.first)
    }
    return databaseOnly
}

/** The class of each index edge of a [compared] unit that no database row matched. */
internal fun indexOnlyClasses(
    edges: Map<DbObjectId, Map<Target, IndexEdge>>,
    compared: Set<DbObjectId>,
    matchedIndex: Set<Pair<DbObjectId, Target>>,
    sourceStatus: Map<DbObjectId, Pair<SourceStatus, Int?>>,
    dbObjects: Map<DbObjectId, DbObject>
): ArrayList<IndexOnlyEdge> {
    val indexOnly = ArrayList<IndexOnlyEdge>()
    for ((obj, unitEdges) in edges.toSortedMap()) {
        if (obj !in compared) continue
        for (edge in unitEdges.values.sortedBy { it.target }) {
            if (obj to edge.target in matchedIndex) continue
            val indexClass = when {
                // Oracle records no dependency of a view on a remote object, whatever the view's text.
                obj.type == "VIEW" && edge.target.link != null -> "VIEW_REMOTE"
                // A view's MAKE_REF depends on the row type, not on the relation (verified on Oracle 26ai).
                obj.type == "VIEW" && edge.typeUseOnly && edge.target.type in RELATION_TYPES -> "VIEW_MAKE_REF"
                sourceStatus[obj]?.first == SourceStatus.DIFFERS -> "SOURCE_DIFFERS"
                dbObjects[obj]?.status == "INVALID" -> "UNIT_INVALID"
                edge.target.byName -> "BY_NAME_ONLY"
                else -> "INDEX_ONLY"
            }
            indexOnly += IndexOnlyEdge(indexClass, obj, edge.target, edge.locations.toList())
        }
    }
    return indexOnly
}

private val RELATION_TYPES = setOf("TABLE", "VIEW", "MATERIALIZED VIEW")
private val PACKAGE_ROLES = setOf("CALL", "TYPE_USE", "READ", "WRITE", "RAISE", "HANDLE")

/**
 * Whether evidence with [roles] can be about an object of [referencedType]: SQL roles or a type use for relations,
 * a call, a type use, a member read or write, a raise or a handler for packages, a call or a type use for types, a call for other
 * callables, anything but a declaration otherwise (a sequence: a read).
 */
private fun fits(referencedType: String, roles: Set<String>): Boolean = when (referencedType) {
    in RELATION_TYPES -> roles.any { it.startsWith("SQL_") } || "TYPE_USE" in roles
    "PACKAGE" -> roles.any { it in PACKAGE_ROLES }
    "TYPE" -> "CALL" in roles || "TYPE_USE" in roles
    "PROCEDURE", "FUNCTION", "OPERATOR" -> "CALL" in roles
    else -> !declaringOnly(roles)
}
private val UNRESOLVED_STATUSES = setOf("UNRESOLVED", "AMBIGUOUS", "PARTIAL_SOURCE")
