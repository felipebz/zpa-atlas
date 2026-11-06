//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.forms

import java.util.*

/**
 * A property of a Forms object as the module file gives it: its [value] and, when the file says so, where the value
 * comes from ([origin]: the reader's word for it, such as `default`, `overridden`, `inherited`).
 */
data class FormsValue(val value: String, val origin: String? = null)

/**
 * One Oracle Forms module as a [FormsModuleReader] reads it: what the index records of it, whatever format it was
 * exported in. Everything the index does with a module starts from this model, never from the file.
 */
data class FormsModule(
    /**
     * The module's `Name` property: its internal name. The index knows a module by the name its reader gives it
     * ([FormsModuleReader.moduleName]).
     */
    val name: FormsValue,
    val blocks: List<FormsBlock>,
    /** The triggers, program units and record groups, in the order of the file: the order of the module text. */
    val code: List<FormsCode>,
    val libraries: List<FormsLibrary>,
    /** What the file lacks for a complete index, each a coverage note of the module's file. */
    val notes: List<FormsNote> = emptyList(),
    /** The module's parameters (`ModuleParameter`), which its code reads and writes as `:PARAMETER.NAME`. */
    val parameters: List<FormsModuleParameter> = emptyList()
) {
    val triggers: List<FormsTrigger> get() = code.filterIsInstance<FormsTrigger>()
    val programUnits: List<FormsProgramUnit> get() = code.filterIsInstance<FormsProgramUnit>()
    val recordGroups: List<FormsRecordGroup> get() = code.filterIsInstance<FormsRecordGroup>()
}

/**
 * A data block: whether it is a [database] block, the [source] Forms queries it from and the [target] its inserts,
 * updates and deletes go to (the target's name, when the file leaves it blank, is the source's: the Forms default),
 * which of the four operations it allows, and its [where] and [orderBy] clauses as written (empty when none).
 * The values are the Forms defaults where the file says nothing.
 */
data class FormsBlock(
    val name: FormsValue,
    val items: List<FormsItem>,
    val database: Boolean = true,
    val source: FormsDataSource = FormsDataSource.NONE,
    val target: FormsDataSource = FormsDataSource.NONE,
    val queryAllowed: Boolean = true,
    val insertAllowed: Boolean = true,
    val updateAllowed: Boolean = true,
    val deleteAllowed: Boolean = true,
    val where: String = "",
    val orderBy: String = ""
)

/**
 * A block's query data source or DML data target: its [type] and its [name] as written (blank when none). [word] is
 * what the file said for an [FormsDataType.UNKNOWN] type, for the reader's note; null otherwise.
 */
data class FormsDataSource(val type: FormsDataType, val name: String, val word: String? = null) {
    companion object {
        val NONE = FormsDataSource(FormsDataType.NONE, "")
    }
}

/** What a block's data source or target is. [UNKNOWN]: a value the reader does not know, never guessed. */
enum class FormsDataType { NONE, TABLE, PROCEDURE, QUERY, TRANSACTIONAL_TRIGGERS, UNKNOWN }

/**
 * An item: whether it is a [database] item, its [columnName] as written (blank: the item's own name), its [kind], and
 * whether it allows insert and update, and its size: its [dataType], its [maximumLength] and the unit of that length
 * ([lengthSemantics], null when the module leaves it to the session's `NLS_LENGTH_SEMANTICS`). Forms cuts a longer
 * value to the length (FRM-40831). The values are the Forms defaults where the file says nothing.
 */
data class FormsItem(
    val name: FormsValue,
    val database: Boolean = true,
    val columnName: String = "",
    val kind: FormsItemKind = FormsItemKind.TEXT_ITEM,
    val insertAllowed: Boolean = true,
    val updateAllowed: Boolean = true,
    /** What the file said for an [FormsItemKind.UNKNOWN] kind, for the reader's note; null otherwise. */
    val kindWord: String? = null,
    val dataType: FormsItemDataType = FormsItemDataType.CHAR,
    val maximumLength: Int = 30,
    val lengthSemantics: FormsLengthSemantics? = null
)

/** The kind of an item. Only the kinds that hold a value ([holdsValue]) may stand for a column. */
enum class FormsItemKind(val holdsValue: Boolean) {
    TEXT_ITEM(true), DISPLAY_ITEM(true), LIST_ITEM(true), CHECK_BOX(true), RADIO_GROUP(true), IMAGE(true),
    PUSH_BUTTON(false), BEAN_AREA(false), CHART(false), HIERARCHICAL_TREE(false), OTHER(false), UNKNOWN(false)
}

/**
 * The data type of an item or a parameter, as Forms names it: [character] the types whose value is text, held to the
 * maximum length. [UNKNOWN]: a value the reader does not know, never guessed.
 */
enum class FormsItemDataType(val character: Boolean) {
    CHAR(true), ALPHA(true), LONG(true), NUMBER(false), INTEGER(false), RNUMBER(false), RINTEGER(false), MONEY(false),
    RMONEY(false), DATE(false), DATETIME(false), TIME(false), JDATE(false), EDATE(false), UNKNOWN(false)
}

