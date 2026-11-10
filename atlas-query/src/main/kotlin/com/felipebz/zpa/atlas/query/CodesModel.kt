//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query


import kotlinx.serialization.Serializable

/** A stored code, and whether only a later phase of the indexer produces it (no index holds it today). */
@Serializable
data class Code(val code: String, val reserved: Boolean)

/** Every code the index stores, by enum, in the writer's order. */
@Serializable
data class Codes(
    val roles: List<Code>,
    val statuses: List<Code>,
    val entityKinds: List<Code>,
    val unitKinds: List<Code>,
    val origins: List<Code>,
    val statementKinds: List<Code>,
    val valueKinds: List<Code>,
    val groupDimensions: List<Code>,
    /** Why an occurrence has its status (`occurrence.reason`). */
    val reasons: List<Code>,
    /** How a file's parse went (`source_file.parse_status`). */
    val parseStatuses: List<Code>,
    /** What links two entities ([RelationLink.kind]). */
    val relationKinds: List<Code>,
    /** A Forms block's data source and DML target type ([FormsDataSource.type]). */
    val formsDataTypes: List<Code>,
    /** A Forms item's kind ([FormsItemData.kind]). */
    val formsItemKinds: List<Code>,
    /** What Forms does for a data block without code ([FormsBlockOperation]). */
    val formsOperations: List<Code>,
    /** What Forms does of each of them ([FormsOperationState]). */
    val formsOperationStates: List<Code>
)
