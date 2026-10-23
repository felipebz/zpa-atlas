//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.project.ProjectExternalColumn
import com.felipebz.zpa.api.project.ProjectExternalDatatype
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionaryRelation
import com.felipebz.zpa.atlas.dictionary.DictionaryRelationKind
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DictionaryFingerprintTest {
    private fun column(name: String, type: String = "VARCHAR2", position: Int = 1) =
        ProjectExternalColumn.of(name, position, ProjectExternalDatatype.of(type, null, null, null, null, null))

    private fun dictionary(
        origin: String = "oracle FREE as APP at 2026-01-01T10:00:00.123Z",
        columns: List<ProjectExternalColumn> = listOf(column("ID", "NUMBER", 1), column("NOTE", position = 2)),
        complete: Boolean = true,
        kind: DictionaryRelationKind = DictionaryRelationKind.TABLE,
        completeSchemas: List<String> = listOf("APP"),
        synonyms: List<DictionarySynonym> = listOf(DictionarySynonym("PUBLIC", "ORDERS", "APP", "ORDERS", "TABLE")),
        objects: Map<String, Map<String, String>> = mapOf("APP" to mapOf("ORDERS" to "TABLE", "PKG_ORDERS" to "PACKAGE"))
    ) = AtlasDictionary.of(
        origin, completeSchemas, listOf(DictionaryRelation(ProjectExternalRelation.of("APP", "ORDERS", complete, columns), kind)), synonyms, objects
    )

    @Test
    fun `two loads of the same metadata have the same fingerprint, whatever their time`() {
        assertThat(IncrementalRun.fingerprint(dictionary(origin = "oracle FREE as APP at 2026-03-04T05:06:07Z")))
            .isEqualTo(IncrementalRun.fingerprint(dictionary()))
    }

    @Test
    fun `any difference a walk can see changes the fingerprint`() {
        val base = IncrementalRun.fingerprint(dictionary())
        val others = mapOf(
            "column type" to dictionary(columns = listOf(column("ID", "NUMBER", 1), column("NOTE", "CLOB", 2))),
            "column added" to dictionary(columns = listOf(column("ID", "NUMBER", 1), column("NOTE", position = 2), column("EXTRA", position = 3))),
            "column position" to dictionary(columns = listOf(column("ID", "NUMBER", 2), column("NOTE", position = 1))),
            "columns complete" to dictionary(complete = false),
            "relation kind" to dictionary(kind = DictionaryRelationKind.VIEW),
            "complete schemas" to dictionary(completeSchemas = listOf("APP", "OTHER")),
            "synonym target" to dictionary(synonyms = listOf(DictionarySynonym("PUBLIC", "ORDERS", "APP", "ORDERS_V", "VIEW"))),
            "objects" to dictionary(objects = mapOf("APP" to mapOf("ORDERS" to "TABLE"))),
            "origin" to dictionary(origin = "oracle OTHER as APP at 2026-01-01T10:00:00.123Z")
        )
        for ((what, other) in others) assertThat(IncrementalRun.fingerprint(other)).describedAs(what).isNotEqualTo(base)
        assertThat(IncrementalRun.fingerprint(AtlasDictionary.NONE)).isNotEqualTo(base)
    }
}
