//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.forms

import java.io.ByteArrayInputStream
import java.util.*
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * Reads the form modules Oracle's `frmf2xml` exports (Forms 12, `name_fmb.xml`): a `Module` root with one
 * `FormModule`, whose objects are elements by kind (`Block`, `Item`, `Trigger`, `ProgramUnit`, `RecordGroup`,
 * `AttachedLibrary`, `Event`, `ModuleParameter`), each named by its `Name` attribute.
 *
 * Attributes are read by their local name. With `DUMP=ALL` every property is written, under a prefix that says where
 * its value comes from (`MODULE_default:`, `MODULE_overridden:`, `PARENT_inherited:`, `MODULE_inherited_overridden:`,
 * all bound to the Forms namespace): the word after the module name is the value's origin. Without it only the
 * properties the module itself sets are written, without prefix, so the code a module inherits from another
 * (an object with a `ParentModule`) is missing: a note says so.
 *
 * A calculated item's `Formula` is its trigger `FORMULA-CALCULATION` ([FormsTrigger.FORMULA]), first among the item's
 * triggers; a blank one is none.
 *
 * A block's and an item's data properties (`DatabaseBlock`, `QueryDataSourceType`, `DMLDataName`, `ItemType`, …) are
 * read in the words of the Form Builder that exported the module, Portuguese or English; an absent one is the Forms
 * default. An item's size (`DataType`, which the Form Builder does not translate, `MaximumLength`,
 * `DataLengthSemantics`) and a parameter's data type are read too; `frmf2xml` writes no maximum length of a parameter. A
 * word the reader does not know is not guessed: a type or kind `UNKNOWN`, counted in a `FORMS_VALUE_UNKNOWN` note.
 *
 * `frmf2xml` escapes the line breaks of the code twice: once parsed, a text still has `&#10;` for each line break
 * (`&#x9;` for a tab, `&#xD;` for a carriage return, dropped). Object libraries (`_olb.xml`) and menu modules
 * (`_mmb.xml`) are not indexed.
 */
object Frmf2xmlReader : FormsModuleReader {
    override val name = "frmf2xml"
    override val fileNameEndings = listOf("_fmb.xml")
    override val skippedFileNameEndings = mapOf("_olb.xml" to "object library", "_mmb.xml" to "menu module")

    /** The kinds of object whose `Trigger` children the reader takes, by the level of their triggers. */
    private val OWNERS = mapOf("FormModule" to FormsTriggerLevel.MODULE, "Block" to FormsTriggerLevel.BLOCK, "Item" to FormsTriggerLevel.ITEM,
        "Event" to FormsTriggerLevel.EVENT)

    /** The origins a prefix ends with, the longest first: `inherited_overridden` also ends with `_overridden`. */
    private val ORIGINS = listOf("inherited_overridden", "overridden", "inherited", "default")

