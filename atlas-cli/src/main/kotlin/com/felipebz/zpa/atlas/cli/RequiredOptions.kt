//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import com.beust.jcommander.Parameter

/**
 * Refuses [arguments] when an option marked `required` was left out or given empty. JCommander does not check it for an
 * option with an initial value (every option here has one, `""`), so a left-out `--sources` would be the current folder.
 * Positional parameters (without names) are left to JCommander, which does check them.
 */
fun requireOptions(arguments: Any) {
    for (field in arguments.javaClass.declaredFields) {
        val parameter = field.getAnnotation(Parameter::class.java) ?: continue
        if (!parameter.required || parameter.names.isEmpty()) continue
        field.isAccessible = true
        val blank = when (val value = field.get(arguments)) {
            is String -> value.isBlank()
            is Collection<*> -> value.isEmpty()
            else -> value == null
        }
        if (blank) throw CliValidationException("The option is required: ${parameter.names.first()}")
    }
}
