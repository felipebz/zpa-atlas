//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.forms

/**
 * The PL/SQL text of a module: what the index parses, stores line by line and shows as the module's file. It is
 * built from the model alone, so the server rebuilds the same lines from the same file.
 *
 * The module's code, in [FormsModule.code] order, one blank line between two units:
 * - a trigger: `BEGIN -- path`, its text unchanged, `END;` (a trigger's text is the inside of a block);
 * - a calculated item's formula ([FormsTrigger.isFormula]), an expression: `BEGIN -- path`, `:BLOCK.ITEM :=`, the
 *   expression unchanged, `;`, `END;`;
 * - a program unit: `-- path`, then its text;
 * - a record group query: `-- path`, the query, `;`.
 *
 * Each unit's own text, as the module file has it and the Form Builder numbers it from 1, is the lines [Unit.codeFirstLine]
 * to [Unit.codeLastLine]: its header and the lines added around it are not.
 *
 * A path is the module's name (the one its reader gives it, [FormsModuleReader.moduleName]: by default its file's),
 * the names of the objects the code is on and the code's name, joined with `/`: `MODULE/TRIGGER`, `MODULE/BLOCK/TRIGGER`,
 * `MODULE/BLOCK/ITEM/TRIGGER`, `MODULE/EVENT/TRIGGER`, `MODULE/PROGRAM_UNIT`, `MODULE/RECORD_GROUP`. A package's
 * specification and body have the same path. Code with a blank text is left out.
 */
class FormsModuleText private constructor(val text: String, val units: List<Unit>) {
    /**
     * Where one unit of [FormsModule.code] (at [codeIndex]) is: [firstLine] is its header line, [lastLine] its last;
     * its own text is [codeFirstLine] to [codeLastLine].
     */
    class Unit(val codeIndex: Int, val path: String, val firstLine: Int, val lastLine: Int, val codeFirstLine: Int, val codeLastLine: Int)

    companion object {
        /** The text of [module], known as [name]. */
        fun of(module: FormsModule, name: String): FormsModuleText {
            val text = ModuleTextBuilder()
            val units = ArrayList<Unit>()
            for ((index, code) in module.code.withIndex()) {
                val path = path(name, code)
                val body = when (code) {
                    is FormsTrigger -> code.text.value
                    is FormsProgramUnit -> code.text.value
                    is FormsRecordGroup -> code.query.value
                }
                if (body.isBlank()) continue
                if (units.isNotEmpty()) text.append("\n")
                val first = text.line
                val (codeFirst, codeLast) = text.appendCode(code, path, body)
                units += Unit(index, path, first, text.line - 1, codeFirst, codeLast)
            }
            return FormsModuleText(text.toString(), units)
        }

        /** The path of [code] in the module known as [name]. */
        fun path(name: String, code: FormsCode): String {
            val owner = if (code is FormsTrigger) code.owner else emptyList()
            return (listOf(name) + owner + code.name.value).joinToString("/")
        }
    }
}

/** The text of a module as it is built, and the number of the line it is at. */
private class ModuleTextBuilder {
    private val text = StringBuilder()
    var line = 1
        private set

    /** Appends [part], counting its lines. */
    fun append(part: String) {
        text.append(part)
        for (c in part) if (c == '\n') line++
    }

    /** Appends [code], at [path] and with the text [body], with its header; returns the first and last lines of its own text. */
    fun appendCode(code: FormsCode, path: String, body: String): Pair<Int, Int> = when (code) {
        is FormsTrigger -> if (code.isFormula) appendFormula(code, path, body) else appendTrigger(path, body)
        is FormsProgramUnit -> appendHeaded(path, body)
        is FormsRecordGroup -> appendHeaded(path, body).also { append(";\n") }
    }

    /** Appends a trigger's [body] as a block; returns the first and last lines of [body]. */
    private fun appendTrigger(path: String, body: String): Pair<Int, Int> {
        val codeFirst = line + 1
        append("BEGIN -- $path\n")
        append(body)
        if (!body.endsWith('\n')) append("\n")
        val codeLast = line - 1
        append("END;\n")
        return codeFirst to codeLast
    }

    /** Appends the formula [formula] whose expression is [body]; returns the first and last lines of [body]. */
    private fun appendFormula(formula: FormsTrigger, path: String, body: String): Pair<Int, Int> {
        append("BEGIN -- $path\n")
        // An expression: the value of an assignment to the item, so that it is parsed as one.
        append(":${formula.owner.joinToString(".")} :=\n")
        val codeFirst = line
        append(body.trimEnd('\n') + "\n")
        val codeLast = line - 1
        append(";\n")
        append("END;\n")
        return codeFirst to codeLast
    }

    /** Appends `-- path` and then [body]; returns the first and last lines of [body]. */
    private fun appendHeaded(path: String, body: String): Pair<Int, Int> {
        val codeFirst = line + 1
        append("-- $path\n" + body.trimEnd('\n') + "\n")
        return codeFirst to line - 1
    }

    override fun toString() = text.toString()
}
