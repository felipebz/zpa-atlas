//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.compare

import com.felipebz.zpa.atlas.query.AtlasIndex
import com.felipebz.zpa.atlas.query.SchemaMismatchException
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Locale
import java.util.SortedSet

/**
 * Compares the object-level edges of an index with the database's `ALL_DEPENDENCIES`. It reads the index
 * file and, for the text classes, the sources; it needs neither zpa-core nor a database connection.
 */
class DependencyComparison(
    private val index: Path,
    private val database: DatabaseDependencies,
    private val sourcesRoot: Path? = null,
    private val defaultOwner: String? = null,
    private val builtins: Set<String> = DEFAULT_BUILTINS
) {
    fun compare(): ComparisonResult {
        val config = SQLiteConfig().apply { setReadOnly(true) }
        config.createConnection("jdbc:sqlite:${index.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA query_only = 1") }
            return compare(connection)
        }
    }

    private fun compare(connection: Connection): ComparisonResult {
        // ---- the run
        val version = connection.query("SELECT schema_version FROM run") { it.getInt(1) }.single()
        if (version != AtlasIndex.SCHEMA_VERSION) throw SchemaMismatchException(version, AtlasIndex.SCHEMA_VERSION)
        val run = connection.query("SELECT compilation_schema, sources_root, database_version, database_version_origin, ccflags FROM run") { rs ->
            (1..5).map { rs.getString(it) }
        }.single()
        val (runSchema, recordedRoot) = run
        val root = sourcesRoot ?: Path.of(recordedRoot).takeIf { Files.isDirectory(it) }

        val indexSide = readIndexSide(connection, runSchema, defaultOwner)
        val dbSide = databaseSide(indexSide.units, indexSide.edges)
        val sources = readSources(database, indexSide.units, dbSide.mapped, root)
        val matching = matchEdges(dbSide.dbEdges, indexSide.edges, indexSide.synonymTargets)
        val databaseOnly = databaseOnlyClasses(
            matching.unmatched, matching.matched, database.dependencies, indexSide.evidence, sources.names, sources.sourceStatus, builtins
        )
        val indexOnly = indexOnlyClasses(indexSide.edges, dbSide.compared, matching.matchedIndex, sources.sourceStatus, dbSide.dbObjects)

        val unitRows = unitRows(indexSide, dbSide, sources, matching.matched)
        val mapped = dbSide.mapped
        val dbObjects = dbSide.dbObjects
        val sourceStatus = sources.sourceStatus
        return ComparisonResult(
            unitRows, dbSide.unitsWithoutObject, dbSide.uncomparedEdges, dbSide.objectsWithoutUnit, indexSide.units.count { it.obj == null }, indexSide.scriptEdges,
            matching.matched.sortedWith(compareBy<MatchedEdge>({ it.unit }).thenComparator { a, b -> DatabaseDependencies.DEPENDENCY_ORDER.compare(a.reference, b.reference) }),
            databaseOnly.sortedWith(compareBy<DatabaseOnlyEdge>({ it.dbClass }, { it.unit }).thenComparator { a, b -> DatabaseDependencies.DEPENDENCY_ORDER.compare(a.reference, b.reference) }),
            indexOnly.sortedWith(compareBy({ it.indexClass }, { it.unit }, { it.target })),
            indexSide.collapsed, database.sourceReadable, root != null, builtins, triggerColumns(connection, database, mapped.filter { it.type == "TRIGGER" && it in dbObjects }.toSet(),
                sourceStatus.filterValues { it.first == SourceStatus.DIFFERS }.keys),
            conditionalCompilation(database, run[2], run[3], run[4], dbSide.compared.filter { it.type != "VIEW" && it.type != "MATERIALIZED VIEW" })
        )
    }

    /** The database side: the objects the units map to, those compared, and the database edges of the compared ones. */
    private class DatabaseSide(
        val mapped: SortedSet<DbObjectId>,
        val dbObjects: Map<DbObjectId, DbObject>,
        val objectsWithoutUnit: List<DbObjectId>,
        val unitsWithoutObject: List<DbObjectId>,
        val compared: SortedSet<DbObjectId>,
        val uncomparedEdges: Int,
        val dbEdges: List<DbDependency>
    )

    private fun databaseSide(units: List<IndexUnit>, edges: Map<DbObjectId, Map<Target, IndexEdge>>): DatabaseSide {
        val mapped = units.mapNotNull { it.obj }.toSortedSet()
        val dbObjects = database.objects.associateBy { DbObjectId(it.owner, it.name, it.type) }
        val owners = database.objects.map { it.owner }.toSet()
        val objectsWithoutUnit = dbObjects.keys.filter { it.type in OBJECT_TYPES.values && it.owner in owners && it !in mapped }.sorted()
        val unitsWithoutObject = mapped.filter { it !in dbObjects }
        // Only units the database has are compared: the others have no dependency rows at all.
        val compared = mapped.filter { it in dbObjects }.toSortedSet()
        val uncomparedEdges = unitsWithoutObject.sumOf { edges[it]?.size ?: 0 }
        val dbEdges = database.dependencies
            .filter { DbObjectId(it.owner, it.name, it.type) in compared }
            .distinctBy { listOf(it.owner, it.name, it.type, it.referencedOwner, it.referencedName, it.referencedLink) }
            .sortedWith(DatabaseDependencies.DEPENDENCY_ORDER)
        return DatabaseSide(mapped, dbObjects, objectsWithoutUnit, unitsWithoutObject, compared, uncomparedEdges, dbEdges)
    }

    /** One row per mapped object: its files, statuses and counts. */
    private fun unitRows(indexSide: IndexSide, dbSide: DatabaseSide, sources: UnitSources, matched: List<MatchedEdge>): List<UnitRow> {
        val units = indexSide.units
        val edges = indexSide.edges
        val sourceStatus = sources.sourceStatus
        val filesByObject = units.filter { it.obj != null }.groupBy({ it.obj!! }, { it.path })
        val dbEdgesByObject = dbSide.dbEdges.groupingBy { DbObjectId(it.owner, it.name, it.type) }.eachCount()
        val matchedByObject = matched.groupingBy { it.unit }.eachCount()
        return dbSide.mapped.map { obj ->
            UnitRow(
                obj, filesByObject[obj].orEmpty().distinct().sorted(), dbSide.dbObjects[obj]?.status,
                sourceStatus.getValue(obj).first, sourceStatus.getValue(obj).second,
                dbEdgesByObject[obj] ?: 0, edges[obj]?.size ?: 0, matchedByObject[obj] ?: 0, indexSide.dynamicCount[obj] ?: 0, indexSide.partialCount[obj] ?: 0
            )
        }
    }

    companion object {
        /** Built-in packages that every unit depends on (from `USER_DEPENDENCIES` on Oracle 26ai). */
        val DEFAULT_BUILTINS = setOf("STANDARD", "DBMS_STANDARD", "PLITBLM", "SYS_STUB_FOR_PURITY_ANALYSIS", "DUAL")

        val BUILTIN_OWNERS = setOf("SYS", "PUBLIC")

        /** Unit kinds that are database objects, with their `ALL_OBJECTS.OBJECT_TYPE`. */
        val OBJECT_TYPES = mapOf(
            "PACKAGE" to "PACKAGE", "PACKAGE_BODY" to "PACKAGE BODY", "PROCEDURE" to "PROCEDURE", "FUNCTION" to "FUNCTION",
            "TRIGGER" to "TRIGGER", "TYPE" to "TYPE", "TYPE_BODY" to "TYPE BODY", "VIEW" to "VIEW",
            "MATERIALIZED_VIEW" to "MATERIALIZED VIEW"
        )

        /** Reads a built-in list: one name per line, `#` starts a comment. */
        fun readBuiltins(text: String): Set<String> =
            text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.map { it.uppercase(Locale.ROOT) }.toSet()
    }
}