/** The unit of an item's maximum length. */
enum class FormsLengthSemantics { BYTE, CHAR }

/** An object of a module that holds code. */
sealed interface FormsCode {
    val name: FormsValue
}

/** Where a trigger is defined: on the module, a block, an item, or an event of the module. */
enum class FormsTriggerLevel { MODULE, BLOCK, ITEM, EVENT }

/**
 * A trigger: its [level], the names of the objects it is on below the module ([owner]: none, the block, the block and
 * the item, or the event) and its PL/SQL [text], the statements of a block without `BEGIN` and `END`.
 */
data class FormsTrigger(val level: FormsTriggerLevel, val owner: List<String>, override val name: FormsValue, val text: FormsValue) : FormsCode {
    /** Whether this is a calculated item's formula ([FORMULA]): its text is one expression, not statements. */
    val isFormula: Boolean get() = level == FormsTriggerLevel.ITEM && name.value == FORMULA

    companion object {
        /**
         * The name of the trigger a calculated item's formula is (the internal trigger Forms runs it as): a trigger of
         * level [FormsTriggerLevel.ITEM] on the item, whose text is the formula's expression, without `;`.
         */
        const val FORMULA = "FORMULA-CALCULATION"
    }
}

/** A program unit: a procedure, a function, a package specification or a package body, written without `CREATE`. */
data class FormsProgramUnit(override val name: FormsValue, val text: FormsValue) : FormsCode {
    /** What the text declares, read from its first words. */
    val kind: FormsProgramUnitKind get() = FormsProgramUnitKind.of(text.value)
}

/** A record group built from a query. */
data class FormsRecordGroup(override val name: FormsValue, val query: FormsValue) : FormsCode

/** A PL/SQL library attached to the module, and where the module looks for it. */
data class FormsLibrary(val name: FormsValue, val location: FormsValue?)

/** A coverage note about the module's file: its [kind] (a code such as `FORMS_NOT_FULL_DUMP`) and its [message]. */
data class FormsNote(val kind: String, val message: String)

/**
 * A parameter of a module, by its `Name`, with its [dataType] and its [maximumLength] (null when the file does not
 * give it).
 */
data class FormsModuleParameter(
    val name: FormsValue,
    val dataType: FormsItemDataType = FormsItemDataType.CHAR,
    val maximumLength: Int? = null
)

enum class FormsProgramUnitKind {
    PROCEDURE, FUNCTION, PACKAGE, PACKAGE_BODY, UNKNOWN;

    companion object {
        /** The kind [text] declares: its first words, past blanks and comments (`PACKAGE BODY x IS`, `FUNCTION f`). */
        fun of(text: String): FormsProgramUnitKind {
            var at = 0
            fun skip() {
                while (at < text.length) {
                    when {
                        text[at].isWhitespace() -> at++
                        text.startsWith("--", at) -> at = text.indexOf('\n', at).let { if (it < 0) text.length else it + 1 }
                        text.startsWith("/*", at) -> at = text.indexOf("*/", at + 2).let { if (it < 0) text.length else it + 2 }
                        else -> return
                    }
                }
            }
            fun word(): String {
                skip()
                val start = at
                while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
                return text.substring(start, at).uppercase()
            }
            return when (word()) {
                "PROCEDURE" -> PROCEDURE
                "FUNCTION" -> FUNCTION
                "PACKAGE" -> if (word() == "BODY") PACKAGE_BODY else PACKAGE
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Reads one export format of Forms modules into [FormsModule]. The built-in one reads `frmf2xml` files
 * ([Frmf2xmlReader]); others come from the JARs of the distribution's `readers` folder, registered for
 * [java.util.ServiceLoader] under this interface's name ([FormsModuleReaders.load]).
 */
interface FormsModuleReader {
    /** A short name for messages: `frmf2xml`. */
    val name: String

    /** The endings of the file names this reader reads, case ignored: `_fmb.xml`. */
    val fileNameEndings: List<String>

    /**
     * The endings of the file names of this format that hold no module to index, case ignored, with what such a file
     * is (`_olb.xml` to `object library`): these files are counted in a coverage note, never read.
     */
    val skippedFileNameEndings: Map<String, String> get() = emptyMap()

    /** The module [bytes] hold ([fileName] is the file's name, without folders), or null when they hold none. */
    fun read(fileName: String, bytes: ByteArray): FormsModule?

    /**
     * The module's identity for the file [fileName] (without folders) this reader reads: the name the form is called by
     * (`CALL_FORM`, `OPEN_FORM`), which names the compiled form. By default the file name without the ending this reader
     * claims, in upper case (`demo0004_fmb.xml` is `DEMO0004`); a reader whose files are named otherwise says so here.
     * A blank name is refused: the file is not indexed. The module's `Name` property is only its internal name.
     */
    fun moduleName(fileName: String): String {
        val name = fileName.lowercase(Locale.ROOT)
        val ending = fileNameEndings.firstOrNull { name.endsWith(it.lowercase(Locale.ROOT)) } ?: ""
        return fileName.substring(0, fileName.length - ending.length).uppercase(Locale.ROOT)
    }
}
