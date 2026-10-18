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
import com.felipebz.zpa.api.checks.PlSqlCheck
import com.felipebz.zpa.api.project.ProjectExternalLookup
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.api.symbols.Symbol
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.model.*

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
    private val catalogOf: (ProjectSnapshot) -> DeclarationCatalog,
    /** Whether a bare `json` is always the built-in type: the run targets Oracle 21 or later. */
    private val jsonBuiltIn: Boolean = false,
    /** The members of the object types the sources specify, so that a type body tells its own members from external names. */
    private val typeMembers: ObjectTypeMembers = ObjectTypeMembers()
) : PlSqlCheck() {

    private val state = FileState()
    private lateinit var catalog: DeclarationCatalog
    private lateinit var calls: CallResolver
    private lateinit var types: TypeReferenceResolver
    private lateinit var values: PackageValues
    private lateinit var runSchemas: Set<String>
    private var result: FileFacts? = null
    private var root: AstNode? = null

    private val synonyms = SynonymResolver(dictionary)
    private val resolver = RelationResolver(dictionary, synonyms, state.entities, state.relations)

    // The collaborators of the walk, created when it starts (they need the file's legacy symbol table).
    private lateinit var triggers: Triggers
    private lateinit var plsql: PlSqlNames
    private lateinit var typeReferences: TypeReferences
    private lateinit var sql: SqlStatements
    private lateinit var lineage: ViewLineage

    override fun init() {
        val snapshot = projectAnalysis().snapshot
        catalog = catalogOf(snapshot)
        runSchemas = catalog.schemas + dictionary.schemas + listOfNotNull(defaultSchema) + setOf("SYS", "SYSTEM")
        values = PackageValues(snapshot, catalog)
        calls = CallResolver(snapshot, catalog, values, synonyms, state.entities, state.relations, runSchemas)
        types = TypeReferenceResolver(snapshot, catalog, values, resolver, synonyms, state.entities, state.relations, runSchemas)
    }

    override fun visitFile(node: AstNode) {
        root = node
        val scopes = SqlScopes()
        plsql = PlSqlNames(state, scopes, catalog, calls, values, synonyms, typeMembers, runSchemas, jsonBuiltIn, projectAnalysis())
        triggers = Triggers(state, resolver, plsql)
        typeReferences = TypeReferences(state, types, resolver, calls, plsql, jsonBuiltIn)
        val columnReads = ColumnReads(state, resolver, scopes, plsql)
        sql = SqlStatements(state, resolver, scopes, plsql, typeReferences, triggers, columnReads)
        lineage = ViewLineage(state, scopes, columnReads, plsql)
        var inRecovered = false
        for (child in node.children) {
            when (child.type) {
                PlSqlGrammar.RECOVERY -> {
                    state.recoveredRegions++
                    inRecovered = true
                    state.addUnit(UnitFact(UnitKind.UNPARSED, "", null, spanOf(child), defaultSchema), partial = true)
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
        result = build(ParseStatus.OK.takeIf { state.recoveredRegions == 0 } ?: ParseStatus.RECOVERED)
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
        state.coverage += note
        state.addUnit(UnitFact(UnitKind.UNPARSED, "", null, SourceSpan(1, 0, lineCount + 1, 0), defaultSchema), partial = true)
        return build(ParseStatus.FAILED)
    }

    private fun build(status: ParseStatus): FileFacts {
        if (::catalog.isInitialized) {
            addDeclarationOccurrences()
            state.coverage += catalog.coverageFor(path)
        }
        if (state.localTypeReferences > 0) {
            state.coverage += CoverageFact("LOCAL_ANCHOR", "${state.localTypeReferences} datatype name(s) refer to local declarations (variables, parameters, cursors, types): not indexed")
        }
        if (state.undecidedSqlNames > 0) {
            state.coverage += CoverageFact("SQL_NAME_UNDECIDED", "${state.undecidedSqlNames} unqualified name(s) in static SQL may be a column of a relation whose columns are unknown or a PL/SQL name: not indexed")
        }
        // Mentioned relations and columns repeat once per reference: keep the first of each exact duplicate.
        return FileFacts(path, sha256, sizeBytes, encoding, status, state.recoveredRegions, defaultSchema, state.units, state.statements, state.occurrences,
            LinkedHashSet(state.entities).toList(), LinkedHashSet(state.relations).toList(), state.coverage, resolver.dictionaryRelations.toList(),
            synonyms.dictionarySynonyms.toList(), state.triggerEvents.toList())
    }

    // ---- units --------------------------------------------------------------------------------------------------

    private fun sqlUnit(node: AstNode, partial: Boolean) {
        val index = state.addUnit(UnitFact(UnitKind.SQL_STATEMENT, "", null, spanOf(node), defaultSchema), partial)
        walk(node, Context(index, partial, defaultSchema, null, null, context.symbolTable))
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
                trigger = key?.let { triggers.trigger(node, it, schema) }
            }
            PlSqlGrammar.CREATE_VIEW, PlSqlGrammar.CREATE_MATERIALIZED_VIEW -> (if (node.type == PlSqlGrammar.CREATE_VIEW) UnitKind.VIEW else UnitKind.MATERIALIZED_VIEW).also {
                // The dictionary's view, else a VIEW entity from the source, which wins over the UNKNOWN_RELATION that a
                // reference to it creates with the same key.
                val relation = dictionaryView(written, schema)
                val key = relation?.let { RelationResolver.dictionaryKey(it) } ?: sourceObject(written, if (node.type == PlSqlGrammar.CREATE_VIEW) EntityKind.VIEW else EntityKind.MATERIALIZED_VIEW)
                entityKey = key
                if (key != null) view = ViewScope(key, relation, node, node.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION))
            }
            PlSqlGrammar.ANONYMOUS_BLOCK -> UnitKind.ANONYMOUS_BLOCK
            else -> UnitKind.SQL_STATEMENT
        }
        val index = state.addUnit(UnitFact(kind, displayName, entityKey, span, schema), partial)
        if (kind == UnitKind.PACKAGE_BODY || kind == UnitKind.TYPE_BODY || kind == UnitKind.TRIGGER || (kind == UnitKind.VIEW || kind == UnitKind.MATERIALIZED_VIEW) && entityKey != null) {
            // Package bodies, type bodies, triggers and views are not in the project index; their definition comes from the AST.
            state.occurrences += OccurrenceFact(index, entityKey, null, span, writtenText(unitName ?: node), entityKey,
                setOf(Role.DEFINITION), status(Status.RESOLVED, partial), Origin.STATIC, null, nameSpan = unitName?.let(::spanOf))
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
        val objectType = if (kind == UnitKind.TYPE_BODY) ObjectTypeMembers.typeKey(unitName, defaultSchema) else null
        walk(node, Context(index, partial, schema, entityKey, trigger, context.symbolTable, enclosingPackage, bodyNames, objectType))
        view = null
    }

    private fun subprogramAt(node: AstNode): String? = catalog.subprogramAt(path, node.token.line, node.token.column)

    /** A schema object named in its own CREATE statement (the entity may also come from the project index). */
    private fun sourceObject(written: List<String>, kind: EntityKind): String? {
        if (written.isEmpty()) return null
        val (schema, rest) = catalog.schemaAndName(path, written)
        val key = dbKey(schema, *rest.toTypedArray())
        state.entities += EntityFact(key, kind, schema, rest.last(), null, key.removePrefix("db:"), external = false, precedence = Precedence.SOURCE)
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

    private var view: ViewScope? = null

    // ---- walk ---------------------------------------------------------------------------------------------------

    private fun walk(node: AstNode, context: Context) {
        // A conditional compilation run is a trivia of the next token: read once, by the outermost node that token starts.
        node.token?.let { if (node.parentOrNull?.token !== it) plsql.conditionNames(it, context) }
        var current = context
        when (node.type) {
            PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION,
            PlSqlGrammar.CREATE_PROCEDURE, PlSqlGrammar.CREATE_FUNCTION -> subprogramAt(node)?.let { current = context.inside(it) }
            DmlGrammar.SELECT_EXPRESSION, DmlGrammar.INSERT_EXPRESSION, DmlGrammar.UPDATE_EXPRESSION,
            DmlGrammar.DELETE_EXPRESSION, DmlGrammar.MERGE_EXPRESSION, TclGrammar.LOCK_TABLE_EXPRESSION -> {
                innerConditionNames(node, context)
                val statement = sql.statement(node, context)
                view?.takeIf { it.query === node }?.let { lineage.viewColumns(statement, it) }
                return
            }
            DmlGrammar.EXPLAIN_PLAN -> {
                innerConditionNames(node, context)
                return
            }
            // Calls that build the SQL text are PL/SQL calls, found below.
            PlSqlGrammar.EXECUTE_IMMEDIATE_STATEMENT -> dynamic(node, after(node, PlSqlKeyword.IMMEDIATE), context)
            PlSqlGrammar.OPEN_FOR_STATEMENT -> if (!node.hasDirectChildren(DmlGrammar.SELECT_EXPRESSION)) {
                dynamic(node, after(node, PlSqlKeyword.FOR), context)
            }
            PlSqlGrammar.METHOD_CALL -> {
                dbmsSqlParse(node)?.let { dynamic(node, it, context) }
                plsql.callSite(node, context, null)
            }
            PlSqlGrammar.CALL_STATEMENT -> node.children.firstOrNull { it.type != PlSqlGrammar.LABELS && it.type != PlSqlPunctuator.SEMICOLON }
                ?.takeIf { it.type != PlSqlGrammar.METHOD_CALL }
                ?.let { plsql.callSite(it, context, null) }
            PlSqlGrammar.ANCHORED_DATATYPE, PlSqlGrammar.CUSTOM_DATATYPE -> {
                innerConditionNames(node, context)
                typeReferences.typeUse(node, context, null)
                return
            }
            PlSqlGrammar.JSON_DATATYPE -> {
                typeReferences.jsonTypeUse(node, context)
                return
            }
            PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.MEMBER_EXPRESSION -> if (typeReferences.isAggregateImplementation(node)) {
                typeReferences.aggregateType(node, context)
            } else if (plsql.isValueName(node) && !triggers.correlationColumn(node, context, null)) {
                plsql.valueReference(node, context, null, sql = false)
            }
            PlSqlGrammar.UNIT_NAME -> if (typeReferences.isSupertype(node)) typeReferences.supertypeUse(node, context)
            // In a PL/SQL expression (static SQL is handled with its statement).
            in KEYWORD_FUNCTIONS -> typeReferences.sqlFunctionKeyword(node.firstChild, context, null)
        }
        for (child in node.children) walk(child, current)
    }

    /** The condition names of every token of a node the walk does not enter, but its first (read by [walk]). */
    private fun innerConditionNames(node: AstNode, context: Context) {
        node.tokens.drop(1).forEach { plsql.conditionNames(it, context) }
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
     * A dynamic statement: one `DYNAMIC` occurrence whose reason says what became of its SQL text, then, when zpa parsed
     * the text, the walk of its statement as code of its own ([Context.dynamicText]). A dynamic statement inside a
     * dynamic text is recorded but its text is not asked for: it is not a node of the file.
     */
    private fun dynamic(node: AstNode, sql: AstNode?, context: Context) {
        val id = state.statements.size
        state.statements += StatementFact(id, context.unitIndex, context.container, StatementKind.DYNAMIC, spanOf(node), sha256(normalizedSql(node)))
        val text = if (sql != null && context.dynamic == null) this.context.dynamicSql(sql) else null
        val reason = when {
            text == null -> if (sql != null && hasStringLiteral(sql)) "NOT_PARSED" else "EXPRESSION"
            text.holesDropped -> "PARSED_HOLES_DROPPED"
            text.holes.isNotEmpty() -> "PARSED_WITH_HOLES"
            else -> "PARSED"
        }
        val written = when (node.type) {
            PlSqlGrammar.EXECUTE_IMMEDIATE_STATEMENT -> "EXECUTE IMMEDIATE"
            PlSqlGrammar.OPEN_FOR_STATEMENT -> "OPEN FOR"
            else -> "DBMS_SQL.PARSE"
        }
        state.occurrences += OccurrenceFact(context.unitIndex, context.container, id, spanOf(sql ?: node), written, null, emptySet(),
            status(Status.DYNAMIC, context.partial), context.origin, reason)
        if (text != null) walk(text.root, context.dynamicText(text))
    }

    /**
     * Whether a part of [sql] is a string literal, its operands taken as zpa takes them (a concatenation flattened,
     * parentheses unwrapped): a text zpa could not parse is then `NOT_PARSED`, not an `EXPRESSION`.
     */
    private fun hasStringLiteral(sql: AstNode): Boolean = when {
        sql.type == PlSqlGrammar.CONCATENATION_EXPRESSION ->
            sql.children.any { it.type != PlSqlGrammar.CONCATENATION_OPERATOR && hasStringLiteral(it) }
        sql.type == PlSqlGrammar.BRACKED_EXPRESSION && sql.numberOfChildren == 3 -> hasStringLiteral(sql.children[1])
        sql.type == PlSqlGrammar.CHARACTER_LITERAL -> true
        else -> sql.type == PlSqlGrammar.LITERAL && sql.numberOfChildren == 1 && sql.firstChild.type == PlSqlGrammar.CHARACTER_LITERAL
    }

    // ---- declarations -------------------------------------------------------------------------------------------

    private fun addDeclarationOccurrences() {
        val declarations = catalog.occurrencesFor(path)
        val names = root?.let { declarationNames(it, declarations) }.orEmpty()
        for (declaration in declarations) {
            val unitIndex = state.units.indices.firstOrNull { state.units[it].span.contains(declaration.span.startLine, declaration.span.startCol) }
            if (unitIndex == null) {
                state.coverage += CoverageFact("DECLARATION_OUTSIDE_UNIT", "${declaration.entityKey} at line ${declaration.span.startLine} is outside every parsed unit")
                continue
            }
            val unit = state.units[unitIndex]
            state.occurrences += OccurrenceFact(unitIndex, declaration.containerKey ?: unit.entityKey, null, declaration.span, declaration.written,
                declaration.entityKey, setOf(declaration.role), status(Status.RESOLVED, state.partialUnits[unitIndex]), Origin.STATIC, null,
                nameSpan = names[declaration])
        }
    }

    /**
     * The name spans of [declarations], found in the AST: the outermost node with the declaration's span, then its
     * first `UNIT_NAME` or `IDENTIFIER_NAME` in source order (for a `UNIT_NAME`, the whole name or its last segment),
     * kept only when its text is the written name. One walk over the file, a node checked only when it starts where a
     * declaration starts.
     */
    private fun declarationNames(root: AstNode, declarations: List<DeclarationOccurrence>): Map<DeclarationOccurrence, SourceSpan> {
        if (declarations.isEmpty()) return emptyMap()
        val bySpan = declarations.groupBy { it.span }
        val starts = declarations.mapTo(HashSet()) { position(it.span.startLine, it.span.startCol) }
        val found = HashMap<SourceSpan, AstNode>()
        fun visit(node: AstNode) {
            if (node.hasToken() && position(node.token.line, node.token.column) in starts) {
                val span = spanOf(node)
                if (span in bySpan) found.putIfAbsent(span, node)
            }
            for (child in node.children) visit(child)
        }
        visit(root)
        val result = HashMap<DeclarationOccurrence, SourceSpan>()
        for ((span, node) in found) {
            val name = firstName(node) ?: continue
            for (declaration in bySpan.getValue(span)) {
                matching(name, declaration.written)?.let { result[declaration] = spanOf(it) }
            }
        }
        // A declaration of a region the parser recovered has no node of its span: its first name inside the span.
        for (declaration in declarations) {
            if (declaration in result) continue
            nameWithin(root, declaration.span, declaration.written)?.let { result[declaration] = spanOf(it) }
        }
        return result
    }

    private fun isName(node: AstNode) = node.type == PlSqlGrammar.UNIT_NAME || node.type == PlSqlGrammar.IDENTIFIER_NAME

    private fun firstName(node: AstNode): AstNode? {
        if (isName(node)) return node
        for (child in node.children) firstName(child)?.let { return it }
        return null
    }

    /** [name] or, for a `UNIT_NAME`, one of its segments (the last first), when its text is [written]. */
    private fun matching(name: AstNode, written: String): AstNode? {
        val candidates = if (name.type == PlSqlGrammar.UNIT_NAME) listOf(name) + name.getChildren(PlSqlGrammar.IDENTIFIER_NAME).asReversed() else listOf(name)
        return candidates.firstOrNull { writtenText(it) == written }
    }

    /** The first name node inside [span] that is [written], skipping subtrees outside it. */
    private fun nameWithin(node: AstNode, span: SourceSpan, written: String): AstNode? {
        if (!node.hasToken()) return null
        val own = spanOf(node)
        if (own.endLine < span.startLine || own.endLine == span.startLine && own.endCol <= span.startCol) return null
        if (own.startLine > span.endLine || own.startLine == span.endLine && own.startCol >= span.endCol) return null
        if (isName(node) && span.contains(own.startLine, own.startCol)) matching(node, written)?.let { return it }
        for (child in node.children) nameWithin(child, span, written)?.let { return it }
        return null
    }

    private fun position(line: Int, column: Int): Long = line.toLong() shl 32 or column.toLong()
}
