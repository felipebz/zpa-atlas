//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.api.project.ProjectExternalColumn
import com.felipebz.zpa.api.project.ProjectExternalDatatype
import com.felipebz.zpa.api.project.ProjectExternalDatatype.LengthSemantics
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionaryRelation
import com.felipebz.zpa.atlas.dictionary.DictionaryRelationKind

/** Shared data of the column places tests. */
object ColumnPlacesFixture {
    /** A dictionary listing `ORDERS (ORDER_ID, CODE varchar2(20), NOTE varchar2(40))` and `ITEMS (ORDER_ID, SKU varchar2(15))`. */
    val ORDERS_ITEMS: AtlasDictionary by lazy {
        fun text(length: Int) = ProjectExternalDatatype.of("VARCHAR2", null, length, LengthSemantics.BYTE, null, null)
        val id = ProjectExternalDatatype.of("NUMBER", null, null, null, 10, 0)
        AtlasDictionary.of("orders", listOf("SHOP"), listOf(
            "ORDERS" to listOf("ORDER_ID" to id, "CODE" to text(20), "NOTE" to text(40)),
            "ITEMS" to listOf("ORDER_ID" to id, "SKU" to text(15))
        ).map { (table, columns) ->
            DictionaryRelation(ProjectExternalRelation.of("SHOP", table, true, columns.mapIndexed { i, (c, t) -> ProjectExternalColumn.of(c, i + 1, t) }),
                DictionaryRelationKind.TABLE)
        })
    }
}
