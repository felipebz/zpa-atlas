//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.compare

/**
 * Walks the database dependency graph: every row the run loaded (all objects of the loaded owners, not only the units
 * compared), without remote rows. Oracle records some dependencies a unit's text never names, because it loaded them
 * through another object at the last compile (the attribute type of a type, the table a function anchors to); such a
 * row is explained when the target is reachable from an object the unit depends on.
 */
class TransitivePaths(dependencies: List<DbDependency>) {
    private val next: Map<DbObjectId, List<DbObjectId>> = dependencies
        .filter { it.referencedOwner != null && it.referencedLink == null }
        .groupBy({ DbObjectId(it.owner, it.name, it.type) }, { DbObjectId(it.referencedOwner!!, it.referencedName, it.referencedType) })
        .mapValues { (_, targets) -> targets.distinct() }

    private val reached = HashMap<DbObjectId, Map<DbObjectId, Int>>()

    /**
     * The first hop of [firstHops] (in their order) with the shortest path to [target], and the number of hops from it
     * to [target] (1 when it depends on [target] directly); null when none reaches it within [MAX_DEPTH] hops.
     */
    fun shortest(firstHops: List<DbObjectId>, target: DbObjectId): Pair<DbObjectId, Int>? =
        firstHops.mapNotNull { hop -> distances(hop)[target]?.let { hop to it } }.minByOrNull { it.second }

    /** The objects reachable from [start] within [MAX_DEPTH] hops, with their distance (breadth-first, visited set). */
    private fun distances(start: DbObjectId): Map<DbObjectId, Int> = reached.getOrPut(start) {
        val result = LinkedHashMap<DbObjectId, Int>()
        var frontier = listOf(start)
        val visited = hashSetOf(start)
        for (depth in 1..MAX_DEPTH) {
            val following = ArrayList<DbObjectId>()
            for (node in frontier) {
                for (target in next[node].orEmpty()) {
                    if (visited.add(target)) {
                        result[target] = depth
                        following += target
                    }
                }
            }
            if (following.isEmpty()) break
            frontier = following
        }
        result
    }

    companion object {
        /** Hops after the first: deeper paths are too weak to explain a row (and the graph of 50,000 rows stays cheap). */
        const val MAX_DEPTH = 4
    }
}
