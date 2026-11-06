//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.atlas.forms.FormsBuiltins
import com.felipebz.zpa.atlas.forms.FormsItem
import com.felipebz.zpa.atlas.forms.FormsItemDataType
import com.felipebz.zpa.atlas.forms.FormsItemKind
import com.felipebz.zpa.atlas.forms.FormsLayout
import com.felipebz.zpa.atlas.forms.FormsLengthSemantics
import com.felipebz.zpa.atlas.forms.FormsProgramUnitKind
import com.felipebz.zpa.atlas.forms.FormsTriggerLevel
import com.felipebz.zpa.atlas.model.*

/** The key prefix of the entities of Forms modules: `forms:` and a path in the module (`forms:ORDERS/ORDERS/WHEN-NEW-RECORD-INSTANCE`). */
internal const val FORMS_PREFIX = "forms:"

/** The key prefix of a Forms global variable, the same in every module: `forms::GLOBAL.` and its name (`forms::GLOBAL.USER_ID`). */
internal const val FORMS_GLOBAL_PREFIX = "$FORMS_PREFIX:GLOBAL."

/** What an entity shows: its key without the namespace prefix (`db:` or [FORMS_PREFIX]). */
internal fun displayOf(key: String): String = key.removePrefix("db:").removePrefix(FORMS_PREFIX)

/**
 * The entities of a Forms module ([FormsLayout]) and their keys: `forms:` and the object's path, unique in the module
 * (a path two objects share, a block and a program unit of the same name, numbers the later one `#2`). The module,
 * its blocks, their items, the triggers, the program units (a package's specification and body are one), the record
 * groups with a query, the attached libraries and the parameters (`MODULE/PARAMETER/NAME`); each contained in the
 * object it is on (a trigger on an event is the module's), a library attached to the module. [objects] lists them in
 * the order of the file, each piece of code with the lines of its own text. The globals the code names ([global]) are
 * not the module's: one entity, `forms::GLOBAL.NAME`, for every module that names it.
 */
internal class FormsEntities(private val layout: FormsLayout) {
    val entities = ArrayList<EntityFact>()
    val relations = ArrayList<RelationFact>()
    val objects = ArrayList<FormsObjectFact>()

    /** The module's internal name, when it is not the name it is known by: what its page says it declares. */
    val declaredTexts = if (layout.internalName.equals(layout.name, ignoreCase = true)) emptyList()
    else listOf(DeclaredTextFact(FORMS_PREFIX + layout.name, null, layout.internalName, false))
    private val used = HashSet<String>()
    private val owners = HashMap<List<String>, String>()
    private val programUnits = HashMap<String, String>()
    private val unitKeys = HashMap<FormsLayout.Unit, String>()

    /** The entity of each block of the layout, in its order, and of each of its items. */
    val blockKeys = ArrayList<String>()
    val itemKeys = ArrayList<List<String>>()

    /** The entity of each parameter of the layout, in its order. */
    val parameterKeys = ArrayList<String>()
    private val globals = HashSet<String>()

    /**
     * What each item and parameter holds, as the type of a declaration, for the places column values are read into:
     * a text or display item of a character data type holds its maximum length, in its unit (the compile-time
     * `NLS_LENGTH_SEMANTICS` when the item leaves it: what Forms does with `Null`); one of another data type has no
     * size; a list item, check box, radio group or image holds one of its elements, not a value of a length
     * (`ITEM_HOLDS_ELEMENT`); any other item holds no value (`ITEM_HOLDS_NO_VALUE`). A character parameter whose length
     * the file does not give is `PARAMETER_LENGTH_NOT_GIVEN`; a data type the reader did not know `DATA_TYPE_UNKNOWN`.
     */
    val shapes = ArrayList<DeclaredShapeFact>()

    val moduleKey: String = entity(EntityKind.FORMS_MODULE, layout.name, layout.name, null)

