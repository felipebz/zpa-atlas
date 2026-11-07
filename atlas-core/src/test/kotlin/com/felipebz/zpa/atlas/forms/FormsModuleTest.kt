//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.forms

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The `frmf2xml` reader and the module text, on the invented modules `forms/order_desk_fmb.xml` (`DUMP=ALL`),
 * `forms/order_lite_fmb.xml`, and `forms/data_desk_fmb.xml` and `forms/data_plain_fmb.xml` (the same module, in English
 * words, with and without `DUMP=ALL`), as are `forms/shop_desk_fmb.xml` and `forms/shop_plain_fmb.xml`.
 */
class FormsModuleTest {
    private fun read(resource: String): FormsModule {
        val bytes = FormsModuleTest::class.java.getResourceAsStream("/forms/$resource")!!.use { it.readAllBytes() }
        return Frmf2xmlReader.read(resource.substringAfterLast('/'), bytes)!!
    }

    @Test
    fun `a module exported with DUMP=ALL gives its objects and code in the order of the file, with each value's origin`() {
        val module = read("order_desk_fmb.xml")
        assertThat(module.name).isEqualTo(FormsValue("ORDER_DESK", "overridden"))
        assertThat(module.blocks.map { block -> block.name.value + block.items.map { it.name.value } }).containsExactly("COMPANY[ID, COMPANY_NAME, LABEL]")
        assertThat(module.code.map { code ->
            when (code) {
                is FormsTrigger -> "${code.level} ${code.owner} ${code.name.value} ${code.text.origin}"
                is FormsProgramUnit -> "${code.kind} ${code.name.value}"
                is FormsRecordGroup -> "RECORD_GROUP ${code.name.value}"
            }
        }).containsExactly(
            "ITEM [COMPANY, ID] WHEN-VALIDATE-ITEM overridden",
            "BLOCK [COMPANY] POST-QUERY overridden",
            // Taken from the module it inherits from, REF_LIB.
            "BLOCK [COMPANY] KEY-EXIT inherited",
            "MODULE [] WHEN-NEW-FORM-INSTANCE overridden",
            // The kinds come from the code: the file's ProgramUnitType is in the language of the Forms Builder.
            "PROCEDURE PROC_STANDALONE",
            "FUNCTION FN_LABEL",
            "PACKAGE ORDER_UTIL",
            "PACKAGE_BODY ORDER_UTIL",
            "RECORD_GROUP COMPANY_LOV"
        )
        assertThat(module.libraries).containsExactly(FormsLibrary(FormsValue("ORDER_LIB", "overridden"), FormsValue("order_lib", "overridden")))
        assertThat(module.notes).isEmpty()
    }

    @Test
    fun `a calculated item's formula is its FORMULA-CALCULATION trigger, first among the item's, and a blank one is none`() {
        val module = read("calc_desk_fmb.xml")
        assertThat(module.code.map { code ->
            when (code) {
                is FormsTrigger -> "${code.level} ${code.owner} ${code.name.value} ${code.text.origin}: ${code.text.value}"
                else -> code.name.value
            }
        }).containsExactly(
            "ITEM [LINES, TOTAL] FORMULA-CALCULATION overridden: NVL(:lines.qty, 0) * pack_process.f_next",
            "ITEM [LINES, TOTAL] POST-CHANGE overridden: null;",
            "ITEM [LINES, RATE] FORMULA-CALCULATION overridden: fn_rate(\n  :lines.qty)\n  / 100",
            "FN_RATE"
        )
    }

