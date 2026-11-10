//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query


import kotlinx.serialization.Serializable

/**
 * A primary, unique or foreign key the dictionary gives: its [owner] and [name], [kind] (`PRIMARY_KEY`, `UNIQUE`,
 * `FOREIGN_KEY`), the relation it is on ([table] `OWNER.TABLE`, [relation] its entity, null when the index has none:
 * the dictionary did not read it), its [columns] in key order, whether it is [enabled]. A foreign key also has its
 * [deleteRule] (`NO ACTION`, `CASCADE`, `SET NULL`), [referencedName] (`OWNER.NAME`) and the key it [references], null
 * when the dictionary did not give that key; a referenced key's own [references] is null. A column at position i of a
 * foreign key references the column at position i of the referenced key.
 */
@Serializable
data class TableKey(
    val owner: String,
    val name: String,
    val kind: String,
    val table: String,
    val relation: EntitySummary?,
    val columns: List<KeyColumn>,
    val enabled: Boolean,
    val deleteRule: String?,
    val referencedName: String?,
    val references: TableKey?
)

/** A column of a key: its dictionary [name], and its entity, null when the index has none. */
@Serializable
data class KeyColumn(val name: String, val column: EntitySummary?)

/**
 * A foreign key that references a key of the entity asked about: its [owner] and [name], the relation it is on ([table]
 * `OWNER.TABLE`, [relation] its entity, null when the index has none), its [columns] in key order, whether it is
 * [enabled], its [deleteRule], and the key it [references] (`OWNER.NAME`).
 */
@Serializable
data class ReferencingKey(
    val owner: String,
    val name: String,
    val table: String,
    val relation: EntitySummary?,
    val columns: List<ReferencingColumn>,
    val enabled: Boolean,
    val deleteRule: String?,
    val references: String
)

/** A column of a referencing foreign key: its [name], its entity, and the column it [references] (by name), at the same position of the referenced key. */
@Serializable
data class ReferencingColumn(val name: String, val column: EntitySummary?, val references: String?)

/**
 * A page of the foreign keys referencing the keys of a relation or a column ([EntityDetail.referencedBy]), by table
 * then name: [total] of them, [rows] from [offset], [nextOffset] the offset of the next page (null on the last), and
 * [incomplete] as [EntityDetail.referencedByIncomplete].
 */
@Serializable
data class ReferencingKeyPage(
    val total: Int,
    val offset: Int,
    val rows: List<ReferencingKey>,
    val nextOffset: Int?,
    val incomplete: List<String>?
)
