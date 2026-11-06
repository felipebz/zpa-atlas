//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.model

import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import com.felipebz.zpa.atlas.forms.FormsDataType
import com.felipebz.zpa.atlas.forms.FormsItemKind

/** One-based lines, zero-based columns, exclusive end column (the ZPA token convention). */
data class SourceSpan(val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int) : Comparable<SourceSpan> {
    fun contains(line: Int, col: Int): Boolean =
        (line > startLine || (line == startLine && col >= startCol)) && (line < endLine || (line == endLine && col < endCol))

    override fun compareTo(other: SourceSpan): Int = ORDER.compare(this, other)

    companion object {
        private val ORDER = compareBy<SourceSpan>({ it.startLine }, { it.startCol }, { it.endLine }, { it.endCol })
    }
}

/**
 * Entities are referenced by [key] until the writer assigns ids. [display] is almost always the key without its `db:`
 * prefix: then it is not kept apart from the key, which on a large project saves a string for every entity of every file.
 */
@ConsistentCopyVisibility
data class EntityFact private constructor(
    val key: String,
    val kind: EntityKind,
    val schemaName: String?,
    val name: String,
    val parentKey: String?,
    /** [display] when it is not [key] without its prefix; else null. */
    private val ownDisplay: String?,
    val external: Boolean,
    /** Lower wins when several files produce the same key (a declaration beats a name only mentioned). */
    val precedence: Int
) {
    val display: String get() = ownDisplay ?: key.removePrefix(KEY_PREFIX)

    companion object {
        private const val KEY_PREFIX = "db:"

        operator fun invoke(
            key: String, kind: EntityKind, schemaName: String?, name: String, parentKey: String?, display: String, external: Boolean, precedence: Int
        ) = EntityFact(key, kind, schemaName, name, parentKey, display.takeUnless { it == key.removePrefix(KEY_PREFIX) }, external, precedence)
    }
}

data class RelationFact(val fromKey: String, val kind: RelationKind, val toKey: String)

data class UnitFact(
    val kind: UnitKind,
    val name: String,
    val entityKey: String?,
    val span: SourceSpan,
    /** The schema the unit compiles in, or null when neither written nor configured. */
    val compilationSchema: String?
)

data class StatementFact(
    /** Index in the file's statement list; resolved to a database id by the writer. */
    val localId: Int,
    val unitIndex: Int,
    val containerKey: String?,
    val kind: StatementKind,
    val span: SourceSpan,
    val normalizedHash: String
)

data class OccurrenceFact(
    val unitIndex: Int,
    val containerKey: String?,
    val statementLocalId: Int?,
    val span: SourceSpan,
    val written: String,
    val entityKey: String?,
    val roles: Set<Role>,
    val status: Status,
    val origin: Origin,
    val reason: String?,
    /** For `AMBIGUOUS`: every candidate entity. */
    val candidateKeys: List<String> = emptyList(),
    /** For `CALL`: one per written actual, in written order. */
    val arguments: List<CallArgumentFact> = emptyList(),
    /**
     * The span of the name [written] comes from, when it is not [span]: a definition or declaration spans the whole
     * declaration. Null when the name is the span or is not known (a file the parser rejected).
     */
    val nameSpan: SourceSpan? = null,
    /** For a column write: the value written, one per row of the statement (several for a multi-row `INSERT`). */
    val writtenValues: List<WrittenValueFact> = emptyList()
)

/** The value a column write writes in row [row] of its statement, classified as call arguments are. */
data class WrittenValueFact(val row: Int, val valueKind: ValueKind, val value: String?, val span: SourceSpan)

/** One written actual of a call: its 1-based [ordinal], its label when named, and the formal it binds to when proven. */
data class CallArgumentFact(
    val ordinal: Int,
    val label: String?,
    val parameterKey: String?,
    val span: SourceSpan,
    /** What the argument passes, and its value for a literal, a name or a bind. */
    val valueKind: ValueKind,
    val value: String?
)