    @Test
    fun `a formula is the value of an assignment to its item, its lines unchanged`() {
        val text = FormsModuleText.of(read("calc_desk_fmb.xml"), "CALC_DESK").text
        assertThat(text.substringBefore("\n-- ")).isEqualTo(
            """
            |BEGIN -- CALC_DESK/LINES/TOTAL/FORMULA-CALCULATION
            |:LINES.TOTAL :=
            |NVL(:lines.qty, 0) * pack_process.f_next
            |;
            |END;
            |
            |BEGIN -- CALC_DESK/LINES/TOTAL/POST-CHANGE
            |null;
            |END;
            |
            |BEGIN -- CALC_DESK/LINES/RATE/FORMULA-CALCULATION
            |:LINES.RATE :=
            |fn_rate(
            |  :lines.qty)
            |  / 100
            |;
            |END;
            |""".trimMargin()
        )
    }

    @Test
    fun `the module text wraps each trigger in a block and names each unit by its path`() {
        val text = FormsModuleText.of(read("order_desk_fmb.xml"), "ORDER_DESK")
        // The escaped line breaks are line breaks, the tabs and the blank line are kept, the carriage return is dropped.
        assertThat(text.text).isEqualTo(
            """
            |BEGIN -- ORDER_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM
            |DECLARE
            |  v_count number;
            |BEGIN
            |  select count(*) into v_count from company where id = :company.id;
            |END;
            |order_util.refresh(:company.id);
            |END;
            |
            |BEGIN -- ORDER_DESK/COMPANY/POST-QUERY
            |DECLARE
            |${'\t'}v_label varchar2(100);
            |BEGIN
            |${'\t'}v_label := fn_label(:company.id);
            |
            |${'\t'}:company.label := v_label;
            |END;
            |END;
            |
            |BEGIN -- ORDER_DESK/COMPANY/KEY-EXIT
            |exit_form;
            |END;
            |
            |BEGIN -- ORDER_DESK/WHEN-NEW-FORM-INSTANCE
            |go_block('COMPANY');
            |proc_standalone(1);
            |execute_query;
            |END;
            |
            |-- ORDER_DESK/PROC_STANDALONE
            |PROCEDURE proc_standalone(p_id number) IS
            |BEGIN
            |  message('Company ' || p_id);
            |  update company set dt_changed = sysdate where id = p_id;
            |END;
            |
            |-- ORDER_DESK/FN_LABEL
            |FUNCTION fn_label(p_id number) RETURN varchar2 IS
            |  v_label company.company_name%TYPE;
            |BEGIN
            |  select company_name into v_label from company where id = p_id;
            |  RETURN v_label;
            |END;
            |
            |-- ORDER_DESK/ORDER_UTIL
            |PACKAGE order_util IS
            |  g_refreshed number := 0;
            |  PROCEDURE refresh(p_id number);
            |END;
            |
            |-- ORDER_DESK/ORDER_UTIL
            |PACKAGE BODY order_util IS
            |  PROCEDURE refresh(p_id number) IS
            |    v_next number;
            |  BEGIN
            |    v_next := pack_process.f_next;
            |    pack_process.run_job(p_id, 'R', v_next, sysdate);
            |    g_refreshed := g_refreshed + 1;
            |  END;
            |END;
            |
            |-- ORDER_DESK/COMPANY_LOV
            |SELECT id, company_name
            |FROM company
            |ORDER BY company_name
            |;
            |""".trimMargin()
        )
        assertThat(text.units.map { "${it.path} ${it.firstLine}-${it.lastLine}" }).containsExactly(
            "ORDER_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM 1-8", "ORDER_DESK/COMPANY/POST-QUERY 10-18", "ORDER_DESK/COMPANY/KEY-EXIT 20-22",
            "ORDER_DESK/WHEN-NEW-FORM-INSTANCE 24-28", "ORDER_DESK/PROC_STANDALONE 30-35", "ORDER_DESK/FN_LABEL 37-43", "ORDER_DESK/ORDER_UTIL 45-49",
            "ORDER_DESK/ORDER_UTIL 51-60", "ORDER_DESK/COMPANY_LOV 62-66"
        )
    }

