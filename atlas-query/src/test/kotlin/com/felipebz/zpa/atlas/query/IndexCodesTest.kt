//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.model.EntityKind
import com.felipebz.zpa.atlas.model.Origin
import com.felipebz.zpa.atlas.model.Role
import com.felipebz.zpa.atlas.model.StatementKind
import com.felipebz.zpa.atlas.model.Status
import com.felipebz.zpa.atlas.model.UnitKind
import com.felipebz.zpa.atlas.model.ValueKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The code lists the reader validates against are the codes the writer stores, reserved ones included. */
class IndexCodesTest {
    private fun names(entries: List<Enum<*>>) = entries.map { it.name }

    @Test
    fun `every code list holds the names of the writer's enum, in its order`() {
        assertThat(ROLES).containsExactlyElementsOf(names(Role.entries))
        assertThat(STATUSES).containsExactlyElementsOf(names(Status.entries))
        assertThat(ENTITY_KINDS).containsExactlyElementsOf(names(EntityKind.entries))
        assertThat(UNIT_KINDS).containsExactlyElementsOf(names(UnitKind.entries))
        assertThat(ORIGINS).containsExactlyElementsOf(names(Origin.entries))
        assertThat(STATEMENT_KINDS).containsExactlyElementsOf(names(StatementKind.entries))
        assertThat(VALUE_KINDS).containsExactlyElementsOf(names(ValueKind.entries))
    }

    @Test
    fun `the reserved codes are the enum values marked reserved`() {
        assertThat(RESERVED_ENTITY_KINDS).containsExactlyInAnyOrderElementsOf(EntityKind.entries.filter { it.reserved }.map { it.name })
        assertThat(RESERVED_UNIT_KINDS).containsExactlyInAnyOrderElementsOf(UnitKind.entries.filter { it.reserved }.map { it.name })
        assertThat(RESERVED_ORIGINS).containsExactlyInAnyOrderElementsOf(Origin.entries.filter { it.reserved }.map { it.name })
        // The lists served without reserved codes have none in the writer either.
        assertThat(Role.entries.filter { it.reserved }).isEmpty()
        assertThat(Status.entries.filter { it.reserved }).isEmpty()
    }

    @Test
    fun `the derived sets are made of known codes`() {
        assertThat(USAGE_ROLES).containsExactlyInAnyOrderElementsOf(names(Role.entries) - setOf("DEFINITION", "DECLARATION"))
        assertThat(VALUE_KINDS).containsAll(VALUES_UNSHOWN)
        assertThat(ENTITY_KINDS).containsAll(VIEW_KINDS).containsAll(VIEWED_KINDS)
    }
}
