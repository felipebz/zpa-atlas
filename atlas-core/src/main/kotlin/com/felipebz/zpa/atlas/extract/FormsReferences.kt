//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.atlas.forms.FormsLayout
import com.felipebz.zpa.atlas.forms.FormsTriggerLevel
import com.felipebz.zpa.atlas.model.*
import java.util.*

/**
 * The names a Forms module's code gives the module's own objects, each a use at its place, as the database's names
 * are. Names are matched without case.
 * - `:BLOCK.ITEM` is the item of the block, `:ITEM` in a trigger on a block or an item an item of that block, else (and
 *   elsewhere) the item of that name when one block has one (`AMBIGUOUS` when several do). `:BLOCK.ROWID` with no item
 *   of that name is the record's row id: nothing. `:PARAMETER.NAME` is the module's parameter, `:GLOBAL.NAME` the
 *   global of that name ([FormsEntities.global]), never unresolved; `:SYSTEM.NAME` is nothing. Each takes the roles of
 *   a PL/SQL variable written there: an assignment or `INTO` target, an `OUT` or `IN OUT` argument of a resolved call,
 *   else a read; in SQL too.
 * - The built-ins named by [BUILT_INS], with a literal first argument, `CALL` the object it names: `GO_BLOCK` the block,
 *   `GO_ITEM` the item (`'BLOCK.ITEM'` or `'ITEM'`, as above), `EXECUTE_TRIGGER` the trigger looked up as Forms does,
 *   from the lowest level up (the item the code runs on, its block, the module; from code on no block, the module's,
 *   else the one trigger of that name, `UNVERIFIED`: the item the cursor is on decides), `CALL_FORM`, `OPEN_FORM` and `NEW_FORM` the module of that name (its last name
 *   without its ending: `'/forms/orders.fmx'` is `ORDERS`), whose key [resolveModuleCalls] checks once every module is
 *   known. A first argument that is not a literal is counted in a `FORMS_NAME_NOT_LITERAL` note, by built-in.
 */
internal class FormsReferences(private val layout: FormsLayout, private val entities: FormsEntities, private val state: FileState) {
    private val roles = NameRoles(state)
    private val blocks = HashMap<String, Int>()
    private val items = ArrayList<Map<String, String>>()
    private val itemsByName = HashMap<String, MutableList<String>>()
    private val parameters = HashMap<String, String>()

    /** The triggers by the names of the objects they are on below the module (none, the block, the block and the item), then by name. */
    private val triggers = HashMap<List<String>, MutableMap<String, String>>()
    private val triggersByName = HashMap<String, MutableList<String>>()

    /** The calls of [BUILT_INS] whose first argument is not a literal, by built-in. */
    private val notLiteral = TreeMap<String, Int>()

    init {
        for ((index, block) in layout.blocks.withIndex()) {
            blocks.putIfAbsent(upper(block.name.value), index)
            val byName = HashMap<String, String>()
            for ((position, item) in block.items.withIndex()) {
                val key = entities.itemKeys[index][position]
                if (byName.putIfAbsent(upper(item.name.value), key) == null) itemsByName.getOrPut(upper(item.name.value)) { ArrayList() } += key
            }
            items += byName
        }
        layout.parameters.forEachIndexed { index, name -> parameters.putIfAbsent(upper(name), entities.parameterKeys[index]) }
        for (unit in layout.units) {
            if (unit.kind != FormsLayout.Kind.TRIGGER || unit.level == FormsTriggerLevel.EVENT) continue
            val key = entities.keyOf(unit) ?: continue
            val owner = unit.owner.map(::upper)
            if (triggers.getOrPut(owner) { HashMap() }.putIfAbsent(upper(unit.name), key) == null) triggersByName.getOrPut(upper(unit.name)) { ArrayList() } += key
        }
    }

    /** What a name resolves to: an entity, or candidates, or none and why. */
    private class Found(val key: String?, val status: Status, val reason: String? = null, val candidates: List<String> = emptyList())

