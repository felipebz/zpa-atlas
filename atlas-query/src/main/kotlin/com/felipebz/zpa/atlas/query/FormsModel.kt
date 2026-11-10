//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query


import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The Forms module an object belongs to ([EntityDetail.forms]): the [module], its [title] (its internal name, the one
 * the Form Builder shows, else the name it is known by) and the [path] of its file.
 */
@Serializable
data class FormsInfo(val module: EntitySummary, val title: String, val path: String)

/**
 * A Forms object in a [FormsTree], with the objects under it by group (a block's triggers and items, an item's triggers).
 * A package is two nodes, its specification and its body, told apart by [unitKind] (`PACKAGE`, `PACKAGE_BODY`; null
 * for any other object).
 */
@Serializable
data class FormsNode(
    val entity: EntitySummary,
    val groups: List<FormsGroup> = emptyList(),
    val unitKind: String? = null
)

/**
 * The objects of a group of a [FormsTree], in the order of the module file: [kind] is `TRIGGERS`, `LIBRARIES`,
 * `BLOCKS`, `ITEMS`, `PARAMETERS`, `PROGRAM_UNITS` or `RECORD_GROUPS`.
 */
@Serializable
data class FormsGroup(val kind: String, val nodes: List<FormsNode>)

/**
 * The objects of a Forms module, of a block or of an item, as the Form Builder's Object Navigator shows them: a module
 * has its triggers, attached libraries, blocks (each with its triggers, then its items, each with its triggers),
 * parameters, program units and record groups; a block its triggers and items; an item its triggers. Groups without
 * objects are left out.
 */
@Serializable
data class FormsTree(val root: EntitySummary, val groups: List<FormsGroup>)

/**
 * One piece of code of a Forms module: a trigger, a program unit (a package's specification or body, by [unitKind] as in
 * [CodeAt]) or a record group query, entity [key]. [view] is its own text, without what the module text adds around
 * it, numbered from 1, with its occurrences and colors on those numbers.
 */
@Serializable
data class FormsCode(val key: String, val unitKind: String?, val view: FileView)

/**
 * The code page of a Forms module file: the module ([forms]), the [tree] of its objects, and the [code] shown: the one
 * asked for, else the first in the tree's order; null when the module has none.
 */
@Serializable
data class FormsFile(val forms: FormsInfo, val tree: FormsTree, val code: FormsCode?)

/**
 * The uses of a Forms global variable in one module ([EntityDetail.formsGlobal]): the [module], the [path] of its file,
 * how many of its uses set the global ([writes]) and how many read it ([reads]).
 */
@Serializable
data class FormsGlobalModule(val module: EntitySummary, val path: String, val writes: Int, val reads: Int)

/** What Forms does for a data block without code, the operations of [FormsBlockData.operations], in that order. */
enum class FormsBlockOperation { QUERY, INSERT, UPDATE, DELETE }

/**
 * What Forms does of one [FormsBlockOperation] of a block, the first that holds: [NOT_ALLOWED] the block does not allow
 * it; [REPLACED] a transactional trigger replaces it (`ON-SELECT`, `ON-INSERT`, `ON-UPDATE`, `ON-DELETE`: the block's
 * own, else the module's), whose code runs instead; [NOT_FOLLOWED] the index does not follow what Forms does, the block
 * having no table for it (its data source is not a table or is `DUAL`, or for the DML its target is not one); [DONE]
 * Forms runs it on the block's table, the block's and its items' `FORMS_IMPLICIT` uses.
 */
enum class FormsOperationState { NOT_ALLOWED, REPLACED, NOT_FOLLOWED, DONE }

/**
 * What a database data block does with its data ([EntityDetail.formsBlock]): the data [source] it queries and the DML
 * [target] it inserts into, updates and deletes from, each [FormsBlockOperation] in order with its state, and the
 * `WHERE` and `ORDER BY` clauses the block adds to its query ([where], [orderBy]; empty when it has none).
 */
@Serializable
data class FormsBlockData(
    val source: FormsDataSource,
    val target: FormsDataSource,
    val operations: List<FormsOperation>,
    val where: String,
    val orderBy: String
)

/**
 * A block's data source or DML target: its [type] (`TABLE`, `PROCEDURE`, `QUERY` for a FROM clause query,
 * `TRANSACTIONAL_TRIGGERS`, `NONE`, or `UNKNOWN` for a type the reader does not know), its [name] as written (a blank
 * DML target is the source's), and the relation it names when the block is on a table ([entity]: an `UNKNOWN_RELATION`
 * when the dictionary lacks it); [entity] is left out otherwise.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FormsDataSource(
    val type: String,
    val name: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val entity: EntitySummary? = null
)

/** One [FormsBlockOperation] of a block and its [FormsOperationState]; [trigger] is the replacing trigger, left out unless `REPLACED`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FormsOperation(
    val operation: String,
    val state: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val trigger: EntitySummary? = null
)

/**
 * What an item of a Forms module says of its data ([EntityDetail.formsItem]): whether it is a [database] item, its
 * [kind] (`TEXT_ITEM`, `PUSH_BUTTON`, ... `UNKNOWN` for a kind the reader does not know), its [columnName] as written
 * (empty: the item's own name), the [column] of its block's source it stands for and, when the block's DML target is
 * another relation, the target's [targetColumn] (each left out when there is none). [read], [insertWrite] and
 * [updateWrite] say whether Forms reads its column in the block's query and writes it in the block's insert and update,
 * as the item's `FORMS_IMPLICIT` uses do.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FormsItemData(
    val database: Boolean,
    val kind: String,
    val columnName: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val column: EntitySummary? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val targetColumn: EntitySummary? = null,
    val read: Boolean,
    val insertWrite: Boolean,
    val updateWrite: Boolean
)
