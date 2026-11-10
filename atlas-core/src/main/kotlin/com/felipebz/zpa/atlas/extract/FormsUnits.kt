//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.DdlGrammar
import com.felipebz.zpa.api.DmlGrammar
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.TclGrammar
import com.felipebz.zpa.api.semantics.NameDeclarationKind
import com.felipebz.zpa.atlas.forms.FormsLayout
import com.felipebz.zpa.atlas.model.*

/**
 * The units of a Forms module's text ([layout]): each one recorded, with its definition and declarations, then walked
 * ([walk]) as code compiled on its own in the module's [schema]; and the names its packages give ([names]).
 */
internal class FormsUnits(
    private val layout: FormsLayout,
    private val entities: FormsEntities,
    private val state: FileState,
    private val bound: BoundNames,
    private val bodyGlobals: BodyGlobals,
    /** The schema the module's code compiles in; null when none applies. */
    private val schema: String?,
    /** Where a unit's definition is added, read with the declarations' when the file is left. */
    private val unitDefinitions: MutableList<FileExtractor.UnitDefinition>,
    /** The walk of a unit's code. */
    private val walk: (AstNode, Context) -> Unit
) {
    /** A parameter of a routine of a Forms unit, defined when the file is left. */
    class Parameter(val key: String, val parentKey: String, val node: AstNode, val name: AstNode, val unitIndex: Int)

    /** The parameters of the module's routines, in the order of the walk. */
    val parameters = ArrayList<Parameter>()

    /** The names the module's code gives the declarations of its packages ([FormsEntities.names]). */
    fun names(root: AstNode): FormsNames = entities.names(modulePackages(root), specifiedMembers(root))

    /**
     * The units of the module's text [root]: each top-level node is the code of the unit of [layout] at its first line.
     * A recovered region is an unparsed unit in each unit of the module it overlaps, cut to that unit's lines, with its
     * path and entity: the parser may recover past the end of a unit's code. A unit's code is compiled on its own, so
     * only a recovered region is partial.
     */
    fun units(root: AstNode) {
        for (child in root.children) {
            val unit = layout.unitAt(child.tokenLine)
            when (child.type) {
                PlSqlGrammar.RECOVERY -> recoveredRegion(child)
                PlSqlGrammar.COMPILATION_UNIT -> walkUnit(child.firstChild, unit)
                DmlGrammar.DML_COMMAND, DdlGrammar.DDL_COMMAND -> walkUnit(child, unit)
                TclGrammar.TCL_COMMAND -> if (child.hasDirectChildren(TclGrammar.LOCK_TABLE_EXPRESSION)) walkUnit(
                    child,
                    unit
                )

                else -> Unit
            }
        }
    }

    /**
     * A recovered region [node]: an unparsed unit in each unit of [layout] it overlaps, cut to that unit's lines, with
     * its path and entity; an unparsed unit of no path when it overlaps none.
     */
    private fun recoveredRegion(node: AstNode) {
        state.recoveredRegions++
        val span = spanOf(node)
        val overlapped = layout.units.filter { it.firstLine <= span.endLine && it.lastLine >= span.startLine }
        if (overlapped.isEmpty()) state.addUnit(UnitFact(UnitKind.UNPARSED, "", null, span, schema), partial = true)
        for (part in overlapped) {
            val start = if (part.firstLine > span.startLine) SourceSpan(part.firstLine, 0, 0, 0) else span
            val end = if (part.lastLine < span.endLine) SourceSpan(0, 0, part.lastLine + 1, 0) else span
            state.addUnit(
                UnitFact(
                    UnitKind.UNPARSED, part.path, entities.keyOf(part),
                    SourceSpan(start.startLine, start.startCol, end.endLine, end.endCol), schema
                ), partial = true
            )
        }
    }

    /**
     * One unit of the module: a trigger or a program unit, whose entity the module gives, defined by its code (a
     * package specification declares it), with its parameters and declarations; or a record group query, an SQL
     * statement. Its database names resolve as in an anonymous block: no enclosing package, the run's compilation schema.
     */
    private fun walkUnit(node: AstNode, unit: FormsLayout.Unit?) {
        val key = unit?.let { entities.keyOf(it) }
        val kind = when (unit?.kind) {
            FormsLayout.Kind.TRIGGER -> UnitKind.FORMS_TRIGGER
            FormsLayout.Kind.PROGRAM_UNIT -> UnitKind.FORMS_PROGRAM_UNIT
            FormsLayout.Kind.RECORD_GROUP_QUERY -> UnitKind.SQL_STATEMENT
            null -> {
                state.coverage += CoverageFact(
                    "FORMS_CODE_OUTSIDE_UNIT",
                    "Code at line ${node.tokenLine} of the module text is in no unit of the module"
                )
                if (node.type == PlSqlGrammar.ANONYMOUS_BLOCK) UnitKind.ANONYMOUS_BLOCK else UnitKind.SQL_STATEMENT
            }
        }
        val span = spanOf(node)
        val index = state.addUnit(UnitFact(kind, unit?.path.orEmpty(), key, span, schema), partial = false)
        if (key != null) {
            val name = when (node.type) {
                PlSqlGrammar.CREATE_PACKAGE, PlSqlGrammar.CREATE_PACKAGE_BODY -> node.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME)
                PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION -> node.getFirstChildOrNull(
                    PlSqlGrammar.IDENTIFIER_NAME
                )

                else -> null
            }
            // A trigger has no name in its code: its first token, the `BEGIN` of its header line, stands for it.
            val nameSpan = name?.let(::spanOf) ?: node.token.let {
                SourceSpan(
                    it.line,
                    it.column,
                    it.line,
                    it.column + it.originalValue.length
                )
            }
            val role = if (node.type == PlSqlGrammar.CREATE_PACKAGE) Role.DECLARATION else Role.DEFINITION
            state.occurrences += OccurrenceFact(
                index, key, null, span, name?.let(::writtenText) ?: unit.name, key, setOf(role), Status.RESOLVED,
                Origin.STATIC, null, nameSpan = nameSpan
            )
            unitDefinitions += FileExtractor.UnitDefinition(
                key,
                span,
                node.type == PlSqlGrammar.CREATE_PACKAGE_BODY,
                node
            )
            declarations(node, key, index)
        }
        walk(node, Context(index, false, schema, key, null))
    }

    /**
     * The declarations of a unit [node] whose entity is [key], which zpa's project does not see: a routine's
     * parameters and its own declarations ([BodyGlobals.declareRoutine]), a trigger's declarations, a package's globals
     * (the specification's keyed `PKG.NAME`, the body's `PKG.NAME@BODY`) and, for each routine of a package body, its
     * parameters and declarations under the package, keyed with the routine's name and signature.
     */
    private fun declarations(node: AstNode, key: String, index: Int) {
        when (node.type) {
            PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION -> routine(node, key, key, index)
            PlSqlGrammar.ANONYMOUS_BLOCK -> bodyGlobals.declareRoutine(node, key, key, null, index, false) { false }
            PlSqlGrammar.CREATE_PACKAGE -> bodyGlobals.declare(node, key, null, index, false, suffix = "")
            PlSqlGrammar.CREATE_PACKAGE_BODY -> {
                bodyGlobals.declare(node, key, null, index, false)
                for (item in node.getFirstChildOrNull(PlSqlGrammar.DECLARE_SECTION)?.children.orEmpty()) {
                    if (item.type != PlSqlGrammar.PROCEDURE_DECLARATION && item.type != PlSqlGrammar.FUNCTION_DECLARATION) continue
                    val name = item.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) ?: continue
                    routine(
                        item,
                        key,
                        "$key.${normalizeIdentifier(name.tokenOriginalValue)}${BodyGlobals.signature(item)}",
                        index
                    )
                }
            }
        }
    }

    /** A routine of a unit: its parameters, `keyBase.NAME` under [parentKey], whose uses zpa binds, then its own declarations. */
    private fun routine(routine: AstNode, parentKey: String, keyBase: String, index: Int) {
        val taken = HashSet<String>()
        for (parameter in routine.getFirstChildOrNull(PlSqlGrammar.PARAMETER_DECLARATIONS)
            ?.getChildren(PlSqlGrammar.PARAMETER_DECLARATION).orEmpty()) {
            val name = parameter.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME) ?: continue
            val normalized = normalizeIdentifier(name.tokenOriginalValue)
            val key = "$keyBase.$normalized"
            if (!taken.add(key)) continue
            state.entities += EntityFact(
                key,
                EntityKind.PARAMETER,
                null,
                normalized,
                parentKey,
                displayOf(key),
                external = false,
                precedence = Precedence.DECLARED
            )
            state.relations += RelationFact(parentKey, RelationKind.CONTAINS, key)
            bound.add(NameDeclarationKind.PARAMETER, name.token.line, name.token.column, key)
            parameters += Parameter(key, parentKey, parameter, name, index)
        }
        bodyGlobals.declareRoutine(routine, parentKey, keyBase, null, index, false, taken::contains)
    }

    /**
     * What the packages of the module's text declare, by package name: each name its specification and its body
     * declare, a subprogram or not ([FormsNames]).
     */
    private fun modulePackages(root: AstNode): Map<String, Map<String, Boolean>> {
        val result = HashMap<String, HashMap<String, Boolean>>()
        for (child in root.getChildren(PlSqlGrammar.COMPILATION_UNIT)) {
            val unit = child.firstChild
            if (unit.type != PlSqlGrammar.CREATE_PACKAGE && unit.type != PlSqlGrammar.CREATE_PACKAGE_BODY) continue
            val name = unit.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME)?.getChildren(PlSqlGrammar.IDENTIFIER_NAME)
                ?.lastOrNull() ?: continue
            val declared = result.getOrPut(normalizeIdentifier(name.tokenOriginalValue)) { HashMap() }
            for (declaration in unitDeclarations(unit)) declared[declaration.name] =
                (declared[declaration.name] ?: false) || declaration.subprogram
        }
        return result
    }

    /**
     * What the package specifications of the module's text declare as entities, by package name: their variables,
     * constants and types, each declaration by its name, its entity keyed `PKG.NAME` ([declarations]).
     */
    private fun specifiedMembers(root: AstNode): Map<String, Map<String, AstNode>> {
        val result = HashMap<String, HashMap<String, AstNode>>()
        for (child in root.getChildren(PlSqlGrammar.COMPILATION_UNIT)) {
            val unit = child.firstChild.takeIf { it.type == PlSqlGrammar.CREATE_PACKAGE } ?: continue
            val name = unit.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME)?.getChildren(PlSqlGrammar.IDENTIFIER_NAME)
                ?.lastOrNull() ?: continue
            val declared = result.getOrPut(normalizeIdentifier(name.tokenOriginalValue)) { HashMap() }
            for (item in unit.getFirstChildOrNull(PlSqlGrammar.DECLARE_SECTION)?.children.orEmpty()) {
                BodyGlobals.declaredName(item)
                    ?.let { declared.putIfAbsent(normalizeIdentifier(it.tokenOriginalValue), item) }
            }
        }
        return result
    }
}
