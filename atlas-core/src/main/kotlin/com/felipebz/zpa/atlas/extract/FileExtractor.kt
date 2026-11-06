//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.*
import com.felipebz.zpa.api.checks.PlSqlCheck
import com.felipebz.zpa.api.dynamicsql.DynamicSqlText
import com.felipebz.zpa.api.project.ProjectExternalLookup
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.api.project.SequenceReferenceResolution
import com.felipebz.zpa.api.semantics.NameDeclarationKind
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.forms.FormsLayout
import com.felipebz.zpa.atlas.model.*

/**
 * Extracts the facts of one file from its AST, once the scanner's walk of it ends. A new instance is used for every
 * file; it must be a [PlSqlCheck] because the scanner installs the project analysis only on checks. Nothing is written
 * here: the facts are returned by [facts] and handed to the single writer.
 *
 * The text of a Forms module ([forms]) is walked as the database's code is, unit by unit, each unit the module's code
 * at its lines: a trigger, a program unit or a record group query, named by its path, whose entity is the module's.
 */
class FileExtractor internal constructor(
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
    private val typeMembers: ObjectTypeMembers = ObjectTypeMembers(),
    /** The run's instances of the strings its occurrences hold. */
    strings: FactStrings = FactStrings(),
    /** Where the units of a Forms module's text are, when the file is one; null for a database source. */
    private val forms: FormsLayout? = null
) : PlSqlCheck() {

    private val state = FileState(strings)
    private lateinit var catalog: DeclarationCatalog
    private lateinit var calls: CallResolver
    private lateinit var types: TypeReferenceResolver
    private lateinit var values: PackageValues
    private lateinit var runSchemas: Set<String>
    private var result: FileFacts? = null
    private var root: AstNode? = null

    /**
     * What `resolveSequenceReference` answers for the file's `….NEXTVAL` and `….CURRVAL` names at [visitFile], before
     * zpa's project visitors add the per-node results it would see later.
     */
    private var sequenceResolutions: Map<AstNode, SequenceReferenceResolution> = emptyMap()

    /** The dynamic statements whose text is a variable, decided once the whole file is walked (every call's modes known). */
    private val variableStatements = ArrayList<VariableStatement>()

    private class VariableStatement(val occurrence: Int, val variable: AstNode, val context: Context, val node: AstNode)

    /** The text nodes of the file's dynamic statements. */
    private val dynamicTexts: MutableSet<AstNode> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    /** The entities of the Forms module the file is, keyed before the walk. */
    private val formsEntities = forms?.let { FormsEntities(it) }

    private val synonyms = SynonymResolver(dictionary)
    private val resolver = RelationResolver(dictionary, synonyms, state.entities, state.relations)

    // The collaborators of the walk, created when it starts (they need the file's legacy symbol table).
    private lateinit var triggers: Triggers
    private lateinit var plsql: PlSqlNames
    private lateinit var bodyGlobals: BodyGlobals
    private lateinit var fields: FieldUses
    private lateinit var typeReferences: TypeReferences
    private lateinit var sql: SqlStatements
    private lateinit var lineage: ViewLineage
    private lateinit var ddl: DdlTargets
    private lateinit var places: IntoClauses
    private lateinit var shapes: TypeShapes
    private lateinit var bound: BoundNames

    override fun init() {
        val snapshot = projectAnalysis().snapshot
        catalog = catalogOf(snapshot)
        runSchemas = catalog.schemas + dictionary.schemas + listOfNotNull(defaultSchema) + setOf("SYS", "SYSTEM")
        values = PackageValues(snapshot, catalog)
        calls = CallResolver(snapshot, catalog, values, synonyms, state.entities, state.relations, runSchemas)
        types = TypeReferenceResolver(snapshot, catalog, values, resolver, synonyms, state.entities, state.relations, runSchemas)
    }

    /**
     * The walk only notes the tree: [facts] extracts it once the scanner returns. What the extraction asks of zpa is per
     * file or fixed for the run, except `resolveSequenceReference`, asked here for every name that may be a sequence
     * pseudocolumn: its answer depends on how far zpa's project visitors have walked the file.
     */
    override fun visitFile(node: AstNode) {
        root = node
        sequenceResolutions = node.getDescendants(PlSqlGrammar.MEMBER_EXPRESSION)
            .filter { normalizeIdentifier(it.lastChild.tokenValue) in PSEUDOCOLUMNS }
            .associateWithTo(java.util.IdentityHashMap()) { projectAnalysis().resolveSequenceReference(it) }
    }

    /** The facts of the tree [visitFile] noted: the walk of its units, then what needs the whole file walked. */
    private fun extract(node: AstNode): FileFacts {
        val scopes = SqlScopes()
        bound = BoundNames(context::nameBinding, context::typeBinding)
        // The parameters of the file's subprograms and cursors that have an entity: their uses are the names zpa binds
        // to them. A parameter's declaration starts with its name, where zpa places it.
        for (declaration in catalog.occurrencesFor(path)) {
            if (declaration.containerKey == null) continue
            bound.add(NameDeclarationKind.PARAMETER, declaration.span.startLine, declaration.span.startCol, declaration.entityKey)
        }
        bodyGlobals = BodyGlobals(bound, context::recordFields)
        fields = FieldUses(state, catalog, path, NameRoles(state), context::fieldBinding, context::nameBinding, bodyGlobals::fieldAt)
        val sequences = projectAnalysis()
        val formsNames = formsEntities?.names(modulePackages(node))
        plsql = PlSqlNames(state, scopes, catalog, calls, values, synonyms, typeMembers, runSchemas, jsonBuiltIn, bound, fields, formsNames) {
            sequenceResolutions[it] ?: sequences.resolveSequenceReference(it)
        }
        triggers = Triggers(state, resolver, plsql)
        typeReferences = TypeReferences(state, types, resolver, calls, plsql, bound, jsonBuiltIn, formsNames)
        val columnReads = ColumnReads(state, resolver, scopes, plsql, context::nameBinding)
        lineage = ViewLineage(state, scopes, columnReads, plsql)
        places = IntoClauses(state, plsql, columnReads, lineage, context::nameBinding)
        sql = SqlStatements(state, resolver, scopes, plsql, typeReferences, triggers, columnReads, places)
        ddl = DdlTargets(path, state, dictionary, resolver, values, catalog, plsql)
        if (forms != null) formsUnits(node, forms) else databaseUnits(node)
        variableTexts()
        // zpa resolves the members after an element, a call result or another file's package variable once it walked the file.
        fields.record()
        shapes = TypeShapes(state, bound, context::typeBinding, node)
        for ((key, datatype) in returnTypes) declaredShapes += DeclaredShapeFact(key, shapes.datatype(datatype))
        intos = places.intos(shapes)
        return build(ParseStatus.OK.takeIf { state.recoveredRegions == 0 } ?: ParseStatus.RECOVERED).also { result = it }
    }

    /** The units of a database source: its compilation units and SQL statements, a recovered region and what follows it partial. */
    private fun databaseUnits(node: AstNode) {
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
    }

    /**
     * The dynamic statements whose text is a variable: the reason of each one's `DYNAMIC` occurrence, then, when the
     * variable holds one known text, the walk of that text ([VariableSqlTexts]). A value is code at one place: when
     * several statements run it, it is walked once, in the context of the first of them in the file. A text read from a
     * variable written several times also gets a coverage note that says how it was read ([VariableSqlTexts.Read]).
     */
    private fun variableTexts() {
        if (variableStatements.isEmpty()) return
        val texts = VariableSqlTexts(this.context, state, dynamicTexts)
        // The nodes a text was read from, and the text walked for them: AstNode compares by identity, so equal lists are
        // the same value.
        val walked = HashMap<List<AstNode>, DynamicSqlText>()
        for (statement in variableStatements.sortedWith(compareBy({ it.variable.token.line }, { it.variable.token.column }))) {
            val outcome = texts.outcome(statement.variable)
            state.occurrences[statement.occurrence] = state.occurrences[statement.occurrence].copy(reason = outcome.reason)
            outcome.read?.let { read ->
                val at = statement.variable.token
                state.coverage += CoverageFact(read.note, "Dynamic statement at line ${at.line}, column ${at.column}: its text is ${read.description}")
            }
            val text = outcome.text ?: continue
            val value = outcome.value!!
            val known = walked[value]
            if (known == null) {
                walked[value] = text
                walk(text.root, statement.context.dynamicText(text))
            }
            if (statement.node.type == PlSqlGrammar.OPEN_FOR_STATEMENT) places.openText(statement.node, known ?: text, statement.occurrence)
        }
    }

    /**
     * The facts of the file, extracted on the first call, once the scanner's walk ended; a file the parser rejected has
     * only its declarations, in one unparsed unit.
     */
    fun facts(): FileFacts = result
        ?: root?.let { extract(it) }
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
        for ((reason, count) in state.unvaluedWrites) {
            state.coverage += CoverageFact("WRITE_VALUE_UNKNOWN", "$count column write(s) without a value: $reason")
        }
        if (state.undecidedSqlNames > 0) {
            state.coverage += CoverageFact("SQL_NAME_UNDECIDED", "${state.undecidedSqlNames} unqualified name(s) in static SQL may be a column of a relation whose columns are unknown or a PL/SQL name: not indexed")
        }
        // A file the parser rejected was never walked: no body globals, no parameter uses, no gaps.
        val usesGaps = if (::bodyGlobals.isInitialized) bodyGlobals.gaps(context::usageEvidence, context::regionUncertainty) + parameterGaps else emptyList()
        // From the declarations, as the declaration occurrences: a file the parser rejected keeps them.
        val membersGaps = if (::catalog.isInitialized) catalog.membersGapsFor(path) else emptyList()
        var formsObjects = emptyList<FormsObjectFact>()
        var formsUses = emptyList<FormsUseFact>()
        formsEntities?.let {
            val blocks = FormsDataBlocks(forms!!, it, resolver, defaultSchema)
            state.entities += it.entities
            state.relations += it.relations
            state.relations += blocks.relations
            declaredTexts += it.declaredTexts
            state.coverage += blocks.coverage
            formsObjects = it.objects.map(blocks::withProperties)
            formsUses = blocks.uses
        }
        forms?.notes?.forEach { state.coverage += CoverageFact(it.kind, it.message) }
        // Mentioned relations and columns repeat once per reference: keep the first of each exact duplicate.
        return FileFacts(path, sha256, sizeBytes, encoding, status, state.recoveredRegions, defaultSchema, state.units, state.statements, state.occurrences,
            LinkedHashSet(state.entities).toList(), LinkedHashSet(state.relations).toList(), state.coverage, resolver.dictionaryRelations.toList(),
            synonyms.dictionarySynonyms.toList(), state.triggerEvents.toList(), declaredTexts + state.columnTypes, docComments, usesGaps, membersGaps,
            declaredShapes, intos, state.placesLeftOut.toMap(), if (forms == null) FileKind.DB_SOURCE else FileKind.FORMS_MODULE, formsObjects, formsUses)
    }

    /** The types the file's declarations with an entity write ([TypeShapes]); none for a file that was not walked. */
    private val declaredShapes = ArrayList<DeclaredShapeFact>()

    /** The return types of the file's functions, by the key of what each returns; their shapes are told when the file is left. */
    private val returnTypes = ArrayList<Pair<String, AstNode>>()

    /** The `INTO`s of the file ([IntoClauses]), decided when the file is left. */
    private var intos: List<IntoFact> = emptyList()

    private fun declaredShape(key: String, node: AstNode) {
        if (::shapes.isInitialized) shapes.of(node, key)?.let { declaredShapes += DeclaredShapeFact(key, it) }
    }

    // ---- units --------------------------------------------------------------------------------------------------

    private fun sqlUnit(node: AstNode, partial: Boolean) {
        val index = state.addUnit(UnitFact(UnitKind.SQL_STATEMENT, "", null, spanOf(node), defaultSchema), partial)
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
        // A package body's own variables, constants and types, and each routine's, before the walk that finds their uses.
        if (kind == UnitKind.PACKAGE_BODY) entityKey?.let { bodyGlobals.declare(node, it, catalog.schemaAndName(path, written).first, index, partial, state) }
        declareRoutines(node, kind, entityKey, written, index, partial)
        if (kind == UnitKind.PACKAGE_BODY || kind == UnitKind.TYPE_BODY || kind == UnitKind.TRIGGER || (kind == UnitKind.VIEW || kind == UnitKind.MATERIALIZED_VIEW) && entityKey != null) {
            // Package bodies, type bodies, triggers and views are not in the project index; their definition comes from the AST.
            state.occurrences += OccurrenceFact(index, entityKey, null, span, writtenText(unitName ?: node), entityKey,
                setOf(Role.DEFINITION), status(Status.RESOLVED, partial), Origin.STATIC, null, nameSpan = unitName?.let(::spanOf))
            entityKey?.let { unitDefinitions += UnitDefinition(it, span, kind == UnitKind.PACKAGE_BODY || kind == UnitKind.TYPE_BODY, node) }
        }
        val enclosingPackage = if (kind == UnitKind.PACKAGE || kind == UnitKind.PACKAGE_BODY) {
            unitName?.getChildren(PlSqlGrammar.IDENTIFIER_NAME)?.map { it.tokenOriginalValue }?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val bodyNames = if (kind == UnitKind.PACKAGE_BODY) {
            unitDeclarations(node).filter { !it.subprogram }.mapTo(HashSet()) { it.name }
        } else {
            emptySet()
        }
        val objectType = if (kind == UnitKind.TYPE_BODY) ObjectTypeMembers.typeKey(unitName, defaultSchema) else null
        walk(node, Context(index, partial, schema, entityKey, trigger, enclosingPackage, bodyNames, objectType))
        view = null
    }

    /**
     * The routines of the unit [node] of [kind] whose entity is [entityKey]: a package body's subprograms, a standalone
     * subprogram, a trigger, a type body's methods (whose declarations hang on the type, keyed with the method's name and
     * signature);
     * each one's own declarations ([BodyGlobals.declareRoutine]). An anonymous block or script has no routine: its
     * declarations are left out.
     */
    private fun declareRoutines(node: AstNode, kind: UnitKind, entityKey: String?, written: List<String>, index: Int, partial: Boolean) {
        val routines = ArrayList<Triple<AstNode, String, String>>()
        when (kind) {
            UnitKind.PACKAGE_BODY -> for (item in node.getFirstChildOrNull(PlSqlGrammar.DECLARE_SECTION)?.children.orEmpty()) {
                if (item.type != PlSqlGrammar.PROCEDURE_DECLARATION && item.type != PlSqlGrammar.FUNCTION_DECLARATION) continue
                subprogramAt(item)?.let { routines += Triple(item, it, it) }
            }
            UnitKind.PROCEDURE, UnitKind.FUNCTION, UnitKind.TRIGGER -> entityKey?.let { routines += Triple(node, it, it) }
            UnitKind.TYPE_BODY -> if (entityKey != null) for (member in node.children) {
                val method = when (member.type) {
                    PlSqlGrammar.TYPE_SUBPROGRAM, PlSqlGrammar.MAP_ORDER_FUNCTION ->
                        member.children.firstOrNull { it.type == PlSqlGrammar.PROCEDURE_DECLARATION || it.type == PlSqlGrammar.FUNCTION_DECLARATION }
                    PlSqlGrammar.TYPE_CONSTRUCTOR -> member
                    else -> null
                } ?: continue
                val name = method.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { normalizeIdentifier(it.tokenOriginalValue) } ?: continue
                // The method's signature tells its overloads apart: `TY.PATH_PUT(ELEM IN PLJSON_ELEMENT,…)`.
                routines += Triple(method, entityKey, "$entityKey.$name${BodyGlobals.signature(method)}")
            }
            else -> Unit
        }
        if (routines.isEmpty()) return
        val schema = catalog.schemaAndName(path, written).first
        for ((routine, parentKey, keyBase) in routines) {
            bodyGlobals.declareRoutine(routine, parentKey, keyBase, schema, index, partial, state, catalog::isDeclared)
        }
    }

    // ---- Forms units --------------------------------------------------------------------------------------------

    /**
     * The units of a Forms module's text: each top-level node is the code of the unit of [layout] at its first line. A
     * recovered region is an unparsed unit in each unit of the module it overlaps, cut to that unit's lines, with its
     * path and entity: the parser may recover past the end of a unit's code. A unit's code is compiled on its own, so
     * only a recovered region is partial.
     */
    private fun formsUnits(node: AstNode, layout: FormsLayout) {
        for (child in node.children) {
            val unit = layout.unitAt(child.tokenLine)
            when (child.type) {
                PlSqlGrammar.RECOVERY -> {
                    state.recoveredRegions++
                    val span = spanOf(child)
                    val overlapped = layout.units.filter { it.firstLine <= span.endLine && it.lastLine >= span.startLine }
                    if (overlapped.isEmpty()) state.addUnit(UnitFact(UnitKind.UNPARSED, "", null, span, defaultSchema), partial = true)
                    for (part in overlapped) {
                        val start = if (part.firstLine > span.startLine) SourceSpan(part.firstLine, 0, 0, 0) else span
                        val end = if (part.lastLine < span.endLine) SourceSpan(0, 0, part.lastLine + 1, 0) else span
                        state.addUnit(UnitFact(UnitKind.UNPARSED, part.path, formsEntities!!.keyOf(part),
                            SourceSpan(start.startLine, start.startCol, end.endLine, end.endCol), defaultSchema), partial = true)
                    }
                }
                PlSqlGrammar.COMPILATION_UNIT -> formsUnit(child.firstChild, unit)
                DmlGrammar.DML_COMMAND, DdlGrammar.DDL_COMMAND -> formsUnit(child, unit)
                TclGrammar.TCL_COMMAND -> if (child.hasDirectChildren(TclGrammar.LOCK_TABLE_EXPRESSION)) formsUnit(child, unit)
                else -> Unit
            }
        }
    }

    /**
     * One unit of a Forms module: a trigger or a program unit, whose entity the module gives, defined by its code (a
     * package specification declares it), with its parameters and declarations; or a record group query, an SQL
     * statement. Its database names resolve as in an anonymous block: no enclosing package, the run's compilation schema.
     */
    private fun formsUnit(node: AstNode, unit: FormsLayout.Unit?) {
        val key = unit?.let { formsEntities!!.keyOf(it) }
        val kind = when (unit?.kind) {
            FormsLayout.Kind.TRIGGER -> UnitKind.FORMS_TRIGGER
            FormsLayout.Kind.PROGRAM_UNIT -> UnitKind.FORMS_PROGRAM_UNIT
            FormsLayout.Kind.RECORD_GROUP_QUERY -> UnitKind.SQL_STATEMENT
            null -> {
                state.coverage += CoverageFact("FORMS_CODE_OUTSIDE_UNIT", "Code at line ${node.tokenLine} of the module text is in no unit of the module")
                if (node.type == PlSqlGrammar.ANONYMOUS_BLOCK) UnitKind.ANONYMOUS_BLOCK else UnitKind.SQL_STATEMENT
            }
        }
        val span = spanOf(node)
        val index = state.addUnit(UnitFact(kind, unit?.path.orEmpty(), key, span, defaultSchema), partial = false)
        if (key != null && unit != null) {
            val name = when (node.type) {
                PlSqlGrammar.CREATE_PACKAGE, PlSqlGrammar.CREATE_PACKAGE_BODY -> node.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME)
                PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION -> node.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)
                else -> null
            }
            // A trigger has no name in its code: its first token, the `BEGIN` of its header line, stands for it.
            val nameSpan = name?.let(::spanOf) ?: node.token.let { SourceSpan(it.line, it.column, it.line, it.column + it.originalValue.length) }
            val role = if (node.type == PlSqlGrammar.CREATE_PACKAGE) Role.DECLARATION else Role.DEFINITION
            state.occurrences += OccurrenceFact(index, key, null, span, name?.let(::writtenText) ?: unit.name, key, setOf(role), Status.RESOLVED,
                Origin.STATIC, null, nameSpan = nameSpan)
            unitDefinitions += UnitDefinition(key, span, node.type == PlSqlGrammar.CREATE_PACKAGE_BODY, node)
            formsDeclarations(node, key, index)
        }
        walk(node, Context(index, false, defaultSchema, key, null))
    }

    /**
     * The declarations of a Forms unit [node] whose entity is [key], which zpa's project does not see: a routine's
     * parameters and its own declarations ([BodyGlobals.declareRoutine]), a trigger's declarations, a package's globals
     * (the specification's keyed `PKG.NAME`, the body's `PKG.NAME@BODY`) and, for each routine of a package body, its
     * parameters and declarations under the package, keyed with the routine's name and signature.
     */
    private fun formsDeclarations(node: AstNode, key: String, index: Int) {
        when (node.type) {
            PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION -> formsRoutine(node, key, key, index)
            PlSqlGrammar.ANONYMOUS_BLOCK -> bodyGlobals.declareRoutine(node, key, key, null, index, false, state) { false }
            PlSqlGrammar.CREATE_PACKAGE -> bodyGlobals.declare(node, key, null, index, false, state, suffix = "")
            PlSqlGrammar.CREATE_PACKAGE_BODY -> {
                bodyGlobals.declare(node, key, null, index, false, state)
                for (item in node.getFirstChildOrNull(PlSqlGrammar.DECLARE_SECTION)?.children.orEmpty()) {
                    if (item.type != PlSqlGrammar.PROCEDURE_DECLARATION && item.type != PlSqlGrammar.FUNCTION_DECLARATION) continue
                    val name = item.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) ?: continue
                    formsRoutine(item, key, "$key.${normalizeIdentifier(name.tokenOriginalValue)}${BodyGlobals.signature(item)}", index)
                }
            }
        }
    }

    /** A routine of a Forms unit: its parameters, `keyBase.NAME` under [parentKey], whose uses zpa binds, then its own declarations. */
    private fun formsRoutine(routine: AstNode, parentKey: String, keyBase: String, index: Int) {
        val taken = HashSet<String>()
        for (parameter in routine.getFirstChildOrNull(PlSqlGrammar.PARAMETER_DECLARATIONS)?.getChildren(PlSqlGrammar.PARAMETER_DECLARATION).orEmpty()) {
            val name = parameter.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) ?: continue
            val normalized = normalizeIdentifier(name.tokenOriginalValue)
            val key = "$keyBase.$normalized"
            if (!taken.add(key)) continue
            state.entities += EntityFact(key, EntityKind.PARAMETER, null, normalized, parentKey, displayOf(key), external = false, precedence = Precedence.DECLARED)
            state.relations += RelationFact(parentKey, RelationKind.CONTAINS, key)
            bound.add(NameDeclarationKind.PARAMETER, name.token.line, name.token.column, key)
            formsParameters += FormsParameter(key, parentKey, parameter, name, index)
        }
        bodyGlobals.declareRoutine(routine, parentKey, keyBase, null, index, false, state, taken::contains)
    }

    /** A parameter of a routine of a Forms unit, defined when the file is left. */
    private class FormsParameter(val key: String, val parentKey: String, val node: AstNode, val name: AstNode, val unitIndex: Int)

    private val formsParameters = ArrayList<FormsParameter>()

    /**
     * What the packages of a Forms module's text declare, by package name: each name its specification and its body
     * declare, a subprogram or not ([FormsNames]).
     */
    private fun modulePackages(root: AstNode): Map<String, Map<String, Boolean>> {
        if (forms == null) return emptyMap()
        val result = HashMap<String, HashMap<String, Boolean>>()
        for (child in root.getChildren(PlSqlGrammar.COMPILATION_UNIT)) {
            val unit = child.firstChild
            if (unit.type != PlSqlGrammar.CREATE_PACKAGE && unit.type != PlSqlGrammar.CREATE_PACKAGE_BODY) continue
            val name = unit.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME)?.getChildren(PlSqlGrammar.IDENTIFIER_NAME)?.lastOrNull() ?: continue
            val declared = result.getOrPut(normalizeIdentifier(name.tokenOriginalValue)) { HashMap() }
            for (declaration in unitDeclarations(unit)) declared[declaration.name] = (declared[declaration.name] ?: false) || declaration.subprogram
        }
        return result
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
        val token = node.token
        if (node.parentOrNull?.token !== token) plsql.conditionNames(token, context)
        var current = context
        // The object a DDL statement acts on; the walk goes on inside it (the query of CREATE TABLE … AS SELECT).
        if (ddl.isDdl(node)) ddl.statement(node, context)
        when (node.type) {
            PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION,
            PlSqlGrammar.CREATE_PROCEDURE, PlSqlGrammar.CREATE_FUNCTION -> {
                val key = subprogramAt(node)
                key?.let { current = context.inside(it) }
                if (context.dynamic == null) {
                    places.routine(node, current)
                    places.function(node, key)
                    // What a function returns, as its callers name it: the return type gives the shape of its value.
                    if (key != null) node.getFirstChildOrNull(PlSqlGrammar.DATATYPE)?.let { returnTypes += key.removeSuffix("@BODY") + "." + IntoClauses.RETURN_SUFFIX to it }
                }
            }
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
            PlSqlGrammar.EXECUTE_IMMEDIATE_STATEMENT -> {
                dynamic(node, after(node, PlSqlKeyword.IMMEDIATE), context)
                places.dynamic(node)
            }
            PlSqlGrammar.FETCH_STATEMENT -> places.fetch(node, context)
            PlSqlGrammar.ASSIGNMENT_STATEMENT -> if (context.dynamic == null) places.assignment(node, context)
            PlSqlGrammar.RETURN_STATEMENT -> if (context.dynamic == null) places.returnStatement(node, context)
            PlSqlGrammar.OPEN_FOR_STATEMENT -> if (!node.hasDirectChildren(DmlGrammar.SELECT_EXPRESSION)) {
                dynamic(node, after(node, PlSqlKeyword.FOR), context)
            }
            PlSqlGrammar.METHOD_CALL -> {
                dbmsSqlParse(node)?.let { dynamic(node, it, context) }
                val before = state.occurrences.size
                plsql.callSite(node, context, null)
                if (context.dynamic == null) places.call(node, before, context)
            }
            PlSqlGrammar.CALL_STATEMENT -> node.children.firstOrNull { it.type != PlSqlGrammar.LABELS && it.type != PlSqlPunctuator.SEMICOLON }
                ?.takeIf { it.type != PlSqlGrammar.METHOD_CALL }
                ?.let { plsql.callSite(it, context, null) }
            in NAMED_DATATYPES -> {
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
            PlSqlGrammar.UNIT_NAME -> when {
                typeReferences.isSupertype(node) || typeReferences.isObjectRelationType(node) -> typeReferences.supertypeUse(node, context)
                typeReferences.isSuperview(node) -> typeReferences.superviewUse(node, context)
            }
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
     * dynamic text is recorded but its text is not asked for: it is not a node of the file. A text that is a plain
     * variable is decided after the walk of the file ([variableTexts]).
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
        val occurrence = state.occurrences.size - 1
        if (text != null) walk(text.root, context.dynamicText(text))
        if (sql != null && context.dynamic == null) dynamicTexts += sql
        // The text a cursor variable is opened for: the query a FETCH of it reads (a variable's, once the file is walked).
        if (node.type == PlSqlGrammar.OPEN_FOR_STATEMENT && context.dynamic == null) places.openText(node, text, occurrence)
        val variable = if (text == null && sql != null && context.dynamic == null) VariableSqlTexts.variable(sql) else null
        if (variable != null) {
            dynamicTexts += variable
            variableStatements += VariableStatement(occurrence, variable, context, node)
        }
    }

    // ---- declarations -------------------------------------------------------------------------------------------

    /** What the declarations of the file say ([DeclaredTexts]); empty for a file that was not parsed. */
    private val declaredTexts = ArrayList<DeclaredTextFact>()

    /** The documentation comments of the declarations of the file ([DocComments]); empty for a file that was not parsed. */
    private val docComments = ArrayList<DocCommentFact>()

    /** The definitions the units give (package and type bodies, triggers, views): their documentation is read with the declarations'. */
    private class UnitDefinition(val entityKey: String, val span: SourceSpan, val inBody: Boolean, val node: AstNode)

    private val unitDefinitions = ArrayList<UnitDefinition>()

    private fun addDeclarationOccurrences() {
        val declarations = catalog.occurrencesFor(path)
        val found = root?.let { declarationNodes(it, declarations) }
        val names = found?.let { declarationNames(it, declarations) }.orEmpty()
        val docs = root?.takeIf { declarations.isNotEmpty() || unitDefinitions.isNotEmpty() || formsParameters.isNotEmpty() }
            ?.let { DocComments(it, forms?.units?.mapTo(HashSet()) { unit -> unit.firstLine }.orEmpty()) }
        for (definition in unitDefinitions) {
            val node = definition.node.takeIf { it.type in DocComments.NODE_TYPES }
            docs?.of(definition.entityKey, definition.span, definition.inBody, node)?.let { docComments += it }
            // A standalone type's definition: a collection of a type, or an object type.
            if (definition.node.type == PlSqlGrammar.CREATE_TYPE) declaredShape(definition.entityKey, definition.node)
        }
        // A body's own globals: their definition, what their declaration says and their documentation, as the spec's.
        // A file the parser rejected was never walked: it has none.
        for (global in if (::bodyGlobals.isInitialized) bodyGlobals.declared else emptyList()) {
            val span = spanOf(global.node)
            state.occurrences += OccurrenceFact(global.unitIndex, global.packageKey, null, span, global.name.tokenOriginalValue, global.key,
                setOf(Role.DEFINITION), status(Status.RESOLVED, global.partial), Origin.STATIC, null, nameSpan = spanOf(global.name))
            DeclaredTexts.of(global.key, global.node)?.let { declaredTexts += it }
            declaredShape(global.key, global.node)
            docs?.of(global.key, span, true, null)?.let { docComments += it }
        }
        // The parameters of a Forms module's routines: their definition, what their declaration says, their documentation.
        for (parameter in formsParameters) {
            val span = spanOf(parameter.node)
            state.occurrences += OccurrenceFact(parameter.unitIndex, parameter.parentKey, null, span, parameter.name.tokenOriginalValue, parameter.key,
                setOf(Role.DEFINITION), Status.RESOLVED, Origin.STATIC, null, nameSpan = spanOf(parameter.name))
            DeclaredTexts.of(parameter.key, parameter.node)?.let { declaredTexts += it }
            declaredShape(parameter.key, parameter.node)
            docs?.of(parameter.key, span, false, null)?.let { docComments += it }
            ParameterGaps.of(parameter.key, parameter.node, context::usageEvidence, context::regionUncertainty)?.let { parameterGaps += it }
        }
        // A body's own types and their fields: their definition and what their declaration says.
        for (member in if (::bodyGlobals.isInitialized) bodyGlobals.types else emptyList()) {
            val span = spanOf(member.node)
            state.occurrences += OccurrenceFact(member.unitIndex, member.packageKey, null, span, member.name.tokenOriginalValue, member.key,
                setOf(Role.DEFINITION), status(Status.RESOLVED, member.partial), Origin.STATIC, null, nameSpan = spanOf(member.name))
            DeclaredTexts.of(member.key, member.node)?.let { declaredTexts += it }
            declaredShape(member.key, member.node)
            docs?.of(member.key, span, true, null)?.let { docComments += it }
        }
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
            // A declaration of a region the parser recovered has no node of its span: nothing is read of it.
            val node = found?.declaring?.get(declaration.span)
            node?.let { DeclaredTexts.of(declaration.entityKey, it)?.let { text -> declaredTexts += text } }
            (found?.outermost?.get(declaration.span) ?: node)?.let { declaredShape(declaration.entityKey, it) }
            // A parameter's uses are zpa's bindings: say when its subprogram hides some (a file that was walked only).
            if (declaration.containerKey != null && node != null && ::bodyGlobals.isInitialized) {
                ParameterGaps.of(declaration.entityKey, node, context::usageEvidence, context::regionUncertainty)?.let { parameterGaps += it }
            }
            val inBody = unit.kind == UnitKind.PACKAGE_BODY || unit.kind == UnitKind.TYPE_BODY
            docs?.of(declaration.entityKey, declaration.span, inBody, found?.documented?.get(declaration.span))?.let { docComments += it }
        }
    }

    /** The parameters whose recorded uses may be incomplete ([ParameterGaps]). */
    private val parameterGaps = ArrayList<UsesGapFact>()

    /**
     * The nodes with the span of a declaration, found in one walk over the file, a node checked only when it starts
     * where a declaration starts: the outermost one ([outermost]), the one of a [DeclaredTexts.NODE_TYPES] type
     * ([declaring]), which is the declaration itself under its wrappers, and a package or routine ([documented]).
     */
    private class DeclarationNodes(
        val root: AstNode, val outermost: Map<SourceSpan, AstNode>, val declaring: Map<SourceSpan, AstNode>, val documented: Map<SourceSpan, AstNode>
    )

    private fun declarationNodes(root: AstNode, declarations: List<DeclarationOccurrence>): DeclarationNodes {
        val spans = declarations.mapTo(HashSet()) { it.span }
        val starts = declarations.mapTo(HashSet()) { position(it.span.startLine, it.span.startCol) }
        val outermost = HashMap<SourceSpan, AstNode>()
        val declaring = HashMap<SourceSpan, AstNode>()
        val documented = HashMap<SourceSpan, AstNode>()
        fun visit(node: AstNode) {
            if (node.hasToken() && position(node.token.line, node.token.column) in starts) {
                val span = spanOf(node)
                if (span in spans) {
                    outermost.putIfAbsent(span, node)
                    if (node.type in DeclaredTexts.NODE_TYPES) declaring.putIfAbsent(span, node)
                    if (node.type in DocComments.NODE_TYPES) documented.putIfAbsent(span, node)
                }
            }
            for (child in node.children) visit(child)
        }
        if (declarations.isNotEmpty()) visit(root)
        return DeclarationNodes(root, outermost, declaring, documented)
    }

    /**
     * The name spans of [declarations], found in the AST: the outermost node with the declaration's span, then its
     * first `UNIT_NAME` or `IDENTIFIER_NAME` in source order (for a `UNIT_NAME`, the whole name or its last segment),
     * kept only when its text is the written name.
     */
    private fun declarationNames(nodes: DeclarationNodes, declarations: List<DeclarationOccurrence>): Map<DeclarationOccurrence, SourceSpan> {
        if (declarations.isEmpty()) return emptyMap()
        val bySpan = declarations.groupBy { it.span }
        val root = nodes.root
        val found = nodes.outermost
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
