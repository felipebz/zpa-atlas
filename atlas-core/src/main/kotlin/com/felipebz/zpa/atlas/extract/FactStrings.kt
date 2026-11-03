//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.atlas.model.OccurrenceFact
import com.felipebz.zpa.atlas.model.Role
import java.util.concurrent.ConcurrentHashMap

/**
 * One instance of each equal string and role set the occurrences of a run hold, shared by its files: the same keys,
 * names and roles repeat over millions of occurrences, and every file's facts stay in memory until the index is written.
 */
class FactStrings {
    private val strings = ConcurrentHashMap<String, String>()
    private val roles = ConcurrentHashMap<Set<Role>, Set<Role>>()

    fun of(value: String): String = strings.putIfAbsent(value, value) ?: value

    private fun ofNullable(value: String?): String? = value?.let(::of)

    /** [fact] with its strings and roles replaced by the run's instances of them. */
    internal fun occurrence(fact: OccurrenceFact): OccurrenceFact = fact.copy(
        containerKey = ofNullable(fact.containerKey),
        written = of(fact.written),
        entityKey = ofNullable(fact.entityKey),
        roles = roles.putIfAbsent(fact.roles, fact.roles) ?: fact.roles,
        reason = ofNullable(fact.reason),
        candidateKeys = if (fact.candidateKeys.isEmpty()) fact.candidateKeys else fact.candidateKeys.map(::of),
        arguments = if (fact.arguments.isEmpty()) fact.arguments else fact.arguments.map {
            it.copy(label = ofNullable(it.label), parameterKey = ofNullable(it.parameterKey), value = ofNullable(it.value))
        }
    )
}
