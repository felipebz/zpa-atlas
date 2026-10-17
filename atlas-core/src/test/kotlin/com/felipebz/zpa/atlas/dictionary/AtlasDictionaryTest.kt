//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.dictionary

import com.felipebz.zpa.api.project.ProjectExternalColumn
import com.felipebz.zpa.api.project.ProjectExternalDatatype
import com.felipebz.zpa.api.project.ProjectExternalMetadata
import com.felipebz.zpa.api.project.ProjectExternalRelation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AtlasDictionaryTest {
    private val columns = listOf(ProjectExternalColumn.of("ID", 1, null))

    private fun relation(schema: String, name: String) = ProjectExternalRelation.of(schema, name, true, columns)

    // An acquired dictionary: its relations are found by lookup, never enumerated, so their kind is not recorded.
    private val dictionary = AtlasDictionary.lookupOnly(
        ProjectExternalMetadata.none(), setOf("APP"),
        synonyms = listOf(
            DictionarySynonym("APP", "SYN_REMOTE_VIEW", "OTHER", "REMOTE_VIEW", "VIEW"),
            DictionarySynonym("APP", "SYN_REMOTE_TABLE", "OTHER", "REMOTE_TABLE", "TABLE")
        ),
        objects = mapOf("APP" to mapOf("ORDERS" to "TABLE", "V_ORDERS" to "VIEW", "MV_ORDERS" to "TABLE"))
    )

    @Test
    fun `a view of a read schema is a VIEW through its object type`() {
        assertThat(dictionary.kindOf(relation("APP", "V_ORDERS"))).isEqualTo(DictionaryRelationKind.VIEW)
        assertThat(dictionary.kindOf(relation("APP", "ORDERS"))).isEqualTo(DictionaryRelationKind.TABLE)
    }

    @Test
    fun `a view of another schema is a VIEW through the target type of a synonym`() {
        assertThat(dictionary.kindOf(relation("OTHER", "REMOTE_VIEW"))).isEqualTo(DictionaryRelationKind.VIEW)
        assertThat(dictionary.kindOf(relation("OTHER", "REMOTE_TABLE"))).isEqualTo(DictionaryRelationKind.TABLE)
    }

    @Test
    fun `without an object type the kind falls back to the row object type`() {
        assertThat(dictionary.kindOf(relation("OTHER", "UNLISTED"))).isEqualTo(DictionaryRelationKind.TABLE)
        assertThat(dictionary.kindOf(ProjectExternalRelation.objectTable("OTHER", "UNLISTED_OBJECTS", ProjectExternalDatatype.of("TY_ROW", "OTHER", null, null, null, null), true, columns))).isEqualTo(DictionaryRelationKind.OBJECT_TABLE)
    }

    @Test
    fun `a materialized view keeps the TABLE row the loader keeps`() {
        assertThat(dictionary.kindOf(relation("APP", "MV_ORDERS"))).isEqualTo(DictionaryRelationKind.TABLE)
    }
}