    init {
        objects += FormsObjectFact(moduleKey)
        for (block in layout.blocks) {
            val blockKey = entity(EntityKind.FORMS_BLOCK, "${layout.name}/${block.name.value}", block.name.value, moduleKey)
            owners.putIfAbsent(listOf(block.name.value), blockKey)
            objects += FormsObjectFact(blockKey)
            blockKeys += blockKey
            itemKeys += block.items.map { item ->
                val itemKey = entity(EntityKind.FORMS_ITEM, "${layout.name}/${block.name.value}/${item.name.value}", item.name.value, blockKey)
                owners.putIfAbsent(listOf(block.name.value, item.name.value), itemKey)
                objects += FormsObjectFact(itemKey)
                shapes += DeclaredShapeFact(itemKey, itemShape(item))
                itemKey
            }
        }
        for (unit in layout.units) {
            val key = when (unit.kind) {
                FormsLayout.Kind.TRIGGER -> {
                    val owner = if (unit.level == FormsTriggerLevel.EVENT) moduleKey else owners[unit.owner] ?: moduleKey
                    entity(EntityKind.FORMS_TRIGGER, unit.path, unit.name, owner)
                }
                FormsLayout.Kind.PROGRAM_UNIT -> programUnits.getOrPut(normalizeIdentifier(unit.name)) {
                    entity(EntityKind.FORMS_PROGRAM_UNIT, unit.path, unit.name, moduleKey)
                }
                FormsLayout.Kind.RECORD_GROUP_QUERY -> entity(EntityKind.FORMS_RECORD_GROUP, unit.path, unit.name, moduleKey)
            }
            unitKeys[unit] = key
            val unitKind = when (unit.programUnitKind) {
                FormsProgramUnitKind.PACKAGE -> UnitKind.PACKAGE
                FormsProgramUnitKind.PACKAGE_BODY -> UnitKind.PACKAGE_BODY
                else -> null
            }
            objects += FormsObjectFact(key, unit.codeFirstLine, unit.codeLastLine, unitKind)
        }
        for (library in layout.libraries) {
            val key = entity(EntityKind.FORMS_LIBRARY, "${layout.name}/$library", library, moduleKey, contained = false)
            relations += RelationFact(moduleKey, RelationKind.FORMS_ATTACHED_LIBRARY, key)
            objects += FormsObjectFact(key)
        }
        for (parameter in layout.parameters) {
            val name = parameter.name.value
            val key = entity(EntityKind.FORMS_PARAMETER, "${layout.name}/PARAMETER/$name", name, moduleKey)
            parameterKeys += key
            objects += FormsObjectFact(key)
            shapes += DeclaredShapeFact(key, valueShape(parameter.dataType, parameter.maximumLength, null))
        }
    }

    /** The entity of [unit]: a trigger, a program unit or a record group. */
    fun keyOf(unit: FormsLayout.Unit): String? = unitKeys[unit]

    /** The entity of the global [name] (normalized), added the first time the module's code names it. */
    fun global(name: String): String {
        val key = FORMS_GLOBAL_PREFIX + name
        if (globals.add(key)) entities += EntityFact(key, EntityKind.FORMS_GLOBAL, null, name, null, displayOf(key), external = false, precedence = Precedence.DECLARED)
        return key
    }

    /**
     * The module's names for [FormsNames]: each program unit's entity by its name, with the name of the package it is
     * (a package specification or body), and the declarations of each package by its name: [packages] (from the
     * walked tree, the specification's and the body's together), a subprogram or not.
     */
    fun names(packages: Map<String, Map<String, Boolean>>): FormsNames {
        val own = HashMap<String, FormsNames.Own>()
        for (unit in layout.units) {
            if (unit.kind != FormsLayout.Kind.PROGRAM_UNIT) continue
            val name = normalizeIdentifier(unit.name)
            val isPackage = unit.programUnitKind == FormsProgramUnitKind.PACKAGE || unit.programUnitKind == FormsProgramUnitKind.PACKAGE_BODY
            val existing = own[name]
            if (existing == null || isPackage && existing.packageName == null) own[name] = FormsNames.Own(unitKeys.getValue(unit), if (isPackage) name else null)
        }
        return FormsNames(own, packages)
    }