data class CoverageFact(val kind: String, val message: String)

/**
 * One event a trigger fires on: `INSERT`, `UPDATE`, `DELETE`, or a DDL or database event (`LOGON`, `CREATE`). An
 * `UPDATE OF a, b` gives one fact per column, with the column entity when it resolves.
 */
data class TriggerEventFact(val triggerKey: String, val timing: TriggerTiming, val level: TriggerLevel?, val event: String, val columnKey: String?)

/**
 * What the declaration of [entityKey] says, as written ([com.felipebz.zpa.atlas.extract.DeclaredTexts]): [datatype] is
 * a function's or cursor's return type, else the declared type; [value] a parameter's default or a variable's,
 * constant's or field's initial value, cut when [valueCut]. For a dictionary column, [datatype] is its type as a
 * `CREATE TABLE` writes it, then ` NOT NULL` when the dictionary says so ([com.felipebz.zpa.atlas.dictionary.ColumnDatatypes]).
 */
data class DeclaredTextFact(val entityKey: String, val datatype: String?, val value: String?, val valueCut: Boolean)

/**
 * A primary, unique or foreign key the dictionary gives ([com.felipebz.zpa.atlas.dictionary.DictionaryKey]), with the
 * entities of its ends: [relationKey] is the entity of its relation `tableOwner.tableName`, null when the index has
 * none; [columns] its columns in key order, each with its entity, null when the index has none.
 */
data class KeyFact(
    val owner: String,
    val name: String,
    val kind: KeyKind,
    val tableOwner: String,
    val tableName: String,
    val relationKey: String?,
    val columns: List<Pair<String, String?>>,
    val enabled: Boolean,
    val deleteRule: String?,
    val referencedOwner: String?,
    val referencedName: String?
)

/**
 * The documentation comment of one declaration of [entityKey] ([com.felipebz.zpa.atlas.extract.DocComments]): where it
 * is ([shape], its first [line]), whether the declaration is in a package or type body ([inBody]), and its [text]
 * without comment markers, separator lines or annotation lines, cut when [textCut].
 */
data class DocCommentFact(val entityKey: String, val inBody: Boolean, val shape: DocShape, val line: Int, val text: String, val textCut: Boolean)

/**
 * The recorded uses of [entityKey] may be incomplete ([com.felipebz.zpa.atlas.extract.BodyGlobals.gaps]): [reasons]
 * names, as zpa's `UsageUncertainty`, what may hold the uses the index could not record.
 */
data class UsesGapFact(val entityKey: String, val reasons: List<String>)

/**
 * The members the index lists for [entityKey], a standalone object type, may not be all its attributes: [reasons] says
 * why, `NOT_EXTRACTED` (an attribute zpa could not read, or conditional-compilation text it cannot decide in the
 * declaration) or `ALTER_TYPE` (an `ALTER TYPE` of the project adds, drops or changes attributes).
 */
data class MembersGapFact(val entityKey: String, val reasons: List<String>)

/**
 * An object of a Forms module, in the order of its file: [entityKey], and for code (a trigger, a program unit's
 * specification or body, a record group query) the lines of the module text that are its own text, and [unitKind]
 * `PACKAGE` or `PACKAGE_BODY` for a package's halves; for a block its [block] properties, for an item its [item] ones.
 */
data class FormsObjectFact(
    val entityKey: String, val codeFirstLine: Int? = null, val codeLastLine: Int? = null, val unitKind: UnitKind? = null,
    val block: FormsBlockFact? = null, val item: FormsItemFact? = null
)

/**
 * What a data block says of its data ([com.felipebz.zpa.atlas.forms.FormsBlock]): whether it is a database block, its
 * query data source and DML data target (each its type and name as written, and the relation the name resolves to when
 * the block is on a table and that one is a table), the operations it allows and its `WHERE` and `ORDER BY` clauses.
 */