    @Test
    fun `a block and an item give their data properties in English words, the same exported with DUMP=ALL or without`() {
        val desk = read("data_desk_fmb.xml")
        val plain = read("data_plain_fmb.xml")
        fun withoutOrigins(module: FormsModule) = module.blocks.map { block ->
            block.copy(name = FormsValue(block.name.value), items = block.items.map { it.copy(name = FormsValue(it.name.value)) })
        }
        assertThat(withoutOrigins(plain)).isEqualTo(withoutOrigins(desk))
        val blocks = desk.blocks.associateBy { it.name.value }
        val company = blocks.getValue("COMPANY")
        assertThat(listOf(company.source, company.target))
            .containsExactly(FormsDataSource(FormsDataType.TABLE, "V_COMPANY"), FormsDataSource(FormsDataType.TABLE, "COMPANY"))
        assertThat(company.where to company.orderBy).isEqualTo("st_active = 'Y'" to "company_name")
        assertThat(company.items.map { "${it.name.value} ${it.database} ${it.kind} [${it.columnName}] ${it.insertAllowed} ${it.updateAllowed} ${it.kindWord}" })
            .containsExactly(
                "ID true TEXT_ITEM [] true false null",
                "NAME true TEXT_ITEM [COMPANY_NAME] true true null",
                "SAVE true PUSH_BUTTON [] true true null",
                "LABEL false DISPLAY_ITEM [] true true null",
                "LEVEL true UNKNOWN [] true true Slider"
            )
        // A blank DML target name is the source's, whatever the target's type.
        assertThat(blocks.getValue("EVENTS").target).isEqualTo(FormsDataSource(FormsDataType.TABLE, "EVENT_LOG"))
        assertThat(blocks.getValue("EVENTS").insertAllowed).isFalse()
        assertThat(blocks.getValue("QUEUE").target).isEqualTo(FormsDataSource(FormsDataType.NONE, "SEND_QUEUE"))
        assertThat(blocks.getValue("CONTROL").source).isEqualTo(FormsDataSource(FormsDataType.TABLE, ""))
        assertThat(listOf(blocks.getValue("ORDERS").source, blocks.getValue("ORDERS").target)).containsExactly(
            FormsDataSource(FormsDataType.PROCEDURE, "order_api.query_orders"), FormsDataSource(FormsDataType.TRANSACTIONAL_TRIGGERS, "order_api.query_orders")
        )
        assertThat(blocks.getValue("ODD").source).isEqualTo(FormsDataSource(FormsDataType.UNKNOWN, "IT_ORDERS", "Stored Thing"))
        assertThat(desk.notes).containsExactly(FormsNote("FORMS_VALUE_UNKNOWN", "2 value(s) not known: ItemType \"Slider\" (1), QueryDataSourceType \"Stored Thing\" (1)"))
        assertThat(plain.notes).isEqualTo(desk.notes)
    }

    @Test
    fun `a Doag module gives its data properties in the Portuguese words of its Form Builder`() {
        val module = read("doag/demo0004_fmb.xml")
        assertThat(module.blocks.map { "${it.name.value} ${it.database} ${it.source} ${it.target} ${it.queryAllowed} ${it.insertAllowed} ${it.updateAllowed} ${it.deleteAllowed}" })
            .containsExactly(
                "V\$LOCK true ${FormsDataSource(FormsDataType.TABLE, "v\$lock")} ${FormsDataSource(FormsDataType.TABLE, "v\$lock")} true false false false",
                "V\$SESSION true ${FormsDataSource(FormsDataType.TABLE, "V\$session")} ${FormsDataSource(FormsDataType.TABLE, "V\$session")} true false false false",
                "STEUERBLOCK false ${FormsDataSource(FormsDataType.TABLE, "")} ${FormsDataSource(FormsDataType.TABLE, "")} true true true true"
            )
        val session = module.blocks[1].items.associateBy { it.name.value }
        assertThat(listOf(session.getValue("SERIAL#").columnName, session.getValue("USERNAME").columnName)).containsExactly("SERIAL#", "")
        assertThat(module.blocks.flatMap { it.items }.groupingBy { it.kind }.eachCount())
            .isEqualTo(mapOf(FormsItemKind.TEXT_ITEM to 23, FormsItemKind.PUSH_BUTTON to 5))
        assertThat(module.blocks[0].items.count { it.database }).isEqualTo(8)
        // "Nulo" leaves the unit of the length to the session.
        assertThat(module.blocks.flatMap { it.items }.groupingBy { "${it.dataType} ${it.lengthSemantics}" }.eachCount())
            .isEqualTo(mapOf("CHAR null" to 13, "NUMBER null" to 9, "CHAR BYTE" to 5, "DATE null" to 1))
        assertThat(module.notes).isEmpty()
    }

