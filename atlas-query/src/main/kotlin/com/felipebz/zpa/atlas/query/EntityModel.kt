//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query


import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

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
data class SearchHit(val key: String, val kind: String, val display: String, val external: Boolean, val usages: Long, val name: String)

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
    @EncodeDefault(EncodeDefault.Mode.NEVER) val formsItem: FormsItemData? = null,
    /** For a Forms global variable, the modules that use it ([FormsGlobalModule]); left out otherwise. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val formsGlobal: List<FormsGlobalModule>? = null
)
