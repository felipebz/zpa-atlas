//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.forms

/**
 * What the walk of a module's text needs of the module besides the text: its name, blocks, items and attached
 * libraries, its notes, and the lines and path of each unit of the text. The code itself is in the text only, so the
 * model can be let go once the text is built.
 */
internal class FormsLayout(
    /** The name the module is known by, its file's ([FormsModuleReaders.moduleName]). */
    val name: String,
    /** The module's `Name` property, its internal name. */
    val internalName: String,
    val blocks: List<Block>,
    val libraries: List<String>,
    val units: List<Unit>,
    val notes: List<FormsNote>
) {
    class Block(val name: String, val items: List<String>)

    enum class Kind { TRIGGER, PROGRAM_UNIT, RECORD_GROUP_QUERY }

    /**
     * One unit of the text: its [kind], [path] and [name], the objects it is on below the module ([owner], a trigger's),
     * a trigger's [level], a program unit's [programUnitKind], its lines (the header line first) and the lines of its own
     * text ([FormsModuleText.Unit.codeFirstLine]).
     */
    class Unit(
        val kind: Kind, val path: String, val name: String, val owner: List<String>, val level: FormsTriggerLevel?,
        val programUnitKind: FormsProgramUnitKind?, val firstLine: Int, val lastLine: Int, val codeFirstLine: Int, val codeLastLine: Int
    )

    /** The unit of the text whose lines hold [line], or null (a blank line between two units). */
    fun unitAt(line: Int): Unit? {
        var low = 0
        var high = units.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val unit = units[middle]
            when {
                line < unit.firstLine -> high = middle - 1
                line > unit.lastLine -> low = middle + 1
                else -> return unit
            }
        }
        return null
    }

    companion object {
        fun of(module: FormsModule, name: String, text: FormsModuleText): FormsLayout = FormsLayout(
            name,
            module.name.value,
            module.blocks.map { block -> Block(block.name.value, block.items.map { it.name.value }) },
            module.libraries.map { it.name.value },
            text.units.map { unit ->
                when (val code = module.code[unit.codeIndex]) {
                    is FormsTrigger -> Unit(Kind.TRIGGER, unit.path, code.name.value, code.owner, code.level, null, unit.firstLine, unit.lastLine,
                        unit.codeFirstLine, unit.codeLastLine)
                    is FormsProgramUnit -> Unit(Kind.PROGRAM_UNIT, unit.path, code.name.value, emptyList(), null, code.kind, unit.firstLine, unit.lastLine,
                        unit.codeFirstLine, unit.codeLastLine)
                    is FormsRecordGroup -> Unit(Kind.RECORD_GROUP_QUERY, unit.path, code.name.value, emptyList(), null, null, unit.firstLine, unit.lastLine,
                        unit.codeFirstLine, unit.codeLastLine)
                }
            },
            module.notes
        )
    }
}
