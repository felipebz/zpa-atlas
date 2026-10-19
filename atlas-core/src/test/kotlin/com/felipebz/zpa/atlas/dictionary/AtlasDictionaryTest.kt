//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.dictionary

import com.felipebz.zpa.api.project.ProjectExternalColumn
import com.felipebz.zpa.api.project.ProjectExternalDatatype
import com.felipebz.zpa.api.project.ProjectExternalMetadata
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.extract.SynonymResolver
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
            DictionarySynonym("APP", "SYN_REMOTE_TABLE", "OTHER", "REMOTE_TABLE", "TABLE"),
            DictionarySynonym("APP", "SYN_REMOTE_MVIEW", "OTHER", "REMOTE_MVIEW", "MATERIALIZED VIEW")
        ),
        objects = mapOf("APP" to mapOf("ORDERS" to "TABLE", "V_ORDERS" to "VIEW", "MV_ORDERS" to "MATERIALIZED VIEW"))
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

    private val rowType = ProjectExternalDatatype.of("TY_ROW", "OTHER", null, null, null, null)
    private val objectView = ProjectExternalRelation.objectView("OTHER", "OBJ_V", rowType, true, columns)
    private val plainView = ProjectExternalRelation.view("OTHER", "PLAIN_V", true, columns)
    private val objectTable = ProjectExternalRelation.objectTable("OTHER", "OBJ_T", rowType, true, columns)

    @Test
    fun `without the namespace a relation read from ALL_VIEWS is a VIEW, with or without a row type`() {
        assertThat(listOf(objectView, plainView, objectTable).map { dictionary.kindOf(it) })
            .containsExactly(DictionaryRelationKind.VIEW, DictionaryRelationKind.VIEW, DictionaryRelationKind.OBJECT_TABLE)
    }

    @Test
    fun `with the namespace read its object type comes first`() {
        val read = AtlasDictionary.lookupOnly(
            ProjectExternalMetadata.none(), setOf("OTHER"),
            objects = mapOf("OTHER" to mapOf("OBJ_V" to "MATERIALIZED VIEW", "PLAIN_V" to "VIEW", "OBJ_T" to "TABLE"))
        )
        assertThat(listOf(objectView, plainView, objectTable).map { read.kindOf(it) })
            .containsExactly(DictionaryRelationKind.MATERIALIZED_VIEW, DictionaryRelationKind.VIEW, DictionaryRelationKind.OBJECT_TABLE)
    }

    @Test
    fun `a materialized view is a MATERIALIZED_VIEW through its object type`() {
        assertThat(dictionary.kindOf(relation("APP", "MV_ORDERS"))).isEqualTo(DictionaryRelationKind.MATERIALIZED_VIEW)
    }

    @Test
    fun `a synonym to a materialized view reaches it as a relation`() {
        assertThat(dictionary.kindOf(relation("OTHER", "REMOTE_MVIEW"))).isEqualTo(DictionaryRelationKind.MATERIALIZED_VIEW)
        val target = SynonymResolver(dictionary).follow("SYN_REMOTE_MVIEW", "APP", SynonymResolver.RELATIONS)
        assertThat(target?.let { "${it.owner}.${it.name}" }).isEqualTo("OTHER.REMOTE_MVIEW")
    }
}
