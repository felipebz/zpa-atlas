//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli

import com.felipebz.zpa.atlas.forms.*
import com.felipebz.zpa.atlas.index.IncrementalReport
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

    /** A JAR in the readers folder that registers [reader] for the ServiceLoader; [build] tells two builds of it apart. */
    private fun jar(name: String, reader: KClass<*>, build: String = "1"): Path {
        val folder = temp.resolve("readers").createDirectories()
        val type = reader.java.name
        JarOutputStream(Files.newOutputStream(folder.resolve(name))).use { jar ->
            jar.putNextEntry(JarEntry(type.replace('.', '/') + ".class"))
            reader.java.getResourceAsStream("/" + type.replace('.', '/') + ".class")!!.use { it.transferTo(jar) }
            jar.putNextEntry(JarEntry("META-INF/services/${FormsModuleReader::class.java.name}"))
            jar.write("$type\n".toByteArray())
            jar.putNextEntry(JarEntry("build.txt"))
            jar.write(build.toByteArray())
        }
        return folder
    }

    /** Indexes [sources] into [db] with the readers of [folder], incrementally: what the run did, and the keys of the Forms modules. */
    private fun incremental(sources: Path, db: Path, folder: Path): Pair<IncrementalReport, List<String>> = moduleReaders(folder).use { readers ->
        val report = Indexer().index(IndexOptions(sources, db, threads = 1, readers = readers), incremental = true).incremental!!
        val modules = DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().executeQuery("SELECT key FROM entity WHERE kind = 'FORMS_MODULE' ORDER BY key")
                .use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        report to modules
    }

    @Test
    fun `an incremental run reads again the modules of a reader whose JAR changed, and only those`() {
        val sources = temp.resolve("src").createDirectories()
        sources.resolve("accounts_01.ver").writeText("message('accounts');")
        sources.resolve("orders_fmb.xml").writeText(
            """<Module version="122010300" xmlns="http://xmlns.oracle.com/Forms"><FormModule Name="ORDERS">
               |<Trigger Name="WHEN-NEW-FORM-INSTANCE" TriggerText="message('orders');"/></FormModule></Module>""".trimMargin()
        )
        val db = temp.resolve("atlas.db")
        try {
            val folder = jar("versioned.jar", VersionedReader::class, build = "1")
            assertThat(incremental(sources, db, folder).second).containsExactly("forms:ACCOUNTS_01", "forms:ORDERS")
            // The same readers: nothing to walk.
            val (same, _) = incremental(sources, db, folder)
            assertThat(same.line()).isEqualTo("Incremental run: walked 0 of 2 files (0 changed, 0 new, 0 affected), 0 removed")
            // Another build of the reader, which names the module otherwise: its file is read again, the other is not.
            VersionedReader.dropsVersion = true
            jar("versioned.jar", VersionedReader::class, build = "2")
            val (rebuilt, modules) = incremental(sources, db, folder)
            assertThat(rebuilt.line())
                .isEqualTo("Incremental run: walked 1 of 2 files (0 changed, 0 new, 0 affected, 1 read by another build of their module reader), 0 removed")
            assertThat(modules).containsExactly("forms:ACCOUNTS", "forms:ORDERS")
        } finally {
            VersionedReader.dropsVersion = false
        }
    }

    @Test
    fun `a reader of the readers folder indexes its modules next to the frmf2xml ones, and the server shows their text`() {
        // The JARs stay open while the readers are used, for the index and for the server, and are closed after.
        moduleReaders(jar("ledger.jar", LedgerJsonReader::class)).use { readers ->
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

/**
 * A reader of `.ver` files, each one module whose text is one trigger. Its two builds differ in how they name a module:
 * `accounts_01.ver` is `ACCOUNTS_01`, then with [dropsVersion], `ACCOUNTS`.
 */
class VersionedReader : FormsModuleReader {
    override val name = "versioned"
    override val fileNameEndings = listOf(".ver")

    override fun moduleName(fileName: String): String = super.moduleName(fileName).let { if (dropsVersion) it.replace(Regex("_\\d+$"), "") else it }

    override fun read(fileName: String, bytes: ByteArray): FormsModule = FormsModule(
        FormsValue(fileName), emptyList(),
        listOf(FormsTrigger(FormsTriggerLevel.MODULE, emptyList(), FormsValue("WHEN-NEW-FORM-INSTANCE"), FormsValue(bytes.decodeToString()))), emptyList()
    )

    companion object {
        /** Which build of the reader runs: the class is the test's, whichever JAR registers it. */
        @Volatile
        var dropsVersion = false
    }
}

/** A reader that claims every XML file. */
class AnyXmlReader : FormsModuleReader {
    override val name = "any-xml"
    override val fileNameEndings = listOf(".xml")
    override fun read(fileName: String, bytes: ByteArray): FormsModule? = null
}