    /**
     * A host variable naming an object of the module ([name] a plain `:X` or `:X.Y`): its use recorded, true; false for
     * any other name, and in a dynamic SQL text, whose binds are the text's own.
     */
    fun hostName(name: AstNode, context: Context, statementId: Int?): Boolean {
        if (context.dynamic != null) return false
        val parts = when (name.type) {
            PlSqlGrammar.VARIABLE_NAME -> listOf(name)
            PlSqlGrammar.MEMBER_EXPRESSION -> name.children.filter { it.type != PlSqlPunctuator.DOT }
            else -> return false
        }
        if (parts.size > 2 || parts.size == 2 && parts[1].type != PlSqlGrammar.IDENTIFIER_NAME) return false
        val host = parts[0].takeIf { it.type == PlSqlGrammar.VARIABLE_NAME }?.getFirstChildOrNull(PlSqlGrammar.HOST_AND_INDICATOR_VARIABLE) ?: return false
        // `:X` alone: no indicator variable, no position.
        if (host.numberOfChildren != 2) return false
        val first = host.getFirstChildOrNull(PlSqlGrammar.IDENTIFIER_NAME)?.let { upper(normalizeIdentifier(it.tokenOriginalValue)) } ?: return false
        val second = parts.getOrNull(1)?.let { upper(normalizeIdentifier(it.tokenOriginalValue)) }
        val found = when {
            second == null -> item(null, first, layout.unitAt(name.tokenLine))
            first == "SYSTEM" -> return true
            first == "GLOBAL" -> Found(entities.global(second), Status.RESOLVED)
            first == "PARAMETER" -> parameters[second]?.let { Found(it, Status.RESOLVED) } ?: Found(null, Status.UNRESOLVED, Reason.NO_SUCH_PARAMETER.name)
            second == "ROWID" && blocks[first]?.let { "ROWID" !in items[it] } == true -> return true
            else -> item(first, second, layout.unitAt(name.tokenLine))
        }
        // The segments are not passed: an item named DELETE is not a collection method.
        state.valueOccurrence(spanOf(name), writtenText(name), context, statementId, found.status, found.key, found.reason, found.candidates,
            roles.valueRoles(name, emptyList()))
        return true
    }

