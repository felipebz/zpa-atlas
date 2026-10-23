//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Every value an object holds, as text that is the same for two objects holding the same values: fields by name, sets
 * and maps in sorted order, lists in their order. It reads the objects' fields, so it sees what the objects keep even
 * when no public member shows it.
 *
 * Left out: fields of the types [skipped] names (source positions), lazily computed fields (`by lazy`, derived from the
 * others), static and transient fields.
 */
internal class Rendering(private val skipped: Set<String> = emptySet()) {
    /** The strings met while rendering, upper-cased and unquoted: the names the object refers to. */
    val strings = HashSet<String>()

    private val open = IdentityHashMap<Any, Unit>()

    fun render(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> {
                strings += value.removeSurrounding("\"").uppercase(Locale.ROOT)
                out.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            }
            is Number, is Boolean, is Char -> out.append(value)
            is Enum<*> -> out.append(value.name)
            is Optional<*> -> append(out, value.orElse(null))
            is Lazy<*> -> out.append("<lazy>")
            is List<*> -> sequence(out, "[", "]", value.map { render(it) })
            is Set<*> -> sequence(out, "{", "}", value.map { render(it) }.sorted())
            is Map<*, *> -> sequence(out, "{", "}", value.entries.map { render(it.key) + "=" + render(it.value) }.sorted())
            is Pair<*, *> -> sequence(out, "(", ")", listOf(render(value.first), render(value.second)))
            is Array<*> -> sequence(out, "[", "]", value.map { render(it) })
            else -> {
                val type = value.javaClass
                if (type.name.startsWith("java.") || type.name.startsWith("kotlin.")) {
                    out.append(value.toString())
                    return
                }
                if (open.containsKey(value)) {
                    out.append("<cycle>")
                    return
                }
                open[value] = Unit
                out.append(type.name).append('(')
                var first = true
                for (field in fieldsOf(type)) {
                    if (field.type.name in skipped) continue
                    val fieldValue = field.get(value)
                    if (fieldValue is Lazy<*> || fieldValue != null && fieldValue.javaClass.name in skipped) continue
                    if (!first) out.append(',')
                    first = false
                    out.append(field.name).append('=')
                    append(out, fieldValue)
                }
                out.append(')')
                open.remove(value)
            }
        }
    }

    private fun sequence(out: StringBuilder, start: String, end: String, items: List<String>) {
        out.append(start)
        items.forEachIndexed { i, item -> if (i > 0) out.append(','); out.append(item) }
        out.append(end)
    }

    companion object {
        private val FIELDS = ConcurrentHashMap<Class<*>, List<Field>>()

        private fun fieldsOf(type: Class<*>): List<Field> = FIELDS.getOrPut(type) {
            generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
                .flatMap { it.declaredFields.asSequence() }
                .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) && !it.isSynthetic }
                .onEach { it.isAccessible = true }
                .sortedBy { it.declaringClass.name + "." + it.name }
                .toList()
        }
    }
}