    private fun entity(kind: EntityKind, path: String, name: String, parentKey: String?, contained: Boolean = true): String {
        var key = FORMS_PREFIX + path
        var count = 1
        while (key in used) key = FORMS_PREFIX + path + "#" + ++count
        used += key
        entities += EntityFact(key, kind, null, name, parentKey, displayOf(key), external = false, precedence = Precedence.DECLARED)
        if (parentKey != null && contained) relations += RelationFact(parentKey, RelationKind.CONTAINS, key)
        return key
    }

    private companion object {
        fun itemShape(item: FormsItem): TypeShape = when (item.kind) {
            FormsItemKind.TEXT_ITEM, FormsItemKind.DISPLAY_ITEM -> valueShape(item.dataType, item.maximumLength, item.lengthSemantics)
            FormsItemKind.LIST_ITEM, FormsItemKind.CHECK_BOX, FormsItemKind.RADIO_GROUP, FormsItemKind.IMAGE -> TypeShape.Unknown("ITEM_HOLDS_ELEMENT")
            else -> TypeShape.Unknown("ITEM_HOLDS_NO_VALUE")
        }

        fun valueShape(type: FormsItemDataType, length: Int?, unit: FormsLengthSemantics?): TypeShape = when {
            type == FormsItemDataType.UNKNOWN -> TypeShape.Unknown("DATA_TYPE_UNKNOWN")
            !type.character -> TypeShape.Unsized(type.name)
            length == null -> TypeShape.Unknown("PARAMETER_LENGTH_NOT_GIVEN")
            else -> TypeShape.Sized(type.name, length, unit?.name ?: TypeShapes.NLS_LENGTH_SEMANTICS)
        }
    }
}

/**
 * The names a module gives its own code, asked before the database and Oracle's built-ins, after the code's own
 * declarations: a name whose first segment is a procedure or function of the module is that program unit, even when
 * the database has a routine of the same name; `PKG.X` with `PKG` a package of the module and `X` one of its
 * declarations is the package (its members are not entities), and so is a name the package declares, written inside
 * it; an `X` the package does not declare is an unknown member. Then the Forms built-ins
 * ([FormsBuiltins]), which are recorded as nothing, as Oracle's own built-in types are.
 */
internal class FormsNames(private val own: Map<String, Own>, private val packages: Map<String, Map<String, Boolean>>) {
    /** A program unit's entity, and for a package its name. */
    class Own(val key: String, val packageName: String?)

    /**
     * The entity a name resolves to, and whether it names a subprogram; [known] is false for a member the package does
     * not declare (`PKG.NOPE`), recorded as a database package's missing member is.
     */
    class Target(val key: String, val subprogram: Boolean?, val known: Boolean = true)

    /** The program unit [segments] (written at [node]) names, or null. */
    fun own(node: AstNode, segments: List<String>): Target? {
        if (segments.isEmpty()) return null
        val names = segments.map { normalizeIdentifier(it) }
        enclosingPackage(node)?.let { name ->
            val declared = packages[name]?.get(names[0])
            val unit = own[name]
            if (declared != null && unit != null) return Target(unit.key, declared)
        }
        val unit = own[names[0]] ?: return null
        val packageName = unit.packageName ?: return Target(unit.key, true)
        val member = names.getOrNull(1) ?: return Target(unit.key, false)
        val declared = packages[packageName]?.get(member) ?: return Target(unit.key, null, known = false)
        return Target(unit.key, declared)
    }

    fun isBuiltIn(segments: List<String>): Boolean = FormsBuiltins.isBuiltIn(segments)

    fun isBuiltInType(segments: List<String>): Boolean = FormsBuiltins.isBuiltInType(segments)

    /** The name of the module package [node] is written in, or null. */
    private fun enclosingPackage(node: AstNode): String? {
        val unit = node.getFirstAncestorOrNull(PlSqlGrammar.CREATE_PACKAGE_BODY) ?: node.getFirstAncestorOrNull(PlSqlGrammar.CREATE_PACKAGE) ?: return null
        return unit.getFirstChildOrNull(PlSqlGrammar.UNIT_NAME)?.getChildren(PlSqlGrammar.IDENTIFIER_NAME)?.lastOrNull()?.let { normalizeIdentifier(it.tokenOriginalValue) }
    }
}
