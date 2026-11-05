//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.forms

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The `frmf2xml` reader and the module text, on the invented modules `forms/order_desk_fmb.xml` (`DUMP=ALL`) and `forms/order_lite_fmb.xml`. */
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
