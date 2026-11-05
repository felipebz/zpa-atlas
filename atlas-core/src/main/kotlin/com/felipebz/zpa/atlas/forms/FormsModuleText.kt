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
 * A path is the module's name (its file's, [FormsModuleReaders.moduleName]), the names of the objects the code is on and the code's name, joined with `/`:
 * `MODULE/TRIGGER`, `MODULE/BLOCK/TRIGGER`, `MODULE/BLOCK/ITEM/TRIGGER`, `MODULE/EVENT/TRIGGER`, `MODULE/PROGRAM_UNIT`,
 * `MODULE/RECORD_GROUP`. A package's specification and body have the same path. Code with a blank text is left out.
 */
class FormsModuleText private constructor(val text: String, val units: List<Unit>) {
    /** Where one unit of [FormsModule.code] (at [codeIndex]) is: [firstLine] is its header line, [lastLine] its last. */
    class Unit(val codeIndex: Int, val path: String, val firstLine: Int, val lastLine: Int)

    companion object {
        /** The text of [module], known as [name]. */
        fun of(module: FormsModule, name: String): FormsModuleText {
            val text = StringBuilder()
            val units = ArrayList<Unit>()
            var line = 1
            fun append(part: String) {
                text.append(part)
                for (c in part) if (c == '\n') line++
            }
            for ((index, code) in module.code.withIndex()) {
                val path = path(name, code)
                val body = when (code) {
                    is FormsTrigger -> code.text.value
                    is FormsProgramUnit -> code.text.value
                    is FormsRecordGroup -> code.query.value
                }
                if (body.isBlank()) continue
                if (units.isNotEmpty()) append("\n")
                val first = line
                when (code) {
is FormsTrigger -> {
                        append("BEGIN -- $path\n")
                        if (code.isFormula) {
                            // An expression: the value of an assignment to the item, so that it is parsed as one.
                            append(":${code.owner.joinToString(".")} :=\n")
                            append(body.trimEnd('\n') + "\n")
                            append(";\n")
                        } else {
                            append(body)
                            if (!body.endsWith('\n')) append("\n")
                        }
                        append("END;\n")
                    }
                    is FormsProgramUnit -> append("-- $path\n" + body.trimEnd('\n') + "\n")
                    is FormsRecordGroup -> append("-- $path\n" + body.trimEnd('\n') + "\n;\n")
                }
                units += Unit(index, path, first, line - 1)
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
