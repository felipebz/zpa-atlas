//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** The run an answer comes from; every API response carries it. */
@Serializable
data class RunStamp(
    val finishedAt: String,
    val atlasVersion: String,
    val zpaVersion: String,
    val compilationSchema: String?,
    val metadataOrigin: String?
)

/** A query result with the run it was read from. */
@Serializable
data class Answer<T>(val run: RunStamp, val result: T)

@Serializable
data class Count(val value: String, val count: Long)

@Serializable
data class RunSummary(
    val schemaVersion: Int,
    val sourcesRoot: String,
    val startedAt: String,
    val filesByParseStatus: List<Count>,
    val occurrencesByStatus: List<Count>,
    val coverageByKind: List<Count>,
    /** The files whose parse failed or was recovered, by path: at most [PARSE_PROBLEMS_SHOWN] of [parseProblemTotal]. */
    val parseProblems: List<FileParse>,
    val parseProblemTotal: Long
) {
    companion object {
        /** The most files [parseProblems] lists: enough to read, few enough for a broken index not to send thousands. */
        const val PARSE_PROBLEMS_SHOWN = 50
    }
}

/** A file of the index and how its parse went. */
@Serializable
data class FileParse(val path: String, val parseStatus: String)

/**
 * A package of the project and its uses from outside it (its own and its members', from other units and from scripts in
 * no unit); [topMember] is its member with the most such uses.
 */
@Serializable
data class PackageUses(val entity: EntitySummary, val uses: Long, val topMember: EntitySummary?, val topMemberUses: Long)

@Serializable
data class EntitySummary(
    val key: String,
    val kind: String,
    val display: String,
    val external: Boolean,
    /** The entity's own name (`entity.name`), the end of [display]: `P_A`, `RUN_JOB`, `NOTIFY_REQ@FAR.EXAMPLE.COM`. */
    val name: String
)

@Serializable
data class SearchHit(val key: String, val kind: String, val display: String, val external: Boolean, val usages: Long, val name: String)

/** One-based lines, zero-based columns, exclusive end column, as stored. */
@Serializable
data class Span(val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int)

/**
 * A definition or declaration: where, its roles and status, and the kind of the unit it sits in ([unitKind]: `PACKAGE`
 * for a package spec, `PACKAGE_BODY` for its body, `TYPE` and `TYPE_BODY` likewise; null outside any unit).
 */
@Serializable
data class Location(
    val path: String, val span: Span, val roles: List<String>, val status: String, val written: String, val unitKind: String?,
    /** In a Forms module, the code the location is in and its line there ([CodeAt]); null elsewhere. */
    val code: CodeAt? = null
)

/**
 * Where a line of a Forms module's text is in the code it belongs to (a trigger, a program unit, a record group query):
 * the code's entity [key], its [unitKind] (`PACKAGE` or `PACKAGE_BODY` for a package's halves, else null) and the
 * [line] in that code's own text, numbered from 1 as the Form Builder numbers it. A line of the header the module text
 * adds before the code is its line 1.
 */
@Serializable
data class CodeAt(val key: String, val line: Int, val unitKind: String? = null)

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
data class FormsNode(val entity: EntitySummary, val groups: List<FormsGroup> = emptyList(), val unitKind: String? = null)

/**
 * The objects of a group of a [FormsTree], in the order of the module file: [kind] is `TRIGGERS`, `LIBRARIES`,
 * `BLOCKS`, `ITEMS`, `PROGRAM_UNITS` or `RECORD_GROUPS`.
 */
@Serializable
data class FormsGroup(val kind: String, val nodes: List<FormsNode>)

/**
 * The objects of a Forms module, of a block or of an item, as the Form Builder's Object Navigator shows them: a module
 * has its triggers, attached libraries, blocks (each with its triggers, then its items, each with its triggers),
 * program units and record groups; a block its triggers and items; an item its triggers. Groups without objects are
 * left out.
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
 * The other half of a routine: its body ([direction] `IN`, an `IMPLEMENTS` link coming in; two when the body is in two
 * files) or, for a body, its spec (`OUT`), with its definitions.
 */
@Serializable
data class Partner(val entity: EntitySummary, val direction: String, val definitions: List<Location>)

@Serializable
data class RelationLink(val kind: String, val direction: String, val entity: EntitySummary)

@Serializable
data class Overload(val entity: EntitySummary, val body: Boolean, val self: Boolean)

/**
 * A `CONTAINS` child, its uses (occurrences that are not a definition or declaration), what its declaration says, its
 * documentation, and why its uses may be incomplete ([EntityDetail.usesIncomplete]).
 */
@Serializable
data class Member(
    val entity: EntitySummary, val usages: Long, val declared: Declared? = null, val documentation: List<Documentation> = emptyList(),
    val usesIncomplete: List<String>? = null
)

/**
 * What a declaration says, as written in the source: comments and line breaks left out, spaces collapsed, nothing
 * resolved (`t.c%TYPE` stays) or evaluated (`sysdate + 1` stays); for code under conditional compilation, the branch the
 * run kept.
 */
