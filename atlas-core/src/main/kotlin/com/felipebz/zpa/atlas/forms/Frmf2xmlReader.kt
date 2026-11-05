//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.forms

import java.io.ByteArrayInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * Reads the form modules Oracle's `frmf2xml` exports (Forms 12, `name_fmb.xml`): a `Module` root with one
 * `FormModule`, whose objects are elements by kind (`Block`, `Item`, `Trigger`, `ProgramUnit`, `RecordGroup`,
 * `AttachedLibrary`, `Event`), each named by its `Name` attribute.
 *
 * Attributes are read by their local name. With `DUMP=ALL` every property is written, under a prefix that says where
 * its value comes from (`MODULE_default:`, `MODULE_overridden:`, `PARENT_inherited:`, `MODULE_inherited_overridden:`,
 * all bound to the Forms namespace): the word after the module name is the value's origin. Without it only the
 * properties the module itself sets are written, without prefix, so the code a module inherits from another
 * (an object with a `ParentModule`) is missing: a note says so.
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
        private val code = ArrayList<FormsCode>()
        private val libraries = ArrayList<FormsLibrary>()
        private var inheriting = 0
        private var otherTriggers = 0

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
            return FormsModule(name, blocks, code, libraries, notes)
        }

        private fun start() {
            val kind = reader.localName
            val attributes = attributes()
            val name = attributes["Name"]
            if (attributes.containsKey("ParentModule")) inheriting++
            when (kind) {
                "Module" -> dumpAll = attributes["Dump"]?.value.equals("All", ignoreCase = true)
                "FormModule" -> if (moduleName == null) moduleName = name
                "Block" -> if (parent() == "FormModule" && name != null) {
                    block = name
                    items = ArrayList()
                }
                "Item" -> if (parent() == "Block" && name != null) items += FormsItem(name)
                "Trigger" -> trigger(name, attributes["TriggerText"])
                "ProgramUnit" -> if (name != null) attributes["ProgramUnitText"]?.let { code += FormsProgramUnit(name, it) }
                "RecordGroup" -> if (name != null) attributes["RecordGroupQuery"]?.takeIf { it.value.isNotBlank() }?.let { code += FormsRecordGroup(name, it) }
                "AttachedLibrary" -> if (name != null) libraries += FormsLibrary(name, attributes["LibraryLocation"])
            }
            open += Open(kind, name?.value)
        }

        private fun end() {
            val closed = open.removeAt(open.size - 1)
            if (closed.kind == "Block" && parent() == "FormModule") {
                block?.let { blocks += FormsBlock(it, items) }
                block = null
            }
        }

        private fun parent(): String? = open.lastOrNull()?.kind

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
