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
import com.felipebz.zpa.api.project.ProjectCallKind
import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.api.squid.SemanticAstNode
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
    private val catalogOf: (ProjectSnapshot) -> DeclarationCatalog
) : PlSqlCheck() {

    private lateinit var catalog: DeclarationCatalog
    private lateinit var calls: CallResolver
    private var result: FileFacts? = null

    private val units = ArrayList<UnitFact>()
    private val partialUnits = ArrayList<Boolean>()
    private val statements = ArrayList<StatementFact>()
    private val occurrences = ArrayList<OccurrenceFact>()
    private val entities = ArrayList<EntityFact>()
    private val relations = ArrayList<RelationFact>()
    private val coverage = ArrayList<CoverageFact>()
    private val resolver = RelationResolver(dictionary, entities, relations)
    private var recoveredRegions = 0

    override fun init() {
        val snapshot = projectAnalysis().snapshot
        catalog = catalogOf(snapshot)
        val runSchemas = catalog.schemas + dictionary.schemas + listOfNotNull(defaultSchema) + setOf("SYS", "SYSTEM")
        calls = CallResolver(snapshot, catalog, entities, relations, runSchemas)
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
        return FileFacts(path, sha256, sizeBytes, encoding, status, recoveredRegions, defaultSchema, units, statements, occurrences, entities, relations, coverage)
    }

    // ---- units --------------------------------------------------------------------------------------------------

    private class Context(
        val unitIndex: Int,
        val partial: Boolean,
        val schema: String?,
        val container: String?,
        val newNames: Set<String>?,
        val triggerKey: String?,
        /** The package spec or body around the code, as written in its CREATE; null elsewhere. */
        val enclosingPackage: List<String>? = null
    ) {
        fun inside(container: String) = Context(unitIndex, partial, schema, container, newNames, triggerKey, enclosingPackage)
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
        walk(node, Context(index, partial, defaultSchema, null, null, null))
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
        var newNames: Set<String>? = null
        var triggerKey: String? = null
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
                triggerKey = key
                newNames = trigger(node, key, schema)
            }
            PlSqlGrammar.CREATE_VIEW, PlSqlGrammar.CREATE_MATERIALIZED_VIEW -> UnitKind.VIEW.also { entityKey = dictionaryView(written, schema) }
            PlSqlGrammar.ANONYMOUS_BLOCK -> UnitKind.ANONYMOUS_BLOCK
            else -> UnitKind.SQL_STATEMENT
        }
        val index = addUnit(UnitFact(kind, displayName, entityKey, span, schema), partial)
        if (kind == UnitKind.PACKAGE_BODY || kind == UnitKind.TYPE_BODY || kind == UnitKind.TRIGGER) {
            // Package bodies, type bodies and triggers are not in the project index; their definition comes from the AST.
            occurrences += OccurrenceFact(index, entityKey, null, span, writtenText(unitName ?: node), entityKey,
                setOf(Role.DEFINITION), status(Status.RESOLVED, partial), Origin.STATIC, null)
        }
        val enclosingPackage = if (kind == UnitKind.PACKAGE || kind == UnitKind.PACKAGE_BODY) {
            unitName?.getChildren(PlSqlGrammar.IDENTIFIER_NAME)?.map { it.tokenOriginalValue }?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        walk(node, Context(index, partial, schema, entityKey, newNames, triggerKey, enclosingPackage))
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

    private fun dictionaryView(written: List<String>, schema: String?): String? {
        if (written.isEmpty() || schema == null) return null
        val lookup = dictionary.metadata.findRelation(RelationResolver.identifier(schema), RelationResolver.identifier(written.last()))
        if (lookup.status != ProjectExternalLookup.Status.FOUND) return null
        val relation = lookup.relation.get()
        resolver.addDictionaryRelation(relation)
        return RelationResolver.dictionaryKey(relation)
    }

    /** `TRIGGER_ON` and the correlation names of a DML trigger; null for system triggers. */
    private fun trigger(node: AstNode, key: String?, schema: String?): Set<String>? {
        val dml = node.getFirstChildOrNull(PlSqlGrammar.SIMPLE_DML_TRIGGER, PlSqlGrammar.INSTEAD_OF_DML_TRIGGER, PlSqlGrammar.COMPOUND_DML_TRIGGER)
            ?: return null
        val target = dml.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME)
        if (key != null && target != null && !dml.hasDirectChildren(PlSqlKeyword.NESTED)) {
            val written = names(target)
            val relation = resolver.resolve(WrittenRelation(written.getOrNull(written.size - 2), written.last(), null), schema)
            relations += RelationFact(key, RelationKind.TRIGGER_ON, relation.entityKey)
        }
        val referencing = dml.getFirstChildOrNull(PlSqlGrammar.REFERENCING_CLAUSE)
        val aliases = HashSet<String>()
        if (referencing != null) {
            val children = referencing.children
            for ((i, child) in children.withIndex()) {
                if (child.type == PlSqlKeyword.NEW) {
                    children.drop(i + 1).firstOrNull { it.type == PlSqlGrammar.IDENTIFIER_NAME }?.let { aliases += normalizeIdentifier(it.tokenOriginalValue) }
                }
            }
        }
        return aliases.ifEmpty { setOf("NEW") }
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
            // The SQL text is not parsed (F2); calls that build it are still PL/SQL calls, found below.
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
            PlSqlGrammar.ASSIGNMENT_STATEMENT -> newColumnAssignment(node, context)
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

    private fun newColumnAssignment(node: AstNode, context: Context) {
        val names = context.newNames ?: return
        val tokens = tokensOf(node.firstChild)
        if (tokens.size >= 2 && tokens[0].value == ":" && normalizeIdentifier(tokens[1].originalValue) in names) {
            coverage += CoverageFact("TRIGGER_NEW_COLUMN_WRITE", "${context.triggerKey} assigns :NEW columns (column writes through :NEW are not indexed in F0.1)")
        }
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
     * `LOCAL_SUBPROGRAM` (local entities are F3).
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
            symbol != null && symbol.kind in NOT_CALLABLE -> return
            symbol != null && (symbol.kind == Symbol.Kind.PROCEDURE || symbol.kind == Symbol.Kind.FUNCTION) && isLocalSubprogram(symbol) ->
                CallOutcome.Call(Status.UNRESOLVED, null, "LOCAL_SUBPROGRAM")
            else -> calls.resolve(segments, kind, arguments.map { WrittenArgument(it.first) }, context.schema, context.enclosingPackage)
        }
        if (outcome !is CallOutcome.Call) return
        val argumentFacts = arguments.mapIndexed { index, (label, expression, _) ->
            CallArgumentFact(index + 1, label?.let { normalizeIdentifier(it) },
                if (outcome.status == Status.RESOLVED) outcome.bindings[index + 1] else null, spanOf(expression))
        }
        occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, spanOf(callee), writtenText(callee), outcome.entityKey,
            setOf(Role.CALL), status(outcome.status, context.partial), Origin.STATIC, outcome.reason, outcome.candidateKeys, argumentFacts)
    }

    // ---- SQL statements -----------------------------------------------------------------------------------------

    private inner class Statement(val root: AstNode, val context: Context, val id: Int) {
        val localNames: Set<String> = root.getDescendants(DmlGrammar.SUBQUERY_FACTORING_CLAUSE)
            .map { normalizeIdentifier(it.firstChild.tokenOriginalValue) }.toSet()

        fun occurrence(node: AstNode, entityKey: String?, roles: Set<Role>, status: Status, reason: String?) {
            occurrences += OccurrenceFact(context.unitIndex, context.container, id, spanOf(node), writtenText(node), entityKey,
                roles, status(status, context.partial), Origin.STATIC, reason)
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
                    else -> implicitColumns(statement, target, Role.SQL_INSERT)
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
                            if (columns.isEmpty()) implicitColumns(statement, target, Role.SQL_INSERT)
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
    }

    private fun isPunctuation(node: AstNode) = node.type is PlSqlPunctuator

    private fun subqueryTarget(node: AstNode) {
        coverage += CoverageFact("DML_SUBQUERY_TARGET", "DML on an inline view or collection at line ${node.tokenLine}: its base relation is not a write target in F0.1")
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
        return Target(reference, written, resolved, alias, written.schema ?: statement.context.schema)
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
                implicitColumns(statement, target, role)
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

    /** Every column written without being named: by dictionary position, at the target's range. */
    private fun implicitColumns(statement: Statement, target: Target, role: Role) {
        val roles = setOf(role, Role.WRITE, Role.IMPLICIT)
        val relation = target.resolved.relation
        if (relation != null && relation.isColumnsComplete) {
            for (field in relation.rowFields) {
                statement.occurrence(target.reference, target.resolved.entityKey + "." + field.name.exact(), roles, Status.RESOLVED, null)
            }
        } else {
            val status = when (target.resolved.status) {
                Status.EXTERNAL, Status.UNRESOLVED -> target.resolved.status
                else -> Status.UNVERIFIED
            }
            statement.occurrence(target.reference, target.resolved.entityKey, roles, status, "COLUMNS_UNKNOWN")
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

    private companion object {
        /** Legacy symbol kinds whose names are indexed or called like subprograms but are values or types. */
        val NOT_CALLABLE = setOf(Symbol.Kind.VARIABLE, Symbol.Kind.PARAMETER, Symbol.Kind.CURSOR, Symbol.Kind.TYPE)
    }
}