    /**
     * A call of the Forms built-in [builtIn] (normalized) with [arguments] (each its expression): for one of
     * [BUILT_INS], the `CALL` of what its literal first argument names, at the literal.
     */
    fun builtInCall(builtIn: String, arguments: List<AstNode>, context: Context, statementId: Int?) {
        if (builtIn !in BUILT_INS) return
        val argument = arguments.firstOrNull() ?: return
        val value = ExpressionValues.of(argument, context::hasHoles)
        if (value.kind != ValueKind.STRING) {
            notLiteral.merge(builtIn, 1, Int::plus)
            return
        }
        val written = value.value!!.trim()
        val found = when (builtIn) {
            "GO_BLOCK" -> blocks[upper(written)]?.let { Found(entities.blockKeys[it], Status.RESOLVED) } ?: Found(null, Status.UNRESOLVED, Reason.NO_SUCH_BLOCK.name)
            "GO_ITEM" -> {
                val names = written.split('.').map(::upper)
                when (names.size) {
                    1 -> item(null, names[0], layout.unitAt(argument.tokenLine))
                    2 -> item(names[0], names[1], layout.unitAt(argument.tokenLine))
                    else -> Found(null, Status.UNRESOLVED, Reason.NO_SUCH_ITEM.name)
                }
            }
            "EXECUTE_TRIGGER" -> trigger(upper(written), layout.unitAt(argument.tokenLine))
            else -> moduleName(written).takeIf { it.isNotEmpty() }?.let { Found(FORMS_PREFIX + it, Status.RESOLVED) }
                ?: Found(null, Status.UNRESOLVED, Reason.MODULE_NOT_INDEXED.name)
        }
        state.occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, spanOf(argument), writtenText(argument), found.key,
            setOf(Role.CALL), status(found.status, context.partial), context.origin, found.reason, found.candidates)
    }

    /** The note of the calls whose first argument is not a literal, when there are some. */
    fun coverage(): List<CoverageFact> {
        if (notLiteral.isEmpty()) return emptyList()
        return listOf(CoverageFact("FORMS_NAME_NOT_LITERAL", "${notLiteral.values.sum()} call(s) of a built-in that names a block, an item, a trigger or a " +
            "module with an expression, not a literal: what they name is not followed (${notLiteral.entries.joinToString(", ") { "${it.key} ${it.value}" }})"))
    }

    /** The item [item] of the block [block], or without a block, as the code at [unit] finds it. */
    private fun item(block: String?, item: String, unit: FormsLayout.Unit?): Found {
        if (block != null) {
            val index = blocks[block] ?: return Found(null, Status.UNRESOLVED, Reason.NO_SUCH_BLOCK.name)
            return items[index][item]?.let { Found(it, Status.RESOLVED) } ?: Found(null, Status.UNRESOLVED, Reason.NO_SUCH_ITEM.name)
        }
        if (unit?.kind == FormsLayout.Kind.TRIGGER && (unit.level == FormsTriggerLevel.BLOCK || unit.level == FormsTriggerLevel.ITEM)) {
            unit.owner.firstOrNull()?.let { blocks[upper(it)] }?.let { items[it][item] }?.let { return Found(it, Status.RESOLVED) }
        }
        return among(itemsByName[item].orEmpty(), Reason.NO_SUCH_ITEM)
    }

    /** The trigger [name] that `EXECUTE_TRIGGER` runs from the code at [unit]. */
    private fun trigger(name: String, unit: FormsLayout.Unit?): Found {
        val owner = if (unit?.kind == FormsLayout.Kind.TRIGGER && unit.level != FormsTriggerLevel.EVENT) unit.owner.map(::upper) else emptyList()
        if (owner.isEmpty()) {
            triggers[emptyList()]?.get(name)?.let { return Found(it, Status.RESOLVED) }
            // Forms looks it up from the item the cursor is on, which code on no block does not know: a guess by name.
            val found = among(triggersByName[name].orEmpty(), Reason.NO_SUCH_TRIGGER)
            return if (found.status == Status.RESOLVED) Found(found.key, Status.UNVERIFIED, Reason.TRIGGER_FOUND_BY_NAME.name) else found
        }
        for (size in owner.size downTo 0) triggers[owner.subList(0, size)]?.get(name)?.let { return Found(it, Status.RESOLVED) }
        return Found(null, Status.UNRESOLVED, Reason.NO_SUCH_TRIGGER.name)
    }

    /** One of [keys]: resolved when it is the only one, ambiguous among several, unresolved for [none] when there is none. */
    private fun among(keys: List<String>, none: Reason): Found = when (keys.size) {
        0 -> Found(null, Status.UNRESOLVED, none.name)
        1 -> Found(keys[0], Status.RESOLVED)
        else -> Found(null, Status.AMBIGUOUS, Reason.AMBIGUOUS.name, keys)
    }

    companion object {
        /** The built-ins whose first argument names a block, an item, a trigger or a module. */
        private val BUILT_INS = setOf("GO_BLOCK", "GO_ITEM", "EXECUTE_TRIGGER", "CALL_FORM", "OPEN_FORM", "NEW_FORM")

        private fun upper(name: String) = name.trim().uppercase(Locale.ROOT)

        /** The module a `CALL_FORM` literal names: its last name, without folders or ending, in upper case. */
        fun moduleName(written: String): String {
            val last = written.substringAfterLast('/').substringAfterLast('\\')
            return upper(if ('.' in last) last.substringBeforeLast('.') else last)
        }

        /**
         * [facts] with each `CALL` of a module ([builtInCall]) checked against the modules of the run: one that is not
         * among them has no entity, `UNRESOLVED` with `MODULE_NOT_INDEXED`. Done on every run, for the facts a run
         * walked and those it took from the cache alike: a module added or removed changes what another one calls.
         */
        fun resolveModuleCalls(facts: List<FileFacts>): List<FileFacts> {
            val modules = facts.flatMapTo(HashSet()) { file -> file.entities.filter { it.kind == EntityKind.FORMS_MODULE }.map { it.key } }
            return facts.map { file ->
                if (file.kind != FileKind.FORMS_MODULE || file.occurrences.none { isMissingModule(it, modules) }) return@map file
                file.withOccurrences(file.occurrences.map { occurrence ->
                    if (!isMissingModule(occurrence, modules)) occurrence
                    else occurrence.copy(entityKey = null, status = if (occurrence.status == Status.PARTIAL_SOURCE) Status.PARTIAL_SOURCE else Status.UNRESOLVED,
                        reason = Reason.MODULE_NOT_INDEXED.name)
                })
            }
        }

        /** A `CALL` of a module's key (`forms:` and a name without a path) that is not one of [modules]. */
        private fun isMissingModule(occurrence: OccurrenceFact, modules: Set<String>): Boolean {
            val key = occurrence.entityKey ?: return false
            return Role.CALL in occurrence.roles && key.startsWith(FORMS_PREFIX) && '/' !in key && !key.startsWith(FORMS_GLOBAL_PREFIX) && key !in modules
        }
    }
}
