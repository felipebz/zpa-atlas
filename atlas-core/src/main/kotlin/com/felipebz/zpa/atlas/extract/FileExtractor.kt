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
    private val resolver = RelationResolver(dictionary, entities, relations)
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
        calls = CallResolver(snapshot, catalog, values, entities, relations, runSchemas)
        types = TypeReferenceResolver(snapshot, catalog, values, resolver, entities, relations, runSchemas)
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
            LinkedHashSet(entities).toList(), LinkedHashSet(relations).toList(), coverage, resolver.dictionaryRelations.toList())
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
        val enclosingPackage: List<String>? = null,
        /** Variables, cursors and types declared at the level of the package body around the code. */
        val bodyNames: Set<String> = emptySet()
    ) {
        fun inside(container: String) = Context(unitIndex, partial, schema, container, newNames, triggerKey, enclosingPackage, bodyNames)
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
        val bodyNames = if (kind == UnitKind.PACKAGE_BODY) {
            context.symbolTable.getScopeFor(node)?.symbols.orEmpty()
                .filter { it.kind == Symbol.Kind.VARIABLE || it.kind == Symbol.Kind.CURSOR || it.kind == Symbol.Kind.TYPE }
                .mapNotNull { symbol -> symbol.node?.let { normalizeIdentifier(it.tokenOriginalValue) } }.toSet()
        } else {
            emptySet()
        }
        walk(node, Context(index, partial, schema, entityKey, newNames, triggerKey, enclosingPackage, bodyNames))
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
            PlSqlGrammar.ASSIGNMENT_STATEMENT -> newColumnAssignment(node, context)
            PlSqlGrammar.ANCHORED_DATATYPE, PlSqlGrammar.CUSTOM_DATATYPE -> {
                typeUse(node, context, null)
                return
            }
            PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.MEMBER_EXPRESSION -> if (isValueName(node)) valueReference(node, context, null, sql = false)
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
            coverage += CoverageFact("TRIGGER_NEW_COLUMN_WRITE", "${context.triggerKey} assigns :NEW columns (column writes through :NEW are not indexed)")
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
        val declared = values.sequence(written, context.schema)
        val (key, status) = if (declared != null) {
            declared to Status.RESOLVED
        } else {
            val names = written.map { normalizeIdentifier(it) }
            val schemaQualified = names.size == 2 && names[0] in runSchemas
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

        fun occurrence(node: AstNode, entityKey: String?, roles: Set<Role>, status: Status, reason: String?, candidates: List<String> = emptyList()) {
            occurrences += OccurrenceFact(context.unitIndex, context.container, id, spanOf(node), writtenText(node), entityKey,
                roles, status(status, context.partial), Origin.STATIC, reason, candidates)
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
        // `CAST(… AS t)` and the other datatypes of the statement.
        root.getDescendants(PlSqlGrammar.ANCHORED_DATATYPE, PlSqlGrammar.CUSTOM_DATATYPE).forEach { typeUse(it, context, id) }
        // Names: INTO targets are PL/SQL values; every other name is a column read when an SQL scope captures it.
        root.getDescendants(PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.MEMBER_EXPRESSION)
            .filter { isValueName(it) && it.getFirstAncestorOrNull(PlSqlGrammar.ANCHORED_DATATYPE, PlSqlGrammar.CUSTOM_DATATYPE) == null }
            .forEach { if (it.getFirstAncestorOrNull(DmlGrammar.INTO_CLAUSE) != null) valueReference(it, context, id, sql = false) else sqlName(statement, it) }
        root.getDescendants(DmlGrammar.SELECT_COLUMN).forEach { selectStar(statement, it) }
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
        val viewColumns: Set<String>?
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

    /** `q.col`, `schema.t.col`: a read of the source the qualifier names; false when no SQL source or qualifier matches. */
    private fun qualifiedColumn(statement: Statement, name: AstNode, segments: List<String>): Boolean {
        val names = segments.map { normalizeIdentifier(it) }
        var scope = sqlScope(name)
        while (scope != null) {
            for (source in scopeSources(statement, scope, name, qualified = true)) {
                val columnIndex = when {
                    source.qualifier != null && source.qualifier == names[0] -> 1
                    names.size >= 3 && source.target != null && source.qualifier == source.target.written.name &&
                        qualifierMatches(names.subList(0, 2), source.target) -> 2
                    else -> continue
                }
                val column = names.getOrNull(columnIndex) ?: return true
                if (column in SQL_PSEUDOCOLUMNS || source.target == null) return true
                val resolved = resolver.resolveColumn(source.target.resolved, column)
                statement.occurrence(name, resolved.entityKey, COLUMN_READ, resolved.status, resolved.reason)
                return true
            }
            scope = sqlScope(scope)
        }
        return isSqlQualified(name, segments)
    }

    /**
     * An unqualified name: captured by SQL (a pseudocolumn, an `ORDER BY` alias), else a column of exactly one source
     * of the innermost scope that has it, else the next scope out while every source of this one is complete. A
     * scope with a source of unknown columns leaves the name undecided; a name no scope captures is a PL/SQL name.
     */
    private fun unqualifiedColumn(statement: Statement, name: AstNode, column: String) {
        if (column in SQL_PSEUDOCOLUMNS || isOrderByAlias(name, column)) return
        var scope = sqlScope(name)
        while (scope != null) {
            val sources = scopeSources(statement, scope, name, qualified = false)
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
                subquery != null -> ColumnSource(clause, alias, null, viewLike = true, viewColumns = selectNames(statement, subquery))
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
            return ColumnSource(reference, alias ?: written.name, null, viewLike = true, viewColumns = factoring?.let { withColumns(statement, it) })
        }
        if (written.link == null && written.name == "DUAL" && (written.schema == null || written.schema == "SYS")) {
            return ColumnSource(reference, alias ?: written.name, null, viewLike = true, viewColumns = setOf("DUMMY"))
        }
        val target = statement.targets[reference] ?: return ColumnSource(reference, alias ?: written.name, null, viewLike = false, viewColumns = null)
        return ColumnSource(reference, alias ?: target.alias ?: written.name, target, viewLike = false, viewColumns = null)
    }

    /** The column names of a `WITH` name: its column list, else the names of its subquery's select list. */
    private fun withColumns(statement: Statement, factoring: AstNode): Set<String>? {
        val children = factoring.children
        val listed = children.drop(1).takeWhile { it.type != PlSqlKeyword.AS }.filter { it.type == PlSqlGrammar.IDENTIFIER_NAME }
        if (listed.isNotEmpty()) return listed.mapTo(HashSet()) { normalizeIdentifier(it.tokenOriginalValue) }
        return factoring.getFirstChildOrNull(DmlGrammar.SELECT_EXPRESSION)?.let { selectNames(statement, it) }
    }

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

    /** An `ORDER BY` item naming an alias of the select list it orders. */
    private fun isOrderByAlias(name: AstNode, column: String): Boolean {
        val orderBy = name.getFirstAncestorOrNull(DmlGrammar.ORDER_BY_CLAUSE) ?: return false
        val owner = orderBy.parentOrNull ?: return false
        val block = if (owner.type == DmlGrammar.QUERY_BLOCK) owner else owner.getFirstChildOrNull(DmlGrammar.QUERY_BLOCK) ?: return false
        if (sqlScope(name) !== owner && sqlScope(name) !== block) return false
        return block.getChildren(DmlGrammar.SELECT_COLUMN).any { columnAlias(it) == column }
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

        val CURSOR_ATTRIBUTES = setOf("ISOPEN", "FOUND", "NOTFOUND", "ROWCOUNT", "BULK_ROWCOUNT", "BULK_EXCEPTIONS")

        /** Collection methods that change the collection. */
        val MODIFYING_METHODS = setOf("DELETE", "EXTEND", "TRIM")

        /** Call statuses that say nothing about a name when no declaration was selected. */
        val INCONCLUSIVE = setOf("INCOMPLETE_INDEX", "PARTIAL_DECLARATIONS")

        val SQL_SCOPES = arrayOf(
            DmlGrammar.QUERY_BLOCK, DmlGrammar.SELECT_EXPRESSION, DmlGrammar.UPDATE_EXPRESSION,
            DmlGrammar.DELETE_EXPRESSION, DmlGrammar.INSERT_EXPRESSION, DmlGrammar.MERGE_EXPRESSION
        )
    }
}