@Serializable
data class Declared(
    /**
     * A function's or cursor's return type, else the declared type (with `NOT NULL` when written); null when not
     * written. For a dictionary column, the dictionary's datatype as a `CREATE TABLE` writes it (`VARCHAR2(30 CHAR)`,
     * `NUMBER(10,2)`, `OWNER.TYPE_NAME`), with ` NOT NULL` when the column is.
     */
    val type: String?,
    /** A parameter's default, or a variable's, constant's or record field's initial value; null when there is none. */
    val value: String?,
    /** [value] was longer than the index keeps and ends with `…`: the whole text is at the definition. */
    val valueCut: Boolean,
    /**
     * `SPEC` when [value] is not written here but in the spec: a body's parameter without a default takes the spec's.
     * Null when [value] is written here or there is none.
     */
    val valueFrom: String?,
    /** For a body (a routine or its parameter) whose spec says otherwise: what the spec says. Null when they agree. */
    val specText: DeclaredText?
) {
    companion object {
        /**
         * The facts of a declaration from its own text and, for a body or a body's parameter, the spec's ([spec]); null
         * when neither says anything. A default only the spec writes is the body's too; a type or value both write is
         * compared ignoring case, and when they differ (Oracle refuses such a pair) each keeps its own, the spec's
         * shown beside.
         */
        fun of(own: DeclaredText?, spec: DeclaredText?): Declared? {
            val fromSpec = own?.value == null && spec?.value != null
            val value = if (fromSpec) spec else own
            if (own?.type == null && value?.value == null) return null
            val differs = spec != null && (!same(own?.type, spec.type) || (!fromSpec && own?.value != null && !same(own.value, spec.value)))
            return Declared(own?.type, value?.value, value?.valueCut == true, if (fromSpec) "SPEC" else null, spec.takeIf { differs })
        }

        private fun same(a: String?, b: String?) = a.equals(b, ignoreCase = true)
    }
}

/** A declaration's text as the index keeps it: see [Declared]. */
@Serializable
data class DeclaredText(val type: String?, val value: String?, val valueCut: Boolean)

/**
 * A documentation comment of a declaration: its [text] without comment markers, separator lines or `--%` annotation
 * lines, line breaks and indentation kept, cut when [textCut]; where it is ([shape]: `ABOVE` the declaration,
 * `AFTER_IS` or `AFTER_BEGIN` of a package or routine, at the `END_OF_LINE` of the declaration; [path], first [line]);
 * [from] `SPEC` or `BODY` when it is the other half's (a routine's spec or body, a package's spec or body), null when
 * it is the declaration's own.
 */
