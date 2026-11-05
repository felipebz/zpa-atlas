//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import com.felipebz.zpa.atlas.forms.*
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.query.AtlasIndex
import kotlinx.serialization.json.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.reflect.KClass

/** Readers of other export formats, loaded from the JARs of a readers folder as `index` and `serve` load them. */
class ModuleReadersTest {
    @TempDir
    lateinit var temp: Path

    /** A JAR in the readers folder that registers [reader] for the ServiceLoader. */
    private fun jar(name: String, reader: KClass<*>): Path {
        val folder = temp.resolve("readers").createDirectories()
        val type = reader.java.name
        JarOutputStream(Files.newOutputStream(folder.resolve(name))).use { jar ->
            jar.putNextEntry(JarEntry(type.replace('.', '/') + ".class"))
            reader.java.getResourceAsStream("/" + type.replace('.', '/') + ".class")!!.use { it.transferTo(jar) }
            jar.putNextEntry(JarEntry("META-INF/services/${FormsModuleReader::class.java.name}"))
            jar.write("$type\n".toByteArray())
        }
        return folder
    }

    @Test
    fun `a reader of the readers folder indexes its modules next to the frmf2xml ones, and the server shows their text`() {
        val readers = moduleReaders(jar("ledger.jar", LedgerJsonReader::class))
        assertThat(readers.readers.map { it.name }).containsExactly("frmf2xml", "ledger-json")
        val sources = temp.resolve("src").createDirectories()
        sources.resolve("books_ledger.json").writeText(
            """{"module": "BOOKS", "triggers": [{"name": "WHEN-NEW-FORM-INSTANCE", "text": "post_entry;"}],
               |"programUnits": [{"name": "POST_ENTRY", "text": "PROCEDURE post_entry IS\nBEGIN\n  delete orders where id = 0;\nEND;"}]}""".trimMargin()
        )
        sources.resolve("orders_fmb.xml").writeText(
            """<Module version="122010300" xmlns="http://xmlns.oracle.com/Forms"><FormModule Name="ORDERS">
               |<Trigger Name="WHEN-NEW-FORM-INSTANCE" TriggerText="message('orders');"/></FormModule></Module>""".trimMargin()
        )
        val db = temp.resolve("atlas.db")
        Indexer().index(IndexOptions(sources, db, threads = 1, readers = readers))
        val units = DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().executeQuery(
                "SELECT f.path || ' ' || f.kind || ' ' || u.kind || ' ' || u.name FROM unit u JOIN source_file f ON f.id = u.file_id ORDER BY u.id"
            ).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        assertThat(units).containsExactly(
            "books_ledger.json FORMS_MODULE FORMS_TRIGGER BOOKS/WHEN-NEW-FORM-INSTANCE",
            "books_ledger.json FORMS_MODULE FORMS_PROGRAM_UNIT BOOKS/POST_ENTRY",
            "orders_fmb.xml FORMS_MODULE FORMS_TRIGGER ORDERS/WHEN-NEW-FORM-INSTANCE"
        )
        val page = AtlasIndex(db, sources, moduleTexts = ReaderModuleTexts(readers)).file("books_ledger.json").result!!
        assertThat(page.stale).isFalse()
        assertThat(page.lines).containsExactly(
            "BEGIN -- BOOKS/WHEN-NEW-FORM-INSTANCE", "post_entry;", "END;", "", "-- BOOKS/POST_ENTRY", "PROCEDURE post_entry IS", "BEGIN",
            "  delete orders where id = 0;", "END;", ""
        )
        assertThat(page.objects.map { it.entity.key }).containsExactly("forms:BOOKS/WHEN-NEW-FORM-INSTANCE", "forms:BOOKS/POST_ENTRY")
    }

    @Test
    fun `two readers that read the same files stop the command`() {
        val folder = jar("clash.jar", AnyXmlReader::class)
        assertThatThrownBy { moduleReaders(folder) }
            .isInstanceOf(CliValidationException::class.java)
            .hasMessageStartingWith("The module readers frmf2xml and any-xml both read the files whose name ends with '_fmb.xml'")
    }
}

/** A reader of an invented JSON format: `{"module": …, "triggers": [{"name", "text"}], "programUnits": [{"name", "text"}]}`. */
class LedgerJsonReader : FormsModuleReader {
    override val name = "ledger-json"
    override val fileNameEndings = listOf("_ledger.json")

    override fun read(fileName: String, bytes: ByteArray): FormsModule {
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        fun list(key: String) = json[key]?.jsonArray.orEmpty().map { it.jsonObject }
        fun value(item: JsonObject, key: String) = FormsValue(item.getValue(key).jsonPrimitive.content)
        val triggers = list("triggers").map { FormsTrigger(FormsTriggerLevel.MODULE, emptyList(), value(it, "name"), value(it, "text")) }
        val units = list("programUnits").map { FormsProgramUnit(value(it, "name"), value(it, "text")) }
        return FormsModule(FormsValue(json["module"]!!.jsonPrimitive.content), emptyList(), triggers + units, emptyList())
    }
}

/** A reader that claims every XML file. */
class AnyXmlReader : FormsModuleReader {
    override val name = "any-xml"
    override val fileNameEndings = listOf(".xml")
    override fun read(fileName: String, bytes: ByteArray): FormsModule? = null
}