data class FormsBlockFact(
    val database: Boolean,
    val sourceType: FormsDataType,
    val sourceName: String,
    val sourceKey: String?,
    val targetType: FormsDataType,
    val targetName: String,
    val targetKey: String?,
    val queryAllowed: Boolean,
    val insertAllowed: Boolean,
    val updateAllowed: Boolean,
    val deleteAllowed: Boolean,
    val where: String,
    val orderBy: String
)

/**
 * What an item says of its data ([com.felipebz.zpa.atlas.forms.FormsItem]): whether it is a database item, its column
 * name as written, its kind, whether it allows insert and update, and, for an item on a column, the column of its
 * block's source ([columnKey]) and, when the block's DML target is another relation, the target's column of that name
 * ([targetColumnKey]).
 */
data class FormsItemFact(
    val database: Boolean,
    val columnName: String,
    val kind: FormsItemKind,
    val insertAllowed: Boolean,
    val updateAllowed: Boolean,
    val columnKey: String?,
    val targetColumnKey: String?
)

/**
 * A use Oracle Forms makes without code ([com.felipebz.zpa.atlas.extract.FormsDataBlocks]): a block's query, insert,
 * update or delete of its table, an item's read or write of its column. It has no unit and no place in the module
 * text: its [containerKey] is the block or the item, its origin `FORMS_IMPLICIT`.
 */
data class FormsUseFact(val containerKey: String, val written: String, val entityKey: String?, val roles: Set<Role>, val status: Status, val reason: String?)

/** Everything one file contributes, produced off the writer thread. */
class FileFacts(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    /** The charset the file was decoded with (`UTF-8`, `windows-1252`, or the forced one). */
    val encoding: String,
    val parseStatus: ParseStatus,
    val recoveredRegions: Int,
    val compilationSchema: String?,
    val units: List<UnitFact>,
    val statements: List<StatementFact>,
    val occurrences: List<OccurrenceFact>,
    val entities: List<EntityFact>,
    val relations: List<RelationFact>,
    val coverage: List<CoverageFact>,
    /** The dictionary relations the file resolved: their entities and columns are added once per run. */
    val dictionaryRelations: List<ProjectExternalRelation> = emptyList(),
    /** The synonyms the file followed: their entities and `SYNONYM_FOR` relations are added once per run. */
    val dictionarySynonyms: List<DictionarySynonym> = emptyList(),
    val triggerEvents: List<TriggerEventFact> = emptyList(),
    val declaredTexts: List<DeclaredTextFact> = emptyList(),
    val docComments: List<DocCommentFact> = emptyList(),
    val usesGaps: List<UsesGapFact> = emptyList(),
    val membersGaps: List<MembersGapFact> = emptyList(),
    /** The types the file's declarations write, for the places column values are read into. */
    val declaredShapes: List<DeclaredShapeFact> = emptyList(),
    /** The `INTO`s of the file whose values may be columns. */
    val intos: List<IntoFact> = emptyList(),
    /** What reads a column's value into PL/SQL without being followed, by shape: counted ([ColumnPlaceFact]). */
    val placesLeftOut: Map<String, Int> = emptyMap(),
    /** A database source, or a Forms module whose text is the module text built from the file ([com.felipebz.zpa.atlas.forms.FormsModuleText]). */
    val kind: FileKind = FileKind.DB_SOURCE,
    /** For a Forms module, its objects in the order of its file. */
    val formsObjects: List<FormsObjectFact> = emptyList(),
    /** For a Forms module, the uses its data blocks make without code, in the order of its objects. */
    val formsUses: List<FormsUseFact> = emptyList()
) {
    /** These facts with [occurrences] instead of their own. */
    fun withOccurrences(occurrences: List<OccurrenceFact>) = FileFacts(path, sha256, sizeBytes, encoding, parseStatus, recoveredRegions, compilationSchema,
        units, statements, occurrences, entities, relations, coverage, dictionaryRelations, dictionarySynonyms, triggerEvents, declaredTexts, docComments,
        usesGaps, membersGaps, declaredShapes, intos, placesLeftOut, kind, formsObjects, formsUses)
}