    @Test
    fun `an item gives its data type, maximum length and unit, a parameter its data type, the same exported with DUMP=ALL or without`() {
        val desk = read("shop_desk_fmb.xml")
        val plain = read("shop_plain_fmb.xml")
        fun sizes(module: FormsModule) = module.blocks.flatMap { block ->
            block.items.map { "${block.name.value}.${it.name.value} ${it.kind} ${it.dataType} ${it.maximumLength} ${it.lengthSemantics}" }
        }
        // DEFAULT_STATUS has the Forms default length, BARE_STATUS no data type: Char.
        assertThat(sizes(desk)).containsExactly(
            "CTRL.SHORT_STATUS TEXT_ITEM CHAR 5 null",
            "CTRL.DEFAULT_STATUS TEXT_ITEM CHAR 30 null",
            "CTRL.WIDE_STATUS TEXT_ITEM CHAR 10 CHAR",
            "CTRL.COUNT_ID TEXT_ITEM NUMBER 10 null",
            "CTRL.STATUS_LIST LIST_ITEM CHAR 10 null",
            "CTRL.BARE_STATUS TEXT_ITEM CHAR 8 null",
            "CTRL.ONLY_HERE TEXT_ITEM CHAR 7 null",
            "CTRL.DUP TEXT_ITEM CHAR 12 null",
            "CTRL.STATUS_IMAGE IMAGE CHAR 30 null",
            "INFO.DUP TEXT_ITEM CHAR 12 null",
            "ORDERS.ORDER_ID TEXT_ITEM NUMBER 10 null",
            "ORDERS.STATUS TEXT_ITEM CHAR 4 null",
            "ORDERS_ALT.STATUS TEXT_ITEM CHAR 6 null"
        )
        assertThat(sizes(plain)).isEqualTo(sizes(desk))
        // frmf2xml does not write a parameter's maximum length.
        for (module in listOf(desk, plain)) {
            assertThat(module.parameters.map { "${it.name.value} ${it.dataType} ${it.maximumLength}" }).containsExactly("P_STATUS CHAR null")
            assertThat(module.notes).isEmpty()
        }
    }

    @Test
    fun `a data type, length or unit the reader does not know is noted, never guessed`() {
        val xml = """<Module version="122010300" xmlns="http://xmlns.oracle.com/Forms"><FormModule Name="ODD_SIZES"><Block Name="B">
            |<Item Name="A" DataType="Blob"/><Item Name="B" MaximumLength="long"/><Item Name="C" DataLengthSemantics="Caracteres largos"/>
            |<Item Name="D" DataType="Number" DataLengthSemantics="Nulo"/></Block></FormModule></Module>""".trimMargin()
        val module = Frmf2xmlReader.read("odd_sizes_fmb.xml", xml.toByteArray())!!
        assertThat(module.blocks.single().items.map { "${it.name.value} ${it.dataType} ${it.maximumLength} ${it.lengthSemantics}" })
            .containsExactly("A UNKNOWN 30 null", "B CHAR 30 null", "C CHAR 30 null", "D NUMBER 30 null")
        assertThat(module.notes).containsExactly(FormsNote("FORMS_VALUE_UNKNOWN",
            "3 value(s) not known: DataType \"Blob\" (1), MaximumLength \"long\" (1), DataLengthSemantics \"Caracteres largos\" (1)"))
    }

