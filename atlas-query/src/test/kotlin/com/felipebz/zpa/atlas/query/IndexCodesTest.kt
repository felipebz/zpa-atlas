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
    fun `every code list holds exactly the names of the writer's enum`() {
        assertThat(ROLES).containsExactlyInAnyOrderElementsOf(names(Role.entries))
        assertThat(STATUSES).containsExactlyInAnyOrderElementsOf(names(Status.entries))
        assertThat(ENTITY_KINDS).containsExactlyInAnyOrderElementsOf(names(EntityKind.entries))
        assertThat(UNIT_KINDS).containsExactlyInAnyOrderElementsOf(names(UnitKind.entries))
        assertThat(ORIGINS).containsExactlyInAnyOrderElementsOf(names(Origin.entries))
        assertThat(STATEMENT_KINDS).containsExactlyInAnyOrderElementsOf(names(StatementKind.entries))
        assertThat(VALUE_KINDS).containsExactlyInAnyOrderElementsOf(names(ValueKind.entries))
    }

    @Test
    fun `the derived sets are made of known codes`() {
        assertThat(USAGE_ROLES).containsExactlyInAnyOrderElementsOf(names(Role.entries) - setOf("DEFINITION", "DECLARATION"))
        assertThat(VALUE_KINDS).containsAll(VALUES_UNSHOWN)
        assertThat(ENTITY_KINDS).containsAll(VIEW_KINDS).containsAll(VIEWED_KINDS)
    }
}