    private val factory: XMLInputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }

    override fun read(fileName: String, bytes: ByteArray): FormsModule? {
        val reader = try {
            synchronized(factory) { factory.createXMLStreamReader(ByteArrayInputStream(bytes)) }
        } catch (_: XMLStreamException) {
            return null
        }
        return try {
            Reading(reader).module()
        } catch (_: XMLStreamException) {
            null
        } finally {
            reader.close()
        }
    }

    /** One element open above the reader: its local name and its `Name`. */
    private class Open(val kind: String, val name: String?)

    private class Reading(private val reader: XMLStreamReader) {
        private val open = ArrayList<Open>()
        private var dumpAll = false
        private var moduleName: FormsValue? = null
        private val blocks = ArrayList<FormsBlock>()
        private var items = ArrayList<FormsItem>()
        private var block: FormsValue? = null
        private var blockAttributes: Map<String, FormsValue> = emptyMap()
        private val code = ArrayList<FormsCode>()
        private val libraries = ArrayList<FormsLibrary>()
        private val parameters = ArrayList<FormsModuleParameter>()
        private var inheriting = 0
        private var otherTriggers = 0

        /** The values of the properties the reader does not know, by property and value, with their counts. */
        private val unknown = LinkedHashMap<Pair<String, String>, Int>()

        fun module(): FormsModule? {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> start()
                    XMLStreamConstants.END_ELEMENT -> end()
                }
            }
            val name = moduleName ?: return null
            val notes = ArrayList<FormsNote>()
            if (!dumpAll && inheriting > 0) {
                notes += FormsNote("FORMS_NOT_FULL_DUMP",
                    "Exported without DUMP=ALL: only the properties the module sets itself are in the file, so the code of the $inheriting " +
                        "object(s) it takes from other modules (ParentModule) may be missing. Export with frmf2xml DUMP=ALL.")
            }
            if (otherTriggers > 0) {
                notes += FormsNote("FORMS_TRIGGER_NOT_READ", "$otherTriggers trigger(s) on an object other than the module, a block, an item or an event: not indexed")
            }
            if (unknown.isNotEmpty()) {
                notes += FormsNote("FORMS_VALUE_UNKNOWN", "${unknown.values.sum()} value(s) not known: " +
                    unknown.entries.joinToString(", ") { (value, count) -> "${value.first} \"${value.second}\" ($count)" })
            }
            return FormsModule(name, blocks, code, libraries, notes, parameters)
        }

        private fun start() {
            val kind = reader.localName
            val attributes = attributes()
            val name = attributes["Name"]
            if (attributes.containsKey("ParentModule")) inheriting++
            when (kind) {
                "Module" -> dumpAll = attributes["Dump"]?.value.equals("All", ignoreCase = true)
                "FormModule" -> if (moduleName == null) moduleName = name
                "Trigger" -> trigger(name, attributes["TriggerText"])
                "Block", "Item", "ProgramUnit", "RecordGroup", "AttachedLibrary", "ModuleParameter" -> if (name != null) startNamed(kind, name, attributes)
            }
            open += Open(kind, name?.value)
        }

        /** Takes the element [kind] named [name], with its [attributes]: a block, an item, code, a library or a parameter. */
        private fun startNamed(kind: String, name: FormsValue, attributes: Map<String, FormsValue>) {
            when (kind) {
                "Block" -> if (parent() == "FormModule") {
                    block = name
                    blockAttributes = attributes
                    items = ArrayList()
                }
                "Item" -> if (parent() == "Block") {
                    items += item(name, attributes)
                    attributes["Formula"]?.takeIf { it.value.isNotBlank() }?.let { formula ->
                        code += FormsTrigger(FormsTriggerLevel.ITEM, listOf(open.last().name.orEmpty(), name.value), FormsValue(FormsTrigger.FORMULA), formula)
                    }
                }
                "ProgramUnit" -> attributes["ProgramUnitText"]?.let { code += FormsProgramUnit(name, it) }
                "RecordGroup" -> attributes["RecordGroupQuery"]?.takeIf { it.value.isNotBlank() }?.let { code += FormsRecordGroup(name, it) }
                "AttachedLibrary" -> libraries += FormsLibrary(name, attributes["LibraryLocation"])
                "ModuleParameter" -> if (parent() == "FormModule") parameters += parameter(name, attributes)
            }
        }

        private fun end() {
            val closed = open.removeAt(open.size - 1)
            if (closed.kind == "Block" && parent() == "FormModule") {
                block?.let { blocks += block(it, items, blockAttributes) }
                block = null
            }
        }

        private fun parent(): String? = open.lastOrNull()?.kind

        /**
         * A block from its attributes: an absent property is the Forms default, an absent data source or target type
         * a table (Forms' default, a database block or not), a blank DML target name the source's.
         */
        private fun block(name: FormsValue, items: List<FormsItem>, attributes: Map<String, FormsValue>): FormsBlock {
            val source = dataSource(attributes, "QueryDataSourceType", "QueryDataSourceName")
            val target = dataSource(attributes, "DMLDataType", "DMLDataName").let { if (it.name.isBlank()) it.copy(name = source.name) else it }
            return FormsBlock(name, items, flag(attributes, "DatabaseBlock"), source, target, flag(attributes, "QueryAllowed"), flag(attributes, "InsertAllowed"),
                flag(attributes, "UpdateAllowed"), flag(attributes, "DeleteAllowed"), attributes["WhereClause"]?.value.orEmpty(),
                attributes["OrderByClause"]?.value.orEmpty())
        }

        private fun dataSource(attributes: Map<String, FormsValue>, typeProperty: String, nameProperty: String): FormsDataSource {
            val name = attributes[nameProperty]?.value?.trim().orEmpty()
            val word = attributes[typeProperty]?.value?.trim().orEmpty()
            if (word.isEmpty()) return FormsDataSource(FormsDataType.TABLE, name)
            val type = DATA_TYPES[word.lowercase(Locale.ROOT)] ?: return FormsDataSource(FormsDataType.UNKNOWN, name, word).also { unknown(typeProperty, word) }
            return FormsDataSource(type, name)
        }

        /**
         * An item from its attributes: an absent property is the Forms default (a database text item that allows insert
         * and update, of type `Char`, 30 long, its unit left to the session).
         */
        private fun item(name: FormsValue, attributes: Map<String, FormsValue>): FormsItem {
            val word = attributes["ItemType"]?.value?.trim().orEmpty()
            val kind = if (word.isEmpty()) FormsItemKind.TEXT_ITEM else ITEM_KINDS[word.lowercase(Locale.ROOT)] ?: FormsItemKind.UNKNOWN.also { unknown("ItemType", word) }
            return FormsItem(name, flag(attributes, "DatabaseItem"), attributes["ColumnName"]?.value?.trim().orEmpty(), kind, flag(attributes, "InsertAllowed"),
                flag(attributes, "UpdateAllowed"), word.takeIf { kind == FormsItemKind.UNKNOWN }, dataType(attributes, "DataType"),
                length(attributes, "MaximumLength") ?: DEFAULT_LENGTH, lengthSemantics(attributes))
        }

        /** A parameter from its attributes: `frmf2xml` does not write a parameter's maximum length, so it is not known. */
        private fun parameter(name: FormsValue, attributes: Map<String, FormsValue>) =
            FormsModuleParameter(name, dataType(attributes, "ParameterDataType"), length(attributes, "MaximumLength"))

        /** A data type, `Char` when absent. */
        private fun dataType(attributes: Map<String, FormsValue>, property: String): FormsItemDataType {
            val word = attributes[property]?.value?.trim().orEmpty()
            if (word.isEmpty()) return FormsItemDataType.CHAR
            return ITEM_DATA_TYPES[word.lowercase(Locale.ROOT)] ?: FormsItemDataType.UNKNOWN.also { unknown(property, word) }
        }

        /** A length, null when absent; a value other than a whole number is noted and taken as absent. */
        private fun length(attributes: Map<String, FormsValue>, property: String): Int? {
            val word = attributes[property]?.value?.trim().orEmpty()
            if (word.isEmpty()) return null
            return word.toIntOrNull()?.takeIf { it >= 0 } ?: null.also { unknown(property, word) }
        }

        /** The unit of an item's length: null when absent or `Null` (the session's), a word the reader does not know noted. */
        private fun lengthSemantics(attributes: Map<String, FormsValue>): FormsLengthSemantics? {
            val word = attributes["DataLengthSemantics"]?.value?.trim().orEmpty()
            val key = word.lowercase(Locale.ROOT)
            if (key.isEmpty() || key in NULL_SEMANTICS) return null
            return LENGTH_SEMANTICS[key] ?: null.also { unknown("DataLengthSemantics", word) }
        }

        /** A yes-or-no property, true when absent (the default of every one the reader takes); a value other than `true` or `false` keeps it, noted. */
        private fun flag(attributes: Map<String, FormsValue>, property: String): Boolean {
            val word = attributes[property]?.value?.trim().orEmpty()
            return when (word.lowercase(Locale.ROOT)) {
                "", "true" -> true
                "false" -> false
                else -> true.also { unknown(property, word) }
            }
        }

        private fun unknown(property: String, word: String) {
            unknown.merge(property to word, 1, Int::plus)
        }

        private fun trigger(name: FormsValue?, text: FormsValue?) {
            if (name == null || text == null) return
            val parent = open.lastOrNull()
            val level = parent?.let { OWNERS[it.kind] }
            if (level == null) {
                otherTriggers++
                return
            }
            // The names of the objects between the module and the trigger: the block, the block and its item, the event.
            val owner = open.drop(1).filter { it.kind == "Block" || it.kind == "Item" || it.kind == "Event" }.map { it.name.orEmpty() }
            code += FormsTrigger(level, owner, name, text)
        }

        /** The attributes of the element the reader is on, by local name, each decoded with its origin. */
        private fun attributes(): Map<String, FormsValue> {
            val result = HashMap<String, FormsValue>()
            for (i in 0 until reader.attributeCount) {
                val prefix = reader.getAttributePrefix(i).orEmpty()
                val origin = if (prefix.isEmpty()) null else ORIGINS.firstOrNull { prefix.endsWith("_$it") }
                result[reader.getAttributeLocalName(i)] = FormsValue(decode(reader.getAttributeValue(i)), origin)
            }
            return result
        }
    }

    /** The words of a data source or target type, in Portuguese and English. */
    private val DATA_TYPES = mapOf(
        "tabela" to FormsDataType.TABLE, "table" to FormsDataType.TABLE,
        "nenhum" to FormsDataType.NONE, "none" to FormsDataType.NONE,
        "procedimento" to FormsDataType.PROCEDURE, "procedure" to FormsDataType.PROCEDURE,
        "consulta da cláusula from" to FormsDataType.QUERY, "from clause query" to FormsDataType.QUERY,
        "gatilhos transacionais" to FormsDataType.TRANSACTIONAL_TRIGGERS, "transactional triggers" to FormsDataType.TRANSACTIONAL_TRIGGERS
    )

    /** The words of an item type, in Portuguese and English. */
    private val ITEM_KINDS = mapOf(
        "item de texto" to FormsItemKind.TEXT_ITEM, "text item" to FormsItemKind.TEXT_ITEM,
        "item de exibição" to FormsItemKind.DISPLAY_ITEM, "display item" to FormsItemKind.DISPLAY_ITEM,
        "item de lista" to FormsItemKind.LIST_ITEM, "list item" to FormsItemKind.LIST_ITEM,
        "caixa de seleção" to FormsItemKind.CHECK_BOX, "check box" to FormsItemKind.CHECK_BOX,
        "grupo de rádio" to FormsItemKind.RADIO_GROUP, "radio group" to FormsItemKind.RADIO_GROUP,
        "imagem" to FormsItemKind.IMAGE, "image" to FormsItemKind.IMAGE,
        "tecla" to FormsItemKind.PUSH_BUTTON, "push button" to FormsItemKind.PUSH_BUTTON,
        "área bean" to FormsItemKind.BEAN_AREA, "bean area" to FormsItemKind.BEAN_AREA,
        "árvore hierárquica" to FormsItemKind.HIERARCHICAL_TREE, "hierarchical tree" to FormsItemKind.HIERARCHICAL_TREE,
        "gráfico" to FormsItemKind.CHART, "item de gráfico" to FormsItemKind.CHART, "chart item" to FormsItemKind.CHART,
        "ole container" to FormsItemKind.OTHER, "contêiner ole" to FormsItemKind.OTHER, "sound" to FormsItemKind.OTHER, "som" to FormsItemKind.OTHER,
        "user area" to FormsItemKind.OTHER, "área do usuário" to FormsItemKind.OTHER, "vbx control" to FormsItemKind.OTHER,
        "controle vbx" to FormsItemKind.OTHER, "activex control" to FormsItemKind.OTHER, "controle activex" to FormsItemKind.OTHER
    )

    /** The words of a data type: Form Builder writes them untranslated. */
    private val ITEM_DATA_TYPES = mapOf(
        "char" to FormsItemDataType.CHAR, "alpha" to FormsItemDataType.ALPHA, "long" to FormsItemDataType.LONG,
        "number" to FormsItemDataType.NUMBER, "integer" to FormsItemDataType.INTEGER, "int" to FormsItemDataType.INTEGER,
        "rnumber" to FormsItemDataType.RNUMBER, "rinteger" to FormsItemDataType.RINTEGER, "rint" to FormsItemDataType.RINTEGER,
        "money" to FormsItemDataType.MONEY, "rmoney" to FormsItemDataType.RMONEY, "date" to FormsItemDataType.DATE,
        "datetime" to FormsItemDataType.DATETIME, "time" to FormsItemDataType.TIME, "jdate" to FormsItemDataType.JDATE,
        "edate" to FormsItemDataType.EDATE
    )

    /** The words of a `DataLengthSemantics` that leave the unit to the session, in Portuguese and English. */
    private val NULL_SEMANTICS = setOf("nulo", "null")

    /** The words of a length unit, in Portuguese and English. */
    private val LENGTH_SEMANTICS = mapOf(
        "byte" to FormsLengthSemantics.BYTE, "char" to FormsLengthSemantics.CHAR, "caractere" to FormsLengthSemantics.CHAR,
        "character" to FormsLengthSemantics.CHAR
    )

    /** An item's maximum length when the file does not give it: the Forms default. */
    private const val DEFAULT_LENGTH = 30

    /** A value with the escapes `frmf2xml` leaves after parsing: `&#10;` a line break, `&#x9;` a tab, carriage returns dropped. */
    internal fun decode(value: String): String {
        if (value.indexOf('&') < 0 && value.indexOf('\r') < 0) return value
        val result = StringBuilder(value.length)
        var at = 0
        while (at < value.length) {
            val c = value[at]
            if (c == '\r') {
                at++
                continue
            }
            if (c == '&') {
                val end = value.indexOf(';', at)
                when (if (end < 0) null else value.substring(at, end + 1)) {
                    "&#10;", "&#xA;", "&#xa;" -> result.append('\n')
                    "&#9;", "&#x9;" -> result.append('\t')
                    "&#13;", "&#xD;", "&#xd;" -> Unit
                    else -> {
                        result.append(c)
                        at++
                        continue
                    }
                }
                at = end + 1
                continue
            }
            result.append(c)
            at++
        }
        return result.toString()
    }
}