@Serializable
data class Documentation(
    val text: String, val textCut: Boolean, val shape: String, val path: String, val line: Int, val from: String?,
    /** In a Forms module, the code the comment is in and its line there ([CodeAt]); null elsewhere. */
    val code: CodeAt? = null
) {
    companion object {
        /**
         * What a page of the [body] half (else the spec half) shows from the [spec] and [body] documentation of a
         * declaration: its own half's, then the other half's (marked) when it says something else; the other half's
         * alone when its own has none. Texts are compared without case, spacing or line breaks.
         */
        fun shown(spec: Documentation?, body: Documentation?, onBody: Boolean): List<Documentation> {
            val (own, other) = if (onBody) body to spec?.copy(from = "SPEC") else spec to body?.copy(from = "BODY")
            if (own == null) return listOfNotNull(other)
            return if (other == null || normalized(own.text) == normalized(other.text)) listOf(own) else listOf(own, other)
        }

        private fun normalized(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").lowercase(java.util.Locale.ROOT)
    }
}

@Serializable
data class UsageSummary(val byRole: List<Count>, val byStatus: List<Count>, val asCandidate: Long)

/** One event of a trigger: its [timing] (`INSTEAD_OF` for INSTEAD OF), level (null for compound and system triggers) and column for `UPDATE OF`. */
@Serializable
data class TriggerEvent(val trigger: EntitySummary, val timing: String, val level: String?, val event: String, val column: EntitySummary?)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EntityDetail(
    val key: String,
    val kind: String,
    val schema: String?,
    val name: String,
    val display: String,
    val external: Boolean,
    val parent: EntitySummary?,
    /** The entities that hold it, outermost first, its [parent] last; empty without a parent. */
    val ancestors: List<EntitySummary>,
    /** By kind code, then by name ignoring case (`_` before the letters), then by key. */
    val children: List<Member>,
    val relations: List<RelationLink>,
    val definitions: List<Location>,
    val overloads: List<Overload>,
    /** The `IMPLEMENTS` partners: a routine's bodies, or a body's spec, each with its definitions. */
    val partners: List<Partner>,
    val usage: UsageSummary,
    /** For a trigger, its events; for a table or view, the events of the triggers on it; for a column, the `UPDATE`
     * events that fire when it is updated (`UPDATE OF` naming it, or `UPDATE` without a column list). */
    val triggerEvents: List<TriggerEvent> = emptyList(),
    /** For a view, the relations its query reads (`SQL_SELECT` occurrences inside it, every level of the query). */
    val reads: List<EntitySummary> = emptyList(),
    /** For a table, view or object table, the views whose query reads it. */
    val views: List<EntitySummary> = emptyList(),
    /** For a view column, the columns its value is computed from (`VIEW_BASE` targets, one hop: a table column or
     * another view's column). */
    val baseColumns: List<EntitySummary> = emptyList(),
    /** For a column, the view columns computed from it (`VIEW_BASE` sources, one hop). */
    val viewColumns: List<EntitySummary> = emptyList(),
    /** What its declaration says (a function's return type, a parameter's type and default, a variable's type and
     * initial value); null when it says nothing beside its name. Each child in [children] has its own. */
    val declared: Declared? = null,
    /**
     * Its documentation ([Documentation.shown]): its own, then the other half's when it says something else, or the
     * other half's alone; empty when neither has one. Each child in [children] has its own.
     */
    val documentation: List<Documentation> = emptyList(),
    /**
     * Why its recorded uses may be incomplete: zpa could not decide some names where it is visible, so uses the index
     * did not record cannot be ruled out. zpa's reasons (`CONDITIONAL_COMPILATION`, `DECLARATION_OUTSIDE_FILE`, ...);
     * null when nothing says so. A package body's own variables and constants have it, and parameters: a parameter
     * gets its spec's or body's too, whose uses its "who uses" shows.
     */
    val usesIncomplete: List<String>? = null,
    /**
     * For a standalone object type, why the attributes in [children] may not be all its attributes: `NOT_EXTRACTED` (one
     * zpa could not read, or conditional compilation it cannot decide), `ALTER_TYPE` (an `ALTER TYPE` of the project
     * changes them; what it adds is not listed). Null when the list is complete.
     */
    val membersIncomplete: List<String>? = null,
    /**
     * From the dictionary: for a relation, its primary, unique and foreign keys (primary first, then unique, then
     * foreign, by name); for a column, the keys it is a column of.
     */
    val keys: List<TableKey> = emptyList(),
    /**
     * From the dictionary: for a relation, the foreign keys (of any relation, itself included) that reference one of its
     * keys; for a column, those that reference a key it is a column of. Each names the key it references, one of [keys].
     */
    val referencedBy: List<ReferencingKey> = emptyList(),
    /**
     * Why [referencedBy] may not be every foreign key referencing [keys] (`TABLES_NOT_VISIBLE`: the loading user may not
     * see every table's constraints); null when nothing says so, or when the entity has no primary or unique key.
     */
    val referencedByIncomplete: List<String>? = null,
    /**
     * For a column, the places ([ColumnPlace]) its value lands in alone counted by class (`FIXED_SIZE`, `UNKNOWN`,
     * `ANCHORED_ELSEWHERE`, `NO_SIZE`, `ANCHORED_TO_COLUMN`, in that order), those read through a view computed from it
     * included; those where it is a part of a concatenation are [concatenatedPlaces].
     */
    val placesByClass: List<Count> = emptyList(),
    /** For a column, how many places its value is a part of a concatenation in ([ColumnPlace.concatenated]). */
    val concatenatedPlaces: Int = 0,
    /** For a variable, parameter, field or other PL/SQL target, the columns whose value it is read into, by key. */
    val receives: List<EntitySummary> = emptyList(),
    /** For an object of a Forms module, the module ([FormsInfo]); null otherwise. */
    val forms: FormsInfo? = null,
    /** For a database block of a Forms module, what it does with its data ([FormsBlockData]); left out otherwise. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val formsBlock: FormsBlockData? = null,
    /** For an item of a Forms module, what it says of its data ([FormsItemData]); left out otherwise. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val formsItem: FormsItemData? = null
)

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
data class FormsDataSource(val type: String, val name: String, @EncodeDefault(EncodeDefault.Mode.NEVER) val entity: EntitySummary? = null)

/** One [FormsBlockOperation] of a block and its [FormsOperationState]; [trigger] is the replacing trigger, left out unless `REPLACED`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class FormsOperation(val operation: String, val state: String, @EncodeDefault(EncodeDefault.Mode.NEVER) val trigger: EntitySummary? = null)

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

/**
 * A place a column's value is read into in PL/SQL: the column read ([read], the page's column or a view column computed
 * from it, then [viaView]), by [source] (`SELECT_INTO`, `FETCH`, `RETURNING`, `ASSIGNMENT` of a row record's field; [bulk] for `BULK COLLECT`), at [path]
 * and [line], [column] in [container]; the target as written ([targetWritten]), its entity ([target], absent for a
 * declaration without one) and the [field] of a record target. How the target is declared: [placeClass], [declaredType]
 * as written, [length] and [unit] (`CHAR`, `BYTE` or `DIGITS`; for a number, [length] is the digits it holds before the
 * point and [scale] its scale), [typeSource] the entity whose declaration decides it (the column an anchor ends at, the
 * variable, field or type that writes the size), [parameterMode] (`OUT`, `IN OUT`) for a parameter target, whose
 * callers' arguments decide the size, and [reason] for `UNKNOWN`.
 *
 * Against the size `S` of the page ([PlaceGroupsPage.size]): its [verdict] ([Verdict]), [verdictReason] ([VerdictReason])
 * for an `UNKNOWN` one whose declaration is known, the [size] compared in [sizeUnit] (the target's own, or the other
 * column's for `ANCHORED_ELSEWHERE`), where a character unit comes from ([unitSource]: `WRITTEN`, `COMPILED` with
 * [unitCompiledIn], `OPTION`), and for a concatenated place that fits, the [room] its size leaves for the other text.
 */
@Serializable
data class ColumnPlace(
    val read: EntitySummary,
    val viaView: Boolean,
    val source: String,
    val bulk: Boolean,
    val path: String,
    val line: Int,
    val column: Int,
    val container: EntitySummary?,
    val targetWritten: String,
    val target: EntitySummary?,
    val field: String?,
    val placeClass: String,
    val declaredType: String?,
    val length: Int?,
    val unit: String?,
    val typeSource: EntitySummary?,
    val reason: String?,
    val scale: Int?,
    val parameterMode: String?,
    /**
     * The value functions the column is read through, outer first (`NVL`; `UPPER`, `TRIM`); empty when read directly.
     * Through them the value that lands may be other than the column's (the other argument of `NVL`).
     */
    val functions: List<String>,
    /**
     * For source `OUT_ARGUMENT`: the `OUT` or `IN OUT` parameter the argument is passed for, and the routine called. For
     * source `IN_ARGUMENT`: the `IN` parameter the column is passed to, and its routine.
     */
    val argumentOf: EntitySummary?,
    val routine: EntitySummary?,
    /** For source `IN_ARGUMENT`: how many calls pass the column to [argumentOf], and one of them ([callPath], [callLine]). */
    val callCount: Int?,
    val callPath: String?,
    val callLine: Int?,
    /** The column's value is a part of a concatenation written into the target (`'C:' || code`): other text lands with it. */
    val concatenated: Boolean,
    val verdict: String = Verdict.UNKNOWN.name,
    val verdictReason: String? = null,
    val size: Int? = null,
    val sizeUnit: String? = null,
    val unitSource: String? = null,
    val unitCompiledIn: CompiledIn? = null,
    val room: Int? = null,
    /** The line of code the row shows, with the piece it marks ([PlaceLine]). */
    val shown: PlaceLine? = null,
    /** In a Forms module, the code [line] is in and its line there ([CodeAt]); null elsewhere. */
    val code: CodeAt? = null,
    /** Likewise for [callLine]. */
    val callCode: CodeAt? = null
)

/**
 * The line of code a place's row shows, at [path] and [line], marking [from] to [to] on it: for a value passed to a
 * parameter, the call's argument; else the column where the place's own statement reads it; else the target ([to]
 * null: to the end of the line). With
 * the page's answer, its indexed [text], whether the file changed since ([stale]) and its [colors].
 */
@Serializable
data class PlaceLine(
    val path: String, val line: Int, val from: Int, val to: Int?, val text: String? = null, val stale: Boolean? = null, val colors: List<List<Int>>? = null,
    /** In a Forms module, the code [line] is in and its line there ([CodeAt]); null elsewhere. */
    val code: CodeAt? = null,
    /** The target as written, to find on the line in place of [from] and [to] (an assignment's span is its `:=`). */
    @kotlinx.serialization.Transient val target: String? = null
)

/** The PL/SQL unit whose `NLS_LENGTH_SEMANTICS` gave a length its unit: `ALL_PLSQL_OBJECT_SETTINGS`'s [owner], [name] and [type]. */
@Serializable
data class CompiledIn(val owner: String, val name: String, val type: String)

/** A column's declared type ([datatype], null when not known) and its size: [length] in [unit], [scale] for a number. */
@Serializable
data class ColumnSize(val datatype: String?, val length: Int?, val unit: String?, val scale: Int?)

@Serializable
data class VerdictCount(val name: String, val count: Int)

/**
 * Which places of a column to list: compared with [size] (the column's when null), those of [verdicts] (all but
 * [Verdict.FOLLOWS] when null), whose target, routine, package or file has every word of [text], read by [source], and
 * with [concatenated] those where the value is one part of a concatenation too.
 */
data class PlaceFilter(
    val size: Int? = null,
    val verdicts: Set<Verdict>? = null,
    val text: String? = null,
    val source: String? = null,
    val concatenated: Boolean = false
) {
    val listed: Set<Verdict> get() = verdicts ?: (Verdict.entries.toSet() - Verdict.FOLLOWS)

    companion object {
        /** The verdicts [names] names, in any case. */
        fun verdicts(names: Collection<String>): Set<Verdict> = names.mapTo(LinkedHashSet()) { name ->
            Verdict.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                ?: throw InvalidQueryException("Unknown verdict: $name; the verdicts are ${Verdict.entries.joinToString(", ")}")
        }
    }
}

/**
 * The places of one routine ([container]; null for an anonymous block or a script, grouped by file): its short name
 * [routine] (the file name without a container), [packageName] the package or routine it is in, [parameters] only for
 * overloads of one package with the same name, [path] its file, [smaller] and [places] counted under the filter, and
 * its [rows] in line order.
 */
@Serializable
data class PlaceGroup(
    val container: EntitySummary?, val routine: String, val packageName: String?, val parameters: String?, val path: String,
    val smaller: Int, val places: Int, val rows: List<ColumnPlace>
)

/**
 * The places of a column ([PlaceFilter]) by routine, a page of [groups] at a time from [offset] ([nextOffset] null on the
 * last, [groupsLeft] after this page): the [column]'s size, the [size] `S` compared with ([sizeGiven] when the reader gave
 * it; null when the column's is not known and none was given), the [counts] by verdict under every filter but the verdict
 * one, the [verdicts] listed, [places] and [routines] listed, [totalPlaces] and [totalRoutines] under every filter but the
 * verdict one, [concatenatedCount] the places where the value is one part of a concatenation under the text and source
 * filters, [optionUnit] the unit a length written without one counts in when the dictionary does not tell it, and
 * [optionSized] how many of the places under every filter but the verdict one count so.
 */
@Serializable
data class PlaceGroupsPage(
    val column: ColumnSize, val size: Int?, val sizeGiven: Boolean, val counts: List<VerdictCount>, val verdicts: List<String>,
    val places: Int, val routines: Int, val totalPlaces: Int, val totalRoutines: Int, val concatenatedCount: Int,
    val optionUnit: String, val optionSized: Int, val offset: Int, val groups: List<PlaceGroup>, val nextOffset: Int?, val groupsLeft: Int
)

/**
 * A page of the places of a column ([PlaceFilter]) as rows, by verdict, then by file and line: as [PlaceGroupsPage]
 * says of the same fields, [total] the places listed, [rows] from [offset], [nextOffset] the offset of the next page
 * (null on the last).
 */
@Serializable
data class ColumnPlacePage(
    val column: ColumnSize, val size: Int?, val sizeGiven: Boolean, val counts: List<VerdictCount>, val verdicts: List<String>,
    val total: Int, val concatenatedCount: Int, val optionUnit: String, val optionSized: Int, val offset: Int, val rows: List<ColumnPlace>,
    val nextOffset: Int?
)

/**
 * What "who uses" covers besides the entity itself: [IMPLEMENTATIONS] its `IMPLEMENTS` partners (for a parameter, the
 * parameter of the same name of its routine's partners), [CHILDREN] its `CONTAINS` descendants, [VIEWS] the view
 * columns computed from them and the views reading them, transitively.
 */
enum class Scope { SELF, IMPLEMENTATIONS, CHILDREN, VIEWS }

/**
 * Which usages to keep: [DIRECT] the rows whose matched entity is the entity, its partners or its contents;
 * [THROUGH_VIEWS] the rows [Scope.VIEWS] added ([OccurrenceRow.viaView]).
 */
enum class UsagePath { DIRECT, THROUGH_VIEWS }

/**
 * Where a usage sits against the entity asked about: [INSIDE] its unit is the entity's own (a package's or type's spec
 * or body: the unit whose entity it is), [OUTSIDE] any other unit, or a script in no entity's unit.
 */
enum class UsageSide { OUTSIDE, INSIDE }

/**
 * The scopes "who uses" adds by default for an entity of [kind], as the entity page does: a subprogram's or parameter's
 * spec or body, a package's spec or body and members, a type's members.
 */
fun defaultUsageScopes(kind: String): List<Scope> = listOfNotNull(
    Scope.IMPLEMENTATIONS.takeIf { kind in setOf("PROCEDURE", "FUNCTION", "PACKAGE", "PARAMETER") },
    Scope.CHILDREN.takeIf { kind in setOf("PACKAGE", "TYPE") }
)

/** Why the impact walk stops at a node. */
enum class ImpactStop {
    /** Code outside the node's own cycle uses it: the nodes one hop further, nodes already reached, or the entity. */
    FOLLOWED,

    /**
     * Nothing in the index uses the node but itself, or the routines it forms a cycle with (routines that call each
     * other, the entity asked for included): it is called from outside the index (Forms, jobs, other systems) or not
     * at all. A [FOLLOWED] node can be called from outside the index too. A DML trigger is an entry point when nothing
     * in the index writes its table in a way that fires it.
     */
    ENTRY_POINT,

    /**
     * A trigger the walk does not follow: a system trigger (DDL or database events), which no write fires, or any
     * trigger when triggers are not followed. [ImpactNode.trigger] gives its table and events.
     */
    TRIGGER,

    /** Code of the package as a whole (its initialization, a declaration): following every user of the package would list too much. */
    PACKAGE_LEVEL,

    /** The node is at the depth limit: its uses were not read. */
    DEPTH_LIMIT,

    /** Code outside any routine, view or package, such as a script: nothing calls it. The node is the file. */
    NO_CONTAINER
}

/**
 * One use on a shortest path to a node: of [uses] (the entity asked for at depth 1), at [path] line [line]. [fires]
 * is set when the use is a write that fires the trigger [uses]: the trigger's events it fires, such as `INSERT`.
 * A use Forms makes without code (a data block's query or DML, an item's column) has [origin] `FORMS_IMPLICIT`, the
 * module's file as [path] and no [line]; [origin] is left out for a use in code.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ImpactVia(
    val uses: String,
    val occurrence: Long,
    val path: String,
    val line: Int?,
    val status: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fires: String? = null,
    /** In a Forms module, the code [line] is in and its line there ([CodeAt]); null elsewhere. */
    val code: CodeAt? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val origin: String? = null
)

/** One event of a trigger node: as [TriggerEvent] without the trigger. */
@Serializable
data class ImpactTriggerEvent(val timing: String, val level: String?, val event: String, val column: EntitySummary?)

/** The table a trigger node is on, and the events that fire it. */
@Serializable
data class ImpactTrigger(val table: EntitySummary?, val events: List<ImpactTriggerEvent>)

/**
 * One node of an impact graph: a routine, view, trigger, type or package ([entity]), a Forms block or item whose data
 * Forms queries or writes without code, or for [ImpactStop.NO_CONTAINER] a file ([path], with its [uses] and
 * [firstLine], counted over the uses of every node reached, not only those on its shortest path). [depth] is the
 * shortest path from the entity; [certain] is true when some path uses only `RESOLVED` uses.
 */
@Serializable
data class ImpactNode(
    val kind: String,
    val entity: EntitySummary? = null,
    val path: String? = null,
    val uses: Int? = null,
    val firstLine: Int? = null,
    val depth: Int,
    val via: ImpactVia,
    val certain: Boolean,
    val stop: String,
    val trigger: ImpactTrigger? = null
)

/**
 * The transitive impact of [entity]: [levels] counts the nodes per depth (from 1), [stops] per stop reason, both over
 * the whole graph; [total] counts the nodes the stop filter keeps, and [nodes] is the page from [offset].
 */
@Serializable
data class ImpactGraph(
    val entity: EntitySummary,
    val included: List<String>,
    val maxDepth: Int,
    val levels: List<Int>,
    val stops: List<Count>,
    val total: Int,
    val offset: Int,
    val nodes: List<ImpactNode>,
    val nextOffset: Int?
)

/**
 * The unit an occurrence is in: its kind, its name, and for "who uses" asked with sides its [entity] (the package,
 * routine, view, trigger or type whose unit it is; null for a script, and when not asked).
 */
@Serializable
data class UnitRef(val kind: String, val name: String, val entity: EntitySummary? = null)

@Serializable
data class CallArgument(
    val ordinal: Int,
    val label: String?,
    val parameter: EntitySummary?,
    val span: Span,
    /** `STRING`, `NUMBER`, `NULL`, `BOOLEAN`, `NAME`, `BIND` or `EXPRESSION`. */
    val valueKind: String,
    /** The literal's value, or the name or bind as written; null for `NULL` and `EXPRESSION`. */
    val value: String?
)

/** The arguments bound to a parameter that pass the same value: [value] null for `NULL`, `BIND` and `EXPRESSION`. */
@Serializable
data class ValueGroup(val valueKind: String, val value: String?, val count: Long)

/**
 * One occurrence of a listing. A use Forms makes without code ([origin] `FORMS_IMPLICIT`: a data block's query, insert,
 * update or delete of its table, an item's read or write of its column) has no line: its [span], [unit],
 * [statementKind] and [code] are null, [path] is the module's file and [container] the block or the item.
 */
@Serializable
data class OccurrenceRow(
    val id: Long,
    val path: String,
    val span: Span?,
    val written: String,
    val roles: List<String>,
    val status: String,
    val reason: String?,
    val origin: String,
    /** The entity in scope this row hit (the occurrence entity, or the candidate); null for a query row without entity. */
    val matched: EntitySummary?,
    val viaCandidate: Boolean,
    /** [matched] is a view column or view the [Scope.VIEWS] scope added, not the entity asked about or its partners and children. */
    val viaView: Boolean,
    val entity: EntitySummary?,
    val container: EntitySummary?,
    val unit: UnitRef?,
    val statementKind: String?,
    val candidates: List<EntitySummary>,
    val arguments: List<CallArgument>,
    /** For a column write: the value written, one per row of its statement. */
    val values: List<WrittenValue>,
    /** In a Forms module, the code the occurrence is in and its line there ([CodeAt]); null elsewhere. */
    val code: CodeAt? = null
)

/** The value a column write writes in [row] of its statement; [value] as for [CallArgument.value]. */
@Serializable
data class WrittenValue(val row: Int, val valueKind: String, val value: String?)

/**
 * Counts over a set of occurrences: roles split (an occurrence counts once under each of its roles). [byOrigin],
 * [byEntityKind] (the kind of the matched entity; occurrences without one are left out), [byUnitKind] (occurrences in
 * no unit, the uses Forms makes without code, are left out) and [byReason] (occurrences without a reason are left out)
 * are filled by the occurrence query only.
 */
@Serializable
data class Facets(
    val byRole: List<Count>,
    val byStatus: List<Count>,
    val byOrigin: List<Count> = emptyList(),
    val byEntityKind: List<Count> = emptyList(),
    val byUnitKind: List<Count> = emptyList(),
    val byReason: List<Count> = emptyList(),
    /** For "who uses" asked with sides: the hits by [UsageSide], under the role and status filters but not the side's. */
    val bySide: List<Count>? = null
)

/**
 * One page of "who uses". [usesIncomplete] lists, for the entity asked for and the children in scope, those whose
 * recorded uses may be incomplete ([EntityDetail.usesIncomplete]); null when none.
 */
@Serializable
data class OccurrencePage(
    val rows: List<OccurrenceRow>, val nextAfter: Long?, val total: Long, val facets: Facets, val usesIncomplete: List<UsesGap>? = null
)

/** An entity whose recorded uses may be incomplete, and zpa's [reasons]. */
@Serializable
data class UsesGap(val key: String, val reasons: List<String>)

/** The size and facets of the occurrences a query matches. */
@Serializable
data class QuerySummary(val total: Long, val facets: Facets)

/**
 * A query over every occurrence of the index. Every filter is optional; set filters combine with AND, the values of
 * one filter with OR. Patterns (`name`, `path`, `value`) take `*` as a wildcard; `name` and patterns are
 * case-insensitive, an exact `value` is not.
 */
data class OccurrenceQuery(
    /** The occurrence's entity has this key; with [inside], or is one of its `CONTAINS` descendants. */
    val entity: String? = null,
    val inside: Boolean = false,
    val entityKinds: Set<String> = emptySet(),
    /**
     * The entity's name, or a dotted name as in the header search: the last segment is the entity's name, each earlier
     * one the next parent up, and the first may be the schema instead (`st_active`, `company.st_active`,
     * `hr.company.st_active`). At most four segments; each is case-insensitive and exact, or a pattern with `*`.
     */
    val name: String? = null,
    /** The entity's schema, exact. */
    val schema: String? = null,
    val external: Boolean? = null,
    /** The occurrence is inside this entity; with [containerInside], or inside one of its `CONTAINS` descendants. */
    val container: String? = null,
    /** The typed form of [container]: every entity this dotted name matches, as for [name], is a container. */
    val containerName: String? = null,
    /** [container] and [containerName] include their `CONTAINS` descendants. */
    val containerInside: Boolean = true,
    val unitKinds: Set<String> = emptySet(),
    /** The file path: a prefix, or with `*` a pattern of the whole path. */
    val path: String? = null,
    /** The occurrence has any of these roles; empty matches every role, definitions and declarations included. */
    val roles: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val reasons: Set<String> = emptySet(),
    val origins: Set<String> = emptySet(),
    val statementKinds: Set<String> = emptySet(),
    /**
     * A written value or call argument of this kind and, for `STRING`, `NUMBER`, `BOOLEAN` and `NAME`, [value]: exact,
     * or with `*` as a wildcard. A [value] without a kind matches a row of any of those four kinds. `NAME` and `BOOLEAN` values are stored upper case, so [value] is upper-cased for them;
     * a `STRING` value matches case-sensitively, exact or as a pattern (where `?` and `[` are themselves); a `NUMBER`
     * matches as written, a pattern case-insensitively.
     */
    val valueKind: String? = null,
    val value: String? = null,
    /** An `AMBIGUOUS` occurrence matches the entity filters through any of its candidates. */
    val candidates: Boolean = true
) {
    val hasEntityFilter: Boolean get() = entity != null || entityKinds.isNotEmpty() || name != null || schema != null || external != null
}

/** What occurrences can be grouped by. */
enum class GroupDimension { ENTITY, ENTITY_KIND, CONTAINER, UNIT, FILE, ROLE, STATUS, REASON, ORIGIN, STATEMENT_KIND, VALUE }

/**
 * The value of one dimension of a group: [entity] for `ENTITY` and `CONTAINER`, [unit] and its [path] for `UNIT`,
 * [path] for `FILE`, [valueKind] and [text] for `VALUE`, [text] otherwise. Every field is null for a group without the
 * dimension (an occurrence without an entity, a reason or a statement).
 */
@Serializable
data class GroupValue(
    val text: String? = null,
    val entity: EntitySummary? = null,
    val unit: UnitRef? = null,
    val path: String? = null,
    val valueKind: String? = null
)

@Serializable
data class OccurrenceGroup(val values: List<GroupValue>, val count: Long)

/** The first groups by count, descending, then by their values; [totalGroups] counts them all. */
@Serializable
data class GroupPage(val dimensions: List<String>, val groups: List<OccurrenceGroup>, val totalGroups: Long)

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

/** Line [number] of a file and its [text]; in a Forms module, the code it is in and its line there ([code]). */
@Serializable
data class SourceLine(val number: Int, val text: String, val code: CodeAt? = null)

/**
 * Lines of a file around one line. [colors], when asked for, has one list per line of [lines], each span as
 * `[startCol, endCol, color]` (the [CodeColor] ordinal), read as [TextHit.colors] is; null when not asked or unreadable.
 */
@Serializable
data class SourceSnippet(val path: String, val lines: List<SourceLine>, val stale: Boolean, val colors: List<List<List<Int>>>? = null)

/**
 * The line occurrence [id] starts on, as indexed, so its span is on [text]: null only when the index holds no text for
 * it (a line shorter than three characters). [stale] is true when the file changed since indexing, null when the server
 * cannot read it; [colors] as [TextHit.colors], when asked for.
 */
@Serializable
data class OccurrenceLine(val id: Long, val text: String?, val stale: Boolean?, val colors: List<List<Int>>? = null)

/**
 * One occurrence of a [FileView], compact: [at] is the span of its name as `[startLine, startCol, endLine, endCol]`
 * (lines from 1, columns from 0, end exclusive); [entity] and [candidates] are indexes into [FileView.entities].
 */
@Serializable
data class FileOccurrence(val at: List<Int>, val roles: List<String>, val status: String, val reason: String?, val entity: Int?, val candidates: List<Int>)

/**
 * A whole indexed file: its [lines] and, unless it is [stale] (changed since it was indexed, so spans would point at
 * the wrong text), its occurrences, with every entity they refer to listed once in [entities], and its [objects].
 * [parseStatus] is how the indexer's parse went. [colors] are the colored spans of the text when they were asked for
 * and could be read, else null: each `[startLine, startCol, endLine, endCol, color]` with the [CodeColor] ordinal.
 * [kind] is the file's (`DB_SOURCE`, `FORMS_MODULE`: whose page is the code page of its units, [FormsFile]).
 */
@Serializable
data class FileView(
    val path: String,
    val stale: Boolean,
    val lines: List<String>,
    val entities: List<EntitySummary>,
    val occurrences: List<FileOccurrence>,
    val parseStatus: String,
    val objects: List<FileObject>,
    val colors: List<List<Int>>?,
    val kind: String
)

/**
 * An object a file declares at its top level (a package spec or body, a type or type body, a standalone routine, a
 * view, a trigger): its [unitKind], its [entity], the [line] it starts at, and the [members] it declares directly.
 */
@Serializable
data class FileObject(val unitKind: String, val entity: EntitySummary, val line: Int, val members: List<FileMember>)

/** A routine, type, cursor, constant, variable or exception an object declares directly, at [line] of the file. */
@Serializable
data class FileMember(val entity: EntitySummary, val line: Int)

/** What a span of code is, as the analyzer's highlighter colors it. */
enum class CodeColor { KEYWORD, LITERAL, COMMENT, DOC_COMMENT }

/**
 * Colors a file's text the way the analyzer reads it; the server is handed one by the command that starts it, so this
 * module does not depend on the analyzer. [ccflags] and [databaseVersion] are the run's, as stored. Answers the spans
 * as [FileView.colors] has them, or null when the text cannot be read.
 */
fun interface CodeColorer {
    fun colors(text: String, ccflags: String?, databaseVersion: String?): List<List<Int>>?
}

/**
 * Rebuilds the text the indexer read from a Forms module's file (`source_file.kind` `FORMS_MODULE`): the module text
 * the readers of the run make of the file's [bytes] ([fileName] is its name, without folders), or null when no reader
 * reads it. The command that starts the server hands one over, so this module does not depend on the readers.
 */
fun interface ModuleTexts {
    fun text(fileName: String, bytes: ByteArray): String?
}

/**
 * One line a text search found: [line] of [path], its [text] as indexed, and [entity], the innermost container of the
 * line when the index knows it. [id] orders the lines (file, then line) and is what `after` takes. [stale] is true when
 * the file changed since indexing, null when the server cannot read it. [colors], when asked for, are the colored
 * spans of [text] as `[startCol, endCol, color]` (the [CodeColor] ordinal): read from the whole file, so a line inside a
 * comment or a string that opens above it is colored as such; read from the line alone when the file changed or
 * cannot be read; null when not asked or the colorer cannot read it.
 */
@Serializable
data class TextHit(
    val id: Long,
    val path: String,
    val line: Int,
    val text: String,
    val entity: EntitySummary?,
    val stale: Boolean?,
    val colors: List<List<Int>>? = null,
    /** In a Forms module, the code the line is in and its line there ([CodeAt]); null elsewhere. */
    val code: CodeAt? = null
)

/**
 * A page of a text search: [rows] in file and line order, [nextAfter] to pass as `after` for the next page (null on the
 * last), [total] lines found in [files] files, and [wholeIndex] when no path or container narrowed the search.
 * [fileLines] gives each file of [rows] its count of lines found, those of later pages included.
 */
@Serializable
data class TextPage(
    val rows: List<TextHit>,
    val nextAfter: Long?,
    val total: Long,
    val wholeIndex: Boolean,
    val files: Long,
    val fileLines: Map<String, Long>
)

/** A request the index cannot answer as asked (an unknown role, a limit out of range, a path outside the index). */
open class InvalidQueryException(message: String) : RuntimeException(message)

/** An impact graph of [nodes] nodes, more than [limit]: a smaller depth gives a smaller one. */
class GraphTooLargeException(val nodes: Int, val limit: Int) :
    RuntimeException("The graph has $nodes nodes, more than $limit: lower maxDepth")

/** A query ran longer than the index allows; narrower filters make it faster. */
class QueryTimeoutException(val seconds: Double) :
    RuntimeException("The query took longer than ${"%.0f".format(java.util.Locale.ROOT, seconds)} s; narrow the filters and try again.")

/** The file was written by an indexer with another schema version. */
class SchemaMismatchException(val found: Int, val expected: Int) :
    RuntimeException("The index has schema version $found; this server reads version $expected. Re-run `zpa-atlas index` to rebuild it, or use the matching zpa-atlas version.")