    @Test
    fun `a module exported without DUMP=ALL lacks the code it inherits, and says so`() {
        val module = read("order_lite_fmb.xml")
        // KEY-EXIT comes from REF_LIB: without DUMP=ALL its text is not in the file.
        assertThat(module.triggers.map { it.name.value }).containsExactly("WHEN-NEW-FORM-INSTANCE")
        assertThat(module.triggers.single().text.origin).isNull()
        assertThat(module.notes.map { it.kind to it.message.substringBefore(" object(s)") })
            .containsExactly("FORMS_NOT_FULL_DUMP" to "Exported without DUMP=ALL: only the properties the module sets itself are in the file, so the code of the 1")
    }

    @Test
    fun `a module exported without DUMP=ALL that inherits nothing has no note`() {
        val xml = """<Module version="122010300" xmlns="http://xmlns.oracle.com/Forms"><FormModule Name="PLAIN">
            |<Trigger Name="WHEN-NEW-FORM-INSTANCE" TriggerText="null;"/></FormModule></Module>""".trimMargin()
        assertThat(Frmf2xmlReader.read("plain_fmb.xml", xml.toByteArray())!!.notes).isEmpty()
    }

    @Test
    fun `a module is known by its file's name, whatever its Name says`() {
        val readers = FormsModuleReaders.BUILT_IN
        assertThat(readers.moduleName("demo0004_fmb.xml")).isEqualTo("DEMO0004")
        assertThat(readers.moduleName("Orders_FMB.XML")).isEqualTo("ORDERS")
        assertThat(readers.moduleName("orders.sql")).isNull()
        val bytes = FormsModuleTest::class.java.getResourceAsStream("/forms/order_desk_fmb.xml")!!.use { it.readAllBytes() }
        assertThat(readers.text("front_desk_fmb.xml", bytes)!!.lines().first()).isEqualTo("BEGIN -- FRONT_DESK/COMPANY/ID/WHEN-VALIDATE-ITEM")
    }

    @Test
    fun `an exported Doag module gives the code it inherits and the kinds its code declares`() {
        val module = read("doag/demo0002_fmb.xml")
        assertThat(module.name.value).isEqualTo("DEMO0002")
        // The triggers whose text the module takes from FRW_REF.
        assertThat(module.triggers.filter { it.text.origin == "inherited" }.map { "${it.owner}/${it.name.value}" })
            .containsExactly("[]/ON-ERROR", "[]/WHEN-NEW-FORM-INSTANCE", "[]/WHEN-NEW-RECORD-INSTANCE", "[]/WHEN-NEW-ITEM-INSTANCE", "[]/WHEN-NEW-BLOCK-INSTANCE")
        assertThat(module.programUnits.groupingBy { it.kind }.eachCount())
            .isEqualTo(mapOf(FormsProgramUnitKind.PROCEDURE to 5, FormsProgramUnitKind.FUNCTION to 1, FormsProgramUnitKind.PACKAGE to 3,
                FormsProgramUnitKind.PACKAGE_BODY to 2))
        assertThat(module.libraries.map { it.name.value }).containsExactly("FRW_LIB")
    }

    @Test
    fun `two readers may not claim the same files`() {
        val xml = object : FormsModuleReader {
            override val name = "any-xml"
            override val fileNameEndings = listOf(".XML")
            override fun read(fileName: String, bytes: ByteArray): FormsModule? = null
        }
        assertThatThrownBy { FormsModuleReaders.of(listOf(Frmf2xmlReader, xml)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("The module readers frmf2xml and any-xml both read the files whose name ends with '_fmb.xml'")
        assertThat(FormsModuleReaders.BUILT_IN.readerOf("ORDERS_FMB.XML")).isSameAs(Frmf2xmlReader)
        assertThat(FormsModuleReaders.BUILT_IN.skipped("menus_mmb.xml")).isEqualTo("menu module")
    }
}
