//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.DdlGrammar
import com.felipebz.zpa.api.DmlGrammar
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlKeyword
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.api.TclGrammar
import com.felipebz.zpa.api.AggregateSqlFunctionsGrammar
import com.felipebz.zpa.api.SingleRowSqlFunctionsGrammar
import com.felipebz.zpa.api.checks.PlSqlCheck
import com.felipebz.zpa.api.project.ProjectExternalLookup
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.api.project.ProjectCallKind
import com.felipebz.zpa.api.project.ProjectParameterMode
import com.felipebz.zpa.api.project.SequenceReferenceResolution
import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.api.squid.SemanticAstNode
import com.felipebz.zpa.api.symbols.Symbol
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.model.*
import java.util.IdentityHashMap

/**
 * Extracts the facts of one file during its AST walk. A new instance is used for every file; it must be a
 * [PlSqlCheck] because the scanner installs the project analysis only on checks. Nothing is written here: the facts
 * are returned by [facts] and handed to the single writer.
 */
class FileExtractor(
    private val path: String,
    private val sha256: String,
    private val sizeBytes: Long,
    /** The charset the file was decoded with, recorded in the index. */
    private val encoding: String,
    private val lineCount: Int,
    private val dictionary: AtlasDictionary,
    private val defaultSchema: String?,
    private val catalogOf: (ProjectSnapshot) -> DeclarationCatalog
) : PlSqlCheck() {

    private lateinit var catalog: DeclarationCatalog
    private lateinit var calls: CallResolver
    private lateinit var types: TypeReferenceResolver
    private lateinit var values: PackageValues
    private lateinit var runSchemas: Set<String>
    private var result: FileFacts? = null

    private val units = ArrayList<UnitFact>()
    private val partialUnits = ArrayList<Boolean>()
    private val statements = ArrayList<StatementFact>()
    private val occurrences = ArrayList<OccurrenceFact>()
    private val entities = ArrayList<EntityFact>()
    private val relations = ArrayList<RelationFact>()
    private val coverage = ArrayList<CoverageFact>()
    private val triggerEvents = ArrayList<TriggerEventFact>()
    private val synonyms = SynonymResolver(dictionary)
    private val resolver = RelationResolver(dictionary, synonyms, entities, relations)
    private var recoveredRegions = 0

    /** Datatype names captured by a local declaration: counted, not indexed (locals are not entities). */
    private var localTypeReferences = 0

    /** Unqualified names in static SQL that may be a column of a source with unknown columns: counted, not indexed. */
    private var undecidedSqlNames = 0

    /** Modes of the formals that resolved calls bind their actual expressions to, for the roles of the values passed. */
    private val argumentModes = IdentityHashMap<AstNode, ProjectParameterMode>()

    /** SQL qualifiers (aliases, relation names, WITH names) owned by each SQL scope node. */
    private val scopeQualifiers = IdentityHashMap<AstNode, Set<String>>()

    override fun init() {
        val snapshot = projectAnalysis().snapshot
        catalog = catalogOf(snapshot)
        runSchemas = catalog.schemas + dictionary.schemas + listOfNotNull(defaultSchema) + setOf("SYS", "SYSTEM")
        values = PackageValues(snapshot, catalog)
        calls = CallResolver(snapshot, catalog, values, synonyms, entities, relations, runSchemas)
        types = TypeReferenceResolver(snapshot, catalog, values, resolver, synonyms, entities, relations, runSchemas)
    }

    override fun visitFile(node: AstNode) {
        var inRecovered = false
        for (child in node.children) {
            when (child.type) {
                PlSqlGrammar.RECOVERY -> {
                    recoveredRegions++
                    inRecovered = true
                    addUnit(UnitFact(UnitKind.UNPARSED, "", null, spanOf(child), defaultSchema), partial = true)
                }
                PlSqlGrammar.EXECUTE_PLSQL_BUFFER -> inRecovered = false
                PlSqlGrammar.COMPILATION_UNIT -> {
                    val inner = child.firstChild
                    if (inner.type != PlSqlGrammar.PROCEDURE_DECLARATION && inner.type != PlSqlGrammar.FUNCTION_DECLARATION &&
                        inner.type != PlSqlGrammar.ANONYMOUS_BLOCK) {
                        inRecovered = false
                    }
                    compilationUnit(inner, inRecovered)
                }
                DmlGrammar.DML_COMMAND -> sqlUnit(child, inRecovered)
                TclGrammar.TCL_COMMAND -> if (child.hasDirectChildren(TclGrammar.LOCK_TABLE_EXPRESSION)) sqlUnit(child, inRecovered)
                DdlGrammar.DDL_COMMAND -> {
                    inRecovered = false
                    val view = child.getFirstDescendantOrNull(PlSqlGrammar.CREATE_VIEW)
                    if (view != null) compilationUnit(view, false) else sqlUnit(child, false)
                }
                else -> Unit
            }
        }
        result = build(ParseStatus.OK.takeIf { recoveredRegions == 0 } ?: ParseStatus.RECOVERED)
    }

    /** The facts of the file; a file the parser rejected has only its declarations, in one unparsed unit. */
    fun facts(): FileFacts = result
        ?: failedFacts(if (::catalog.isInitialized) catalog else null,
            CoverageFact("PARSE_FAILED", "The file could not be parsed; only the declarations of the project index are kept"))

    /**
     * The facts of a file whose walk produced nothing usable: one unparsed unit spanning the file, the declarations of
     * [catalog] (none when the run never built it) and [note]. Must be called on an extractor that did not walk.
     */
    fun failedFacts(catalog: DeclarationCatalog?, note: CoverageFact): FileFacts {
        if (catalog != null) this.catalog = catalog
        coverage += note
        addUnit(UnitFact(UnitKind.UNPARSED, "", null, SourceSpan(1, 0, lineCount + 1, 0), defaultSchema), partial = true)
        return build(ParseStatus.FAILED)
    }

    private fun build(status: ParseStatus): FileFacts {
        if (::catalog.isInitialized) {
            addDeclarationOccurrences()
            coverage += catalog.coverageFor(path)
        }
        if (localTypeReferences > 0) {
            coverage += CoverageFact("LOCAL_ANCHOR", "$localTypeReferences datatype name(s) refer to local declarations (variables, parameters, cursors, types): not indexed")
        }
        if (undecidedSqlNames > 0) {
            coverage += CoverageFact("SQL_NAME_UNDECIDED", "$undecidedSqlNames unqualified name(s) in static SQL may be a column of a relation whose columns are unknown or a PL/SQL name: not indexed")
        }
        // Mentioned relations and columns repeat once per reference: keep the first of each exact duplicate.
        return FileFacts(path, sha256, sizeBytes, encoding, status, recoveredRegions, defaultSchema, units, statements, occurrences,
            LinkedHashSet(entities).toList(), LinkedHashSet(relations).toList(), coverage, resolver.dictionaryRelations.toList(),
            synonyms.dictionarySynonyms.toList(), triggerEvents.toList())
    }

    // ---- units --------------------------------------------------------------------------------------------------

    /**
     * A DML trigger seen from its body: the correlation names, the relation they read and write ([target], null for a
     * nested-table trigger), and the `WHEN` expression, where correlation names are written without a colon.
     */
    private class TriggerScope(val key: String, val newNames: Set<String>, val oldNames: Set<String>, val target: ResolvedRelation?, val whenExpression: AstNode?)

    private class Context(
        val unitIndex: Int,
        val partial: Boolean,
        val schema: String?,
        val container: String?,
        val trigger: TriggerScope?,
        /** The package spec or body around the code, as written in its CREATE; null elsewhere. */
        val enclosingPackage: List<String>? = null,
        /** Variables, cursors and types declared at the level of the package body around the code. */
        val bodyNames: Set<String> = emptySet()
    ) {
        fun inside(container: String) = Context(unitIndex, partial, schema, container, trigger, enclosingPackage, bodyNames)
    }

    private fun addUnit(unit: UnitFact, partial: Boolean): Int {
        units += unit
        partialUnits += partial
        return units.size - 1
    }

    private fun names(unitName: AstNode?): List<String> =
        unitName?.getChildren(PlSqlGrammar.IDENTIFIER_NAME)?.map { normalizeIdentifier(it.tokenOriginalValue) }.orEmpty()

    private fun sqlUnit(node: AstNode, partial: Boolean) {
        val index = addUnit(UnitFact(UnitKind.SQL_STATEMENT, "", null, spanOf(node), defaultSchema), partial)
        walk(node, Context(index, partial, defaultSchema, null, null))
    }

    private fun compilationUnit(node: AstNode, partial: Boolean) {
        val unitName = node.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME)
        val written = names(unitName)
        val schema = if (written.size >= 2) written.first() else defaultSchema
        val span = spanOf(node)
        // A subprogram split off by recovery is a top-level declaration named by an IDENTIFIER_NAME, not a UNIT_NAME.
        val displayName = if (unitName == null && (node.type == PlSqlGrammar.PROCEDURE_DECLARATION || node.type == PlSqlGrammar.FUNCTION_DECLARATION)) {
            node.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { normalizeIdentifier(it.tokenOriginalValue) }.orEmpty()
        } else {
            written.joinToString(".")
        }
        var entityKey: String? = null
        var trigger: TriggerScope? = null
        val kind = when (node.type) {
            PlSqlGrammar.CREATE_PACKAGE -> UnitKind.PACKAGE.also { entityKey = sourceObject(written, EntityKind.PACKAGE) }
            PlSqlGrammar.CREATE_PACKAGE_BODY -> UnitKind.PACKAGE_BODY.also {
                entityKey = sourceObject(written, EntityKind.PACKAGE)
            }
            PlSqlGrammar.CREATE_PROCEDURE, PlSqlGrammar.PROCEDURE_DECLARATION -> UnitKind.PROCEDURE.also { entityKey = subprogramAt(node) }
            PlSqlGrammar.CREATE_FUNCTION, PlSqlGrammar.FUNCTION_DECLARATION -> UnitKind.FUNCTION.also { entityKey = subprogramAt(node) }
            PlSqlGrammar.CREATE_TYPE -> UnitKind.TYPE.also { entityKey = sourceObject(written, EntityKind.TYPE) }
            PlSqlGrammar.CREATE_TYPE_BODY -> UnitKind.TYPE_BODY.also { entityKey = sourceObject(written, EntityKind.TYPE) }
            PlSqlGrammar.CREATE_TRIGGER -> UnitKind.TRIGGER.also {
                val key = sourceObject(written, EntityKind.TRIGGER)
                entityKey = key
                trigger = key?.let { trigger(node, it, schema) }
            }
            PlSqlGrammar.CREATE_VIEW, PlSqlGrammar.CREATE_MATERIALIZED_VIEW -> UnitKind.VIEW.also {
                // The dictionary's view, else a VIEW entity from the source, which wins over the UNKNOWN_RELATION that a
                // reference to it creates with the same key.
                val relation = dictionaryView(written, schema)
                val key = relation?.let { RelationResolver.dictionaryKey(it) } ?: sourceObject(written, EntityKind.VIEW)
                entityKey = key
                if (key != null) view = ViewScope(key, relation, node, node.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION))
            }
            PlSqlGrammar.ANONYMOUS_BLOCK -> UnitKind.ANONYMOUS_BLOCK
            else -> UnitKind.SQL_STATEMENT
        }
        val index = addUnit(UnitFact(kind, displayName, entityKey, span, schema), partial)
        if (kind == UnitKind.PACKAGE_BODY || kind == UnitKind.TYPE_BODY || kind == UnitKind.TRIGGER || kind == UnitKind.VIEW && entityKey != null) {
            // Package bodies, type bodies, triggers and views are not in the project index; their definition comes from the AST.
            occurrences += OccurrenceFact(index, entityKey, null, span, writtenText(unitName ?: node), entityKey,
                setOf(Role.DEFINITION), status(Status.RESOLVED, partial), Origin.STATIC, null)
        }
        val enclosingPackage = if (kind == UnitKind.PACKAGE || kind == UnitKind.PACKAGE_BODY) {
            unitName?.getChildren(PlSqlGrammar.IDENTIFIER_NAME)?.map { it.tokenOriginalValue }?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val bodyNames = if (kind == UnitKind.PACKAGE_BODY) {
            context.symbolTable.getScopeFor(node)?.symbols.orEmpty()
                .filter { it.kind == Symbol.Kind.VARIABLE || it.kind == Symbol.Kind.CURSOR || it.kind == Symbol.Kind.TYPE }
                .mapNotNull { symbol -> symbol.node?.let { normalizeIdentifier(it.tokenOriginalValue) } }.toSet()
        } else {
            emptySet()
        }
        walk(node, Context(index, partial, schema, entityKey, trigger, enclosingPackage, bodyNames))
        view = null
    }

    private fun subprogramAt(node: AstNode): String? = catalog.subprogramAt(path, node.token.line, node.token.column)

    /** A schema object named in its own CREATE statement (the entity may also come from the project index). */
    private fun sourceObject(written: List<String>, kind: EntityKind): String? {
        if (written.isEmpty()) return null
        val (schema, rest) = catalog.schemaAndName(path, written)
        val key = dbKey(schema, *rest.toTypedArray())
        entities += EntityFact(key, kind, schema, rest.last(), null, key.removePrefix("db:"), external = false, precedence = Precedence.SOURCE)
        return key
    }

    /** The dictionary relation of a view written in the sources, or null when the dictionary does not find it. */
    private fun dictionaryView(written: List<String>, schema: String?): ProjectExternalRelation? {
        if (written.isEmpty() || schema == null) return null
        val lookup = dictionary.metadata.findRelation(RelationResolver.identifier(schema), RelationResolver.identifier(written.last()))
        if (lookup.status != ProjectExternalLookup.Status.FOUND) return null
        val relation = lookup.relation.get()
        resolver.addDictionaryRelation(relation)
        return relation
    }

    /**
     * A view being walked: its entity, its dictionary relation (null for a view only the sources know) and its query,
     * whose statement gives the column definitions.
     */
    private class ViewScope(val key: String, val relation: ProjectExternalRelation?, val create: AstNode, val query: AstNode?)

    private var view: ViewScope? = null

    /**
     * The definition sites of the select list of [query]'s first query block (parentheses unwrapped): aliases, column
     * names, and the columns a `*` expands to, following an inline view into its own select list. Returns the star
     * items of this list.
     */
    private fun selectSites(statement: Statement, query: AstNode, sites: MutableMap<String, Pair<AstNode, Set<Role>>>): List<AstNode> {
        var block = query.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK)
        while (block != null && block.firstChild.type == PlSqlPunctuator.LPARENTHESIS) {
            block = block.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)?.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK)
        }
        val stars = ArrayList<AstNode>()
        for (item in block?.getChildren(DmlGrammar.SELECT_COLUMN).orEmpty()) {
            val name = itemNameNode(item)
            if (name != null) {
                sites.putIfAbsent(normalizeIdentifier(name.tokenOriginalValue), name to VIEW_COLUMN_DEFINITION)
                continue
            }
            val expression = item.firstChild
            val sources = starSources(statement, item) ?: continue
            stars += expression
            for (source in sources) {
                val expanded = source.target?.resolved?.relation?.takeIf { it.isColumnsComplete }
                val inline = source.node.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)
                when {
                    expanded != null -> for (field in expanded.rowFields) sites.putIfAbsent(field.name.exact(), expression to VIEW_STAR_DEFINITION)
                    inline != null -> selectSites(statement, inline, sites)
                }
            }
        }
        return stars
    }

    /** The node naming a select item: its alias, else the last name of a column reference; null for anything else. */
    private fun itemNameNode(item: AstNode): AstNode? {
        val alias = item.children.drop(1).firstOrNull { it.type == PlSqlGrammar.IDENTIFIER_NAME || it.type == PlSqlKeyword.BEGIN || it.type == PlSqlKeyword.END }
        if (alias != null) return alias
        val expression = item.firstChild
        if (expression.type != PlSqlGrammar.VARIABLE_NAME && expression.type != PlSqlGrammar.MEMBER_EXPRESSION) return null
        return expression.getLastChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) ?: expression.getFirstDescendantOrNull(PlSqlGrammar.IDENTIFIER_NAME)
    }

    /**
     * The `DEFINITION` of every dictionary column of the view, where the `CREATE VIEW` text names it: the column list,
     * matched by name; otherwise the first query block of the main query (the branch that names the columns of a
     * compound query), matched by name: an alias, a column reference's name, or a `*` (`DEFINITION,IMPLICIT`) for the
     * columns it expands to, or the sites inside the inline view it expands. A single star of the list also takes every
     * column still without a site. A column with no site gets a `VIEW_COLUMN_UNMATCHED` note.
     *
     * A column with a site also gets its lineage: a `VIEW_BASE` relation to every column its value is computed from
     * ([columnAt], [columnByName]), or a `VIEW_LINEAGE_UNKNOWN` note when its source cannot be followed.
     */
    private fun viewColumns(statement: Statement, scope: ViewScope) {
        val relation = scope.relation ?: return
        val columns = relation.rowFields.sortedBy { it.position.asInt }
        val sites = HashMap<String, Pair<AstNode, Set<Role>>>()
        val list = scope.create.getChildren(PlSqlGrammar.VIEW_COLUMN_DEFINITION, PlSqlGrammar.MATERIALIZED_VIEW_COLUMN_DEFINITION)
            .mapNotNull { it.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) }
        val listed = list.map { normalizeIdentifier(it.tokenOriginalValue) }
        if (list.isNotEmpty()) {
            // The names of the list are the view's column names; the database may disagree with the file.
            list.forEachIndexed { index, name -> sites.putIfAbsent(listed[index], name to VIEW_COLUMN_DEFINITION) }
        } else {
            val stars = scope.query?.let { selectSites(statement, it, sites) }.orEmpty()
            // Every column no other item names can only come from a single star (TABLE(...), incomplete columns).
            stars.singleOrNull()?.let { star -> columns.forEach { sites.putIfAbsent(it.name.exact(), star to VIEW_STAR_DEFINITION) } }
        }
        for (column in columns) {
            val name = column.name.exact()
            val (site, roles) = sites[name] ?: run {
                coverage += CoverageFact("VIEW_COLUMN_UNMATCHED", "${scope.key}: column $name has no site in the CREATE VIEW text")
                continue
            }
            val columnKey = scope.key + "." + name
            occurrences += OccurrenceFact(statement.context.unitIndex, scope.key, null, spanOf(site), writtenText(site), columnKey,
                roles, status(Status.RESOLVED, statement.context.partial), Origin.STATIC, null)
            val query = scope.query ?: continue
            val bases = if (list.isNotEmpty()) columnAt(statement, query, listed.indexOf(name)) else columnByName(statement, query, name)
            if (bases == null) {
                coverage += CoverageFact("VIEW_LINEAGE_UNKNOWN", "${scope.key}: column $name comes from a source whose columns cannot be followed")
            } else {
                for (base in bases) if (base != columnKey) relations += RelationFact(columnKey, RelationKind.VIEW_BASE, base)
            }
        }
    }

    // ---- view column lineage ------------------------------------------------------------------------------------

    /**
     * One position of a query block's select list with stars expanded: an item ([Item]), a column of a complete
     * relation a star covers ([Star]), a column of an inline view or `WITH` subquery a star covers ([Inner]), a column
     * without a base that a star covers (`DUAL`, a `WITH … AS (VALUES …)`), or a star over columns that cannot be
     * listed ([Opaque]), whose width is unknown. [name] is the column name the position gives, null for an expression
     * without alias.
     */
    private sealed class Slot(val name: String?) {
        class Item(name: String?, val column: AstNode) : Slot(name)
        class Star(name: String, val key: String) : Slot(name)
        class Inner(name: String?, val query: AstNode, val position: Int) : Slot(name)
        class Constant(name: String) : Slot(name)
        object Opaque : Slot(null)
    }

    /** The query blocks of a compound query, in order, parenthesized branches flattened. */
    private fun branches(query: AstNode): List<AstNode> = query.getChildren(DmlGrammar.QUERY_BLOCK).flatMap { block ->
        if (block.firstChild.type == PlSqlPunctuator.LPARENTHESIS) block.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)?.let { branches(it) }.orEmpty()
        else listOf(block)
    }

    /** The select list of [block] with its stars expanded, by position. */
    private fun slots(statement: Statement, block: AstNode): List<Slot> = statement.slots.getOrPut(block) {
        val result = ArrayList<Slot>()
        for (item in block.getChildren(DmlGrammar.SELECT_COLUMN)) {
            val sources = starSources(statement, item)
            if (sources == null) {
                result += Slot.Item(itemNameNode(item)?.let { normalizeIdentifier(it.tokenOriginalValue) }, item)
                continue
            }
            for (source in sources) {
                val relation = source.target?.resolved?.relation?.takeIf { it.isColumnsComplete }
                when {
                    relation != null -> for (field in relation.rowFields.sortedBy { it.position.asInt }) {
                        result += Slot.Star(field.name.exact(), source.target.resolved.entityKey + "." + field.name.exact())
                    }
                    source.query != null -> result += innerSlots(statement, source.query, source.listed)
                    source.listed != null -> source.listed.forEach { result += Slot.Constant(it) }
                    else -> result += Slot.Opaque
                }
            }
        }
        result
    }

    /** The columns of an inline view or `WITH` subquery a star covers: its column list, else its first branch's names. */
    private fun innerSlots(statement: Statement, query: AstNode, listed: List<String>?): List<Slot> {
        if (listed != null) return listed.mapIndexed { index, name -> Slot.Inner(name, query, index) }
        val inner = slots(statement, branches(query).firstOrNull() ?: return listOf(Slot.Opaque))
        if (inner.any { it is Slot.Opaque }) return listOf(Slot.Opaque)
        return inner.mapIndexed { index, slot -> Slot.Inner(slot.name, query, index) }
    }

    /**
     * The base columns of the column named [name] of [query]: found by name in its first branch, then by position in
     * every branch of a compound query. Null when it cannot be followed (no such name, or a branch whose select list
     * cannot be expanded to that position).
     */
    private fun columnByName(statement: Statement, query: AstNode, name: String): Set<String>? = guarded(statement, query, "n:$name") {
        val branches = branches(query)
        val first = branches.firstOrNull()?.let { slots(statement, it) }
        val index = first?.indexOfFirst { it.name == name } ?: -1
        when {
            first == null || index < 0 -> null
            branches.size == 1 -> bases(statement, first[index])
            first.subList(0, index).any { it is Slot.Opaque } -> null
            else -> columnAt(statement, query, index)
        }
    }

    /** The base columns of the column at [position] of [query]: the union over every branch; null when a branch cannot tell. */
    private fun columnAt(statement: Statement, query: AstNode, position: Int): Set<String>? {
        if (position < 0) return emptySet()
        return guarded(statement, query, "p:$position") {
            val result = HashSet<String>()
            for (branch in branches(query)) {
                val slots = slots(statement, branch)
                if (slots.size <= position || slots.subList(0, position + 1).any { it is Slot.Opaque }) return@guarded null
                result += bases(statement, slots[position]) ?: return@guarded null
            }
            result
        }
    }

    /** A recursive `WITH` reaches the column it is computing again: that path adds nothing. */
    private fun guarded(statement: Statement, query: AstNode, column: String, body: () -> Set<String>?): Set<String>? {
        val active = statement.following.getOrPut(query) { HashSet() }
        if (!active.add(column)) return emptySet()
        try {
            return body()
        } finally {
            active.remove(column)
        }
    }

    private fun bases(statement: Statement, slot: Slot): Set<String>? = when (slot) {
        is Slot.Item -> itemBases(statement, slot.column)
        is Slot.Star -> setOf(slot.key)
        is Slot.Inner -> columnAt(statement, slot.query, slot.position)
        is Slot.Constant -> emptySet()
        Slot.Opaque -> null
    }

    /**
     * Every column read inside a select item, at any depth (a `CASE` condition, the arguments, `PARTITION BY` and
     * `ORDER BY` of a function, a scalar subquery's `WHERE`), plus the base columns of the names that refer to a
     * column of an inline view or `WITH` subquery. Ambiguous and unresolved reads give nothing. Null when a name refers
     * to a source whose columns cannot be followed.
     */
    private fun itemBases(statement: Statement, item: AstNode): Set<String>? {
        val result = HashSet<String>()
        var known = true
        fun visit(node: AstNode) {
            val reads = statement.columnReads[node]
            if (reads != null) {
                for (read in reads) if (read.entityKey != null && read.status in LINEAGE_STATUSES) result += read.entityKey
                return
            }
            if (node.type == PlSqlGrammar.ANCHORED_DATATYPE || node.type == PlSqlGrammar.CUSTOM_DATATYPE) return
            if ((node.type == PlSqlGrammar.VARIABLE_NAME || node.type == PlSqlGrammar.MEMBER_EXPRESSION) && isValueName(node) && nameSegments(node) != null) {
                val followed = nameLineage(statement, node)
                if (followed == null) known = false else result += followed
                return
            }
            node.children.forEach(::visit)
        }
        visit(item)
        return if (known) result else null
    }

    /**
     * The base columns of a name without a column read: a column of an inline view or `WITH` subquery, found as
     * [qualifiedColumn] and [unqualifiedColumn] find a source. Nothing for a pseudocolumn, a PL/SQL name, or a column
     * of a named relation; null for a column of a source whose columns cannot be followed (`TABLE(...)`).
     */
    private fun nameLineage(statement: Statement, name: AstNode): Set<String>? {
        val segments = nameSegments(name)?.map { normalizeIdentifier(it) } ?: return emptySet()
        val column = segments.last()
        if (segments.size > 2 || column in SQL_PSEUDOCOLUMNS || segments.size == 2 && column in PSEUDOCOLUMNS) return emptySet()
        var scope = sqlScope(name)
        while (scope != null) {
            val sources = scopeSources(statement, scope, name, qualified = segments.size == 2)
            if (segments.size == 2) {
                sources.firstOrNull { it.qualifier == segments[0] }?.let { return follow(statement, it, column) }
            } else if (sources.isNotEmpty()) {
                val known = sources.map { has(it, column) }
                val matches = sources.filterIndexed { index, _ -> known[index] == true }
                val open = sources.filterIndexed { index, _ -> known[index] == null }
                when {
                    matches.size == 1 -> return follow(statement, matches.single(), column)
                    matches.size > 1 -> return emptySet()
                    open.size == 1 -> return follow(statement, open.single(), column)
                    open.size > 1 -> return emptySet()
                }
            }
            scope = sqlScope(scope)
        }
        return emptySet()
    }

    /** The base columns of [column] of [source]: nothing for a named relation (its reads are occurrences) or `DUAL`. */
    private fun follow(statement: Statement, source: ColumnSource, column: String): Set<String>? = when {
        source.target != null -> emptySet()
        source.query != null -> source.listed?.let { columnAt(statement, source.query, it.indexOf(column)) } ?: columnByName(statement, source.query, column)
        source.viewLike -> emptySet()
        else -> null
    }

    /**
     * The events of a trigger, its `TRIGGER_ON` relation, and for a DML trigger the scope of its correlation names
     * (`NEW` and `OLD`, or their `REFERENCING` aliases); null for a system trigger.
     */
    private fun trigger(node: AstNode, key: String, schema: String?): TriggerScope? {
        val system = node.getFirstChildOrNull(PlSqlGrammar.SYSTEM_TRIGGER)
        if (system != null) {
            systemEvents(system, key)
            return null
        }
        val dml = node.getFirstChildOrNull(PlSqlGrammar.SIMPLE_DML_TRIGGER, PlSqlGrammar.INSTEAD_OF_DML_TRIGGER, PlSqlGrammar.COMPOUND_DML_TRIGGER)
            ?: return null
        val target = dml.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME)
        val relation = if (target != null && !dml.hasDirectChildren(PlSqlKeyword.NESTED)) {
            val written = names(target)
            resolver.resolve(WrittenRelation(written.getOrNull(written.size - 2), written.last(), null), schema).also {
                relations += RelationFact(key, RelationKind.TRIGGER_ON, it.entityKey)
            }
        } else {
            null
        }
        dmlEvents(dml, key, relation)
        val referencing = dml.getFirstChildOrNull(PlSqlGrammar.REFERENCING_CLAUSE)
        fun aliases(keyword: PlSqlKeyword): Set<String> {
            val children = referencing?.children.orEmpty()
            return children.withIndex().filter { it.value.type == keyword }
                .mapNotNull { (i, _) -> children.drop(i + 1).firstOrNull { it.type == PlSqlGrammar.IDENTIFIER_NAME } }
                .map { normalizeIdentifier(it.tokenOriginalValue) }.toSet()
        }
        val whenExpression = dml.children.indexOfFirst { it.type == PlSqlKeyword.WHEN }.takeIf { it >= 0 }?.let { dml.children.getOrNull(it + 2) }
        return TriggerScope(key, aliases(PlSqlKeyword.NEW).ifEmpty { setOf("NEW") }, aliases(PlSqlKeyword.OLD).ifEmpty { setOf("OLD") }, relation, whenExpression)
    }

    /** The events of a DML trigger; an `UPDATE OF` column resolves on the trigger's relation. */
    private fun dmlEvents(dml: AstNode, key: String, relation: ResolvedRelation?) {
        val row = dml.hasDirectChildren(PlSqlKeyword.ROW)
        val (timing, level) = when (dml.type) {
            PlSqlGrammar.COMPOUND_DML_TRIGGER -> TriggerTiming.COMPOUND to null
            PlSqlGrammar.INSTEAD_OF_DML_TRIGGER -> TriggerTiming.INSTEAD_OF to TriggerLevel.ROW
            else -> (if (dml.firstChild.type == PlSqlKeyword.BEFORE) TriggerTiming.BEFORE else TriggerTiming.AFTER) to
                (if (row) TriggerLevel.ROW else TriggerLevel.STATEMENT)
        }
        val events = if (dml.type == PlSqlGrammar.INSTEAD_OF_DML_TRIGGER) {
            dml.children.takeWhile { it.type != PlSqlKeyword.ON }.filter { it.type in DML_EVENTS }.map { it.tokenValue.uppercase() to emptyList<AstNode>() }
        } else {
            dml.getChildren(PlSqlGrammar.DML_EVENT_CLAUSE).map { it.firstChild.tokenValue.uppercase() to it.getChildren(PlSqlGrammar.IDENTIFIER_NAME) }
        }
        for ((event, columns) in events) {
            if (columns.isEmpty()) {
                triggerEvents += TriggerEventFact(key, timing, level, event, null)
                continue
            }
            for (column in columns) {
                val name = normalizeIdentifier(column.tokenOriginalValue)
                val resolved = relation?.let { resolver.resolveColumn(it, name) }
                val columnKey = resolved?.takeIf { it.status != Status.UNRESOLVED }?.entityKey
                if (columnKey == null) coverage += CoverageFact("TRIGGER_COLUMN_UNRESOLVED", "$key: UPDATE OF column $name is not a column of the trigger's relation")
                triggerEvents += TriggerEventFact(key, timing, level, event, columnKey)
            }
        }
    }

    /** The DDL or database events of a system trigger: each database event carries its own timing. */
    private fun systemEvents(system: AstNode, key: String) {
        val sharedTiming = when (system.firstChild.type) {
            PlSqlKeyword.BEFORE -> TriggerTiming.BEFORE
            PlSqlKeyword.AFTER -> TriggerTiming.AFTER
            PlSqlKeyword.INSTEAD -> TriggerTiming.INSTEAD_OF
            else -> null
        }
        for (ddl in system.getChildren(PlSqlGrammar.DDL_EVENT)) {
            triggerEvents += TriggerEventFact(key, sharedTiming ?: TriggerTiming.AFTER, null, tokensOf(ddl).joinToString(" ") { it.value.uppercase() }, null)
        }
        for (database in system.getChildren(PlSqlGrammar.DATABASE_EVENT)) {
            val tokens = tokensOf(database).map { it.value.uppercase() }
            val timing = if (tokens.first() == "BEFORE") TriggerTiming.BEFORE else TriggerTiming.AFTER
            triggerEvents += TriggerEventFact(key, timing, null, tokens.drop(1).joinToString(" "), null)
        }
    }

    // ---- walk ---------------------------------------------------------------------------------------------------

    private fun walk(node: AstNode, context: Context) {
        var current = context
        when (node.type) {
            PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION,
            PlSqlGrammar.CREATE_PROCEDURE, PlSqlGrammar.CREATE_FUNCTION -> subprogramAt(node)?.let { current = context.inside(it) }
            DmlGrammar.SELECT_EXPRESSION, DmlGrammar.INSERT_EXPRESSION, DmlGrammar.UPDATE_EXPRESSION,
            DmlGrammar.DELETE_EXPRESSION, DmlGrammar.MERGE_EXPRESSION, TclGrammar.LOCK_TABLE_EXPRESSION -> {
                statement(node, context)
                return
            }
            DmlGrammar.EXPLAIN_PLAN -> return
            // The SQL text is not parsed; calls that build it are still PL/SQL calls, found below.
            PlSqlGrammar.EXECUTE_IMMEDIATE_STATEMENT -> dynamic(node, after(node, PlSqlKeyword.IMMEDIATE), context)
            PlSqlGrammar.OPEN_FOR_STATEMENT -> if (!node.hasDirectChildren(DmlGrammar.SELECT_EXPRESSION)) {
                dynamic(node, after(node, PlSqlKeyword.FOR), context)
            }
            PlSqlGrammar.METHOD_CALL -> {
                dbmsSqlParse(node)?.let { dynamic(node, it, context) }
                callSite(node, context, null)
            }
            PlSqlGrammar.CALL_STATEMENT -> node.children.firstOrNull { it.type != PlSqlGrammar.LABELS && it.type != PlSqlPunctuator.SEMICOLON }
                ?.takeIf { it.type != PlSqlGrammar.METHOD_CALL }
                ?.let { callSite(it, context, null) }
            PlSqlGrammar.ANCHORED_DATATYPE, PlSqlGrammar.CUSTOM_DATATYPE -> {
                typeUse(node, context, null)
                return
            }
            PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.MEMBER_EXPRESSION -> if (isAggregateImplementation(node)) {
                aggregateType(node, context)
            } else if (isValueName(node) && !correlationColumn(node, context, null)) {
                valueReference(node, context, null, sql = false)
            }
            PlSqlGrammar.UNIT_NAME -> if (isSupertype(node)) supertypeUse(node, context)
            // In a PL/SQL expression (static SQL is handled with its statement).
            in KEYWORD_FUNCTIONS -> sqlFunctionKeyword(node.firstChild, context, null)
        }
        for (child in node.children) walk(child, current)
    }

    private fun after(node: AstNode, keyword: PlSqlKeyword): AstNode? {
        val children = node.children
        val index = children.indexOfFirst { it.type == keyword }
        return if (index >= 0) children.getOrNull(index + 1) else null
    }

    private fun dbmsSqlParse(node: AstNode): AstNode? {
        val callee = node.firstChild
        if (callee.type == PlSqlGrammar.ARGUMENTS) return null
        val name = tokensOf(callee).joinToString("") { it.value }.uppercase()
        if (name != "DBMS_SQL.PARSE" && name != "SYS.DBMS_SQL.PARSE") return null
        val arguments = node.getFirstChildOrNull(PlSqlGrammar.ARGUMENTS)?.getChildren(PlSqlGrammar.ARGUMENT).orEmpty()
        val named = arguments.firstOrNull { argument ->
            val tokens = tokensOf(argument)
            tokens.size > 2 && tokens[0].value.equals("STATEMENT", ignoreCase = true) && tokens[1].value == "=>"
        }
        return (named ?: arguments.getOrNull(1))?.lastChild ?: node
    }

    /**
     * A column of the trigger's relation through a correlation name: `:new.col` and `:old.col` in the body (in PL/SQL
     * or in static SQL), `new.col` without the colon in the `WHEN` clause. `:new` columns take the roles of any value
     * (assignment and `INTO` targets, `OUT` arguments of resolved calls); `:old` columns are read only. The occurrence
     * spans the whole reference, as other column occurrences span their qualified name. Pseudocolumns give nothing.
     */
    private fun correlationColumn(name: AstNode, context: Context, statementId: Int?): Boolean {
        val scope = context.trigger ?: return false
        if (name.type != PlSqlGrammar.MEMBER_EXPRESSION) return false
        val parts = name.children.filter { it.type != PlSqlPunctuator.DOT }
        if (parts.size != 2 || parts[0].type != PlSqlGrammar.VARIABLE_NAME || parts[1].type != PlSqlGrammar.IDENTIFIER_NAME) return false
        val host = parts[0].getFirstChildOrNull(PlSqlGrammar.HOST_AND_INDICATOR_VARIABLE)
        val correlation = if (host != null) {
            host.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.tokenOriginalValue ?: return false
        } else {
            // Without a colon, only in the WHEN clause.
            val inWhen = scope.whenExpression != null && generateSequence(name) { it.parentOrNull }.any { it === scope.whenExpression }
            if (!inWhen) return false
            parts[0].tokenOriginalValue
        }
        val normalized = normalizeIdentifier(correlation)
        val isNew = normalized in scope.newNames
        if (!isNew && normalized !in scope.oldNames) return false
        val target = scope.target ?: return false
        val column = normalizeIdentifier(parts[1].tokenOriginalValue)
        // `:new.rowid`: captured like `t.rowid` in SQL, with no occurrence.
        if (column in SQL_PSEUDOCOLUMNS) return true
        val roles = if (isNew) valueRoles(name, listOf(correlation, column)) else setOf(Role.READ)
        val resolved = resolver.resolveColumn(target, column)
        occurrences += OccurrenceFact(context.unitIndex, scope.key, statementId, spanOf(name), writtenText(name), resolved.entityKey,
            roles, status(resolved.status, context.partial), Origin.STATIC, resolved.reason)
        return true
    }

    private fun status(normal: Status, partial: Boolean) = if (partial) Status.PARTIAL_SOURCE else normal

    private fun dynamic(node: AstNode, sql: AstNode?, context: Context) {
        val id = statements.size
        statements += StatementFact(id, context.unitIndex, context.container, StatementKind.DYNAMIC, spanOf(node), sha256(normalizedSql(node)))
        val literal = sql != null && sql.type == PlSqlGrammar.LITERAL && sql.numberOfChildren == 1 && sql.firstChild.type == PlSqlGrammar.CHARACTER_LITERAL
        val written = when (node.type) {
            PlSqlGrammar.EXECUTE_IMMEDIATE_STATEMENT -> "EXECUTE IMMEDIATE"
            PlSqlGrammar.OPEN_FOR_STATEMENT -> "OPEN FOR"
            else -> "DBMS_SQL.PARSE"
        }
        occurrences += OccurrenceFact(context.unitIndex, context.container, id, spanOf(sql ?: node), written, null, emptySet(),
            status(Status.DYNAMIC, context.partial), Origin.STATIC, if (literal) "LITERAL" else "EXPRESSION")
    }

    // ---- calls --------------------------------------------------------------------------------------------------

    /** The segments of a plain dotted name as written, or null for anything else (a host variable, `f(x).g`, `c%rowcount`). */
    private fun nameSegments(callee: AstNode): List<String>? {
        val parts = when (callee.type) {
            PlSqlGrammar.VARIABLE_NAME -> listOf(callee)
            PlSqlGrammar.MEMBER_EXPRESSION -> callee.children.filter { it.type != PlSqlPunctuator.DOT }
            else -> return null
        }
        if (parts.any { it.type is PlSqlPunctuator || tokensOf(it).size != 1 }) return null
        return parts.map { it.tokenOriginalValue }
    }

    /**
     * One `CALL` occurrence for a `METHOD_CALL` or a `CALL_STATEMENT` without arguments ([node] is then the name).
     * Names the legacy symbol table binds to a local value or type are not calls; a nested subprogram is
     * `LOCAL_SUBPROGRAM` (local subprograms are not entities).
     */
    private fun callSite(node: AstNode, context: Context, statementId: Int?) {
        val isMethodCall = node.type == PlSqlGrammar.METHOD_CALL
        val callee = if (isMethodCall) node.firstChild else node
        val kind = if (!isMethodCall || node.parent.type == PlSqlGrammar.CALL_STATEMENT) ProjectCallKind.PROCEDURE else ProjectCallKind.FUNCTION
        val actuals = if (isMethodCall) node.getFirstChildOrNull(PlSqlGrammar.ARGUMENTS)?.getChildren(PlSqlGrammar.ARGUMENT).orEmpty() else emptyList()
        val segments = nameSegments(callee) ?: run {
            coverage += CoverageFact("CALL_NOT_A_NAME", "Call at line ${callee.tokenLine} has a callee that is not a plain name")
            return
        }
        val first = if (callee.type == PlSqlGrammar.VARIABLE_NAME) callee else callee.firstChild
        val symbol = (first as? SemanticAstNode)?.symbol
        val arguments = actuals.map { argument ->
            val children = argument.children
            val named = children.size >= 3 && children[1].tokenValue == "=>"
            Triple(if (named) children[0].tokenOriginalValue else null, if (named) children.last() else argument, argument)
        }
        val outcome = when {
            // A type of the package specification is a constructor; local values, cursors and types are not calls, a
            // variable or cursor of the package specification (in its own file) is a member.
            symbol != null && symbol.kind in NOT_CALLABLE && (isLocalDeclaration(symbol) || symbol.kind == Symbol.Kind.PARAMETER) -> return
            symbol != null && (symbol.kind == Symbol.Kind.PROCEDURE || symbol.kind == Symbol.Kind.FUNCTION) && isLocalSubprogram(symbol) ->
                CallOutcome.Call(Status.UNRESOLVED, null, "LOCAL_SUBPROGRAM")
            else -> calls.resolve(segments, kind, arguments.map { WrittenArgument(it.first) }, context.schema, context.enclosingPackage)
        }
        if (outcome is CallOutcome.Value) {
            // `pkg.g_tab(i)`, `pkg.g_tab.exists(i)`, `pkg.g_tab.delete`, `pkg.c_pending(1)`: a package member; in SQL,
            // not when the first segment is a qualifier of the statement.
            if (statementId != null && isSqlQualified(callee, segments)) return
            valueOccurrence(spanOf(callee), writtenText(callee), context, statementId, outcome.status, outcome.entityKey, outcome.reason,
                outcome.candidateKeys, memberRoles(callee, segments, outcome.kind))
            return
        }
        if (outcome !is CallOutcome.Call || symbol?.kind == Symbol.Kind.VARIABLE || symbol?.kind == Symbol.Kind.CURSOR) return
        if (outcome.status == Status.RESOLVED) {
            arguments.forEachIndexed { index, (_, expression, _) -> outcome.modes[index + 1]?.let { argumentModes[expression] = it } }
        }
        val argumentFacts = arguments.mapIndexed { index, (label, expression, _) ->
            CallArgumentFact(index + 1, label?.let { normalizeIdentifier(it) },
                if (outcome.status == Status.RESOLVED) outcome.bindings[index + 1] else null, spanOf(expression))
        }
        occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, spanOf(callee), writtenText(callee), outcome.entityKey,
            setOf(Role.CALL), status(outcome.status, context.partial), Origin.STATIC, outcome.reason, outcome.candidateKeys, argumentFacts)
    }

    // ---- value references ----------------------------------------------------------------------------------------

    /**
     * Whether [node] is the outermost plain name of an expression: not a segment of a longer name, not the callee of a
     * call (handled by [callSite]), not the member after a call (`pkg.g_tab(i).f`).
     */
    private fun isValueName(node: AstNode): Boolean {
        val parent = node.parentOrNull ?: return false
        if (parent.type == PlSqlGrammar.MEMBER_EXPRESSION || parent.type == PlSqlGrammar.CALL_STATEMENT) return false
        if (parent.type == PlSqlGrammar.METHOD_CALL && parent.firstChild === node) return false
        return node.previousSiblingOrNull?.type != PlSqlPunctuator.DOT
    }

    /**
     * A value reference: a sequence pseudocolumn; else, unless the legacy table captures the first segment
     * lexically, a package value, exception or cursor, a project function called without parentheses, or a missing
     * member of a project package. A cursor attribute (`pkg.c%isopen`) is a reference to the name before `%`. In SQL
     * ([sql]) only qualified names whose first segment is not an SQL qualifier are read.
     */
    private fun valueReference(name: AstNode, context: Context, statementId: Int?, sql: Boolean) {
        val attribute = name.type == PlSqlGrammar.MEMBER_EXPRESSION && name.hasDirectChildren(PlSqlPunctuator.MOD)
        val parts = if (attribute) name.children.takeWhile { it.type != PlSqlPunctuator.MOD } else listOf(name)
        val segments = (if (attribute) attributeName(name, parts) else nameSegments(name)) ?: return
        val span = spanOf(parts.first(), parts.last())
        val text = parts.joinToString("") { writtenText(it) }
        if (!attribute && name.type == PlSqlGrammar.MEMBER_EXPRESSION && segments.size in 2..3 && normalizeIdentifier(segments.last()) in PSEUDOCOLUMNS) {
            sequenceReference(name, segments, context, statementId)
            return
        }
        if (sql && (segments.size == 1 || isSqlQualified(name, segments))) return
        val first = if (name.type == PlSqlGrammar.VARIABLE_NAME) name else name.firstChild
        val symbol = (first as? SemanticAstNode)?.symbol
        if (symbol != null) {
            when (symbol.kind) {
                Symbol.Kind.PROCEDURE, Symbol.Kind.FUNCTION -> if (isLocalSubprogram(symbol)) {
                    if (segments.size == 1) callOccurrence(name, context, statementId, CallOutcome.Call(Status.UNRESOLVED, null, "LOCAL_SUBPROGRAM"))
                    return
                }
                Symbol.Kind.PACKAGE, Symbol.Kind.TRIGGER -> Unit
                else -> if (isLocalDeclaration(symbol)) return
            }
        }
        if (isOwnBodyMember(segments, context)) return
        val packageName = context.enclosingPackage?.let { qualifiedName(it) }
        val valueReadable = segments.size >= 2 || (packageName != null && (symbol == null || symbol.scope.type == PlSqlGrammar.CREATE_PACKAGE))
        if (valueReadable) {
            values.packageMember(segments, context.schema, packageName)?.let {
                valueOccurrence(span, text, context, statementId, it.status, it.entityKey, it.reason, it.candidateKeys,
                    if (attribute) setOf(Role.READ) else memberRoles(name, segments, it.kind))
                return
            }
        }
        val callable = segments.size >= 2 || symbol == null || symbol.kind == Symbol.Kind.FUNCTION || symbol.kind == Symbol.Kind.PROCEDURE
        if (callable) {
            when (val outcome = calls.resolve(segments, ProjectCallKind.FUNCTION, emptyList(), context.schema, context.enclosingPackage, withoutParentheses = true)) {
                is CallOutcome.Value -> {
                    valueOccurrence(span, text, context, statementId, outcome.status, outcome.entityKey, outcome.reason, outcome.candidateKeys,
                        if (attribute) setOf(Role.READ) else memberRoles(name, segments, outcome.kind))
                    return
                }
                // Only a name the project knows is a call: anything else may be a value outside the project. A name
                // before `%` is never a call.
                is CallOutcome.Call -> if (!attribute && outcome.reason != "NOT_IN_PROJECT" && !(outcome.reason in INCONCLUSIVE && outcome.entityKey == null)) {
                    callOccurrence(name, context, statementId, outcome)
                    return
                }
                // A member of a package or type a synonym names, kind unknown: a read of the owner.
                is CallOutcome.ExternalMember -> {
                    valueOccurrence(span, text, context, statementId, Status.UNRESOLVED, outcome.entityKey, "NOT_IN_PROJECT", emptyList(), setOf(Role.READ))
                    return
                }
                CallOutcome.NotACall -> Unit
            }
        }
        if (segments.size >= 2 && values.isPackageMember(segments, context.schema)) {
            valueOccurrence(span, text, context, statementId, Status.UNRESOLVED, null, "UNKNOWN_MEMBER", emptyList(), setOf(Role.READ))
        }
    }

    /** The segments of the name before `%` of a cursor attribute (`c%isopen`, `pkg.c%rowcount`), or null. */
    private fun attributeName(name: AstNode, parts: List<AstNode>): List<String>? {
        val attribute = name.children.lastOrNull()?.takeIf { name.children.indexOf(it) == parts.size + 1 } ?: return null
        if (normalizeIdentifier(attribute.tokenOriginalValue) !in CURSOR_ATTRIBUTES) return null
        val segments = parts.filter { it.type != PlSqlPunctuator.DOT }
        if (segments.isEmpty() || segments.any { it.type is PlSqlPunctuator || tokensOf(it).size != 1 }) return null
        return segments.map { it.tokenOriginalValue }
    }

    /** `pkg.v` or `s.pkg.v` inside the body of `pkg`, where `v` is declared in the body: captured lexically. */
    private fun isOwnBodyMember(segments: List<String>, context: Context): Boolean {
        val enclosing = context.enclosingPackage ?: return false
        if (context.bodyNames.isEmpty() || segments.size < 2) return false
        val names = segments.map { normalizeIdentifier(it) }
        val name = normalizeIdentifier(enclosing.last())
        val member = when {
            names[0] == name -> 1
            names.size >= 3 && names[1] == name && names[0] == context.schema -> 2
            else -> return false
        }
        return names[member] in context.bodyNames
    }

    private fun valueOccurrence(span: SourceSpan, written: String, context: Context, statementId: Int?, status: Status, entityKey: String?,
                                reason: String?, candidates: List<String>, roles: Set<Role>) {
        occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, span, written, entityKey,
            roles, status(status, context.partial), Origin.STATIC, reason, candidates)
    }

    /** A function called without parentheses: a `CALL` occurrence without actuals. */
    private fun callOccurrence(name: AstNode, context: Context, statementId: Int?, outcome: CallOutcome.Call) {
        occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, spanOf(name), writtenText(name), outcome.entityKey,
            setOf(Role.CALL), status(outcome.status, context.partial), Origin.STATIC, outcome.reason, outcome.candidateKeys)
    }

    /**
     * The roles of a package member: for an exception `RAISE` as the exception of a `RAISE` statement and `HANDLE`
     * as a name of an exception handler (the direct parent only), else `READ`; for a cursor `READ`; for a value
     * [valueRoles].
     */
    private fun memberRoles(name: AstNode, segments: List<String>, kind: MemberKind): Set<Role> = when (kind) {
        MemberKind.EXCEPTION -> when (name.parentOrNull?.type) {
            PlSqlGrammar.RAISE_STATEMENT -> setOf(Role.RAISE)
            PlSqlGrammar.EXCEPTION_HANDLER -> setOf(Role.HANDLE)
            else -> setOf(Role.READ)
        }
        MemberKind.CURSOR -> setOf(Role.READ)
        MemberKind.VALUE -> valueRoles(name, segments)
    }

    /**
     * The roles of a value: `WRITE` for an assignment target, an `INTO` target, an actual bound to an `OUT`
     * formal of a resolved call or a modifying collection method; `READ` and `WRITE` for an `IN OUT` formal; else `READ`.
     */
    private fun valueRoles(name: AstNode, segments: List<String>): Set<Role> {
        var expression = name
        name.parentOrNull?.takeIf { it.type == PlSqlGrammar.METHOD_CALL && it.firstChild === name }?.let { expression = it }
        while (true) {
            val parent = expression.parentOrNull ?: break
            if (parent.type != PlSqlGrammar.OBJECT_REFERENCE || parent.firstChild !== expression) break
            expression = parent
        }
        val parent = expression.parentOrNull
        if (parent != null) {
            if (parent.type == PlSqlGrammar.ASSIGNMENT_STATEMENT) {
                val assignment = parent.children.indexOfFirst { it.type == PlSqlPunctuator.ASSIGNMENT }
                if (parent.children.indexOf(expression) < assignment) return setOf(Role.WRITE)
            }
            if (parent.type == DmlGrammar.INTO_CLAUSE) return setOf(Role.WRITE)
            val mode = argumentModes[expression] ?: if (parent.type == PlSqlGrammar.ARGUMENT) argumentModes[parent] else null
            when (mode) {
                ProjectParameterMode.OUT -> return setOf(Role.WRITE)
                ProjectParameterMode.IN_OUT -> return setOf(Role.READ, Role.WRITE)
                else -> Unit
            }
        }
        if (segments.size >= 2 && normalizeIdentifier(segments.last()) in MODIFYING_METHODS) return setOf(Role.WRITE)
        return setOf(Role.READ)
    }

    /**
     * `seq.nextval`, `s.seq.currval`: a project sequence, else an external `SEQUENCE` entity, `db:S.SEQ` when
     * `S` is a schema of the run and `db:~.…` otherwise (the dictionary does not know sequences). Not a sequence when the
     * first segment is captured lexically, is an SQL qualifier, or the legacy table binds the whole name.
     */
    private fun sequenceReference(name: AstNode, segments: List<String>, context: Context, statementId: Int?) {
        val symbol = (name.firstChild as? SemanticAstNode)?.symbol
        if (symbol != null && symbol.kind != Symbol.Kind.PACKAGE && isLocalDeclaration(symbol)) return
        if (statementId != null && isSqlQualified(name, segments)) return
        if (projectAnalysis().resolveSequenceReference(name) == SequenceReferenceResolution.RESOLVED_NON_SEQUENCE) return
        val written = segments.dropLast(1)
        val children = name.children
        val lastDot = children.indexOfLast { it.type == PlSqlPunctuator.DOT }
        val nameParts = children.subList(0, lastDot)
        val text = nameParts.joinToString("") { writtenText(it) }
        val span = spanOf(nameParts.first(), nameParts.last())
        // A sequence the project does not declare may be named by a synonym: `syn_seq.nextval` reads the target.
        val target = if (values.sequence(written, context.schema) == null && written.size == 1) {
            synonyms.follow(normalizeIdentifier(written[0]), context.schema, SynonymResolver.SEQUENCES)
        } else {
            null
        }
        val resolvedName = target?.let { SynonymResolver.written(it, emptyList()) } ?: written
        val declared = values.sequence(resolvedName, context.schema)
        val (key, status) = if (declared != null) {
            declared to Status.RESOLVED
        } else {
            val names = resolvedName.map { normalizeIdentifier(it) }
            val schemaQualified = names.size == 2 && (names[0] in runSchemas || target != null)
            val key = if (schemaQualified) dbKey(names[0], names[1]) else dbKey(UNKNOWN_SCHEMA, *names.toTypedArray())
            entities += EntityFact(key, EntityKind.SEQUENCE, if (schemaQualified) names[0] else null, names.last(), null, key.removePrefix("db:"),
                external = true, precedence = Precedence.MENTIONED)
            key to Status.UNVERIFIED
        }
        occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, span, text, key,
            setOf(Role.READ), status(status, context.partial), Origin.STATIC, null)
    }

    /** Whether the first segment of a name in SQL is an alias, relation name or WITH name of an enclosing SQL scope. */
    private fun isSqlQualified(name: AstNode, segments: List<String>): Boolean {
        if (segments.size < 2) return false
        val first = normalizeIdentifier(segments[0])
        var scope = sqlScope(name)
        while (scope != null) {
            if (first in qualifiersOf(scope)) return true
            scope = sqlScope(scope)
        }
        return false
    }

    private fun sqlScope(node: AstNode): AstNode? = node.getFirstAncestorOrNull(*SQL_SCOPES)

    /** The qualifiers [scope] owns directly (as zpa-core's `sqlScopeQualifiers`): not those of its nested scopes. */
    private fun qualifiersOf(scope: AstNode): Set<String> = scopeQualifiers.getOrPut(scope) {
        val result = HashSet<String>()
        for (node in scope.getDescendants(DmlGrammar.ALIAS, DmlGrammar.TABLE_REFERENCE, DmlGrammar.SUBQUERY_FACTORING_CLAUSE, DmlGrammar.INSERT_INTO_CLAUSE)) {
            if (sqlScope(node) !== scope) continue
            when (node.type) {
                DmlGrammar.ALIAS -> result += normalizeIdentifier(node.tokenOriginalValue)
                DmlGrammar.TABLE_REFERENCE -> result += written(node).name
                DmlGrammar.SUBQUERY_FACTORING_CLAUSE -> result += normalizeIdentifier(node.firstChild.tokenOriginalValue)
                else -> node.getChildren(PlSqlGrammar.IDENTIFIER_NAME).forEach { result += normalizeIdentifier(it.tokenOriginalValue) }
            }
        }
        if (scope.type == DmlGrammar.MERGE_EXPRESSION) scope.getChildren(PlSqlGrammar.IDENTIFIER_NAME).forEach { result += normalizeIdentifier(it.tokenOriginalValue) }
        result
    }

    // ---- type references ----------------------------------------------------------------------------------------

    /**
     * One `TYPE_USE` occurrence for the name of an `ANCHORED_DATATYPE` (`name%TYPE`, `name%ROWTYPE`) or a
     * `CUSTOM_DATATYPE`. A one-segment name is a `VARIABLE_NAME`, a longer one a `MEMBER_EXPRESSION`. A first segment
     * the legacy table binds to a local declaration is counted (`LOCAL_ANCHOR`), not indexed; built-ins give nothing.
     * A binding to the declaration the datatype belongs to is ignored: `comentario comentario.texto%type` names the
     * table, the item is not visible in its own declaration.
     */
    private fun typeUse(node: AstNode, context: Context, statementId: Int?) {
        val name = node.firstChild
        val segments = nameSegments(name) ?: run {
            coverage += CoverageFact("TYPE_NOT_A_NAME", "Datatype at line ${name.tokenLine} is not a plain name")
            return
        }
        val first = if (name.type == PlSqlGrammar.VARIABLE_NAME) name else name.firstChild
        val symbol = (first as? SemanticAstNode)?.symbol?.takeUnless { isOwnDeclaration(it, node) }
        if (symbol != null && isLocalDeclaration(symbol) ||
            symbol == null && node.type == PlSqlGrammar.CUSTOM_DATATYPE && segments.size == 1 && isLocalRefCursorType(node, normalizeIdentifier(segments[0]))) {
            localTypeReferences++
            return
        }
        val outcome = if (node.type == PlSqlGrammar.ANCHORED_DATATYPE) {
            val enclosingMember = symbol != null && symbol.scope.type == PlSqlGrammar.CREATE_PACKAGE
            types.anchored(segments, node.lastChild.type == PlSqlKeyword.ROWTYPE, context.schema, context.enclosingPackage, enclosingMember)
        } else {
            types.named(segments, context.schema, context.enclosingPackage) ?: return
        }
        occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, spanOf(name), writtenText(name), outcome.entityKey,
            setOf(Role.TYPE_USE), status(outcome.status, context.partial), Origin.STATIC, outcome.reason, outcome.candidateKeys)
    }

    /** The type after `AGGREGATE USING` of a user-defined aggregate function. */
    private fun isAggregateImplementation(node: AstNode): Boolean =
        node.previousSiblingOrNull?.type == PlSqlKeyword.USING && node.parentOrNull?.hasDirectChildren(PlSqlKeyword.AGGREGATE) == true

    /** `AGGREGATE USING t`: a `TYPE_USE` of the implementation type. */
    private fun aggregateType(name: AstNode, context: Context) {
        val segments = nameSegments(name) ?: return
        typeUseOf(name, segments, context)
    }

    /** The supertype of `CREATE TYPE t UNDER s`. */
    private fun isSupertype(node: AstNode): Boolean =
        node.previousSiblingOrNull?.type == PlSqlKeyword.UNDER && node.parentOrNull?.type == PlSqlGrammar.OBJECT_TYPE_DEFINITION

    /** `UNDER s`: a `TYPE_USE` of the supertype. */
    private fun supertypeUse(name: AstNode, context: Context) {
        val segments = name.getChildren(PlSqlGrammar.IDENTIFIER_NAME).map { it.tokenOriginalValue }.ifEmpty { return }
        typeUseOf(name, segments, context)
    }

    private fun typeUseOf(name: AstNode, segments: List<String>, context: Context) {
        val outcome = types.named(segments, context.schema, context.enclosingPackage) ?: return
        occurrences += OccurrenceFact(context.unitIndex, context.container, null, spanOf(name), writtenText(name), outcome.entityKey,
            setOf(Role.TYPE_USE), status(outcome.status, context.partial), Origin.STATIC, outcome.reason, outcome.candidateKeys)
    }

    /**
     * A built-in SQL function the grammar parses as its own expression (`XMLAGG(… ORDER BY …)`, `XMLSEQUENCE(…)`): a
     * `CALL` of its keyword only when a synonym names it (Oracle records the public synonym, and the target for an
     * operator); nothing otherwise, as for other built-ins.
     */
    private fun sqlFunctionKeyword(keyword: AstNode, context: Context, statementId: Int?) {
        val outcome = calls.resolve(listOf(keyword.tokenOriginalValue), ProjectCallKind.FUNCTION, emptyList(), context.schema, context.enclosingPackage)
        if (outcome is CallOutcome.Call && outcome.entityKey != null) callOccurrence(keyword, context, statementId, outcome)
    }

    private fun isOwnDeclaration(symbol: Symbol, reference: AstNode): Boolean {
        val declaration = symbol.node?.parentOrNull ?: return false
        return generateSequence(reference) { it.parentOrNull }.any { it === declaration }
    }

    /**
     * A REF CURSOR type of an enclosing block, subprogram or package body: the legacy table does not register
     * `REF_CURSOR_DECLARATION`. Declarations of a package specification are project types, never local.
     */
    private fun isLocalRefCursorType(reference: AstNode, name: String): Boolean {
        var node = reference.parentOrNull
        while (node != null && node.type != PlSqlGrammar.CREATE_PACKAGE) {
            val sections = if (node.type == PlSqlGrammar.DECLARE_SECTION) listOf(node) else node.getChildren(PlSqlGrammar.DECLARE_SECTION)
            val declared = sections.any { section ->
                section.getChildren(PlSqlGrammar.REF_CURSOR_DECLARATION).any {
                    it.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { identifier -> normalizeIdentifier(identifier.tokenOriginalValue) } == name
                }
            }
            if (declared) return true
            node = node.parentOrNull
        }
        return false
    }

    // ---- SQL statements -----------------------------------------------------------------------------------------

    private inner class Statement(val root: AstNode, val context: Context, val id: Int) {
        val localNames: Set<String> = root.getDescendants(DmlGrammar.SUBQUERY_FACTORING_CLAUSE)
            .map { normalizeIdentifier(it.firstChild.tokenOriginalValue) }.toSet()

        /** The relation of every `TABLE_REFERENCE` that [target] resolved. */
        val targets = IdentityHashMap<AstNode, Target>()

        /** The column sources each SQL scope owns directly. */
        val sources = IdentityHashMap<AstNode, List<ColumnSource>>()

        /** The column reads emitted at each name node, so that an `ORDER BY` name can repeat the read of its select item. */
        val columnReads = IdentityHashMap<AstNode, MutableList<OccurrenceFact>>()

        /** The select list of each query block with its stars expanded, for view column lineage. */
        val slots = IdentityHashMap<AstNode, List<Slot>>()

        /** The columns (`n:NAME`, `p:POSITION`) of each query whose lineage is being followed, to stop at recursive `WITH` names. */
        val following = IdentityHashMap<AstNode, MutableSet<String>>()

        fun occurrence(node: AstNode, entityKey: String?, roles: Set<Role>, status: Status, reason: String?, candidates: List<String> = emptyList()) {
            val fact = OccurrenceFact(context.unitIndex, context.container, id, spanOf(node), writtenText(node), entityKey,
                roles, status(status, context.partial), Origin.STATIC, reason, candidates)
            occurrences += fact
            if (roles == COLUMN_READ) columnReads.getOrPut(node) { ArrayList() } += fact
        }
    }

    private class Target(val reference: AstNode, val written: WrittenRelation, val resolved: ResolvedRelation, val alias: String?, val schema: String?)

    private fun statement(root: AstNode, context: Context) {
        val kind = when (root.type) {
            DmlGrammar.SELECT_EXPRESSION -> StatementKind.SELECT
            DmlGrammar.INSERT_EXPRESSION -> StatementKind.INSERT
            DmlGrammar.UPDATE_EXPRESSION -> StatementKind.UPDATE
            DmlGrammar.DELETE_EXPRESSION -> StatementKind.DELETE
            DmlGrammar.MERGE_EXPRESSION -> StatementKind.MERGE
            else -> StatementKind.LOCK
        }
        val id = statements.size
        statements += StatementFact(id, context.unitIndex, context.container, kind, spanOf(root), sha256(normalizedSql(root)))
        val statement = Statement(root, context, id)
        val targets = HashSet<AstNode>()
        when (kind) {
            StatementKind.UPDATE, StatementKind.DELETE -> {
                val clause = root.getFirstChildOrNull(DmlGrammar.DML_TABLE_EXPRESSION_CLAUSE)
                if (clause != null) targets += clause
                val reference = clause?.getFirstChildOrNull(DmlGrammar.TABLE_REFERENCE)
                if (reference == null) {
                    subqueryTarget(root)
                } else {
                    val role = if (kind == StatementKind.UPDATE) Role.SQL_UPDATE else Role.SQL_DELETE
                    val alias = clause.getFirstChildOrNull(DmlGrammar.ALIAS)?.let { normalizeIdentifier(it.tokenOriginalValue) }
                    val target = target(statement, reference, setOf(role), alias)
                    if (target != null && kind == StatementKind.UPDATE) {
                        root.getChildren(DmlGrammar.UPDATE_COLUMN).forEach { setColumn(statement, it, target, Role.SQL_UPDATE, allowRow = true) }
                    }
                }
            }
            StatementKind.INSERT -> for (clause in root.getDescendants(DmlGrammar.INSERT_INTO_CLAUSE)) {
                val reference = clause.getFirstChildOrNull(DmlGrammar.TABLE_REFERENCE)
                if (reference == null) {
                    subqueryTarget(clause)
                    continue
                }
                val alias = clause.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { normalizeIdentifier(it.tokenOriginalValue) }
                val target = target(statement, reference, setOf(Role.SQL_INSERT), alias) ?: continue
                val columns = clause.getFirstChildOrNull(DmlGrammar.INSERT_COLUMNS)
                val setClause = clause.nextSiblingOrNull?.takeIf { it.type == DmlGrammar.INSERT_SET_CLAUSE }
                when {
                    columns != null -> columns.children.filter { !isPunctuation(it) }.forEach { column(statement, it, target, Role.SQL_INSERT) }
                    setClause != null -> setClause.getChildren(DmlGrammar.UPDATE_COLUMN).forEach { setColumn(statement, it, target, Role.SQL_INSERT, allowRow = false) }
                    else -> implicitColumns(statement, target, setOf(Role.SQL_INSERT, Role.WRITE, Role.IMPLICIT))
                }
            }
            StatementKind.MERGE -> {
                val reference = root.getFirstChildOrNull(DmlGrammar.TABLE_REFERENCE)
                if (reference == null) {
                    subqueryTarget(root)
                } else {
                    val update = root.getFirstChildOrNull(DmlGrammar.MERGE_UPDATE_CLAUSE)
                    val insert = root.getFirstChildOrNull(DmlGrammar.MERGE_INSERT_CLAUSE)
                    val roles = buildSet {
                        add(Role.SQL_MERGE)
                        if (update != null) add(Role.SQL_UPDATE)
                        if (update != null && update.hasDirectChildren(PlSqlKeyword.DELETE)) add(Role.SQL_DELETE)
                        if (insert != null) add(Role.SQL_INSERT)
                    }
                    val alias = root.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { normalizeIdentifier(it.tokenOriginalValue) }
                    val target = target(statement, reference, roles, alias)
                    if (target != null) {
                        update?.getChildren(DmlGrammar.UPDATE_COLUMN)?.forEach { setColumn(statement, it, target, Role.SQL_UPDATE, allowRow = false) }
                        if (insert != null) {
                            val children = insert.children
                            val start = children.indexOfFirst { it.type == PlSqlKeyword.INSERT }
                            val end = children.indexOfFirst { it.type == PlSqlKeyword.VALUES }
                            val columns = children.subList(start + 1, end).filter { !isPunctuation(it) }
                            if (columns.isEmpty()) implicitColumns(statement, target, setOf(Role.SQL_INSERT, Role.WRITE, Role.IMPLICIT))
                            else columns.forEach { column(statement, it, target, Role.SQL_INSERT) }
                        }
                    }
                }
            }
            StatementKind.LOCK -> root.getChildren(DmlGrammar.TABLE_REFERENCE).forEach { target(statement, it, setOf(Role.SQL_LOCK), null) }
            else -> Unit
        }
        for (clause in root.getDescendants(DmlGrammar.DML_TABLE_EXPRESSION_CLAUSE)) {
            if (clause in targets) continue
            val reference = clause.getFirstChildOrNull(DmlGrammar.TABLE_REFERENCE) ?: continue
            target(statement, reference, setOf(Role.SQL_SELECT), null)
        }
        // The PL/SQL compiler resolves the function names of static SQL, so they follow the PL/SQL rules.
        root.getDescendants(PlSqlGrammar.METHOD_CALL).forEach { callSite(it, context, id) }
        root.getDescendants(*KEYWORD_FUNCTIONS).forEach { sqlFunctionKeyword(it.firstChild, context, id) }
        // `CAST(… AS t)` and the other datatypes of the statement.
        root.getDescendants(PlSqlGrammar.ANCHORED_DATATYPE, PlSqlGrammar.CUSTOM_DATATYPE).forEach { typeUse(it, context, id) }
        // Names: correlation columns of a trigger; INTO targets are PL/SQL values; every other name is a column read when
        // an SQL scope captures it.
        root.getDescendants(PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.MEMBER_EXPRESSION)
            .filter { isValueName(it) && it.getFirstAncestorOrNull(PlSqlGrammar.ANCHORED_DATATYPE, PlSqlGrammar.CUSTOM_DATATYPE) == null }
            .filterNot { correlationColumn(it, context, id) }
            .forEach { if (it.getFirstAncestorOrNull(DmlGrammar.INTO_CLAUSE) != null) valueReference(it, context, id, sql = false) else sqlName(statement, it) }
        root.getDescendants(DmlGrammar.SELECT_COLUMN).forEach { selectStar(statement, it) }
        view?.takeIf { it.query === root }?.let { viewColumns(statement, it) }
    }

    private fun isPunctuation(node: AstNode) = node.type is PlSqlPunctuator

    private fun subqueryTarget(node: AstNode) {
        coverage += CoverageFact("DML_SUBQUERY_TARGET", "DML on an inline view or collection at line ${node.tokenLine}: its base relation is not a write target")
    }

    private fun written(reference: AstNode): WrittenRelation {
        val children = reference.children
        val remote = children.indexOfFirst { it.type == PlSqlPunctuator.REMOTE }
        val local = (if (remote >= 0) children.subList(0, remote) else children).filter { it.type == PlSqlGrammar.IDENTIFIER_NAME }
            .map { normalizeIdentifier(it.tokenOriginalValue) }
        val link = if (remote >= 0) children.subList(remote + 1, children.size).filter { it.type == PlSqlGrammar.IDENTIFIER_NAME }
            .joinToString(".") { normalizeIdentifier(it.tokenOriginalValue) } else null
        return WrittenRelation(local.getOrNull(local.size - 2), local.last(), link)
    }

    /** A relation occurrence, or null for names that are not relations (WITH names, DUAL). */
    private fun target(statement: Statement, reference: AstNode, roles: Set<Role>, alias: String?): Target? {
        val written = written(reference)
        if (written.link == null && written.schema == null && written.name in statement.localNames) return null
        if (written.link == null && written.name == "DUAL" && (written.schema == null || written.schema == "SYS")) return null
        val resolved = resolver.resolve(written, statement.context.schema)
        statement.occurrence(reference, resolved.entityKey, roles, resolved.status, resolved.reason)
        return Target(reference, written, resolved, alias, written.schema ?: statement.context.schema).also { statement.targets[reference] = it }
    }

    private fun setColumn(statement: Statement, updateColumn: AstNode, target: Target, role: Role, allowRow: Boolean) {
        var left = updateColumn.firstChild
        // `SET (col) = (…)`: one parenthesized column is a BRACKED_EXPRESSION, not a MULTIPLE_VALUE_EXPRESSION.
        while (left.type == PlSqlGrammar.BRACKED_EXPRESSION) {
            left = left.children.singleOrNull { !isPunctuation(it) } ?: break
        }
        when {
            left.type == PlSqlGrammar.MULTIPLE_VALUE_EXPRESSION ->
                left.children.filter { !isPunctuation(it) }.forEach { column(statement, it, target, role) }
            allowRow && left.type == PlSqlGrammar.VARIABLE_NAME && left.tokenOriginalValue.uppercase() == "ROW" ->
                implicitColumns(statement, target, setOf(role, Role.WRITE, Role.IMPLICIT))
            else -> column(statement, left, target, role)
        }
    }

    private fun column(statement: Statement, node: AstNode, target: Target, role: Role) {
        val parts = node.children.filter { it.type != PlSqlPunctuator.DOT }.ifEmpty { listOf(node) }
        if (parts.any { it.type is PlSqlPunctuator || tokensOf(it).size != 1 }) {
            coverage += CoverageFact("COLUMN_EXPRESSION", "Written column at line ${node.tokenLine} is not a plain name")
            return
        }
        val segments = parts.map { normalizeIdentifier(it.tokenOriginalValue) }
        val qualifier = segments.dropLast(1)
        val roles = setOf(role, Role.WRITE)
        if (qualifier.isNotEmpty() && !qualifierMatches(qualifier, target)) {
            statement.occurrence(node, null, roles, Status.UNRESOLVED, "QUALIFIER_MISMATCH")
            return
        }
        val resolved = resolver.resolveColumn(target.resolved, segments.last())
        statement.occurrence(node, resolved.entityKey, roles, resolved.status, resolved.reason)
    }

    private fun qualifierMatches(qualifier: List<String>, target: Target): Boolean = when (qualifier.size) {
        1 -> qualifier[0] == (target.alias ?: target.written.name)
        2 -> target.alias == null && qualifier[1] == target.written.name && qualifier[0] == target.schema
        else -> false
    }

    /**
     * Every column written or read without being named (`INSERT` without a column list, `SET ROW`, `*`): by dictionary
     * position, at [at] (the target's range for writes).
     */
    private fun implicitColumns(statement: Statement, target: Target, roles: Set<Role>, at: AstNode = target.reference) {
        val relation = target.resolved.relation
        if (relation != null && relation.isColumnsComplete) {
            for (field in relation.rowFields) {
                statement.occurrence(at, target.resolved.entityKey + "." + field.name.exact(), roles, Status.RESOLVED, null)
            }
        } else {
            val status = when (target.resolved.status) {
                Status.EXTERNAL, Status.UNRESOLVED -> target.resolved.status
                else -> Status.UNVERIFIED
            }
            statement.occurrence(at, target.resolved.entityKey, roles, status, "COLUMNS_UNKNOWN")
        }
    }

    // ---- column reads -----------------------------------------------------------------------------------------

    /**
     * A column source of an SQL scope: a named relation ([target]), an inline view or `WITH` name ([viewColumns],
     * null when its select list has an unnamed item), or anything else (a table function, a collection), never
     * complete.
     */
    private class ColumnSource(
        /** The node in the `FROM` clause (or the target's reference). */
        val node: AstNode,
        /** The alias, else the relation or `WITH` name; null for an inline view or a function without alias. */
        val qualifier: String?,
        val target: Target?,
        val viewLike: Boolean,
        val viewColumns: Set<String>?,
        /** The query of an inline view or `WITH` name, which gives the values of its columns. */
        val query: AstNode? = null,
        /** The column names of a `WITH` column list or `DUAL`, in order. */
        val listed: List<String>? = null
    )

    /** Whether [source] has [column]: null when its columns are not known (or the dictionary cannot tell). */
    private fun has(source: ColumnSource, column: String): Boolean? = when {
        source.target != null -> resolver.hasColumn(source.target.resolved, column)
        source.viewLike -> source.viewColumns?.let { column in it }
        else -> null
    }

    /**
     * A name of static SQL: a column read when an SQL scope captures it, else a PL/SQL name. Qualified names look for
     * the source their qualifier names; unqualified ones walk the scopes outward from the innermost query block.
     */
    private fun sqlName(statement: Statement, name: AstNode) {
        if (isWrittenColumn(name) || isStarQualifier(name)) return
        val attribute = name.type == PlSqlGrammar.MEMBER_EXPRESSION && name.hasDirectChildren(PlSqlPunctuator.MOD)
        val segments = (if (attribute) null else nameSegments(name)) ?: run {
            valueReference(name, statement.context, statement.id, sql = true)
            return
        }
        if (segments.size >= 2) {
            val sequence = segments.size <= 3 && normalizeIdentifier(segments.last()) in PSEUDOCOLUMNS
            if (sequence || !qualifiedColumn(statement, name, segments)) valueReference(name, statement.context, statement.id, sql = true)
            return
        }
        unqualifiedColumn(statement, name, normalizeIdentifier(segments.single()))
    }

    /**
     * `q.col`, `schema.t.col`: a read of the source the qualifier names; false when no SQL source or qualifier matches.
     * A complete source that lacks the column does not capture it: the same qualifier in an enclosing scope is tried,
     * then the PL/SQL name (a record of an enclosing cursor loop named like the alias); only when PL/SQL finds nothing
     * either is it a read of the missing column of the innermost source.
     */
    private fun qualifiedColumn(statement: Statement, name: AstNode, segments: List<String>): Boolean {
        val names = segments.map { normalizeIdentifier(it) }
        var lacking: Pair<Target, String>? = null
        var scope = sqlScope(name)
        scopes@ while (scope != null) {
            val current: AstNode = scope
            for (source in scopeSources(statement, current, name, qualified = true)) {
                val columnIndex = when {
                    source.qualifier != null && source.qualifier == names[0] -> 1
                    names.size >= 3 && source.target != null && source.qualifier == source.target.written.name &&
                        qualifierMatches(names.subList(0, 2), source.target) -> 2
                    else -> continue
                }
                val column = names.getOrNull(columnIndex) ?: return true
                if (column in SQL_PSEUDOCOLUMNS || source.target == null) return true
                if (resolver.hasColumn(source.target.resolved, column) == false) {
                    if (lacking == null) lacking = source.target to column
                    scope = sqlScope(current)
                    continue@scopes
                }
                read(statement, name, source.target, column)
                return true
            }
            scope = sqlScope(current)
        }
        val (target, column) = lacking ?: return isSqlQualified(name, segments)
        val before = occurrences.size
        valueReference(name, statement.context, statement.id, sql = false)
        val first = if (name.type == PlSqlGrammar.VARIABLE_NAME) name else name.firstChild
        if (occurrences.size == before && (first as? SemanticAstNode)?.symbol == null) read(statement, name, target, column)
        return true
    }

    /**
     * An unqualified name: captured by SQL (a pseudocolumn, the name of an `ORDER BY` select item), else a column of
     * exactly one source of the innermost scope that has it, else the next scope out while every source of this one is
     * complete. A scope with a source of unknown columns leaves the name undecided; a name no scope captures is a
     * PL/SQL name. In `MERGE … INSERT VALUES` the target takes precedence over the `USING` source.
     */
    private fun unqualifiedColumn(statement: Statement, name: AstNode, column: String) {
        if (column in SQL_PSEUDOCOLUMNS || orderByItem(statement, name, column)) return
        var scope = sqlScope(name)
        while (scope != null) {
            val sources = mergeValuesSources(name, scope, column, scopeSources(statement, scope, name, qualified = false))
            if (sources.isNotEmpty()) {
                val known = sources.map { has(it, column) }
                val matches = sources.filterIndexed { index, _ -> known[index] == true }
                when {
                    matches.size == 1 -> {
                        matches.single().target?.let { read(statement, name, it, column) }
                        return
                    }
                    matches.size > 1 -> {
                        val named = matches.mapNotNull { it.target }
                        if (isJoinedColumn(scope, column)) {
                            named.forEach { read(statement, name, it, column) }
                        } else {
                            val candidates = named.mapNotNull { resolver.resolveColumn(it.resolved, column).entityKey }.distinct()
                            statement.occurrence(name, null, COLUMN_READ, Status.AMBIGUOUS, "AMBIGUOUS_COLUMN", candidates)
                        }
                        return
                    }
                    known.any { it == null } -> {
                        undecided(statement, name, scope, column, sources)
                        return
                    }
                }
            }
            scope = sqlScope(scope)
        }
        valueReference(name, statement.context, statement.id, sql = false)
    }

    private fun read(statement: Statement, name: AstNode, target: Target, column: String) {
        val resolved = resolver.resolveColumn(target.resolved, column)
        statement.occurrence(name, resolved.entityKey, COLUMN_READ, resolved.status, resolved.reason)
    }

    /**
     * A name that may be a column of a source with unknown columns: a project entity PL/SQL finds is kept as
     * `UNVERIFIED` (`COLUMN_NOT_EXCLUDED`); a name PL/SQL binds to something that is not an entity (a declaration in
     * scope, a parameterless SQL function) or that an enclosing scope has as a known column is counted; else the column
     * of the only source when it is a named relation; else nothing (counted).
     */
    private fun undecided(statement: Statement, name: AstNode, scope: AstNode, column: String, sources: List<ColumnSource>) {
        val before = occurrences.size
        valueReference(name, statement.context, statement.id, sql = false)
        val added = occurrences.subList(before, occurrences.size)
        if (added.isNotEmpty() && added.all { it.entityKey != null || it.candidateKeys.isNotEmpty() }) {
            for (index in added.indices) {
                added[index] = added[index].copy(status = status(Status.UNVERIFIED, statement.context.partial), reason = "COLUMN_NOT_EXCLUDED")
            }
            return
        }
        added.clear()
        // The legacy symbol table binds any name with a declaration in scope, column or not: still undecided.
        val bound = (name as? SemanticAstNode)?.symbol != null || column in SQL_PARAMETERLESS_FUNCTIONS
        val single = sources.singleOrNull()?.target
        if (!bound && single != null && !isKnownOutside(statement, name, scope, column)) read(statement, name, single, column) else undecidedSqlNames++
    }

    /** Whether a scope enclosing [scope] has a source known to have [column] (a correlated name we cannot place). */
    private fun isKnownOutside(statement: Statement, name: AstNode, scope: AstNode, column: String): Boolean {
        var outer = sqlScope(scope)
        while (outer != null) {
            if (scopeSources(statement, outer, name, qualified = false).any { has(it, column) == true }) return true
            outer = sqlScope(outer)
        }
        return false
    }

    /**
     * The column sources that can capture [name] in [scope]: those it owns, except that an `INSERT` target is one only
     * for `RETURNING` (any position for a qualified name), and a `SELECT_EXPRESSION` lends its `ORDER BY` the sources
     * of its first query block.
     */
    private fun scopeSources(statement: Statement, scope: AstNode, name: AstNode, qualified: Boolean): List<ColumnSource> = when (scope.type) {
        DmlGrammar.SELECT_EXPRESSION -> {
            val orderBy = name.getFirstAncestorOrNull(DmlGrammar.ORDER_BY_CLAUSE)
            val block = scope.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK)
            if (orderBy != null && orderBy.parentOrNull === scope && block != null) sourcesOf(statement, block) else sourcesOf(statement, scope)
        }
        DmlGrammar.INSERT_EXPRESSION ->
            if (qualified || name.getFirstAncestorOrNull(DmlGrammar.RETURNING_INTO_CLAUSE)?.let { sqlScope(it) === scope } == true) sourcesOf(statement, scope)
            else emptyList()
        else -> sourcesOf(statement, scope)
    }

    /** The sources [scope] owns directly: its `FROM` relations, the targets of `UPDATE`, `DELETE`, `INSERT`, `MERGE`. */
    private fun sourcesOf(statement: Statement, scope: AstNode): List<ColumnSource> = statement.sources.getOrPut(scope) {
        val result = ArrayList<ColumnSource>()
        if (scope.type == DmlGrammar.MERGE_EXPRESSION) {
            scope.getFirstChildOrNull(DmlGrammar.TABLE_REFERENCE)?.let { reference ->
                result += namedSource(statement, reference, scope.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { normalizeIdentifier(it.tokenOriginalValue) })
            }
        }
        if (scope.type == DmlGrammar.INSERT_EXPRESSION) {
            for (clause in scope.getDescendants(DmlGrammar.INSERT_INTO_CLAUSE)) {
                if (sqlScope(clause) !== scope) continue
                val reference = clause.getFirstChildOrNull(DmlGrammar.TABLE_REFERENCE) ?: continue
                result += namedSource(statement, reference, clause.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { normalizeIdentifier(it.tokenOriginalValue) })
            }
        }
        for (clause in scope.getDescendants(DmlGrammar.DML_TABLE_EXPRESSION_CLAUSE)) {
            if (sqlScope(clause) !== scope) continue
            val alias = clause.getFirstChildOrNull(DmlGrammar.ALIAS)?.let { normalizeIdentifier(it.tokenOriginalValue) }
            val reference = clause.getFirstChildOrNull(DmlGrammar.TABLE_REFERENCE)
            val subquery = clause.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)
            result += when {
                reference != null -> namedSource(statement, reference, alias)
                subquery != null -> ColumnSource(clause, alias, null, viewLike = true, viewColumns = selectNames(statement, subquery), query = subquery)
                else -> ColumnSource(clause, alias, null, viewLike = false, viewColumns = null)
            }
        }
        result
    }

    /** A relation reference as a source: a `WITH` name, `DUAL` (one column, `DUMMY`), or the relation [target] resolved. */
    private fun namedSource(statement: Statement, reference: AstNode, alias: String?): ColumnSource {
        val written = written(reference)
        if (written.link == null && written.schema == null && written.name in statement.localNames) {
            val factoring = statement.root.getDescendants(DmlGrammar.SUBQUERY_FACTORING_CLAUSE)
                .firstOrNull { normalizeIdentifier(it.firstChild.tokenOriginalValue) == written.name }
            return ColumnSource(reference, alias ?: written.name, null, viewLike = true, viewColumns = factoring?.let { withColumns(statement, it) },
                query = factoring?.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION), listed = factoring?.let { withList(it) }?.ifEmpty { null })
        }
        if (written.link == null && written.name == "DUAL" && (written.schema == null || written.schema == "SYS")) {
            return ColumnSource(reference, alias ?: written.name, null, viewLike = true, viewColumns = setOf("DUMMY"), listed = listOf("DUMMY"))
        }
        val target = statement.targets[reference] ?: return ColumnSource(reference, alias ?: written.name, null, viewLike = false, viewColumns = null)
        return ColumnSource(reference, alias ?: target.alias ?: written.name, target, viewLike = false, viewColumns = null)
    }

    /** The column names of a `WITH` name: its column list, else the names of its subquery's select list. */
    private fun withColumns(statement: Statement, factoring: AstNode): Set<String>? {
        val listed = withList(factoring)
        if (listed.isNotEmpty()) return listed.toHashSet()
        return factoring.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)?.let { selectNames(statement, it) }
    }

    /** The column list of a `WITH` name, empty without one. */
    private fun withList(factoring: AstNode): List<String> =
        factoring.children.drop(1).takeWhile { it.type != PlSqlKeyword.AS }.filter { it.type == PlSqlGrammar.IDENTIFIER_NAME }
            .map { normalizeIdentifier(it.tokenOriginalValue) }

    /**
     * The names of a subquery's select list (the first branch of a compound query): an alias, the last segment of a
     * plain column, or every column of the complete dictionary relations a `*` or `t.*` covers; null when an item has no
     * known name (an expression without alias, a `*` over a source whose columns are unknown).
     */
    private fun selectNames(statement: Statement, subquery: AstNode): Set<String>? {
        val block = subquery.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK) ?: return null
        val names = HashSet<String>()
        for (column in block.getChildren(DmlGrammar.SELECT_COLUMN)) {
            val star = starSources(statement, column)
            if (star != null) {
                if (star.isEmpty()) return null
                for (source in star) {
                    val relation = source.target?.resolved?.relation?.takeIf { it.isColumnsComplete } ?: return null
                    relation.rowFields.forEach { names += it.name.exact() }
                }
                continue
            }
            names += columnAlias(column) ?: nameSegments(column.firstChild)?.last()?.let { normalizeIdentifier(it) } ?: return null
        }
        return names
    }

    private fun columnAlias(selectColumn: AstNode): String? {
        val children = selectColumn.children
        return children.lastOrNull()?.takeIf { children.size > 1 && it.type == PlSqlGrammar.IDENTIFIER_NAME }?.let { normalizeIdentifier(it.tokenOriginalValue) }
    }

    /**
     * An `ORDER BY` name (bare, or inside an expression) that names a select item of the query it orders (the first
     * branch of a compound query): an alias, or the last segment of a plain column without alias. An expression item
     * gives nothing; a plain column item repeats the item's read; several items are `AMBIGUOUS` (ORA-00960).
     */
    private fun orderByItem(statement: Statement, name: AstNode, column: String): Boolean {
        val orderBy = name.getFirstAncestorOrNull(DmlGrammar.ORDER_BY_CLAUSE) ?: return false
        val owner = orderBy.parentOrNull ?: return false
        val block = if (owner.type == DmlGrammar.QUERY_BLOCK) owner else owner.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK) ?: return false
        if (sqlScope(name) !== owner && sqlScope(name) !== block) return false
        val items = block.getChildren(DmlGrammar.SELECT_COLUMN).filter { item ->
            (columnAlias(item) ?: nameSegments(item.firstChild)?.last()?.let { normalizeIdentifier(it) }) == column
        }
        if (items.isEmpty()) return false
        val reads = items.map { item -> if (nameSegments(item.firstChild) != null) statement.columnReads[item.firstChild].orEmpty() else emptyList() }
        if (items.size > 1) {
            val candidates = reads.flatten().flatMap { listOfNotNull(it.entityKey) + it.candidateKeys }.distinct()
            statement.occurrence(name, null, COLUMN_READ, Status.AMBIGUOUS, "AMBIGUOUS_COLUMN", candidates)
            return true
        }
        for (read in reads.single()) {
            occurrences += read.copy(span = spanOf(name), written = writtenText(name))
            statement.columnReads.getOrPut(name) { ArrayList() } += occurrences.last()
        }
        return true
    }

    /**
     * The sources of a name in the `VALUES` of a `MERGE` insert: the target when it has the column (Oracle reads it
     * and rejects the statement, ORA-38101), else only the `USING` source; [sources] anywhere else.
     */
    private fun mergeValuesSources(name: AstNode, scope: AstNode, column: String, sources: List<ColumnSource>): List<ColumnSource> {
        if (scope.type != DmlGrammar.MERGE_EXPRESSION) return sources
        val insert = name.getFirstAncestorOrNull(DmlGrammar.MERGE_INSERT_CLAUSE)?.takeIf { sqlScope(it) === scope } ?: return sources
        var node = name
        while (node.parentOrNull !== insert) node = node.parent
        val children = insert.children
        if (children.indexOf(node) < children.indexOfFirst { it.type == PlSqlKeyword.VALUES }) return sources
        val target = sources.firstOrNull() ?: return sources
        return when (has(target, column)) {
            true -> listOf(target)
            false -> sources.drop(1)
            null -> sources
        }
    }

    /** A column of a `USING` join, or any column when [scope] has a `NATURAL` join: one read per joined relation. */
    private fun isJoinedColumn(scope: AstNode, column: String): Boolean =
        scope.getDescendants(DmlGrammar.ON_OR_USING_EXPRESSION, DmlGrammar.INNER_CROSS_JOIN_CLAUSE, DmlGrammar.OUTER_JOIN_CLAUSE).any { node ->
            sqlScope(node) === scope && when (node.type) {
                DmlGrammar.ON_OR_USING_EXPRESSION -> node.hasDirectChildren(PlSqlKeyword.USING) &&
                    node.getChildren(PlSqlGrammar.IDENTIFIER_NAME).any { normalizeIdentifier(it.tokenOriginalValue) == column }
                else -> node.hasDirectChildren(PlSqlKeyword.NATURAL)
            }
        }

    /** A written column: an `INSERT` column list, the left side of `SET`, a `MERGE` insert list (emitted as writes). */
    private fun isWrittenColumn(name: AstNode): Boolean {
        var node = name
        while (node.parentOrNull?.type == PlSqlGrammar.BRACKED_EXPRESSION || node.parentOrNull?.type == PlSqlGrammar.MULTIPLE_VALUE_EXPRESSION) node = node.parent
        val parent = node.parentOrNull ?: return false
        return when (parent.type) {
            DmlGrammar.INSERT_COLUMNS -> true
            DmlGrammar.UPDATE_COLUMN -> parent.firstChild === node
            DmlGrammar.MERGE_INSERT_CLAUSE -> {
                val children = parent.children
                val index = children.indexOf(node)
                index > children.indexOfFirst { it.type == PlSqlKeyword.INSERT } && index < children.indexOfFirst { it.type == PlSqlKeyword.VALUES }
            }
            else -> false
        }
    }

    /** The `t` of `t.*`: read through [selectStar]. */
    private fun isStarQualifier(name: AstNode): Boolean {
        val parent = name.parentOrNull ?: return false
        return parent.type == PlSqlGrammar.OBJECT_REFERENCE && parent.firstChild === name && parent.lastChild.tokenValue == "*"
    }

    /**
     * `*` and `t.*` in a select list: every column of the sources they cover, implicit reads at the `*` for a complete
     * dictionary relation, one `COLUMNS_UNKNOWN` read of a named relation whose columns are unknown.
     */
    private fun selectStar(statement: Statement, selectColumn: AstNode) {
        val covered = starSources(statement, selectColumn) ?: return
        for (source in covered) source.target?.let { implicitColumns(statement, it, COLUMN_IMPLICIT_READ, selectColumn.firstChild) }
    }

    /** The sources a `*` or `t.*` select item covers, or null when the item is not one. */
    private fun starSources(statement: Statement, selectColumn: AstNode): List<ColumnSource>? {
        val item = selectColumn.firstChild
        val block = sqlScope(selectColumn) ?: return null
        return when {
            item.tokenValue == "*" && item.type is PlSqlPunctuator -> sourcesOf(statement, block)
            item.type == PlSqlGrammar.OBJECT_REFERENCE && item.lastChild.tokenValue == "*" -> {
                val qualifier = item.children.filter { it.type != PlSqlPunctuator.DOT && it.tokenValue != "*" }
                    .flatMap { tokensOf(it) }.filter { it.type !is PlSqlPunctuator }.map { normalizeIdentifier(it.originalValue) }
                sourcesOf(statement, block).filter { source ->
                    source.qualifier != null && source.qualifier == qualifier.singleOrNull() ||
                        source.target != null && qualifier.size == 2 && source.qualifier == source.target.written.name && qualifierMatches(qualifier, source.target)
                }.take(1)
            }
            else -> null
        }
    }

    // ---- declarations -------------------------------------------------------------------------------------------

    private fun addDeclarationOccurrences() {
        for (declaration in catalog.occurrencesFor(path)) {
            val unitIndex = units.indices.firstOrNull { units[it].span.contains(declaration.span.startLine, declaration.span.startCol) }
            if (unitIndex == null) {
                coverage += CoverageFact("DECLARATION_OUTSIDE_UNIT", "${declaration.entityKey} at line ${declaration.span.startLine} is outside every parsed unit")
                continue
            }
            val unit = units[unitIndex]
            occurrences += OccurrenceFact(unitIndex, declaration.containerKey ?: unit.entityKey, null, declaration.span, declaration.written,
                declaration.entityKey, setOf(declaration.role), status(Status.RESOLVED, partialUnits[unitIndex]), Origin.STATIC, null)
        }
    }

    /**
     * A subprogram the legacy table declares outside a package scope and below the file scope: nested in a standalone
     * unit, a subprogram or a block. `Symbol.isGlobal` is not used: it is true for subprograms nested in standalone
     * units, because their scope's outer scope is the file scope. The unit itself (recursion) is declared in the file
     * scope and package members in the package spec or body scope; those are not local.
     */
    private fun isLocalSubprogram(symbol: Symbol): Boolean {
        val scope = symbol.scope
        if (scope.outer == null) return false
        return scope.type != PlSqlGrammar.CREATE_PACKAGE && scope.type != PlSqlGrammar.CREATE_PACKAGE_BODY
    }

    /**
     * A value or type the legacy table declares outside a package specification and below the file scope: in a
     * block, a subprogram or a package body. zpa-core indexes the package values and types of specifications only, so
     * these are never project declarations.
     */
    private fun isLocalDeclaration(symbol: Symbol): Boolean {
        val scope = symbol.scope
        return scope.outer != null && scope.type != PlSqlGrammar.CREATE_PACKAGE
    }

    private companion object {
        /** Legacy symbol kinds whose names are indexed or called like subprograms but are values or types. */
        val NOT_CALLABLE = setOf(Symbol.Kind.VARIABLE, Symbol.Kind.PARAMETER, Symbol.Kind.CURSOR, Symbol.Kind.TYPE)

        val PSEUDOCOLUMNS = setOf("NEXTVAL", "CURRVAL")

        /** Names static SQL captures itself: never a column of a source, never a PL/SQL name. */
        val SQL_PSEUDOCOLUMNS = setOf(
            "ROWNUM", "ROWID", "LEVEL", "CONNECT_BY_ISLEAF", "CONNECT_BY_ISCYCLE", "ORA_ROWSCN", "COLUMN_VALUE", "OBJECT_VALUE", "OBJECT_ID"
        )

        /** SQL functions called without parentheses: PL/SQL names, never a column the scope could not exclude. */
        val SQL_PARAMETERLESS_FUNCTIONS = setOf(
            "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIMESTAMP", "LOCALTIMESTAMP", "USER", "UID", "DBTIMEZONE", "SESSIONTIMEZONE"
        )

        val COLUMN_READ = setOf(Role.READ)
        val COLUMN_IMPLICIT_READ = setOf(Role.READ, Role.IMPLICIT)

        val VIEW_COLUMN_DEFINITION = setOf(Role.DEFINITION)
        val VIEW_STAR_DEFINITION = setOf(Role.DEFINITION, Role.IMPLICIT)

        val DML_EVENTS = setOf(PlSqlKeyword.INSERT, PlSqlKeyword.UPDATE, PlSqlKeyword.DELETE)

        /** Built-in SQL functions the grammar parses as their own expression, named by a synonym in the dictionary. */
        val KEYWORD_FUNCTIONS = arrayOf<com.felipebz.flr.api.AstNodeType>(
            AggregateSqlFunctionsGrammar.XMLAGG_EXPRESSION, SingleRowSqlFunctionsGrammar.XMLSEQUENCE_EXPRESSION
        )

        val CURSOR_ATTRIBUTES = setOf("ISOPEN", "FOUND", "NOTFOUND", "ROWCOUNT", "BULK_ROWCOUNT", "BULK_EXCEPTIONS")

        /** Collection methods that change the collection. */
        val MODIFYING_METHODS = setOf("DELETE", "EXTEND", "TRIM")

        /** Call statuses that say nothing about a name when no declaration was selected. */
        val INCONCLUSIVE = setOf("INCOMPLETE_INDEX", "PARTIAL_DECLARATIONS")

        /** The statuses of a column read whose column is a base column of the select item it is in. */
        val LINEAGE_STATUSES = setOf(Status.RESOLVED, Status.UNVERIFIED, Status.EXTERNAL, Status.PARTIAL_SOURCE)

        val SQL_SCOPES = arrayOf(
            DmlGrammar.QUERY_BLOCK, DmlGrammar.SELECT_EXPRESSION, DmlGrammar.UPDATE_EXPRESSION,
            DmlGrammar.DELETE_EXPRESSION, DmlGrammar.INSERT_EXPRESSION, DmlGrammar.MERGE_EXPRESSION
        )
    }
}
