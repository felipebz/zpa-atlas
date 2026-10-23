//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.project.ProjectExternalMetadata
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * The dictionary checksum of the facts cache names what it covers in these classes. A field it does not know of would
 * leave a cache in use after a dictionary change: when this fails, cover the new field in [IncrementalRun.fingerprint]
 * (or note why it is derived from the others), then add it here.
 */
class DictionaryFieldsTest {
    private fun fields(type: Class<*>): Set<String> =
        type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.mapTo(HashSet()) { it.name }

    @Test
    fun `the checksum covers every field of the dictionary classes`() {
        // Covered: metadata, relations, enumerable, schemas, synonyms, objects. Derived from them: the three maps.
        assertThat(fields(AtlasDictionary::class.java)).containsExactlyInAnyOrder(
            "metadata", "relations", "enumerable", "schemas", "synonyms", "objects", "kinds", "synonymsByName", "synonymTargetTypes"
        )
        // Covered: origin (its acquisition time aside), completeSchemas, relations.
        assertThat(fields(ProjectExternalMetadata::class.java)).containsExactlyInAnyOrder("origin", "completeSchemas", "relations")
        // Covered: schema, name, isColumnsComplete, columns, rowObjectType, isView. Derived: qualifiedName, rowFields, byName.
        assertThat(fields(ProjectExternalRelation::class.java)).containsExactlyInAnyOrder(
            "schema", "name", "isColumnsComplete", "columns", "rowObjectType", "isView", "qualifiedName", "rowFields", "byName"
        )
    }
}
