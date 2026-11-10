//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.query

import com.felipebz.zpa.atlas.Fixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** An entity's page: overloads, halves, declarations, documentation, triggers, views and keys. */
class EntityPageTest {
    private val db: Path = SharedFixtureIndex.db
    private val index = AtlasIndex(db, Fixture.sources)

    @Test
    fun `a spec parameter carries the flag of the body parameter whose uses it shows, and alone carries none`() {
        // hr/param_fixes.sql: the body of PK_G.RUN has a $IF zpa does not decide.
        val spec = "db:HR.PK_G.RUN(P_V IN NUMBER).P_V"
        val body = "db:HR.PK_G.RUN(P_V IN NUMBER)@BODY.P_V"
        assertThat(index.entity(spec).result!!.usesIncomplete).containsExactly("CONDITIONAL_COMPILATION")
        assertThat(index.entity(body).result!!.usesIncomplete).containsExactly("CONDITIONAL_COMPILATION")
        assertThat(index.occurrences(spec, UsageFilter(scope = setOf(Scope.SELF, Scope.IMPLEMENTATIONS))).result!!.usesIncomplete)
            .containsExactly(UsesGap(body, listOf("CONDITIONAL_COMPILATION")))
        assertThat(index.occurrences(spec, UsageFilter(scope = setOf(Scope.SELF))).result!!.usesIncomplete).isNull()
        // The routine itself, with its spec and body, is unchanged: no flag.
        assertThat(index.entity("db:HR.PK_G.RUN(P_V IN NUMBER)").result!!.usesIncomplete).isNull()
    }

    @Test
    fun `entity carries overloads, relations and a usage summary`() {
        val entity = index.entity(runJob5).result!!
        assertThat(entity.parent!!.key).isEqualTo("db:HR.PACK_PROCESS")
        assertThat(entity.overloads.map { it.entity.key to it.body }).containsExactly(
            runJob4 to false, "$runJob4@BODY" to true, runJob5 to false, "$runJob5@BODY" to true
        )
        assertThat(entity.overloads.single { it.self }.entity.key).isEqualTo(runJob5)
        assertThat(entity.relations).contains(RelationLink("IMPLEMENTS", "IN", EntitySummary("$runJob5@BODY", "PROCEDURE", "HR.PACK_PROCESS.RUN_JOB(P_A IN NUMBER,P_B IN VARCHAR2,P_C IN NUMBER,P_D IN DATE,P_E IN NUMBER)@BODY", false, "RUN_JOB")))
        assertThat(entity.children.map { it.entity.key.substringAfterLast('.') }).containsExactly("P_A", "P_B", "P_C", "P_D", "P_E")
        assertThat(entity.definitions.map { it.path to it.roles }).containsExactly("hr/pack_process.pks" to listOf("DECLARATION"))
        // Two resolved calls in pack_calls.pkb; two ambiguous calls list it as a candidate.
        assertThat(entity.usage.byRole).containsExactly(Count("CALL", 2))
        assertThat(entity.usage.byStatus).containsExactly(Count("RESOLVED", 2))
        assertThat(entity.usage.asCandidate).isEqualTo(2)
        assertThat(index.entity("db:NO.SUCH").result).isNull()
    }

    @Test
    fun `entity carries its other half with its lines, its ancestors and the unit kind of each definition`() {
        val spec = index.entity(runJob5).result!!
        val body = spec.partners.single()
        assertThat(body.entity.key to body.direction).isEqualTo("$runJob5@BODY" to "IN")
        assertThat(body.definitions.map { it.path to it.unitKind }).containsExactly("hr/pack_process.pkb" to "PACKAGE_BODY")
        assertThat(spec.definitions.map { it.unitKind }).containsExactly("PACKAGE")
        val back = index.entity("$runJob5@BODY").result!!.partners.single()
        assertThat(back.entity.key to back.direction).isEqualTo(runJob5 to "OUT")
        assertThat(back.definitions.map { it.path }).containsExactly("hr/pack_process.pks")
        // Only in the body: no partner. A package holds both halves itself, told apart by the unit kind.
        assertThat(index.entity("db:HR.PACK_PROCESS.F_PRIVATE()@BODY").result!!.partners).isEmpty()
        assertThat(index.entity("db:HR.PACK_PROCESS").result!!.definitions.map { it.path to it.unitKind })
            .containsExactlyInAnyOrder("hr/pack_process.pks" to "PACKAGE", "hr/pack_process.pkb" to "PACKAGE_BODY")
        val parameter = index.entity("db:HR.PACK_PROCESS.GET_EVENT(P_ID IN NUMBER,P_OUT OUT NUMBER,P_IO IN OUT NUMBER).P_ID").result!!
        assertThat(parameter.ancestors.map { it.key })
            .containsExactly("db:HR.PACK_PROCESS", "db:HR.PACK_PROCESS.GET_EVENT(P_ID IN NUMBER,P_OUT OUT NUMBER,P_IO IN OUT NUMBER)")
        assertThat(index.entity("db:HR.PACK_PROCESS").result!!.ancestors).isEmpty()
    }

    @Test
    fun `entity and each child say what their declaration says`() {
        assertThat(index.entity("db:HR.PACK_PROCESS.F_NEXT()").result!!.declared).isEqualTo(Declared("number", null, false, null, null))
        assertThat(index.entity("db:HR.PACK_PROCESS.C_KIND").result!!.declared).isEqualTo(Declared("varchar2(1)", "'J'", false, null, null))
        val defaults = index.entity(runJob5).result!!.children.associate { it.entity.name to it.declared?.value }
        assertThat(defaults).containsExactlyInAnyOrderEntriesOf(mapOf("P_A" to null, "P_B" to null, "P_C" to null, "P_D" to null, "P_E" to "0"))
        assertThat(index.entity(runJob5).result!!.declared).isNull()
        val pack = index.entity("db:HR.PACK_PROCESS").result!!.children.associate { it.entity.name to it.declared?.type }
        assertThat(pack).containsEntry("G_COUNTER", "number").containsEntry("C_KIND", "varchar2(1)").containsEntry("E_FAILED", null)
    }

    @Test
    fun `a body's parameter takes the default only its spec writes, and says what the spec says when they differ`() {
        val post = "db:HR.PACK_DECLARED.POST(P_ID IN NUMBER,P_NOTE IN VARCHAR2,P_WHEN IN DATE)"
        assertThat(index.entity("$post@BODY.P_NOTE").result!!.declared).isEqualTo(Declared("varchar2", "'none'", false, "SPEC", null))
        assertThat(index.entity("$post@BODY.P_WHEN").result!!.declared)
            .isEqualTo(Declared("date", "trunc(sysdate)", false, null, DeclaredText("date", "sysdate", false)))
        assertThat(index.entity("$post@BODY.P_ID").result!!.declared).isEqualTo(Declared("number", null, false, null, null))
        // The body routine's page has the same facts on its parameter children.
        val children = index.entity("$post@BODY").result!!.children.associate { it.entity.name to it.declared }
        assertThat(children["P_NOTE"]?.valueFrom).isEqualTo("SPEC")
        // The spec's own page: its own default, nothing to compare.
        assertThat(index.entity("$post.P_WHEN").result!!.declared).isEqualTo(Declared("date", "sysdate", false, null, null))
    }

    @Test
    fun `each half shows its own documentation, then the other half's when it says something else, or the other's alone`() {
        fun docs(key: String) = index.entity(key).result!!.documentation.map { "${it.from ?: "OWN"} ${it.shape} ${it.text}" }
        val pack = "db:HR.PACK_DOC"
        // Both the same: shown once, on either page.
        assertThat(docs("$pack.SAME_BOTH()")).containsExactly("OWN ABOVE Spec and body say the same.")
        assertThat(docs("$pack.SAME_BOTH()@BODY")).containsExactly("OWN AFTER_BEGIN Spec and body say the same.")
        // Both different: its own first, then the other half's, marked.
        assertThat(docs("$pack.DIFFER_BOTH()")).containsExactly("OWN ABOVE The spec says this.", "BODY AFTER_BEGIN The body says that.")
        assertThat(docs("$pack.DIFFER_BOTH()@BODY")).containsExactly("OWN AFTER_BEGIN The body says that.", "SPEC ABOVE The spec says this.")
        // One half only: the other page shows it, marked.
        assertThat(docs("$pack.BODY_ONLY()")).containsExactly("BODY AFTER_IS Only the body says this,\n  indented on its second line.")
        assertThat(docs("$pack.SPEC_ONLY()@BODY")).containsExactly("SPEC ABOVE Only the spec says this.\n  Indented under a star.")
        // A package holds both halves: the spec has none, the body's block after IS shows from the body.
        assertThat(docs(pack)).containsExactly("BODY AFTER_IS Purpose:  Ledger posting.\n\nWho     Date        Description\nABC     01.02.2024  Created")
        // Each child has its own: the body's parameters show the spec's end-of-line comments.
        val post = "$pack.POST_ENTRY(P_ACCOUNT IN NUMBER,P_AMOUNT IN NUMBER)"
        val parameters = index.entity("$post@BODY").result!!.children.associate { it.entity.name to it.documentation.map { doc -> "${doc.from} ${doc.text} ${doc.path}:${doc.line}" } }
        assertThat(parameters).containsEntry("P_ACCOUNT", listOf("SPEC the account to post to hr/pack_doc.pks:4"))
    }

    @Test
    fun `trigger events are listed on the trigger, its table and the updated columns`() {
        fun events(key: String) = index.entity(key).result!!.triggerEvents.map { "${it.trigger.key} ${it.timing} ${it.event} ${it.column?.key ?: "-"}" }
        assertThat(events("db:HR.TRG_COMPANY_FORMS")).containsExactly(
            "db:HR.TRG_COMPANY_FORMS BEFORE INSERT -",
            "db:HR.TRG_COMPANY_FORMS BEFORE UPDATE -",
            "db:HR.TRG_COMPANY_FORMS BEFORE UPDATE db:HR.COMPANY.COMPANY_NAME",
            "db:HR.TRG_COMPANY_FORMS BEFORE UPDATE db:HR.COMPANY.ST_ACTIVE"
        )
        assertThat(events("db:HR.COMPANY")).contains("db:HR.TRG_COMPANY_STMT AFTER DELETE -").doesNotContain("db:HR.TRG_V_COMPANY_IOI INSTEAD_OF INSERT -")
        assertThat(events("db:HR.V_COMPANY")).containsExactly("db:HR.TRG_V_COMPANY_IOI INSTEAD_OF INSERT -")
        // UPDATE OF the column, or UPDATE without a column list (also through the synonym); not UPDATE OF another column, not other events.
        assertThat(events("db:HR.COMPANY.ST_ACTIVE")).containsExactly(
            "db:HR.TRG_COMPANY_BU BEFORE UPDATE -",
            "db:HR.TRG_COMPANY_COMPOUND COMPOUND UPDATE -",
            "db:HR.TRG_COMPANY_FORMS BEFORE UPDATE db:HR.COMPANY.ST_ACTIVE",
            "db:HR.TRG_COMPANY_VALUES BEFORE UPDATE -",
            "db:HR.TRG_DYNAMIC BEFORE UPDATE -",
            "db:HR.TRG_SYN_COMPANY BEFORE UPDATE -"
        )
        // TRG_COMPANY_FORMS lists columns, one of them unresolved (no column): it does not fire on other columns.
        assertThat(events("db:HR.COMPANY.DT_CHANGED")).noneMatch { it.startsWith("db:HR.TRG_COMPANY_FORMS") }
    }

    @Test
    fun `a view lists what its query reads and a relation lists the views over it`() {
        fun keys(list: List<EntitySummary>) = list.map { it.key }
        // Every level of the query: the WITH subquery's table too.
        assertThat(keys(index.entity("db:HR.V_ACTIVE_COMPANY").result!!.reads)).containsExactly("db:HR.COMPANY")
        assertThat(keys(index.entity("db:HR.V_LOG_UNION").result!!.reads)).containsExactly("db:HR.EVENT_LOG", "db:HR.SEND_QUEUE")
        assertThat(keys(index.entity("db:HR.V_COMPANY_NAMES").result!!.reads)).containsExactly("db:HR.V_COMPANY_LIST")
        assertThat(keys(index.entity("db:HR.V_COMPANY_LIST").result!!.views)).containsExactly("db:HR.MV_COMPANY_LOG", "db:HR.V_COMPANY_NAMES")
        // A procedure reading the table is not a view over it.
        assertThat(keys(index.entity("db:HR.EVENT_LOG").result!!.views)).containsExactly(
            "db:HR.V_INLINE_NESTED", "db:HR.V_INLINE_UNION", "db:HR.V_LIN_JOIN", "db:HR.V_LIN_SCALAR", "db:HR.V_LIN_UNION",
            "db:HR.V_LIN_WITH_STAR", "db:HR.V_LOG_UNION", "db:HR.V_SOURCE_ONLY"
        )
        assertThat(index.entity("db:HR.COMPANY.ID").result!!.views).isEmpty()
    }

    @Test
    fun `a view column lists the columns it comes from and a column the view columns computed from it`() {
        fun keys(list: List<EntitySummary>) = list.map { it.key }
        assertThat(keys(index.entity("db:HR.V_CHAIN2.LABEL").result!!.baseColumns)).containsExactly("db:HR.V_CHAIN1.COMPANY_NAME")
        val middle = index.entity("db:HR.V_CHAIN1.COMPANY_NAME").result!!
        assertThat(keys(middle.baseColumns)).containsExactly("db:HR.COMPANY.COMPANY_NAME")
        assertThat(keys(middle.viewColumns)).containsExactly("db:HR.V_CHAIN2.LABEL")
        // The lineage has its own lists, in neither direction among the relations.
        assertThat(middle.relations).noneMatch { it.kind == "VIEW_BASE" }
        // One hop: the second level of the chain is not listed on the table column.
        assertThat(keys(index.entity("db:HR.COMPANY.COMPANY_NAME").result!!.viewColumns))
            .contains("db:HR.V_CHAIN1.COMPANY_NAME").doesNotContain("db:HR.V_CHAIN2.LABEL")
        assertThat(index.entity("db:HR.COMPANY").result!!.viewColumns).isEmpty()
    }

    @Test
    fun `a table lists its keys and the foreign keys to them, a column its own keys, its type and NOT NULL`() {
        fun text(key: TableKey): String = "${key.owner}.${key.name} ${key.kind} ${key.table} ${key.relation?.key ?: "-"} " +
            key.columns.joinToString(",") { it.column?.key ?: it.name } + (if (key.enabled) "" else " DISABLED") +
            (key.deleteRule?.let { " $it" } ?: "") + (key.referencedName?.let { " -> $it ${key.references?.columns?.joinToString(",") { c -> c.column?.key ?: c.name } ?: "?"}" } ?: "")
        val orders = index.entity("db:HR.KY_ORDERS").result!!
        assertThat(orders.keys.map(::text)).containsExactly(
            "HR.PK_KY_ORDERS PRIMARY_KEY HR.KY_ORDERS db:HR.KY_ORDERS db:HR.KY_ORDERS.ORDER_ID,db:HR.KY_ORDERS.LINE_NO",
            "HR.UK_KY_ORDERS_CODE UNIQUE HR.KY_ORDERS db:HR.KY_ORDERS db:HR.KY_ORDERS.CODE"
        )
        // A referencing table the dictionary did not read gives names only; each foreign key names the key it references,
        // and each of its columns the column it references.
        fun referencing(key: ReferencingKey): String = "${key.owner}.${key.name} ${key.table} ${key.relation?.key ?: "-"} " +
            key.columns.joinToString(",") { "${it.column?.key ?: it.name}->${it.references}" } + (if (key.enabled) "" else " DISABLED") +
            (key.deleteRule?.let { " $it" } ?: "") + " -> ${key.references}"
        assertThat(orders.referencedBy.map(::referencing)).containsExactly(
            "FIN.FK_PAYMENTS_ORDER FIN.PAYMENTS - ORDER_ID->ORDER_ID,LINE_NO->LINE_NO SET NULL -> HR.PK_KY_ORDERS",
            "HR.FK_KY_LINES_CODE HR.KY_LINES db:HR.KY_LINES db:HR.KY_LINES.ORDER_CODE->CODE DISABLED NO ACTION -> HR.UK_KY_ORDERS_CODE",
            "HR.FK_KY_LINES_ORDER HR.KY_LINES db:HR.KY_LINES db:HR.KY_LINES.ORDER_ID->ORDER_ID,db:HR.KY_LINES.LINE_NO->LINE_NO CASCADE -> HR.PK_KY_ORDERS"
        )
        // The dictionary sees every table: nothing says the lists may be incomplete.
        assertThat(orders.referencedByIncomplete).isNull()
        // A column: the keys it is in, and only the foreign keys to those.
        val lineNo = index.entity("db:HR.KY_ORDERS.LINE_NO").result!!
        assertThat(lineNo.declared?.type).isEqualTo("NUMBER(4) NOT NULL")
        assertThat(lineNo.keys.map { it.name }).containsExactly("PK_KY_ORDERS")
        assertThat(lineNo.referencedBy.map { it.name }).containsExactly("FK_PAYMENTS_ORDER", "FK_KY_LINES_ORDER")
        // The same, a page at a time.
        val first = index.referencingKeys("db:HR.KY_ORDERS", 0, 2).result!!
        assertThat(first.rows.map { it.name } to first.nextOffset).isEqualTo(listOf("FK_PAYMENTS_ORDER", "FK_KY_LINES_CODE") to 2)
        val last = index.referencingKeys("db:HR.KY_ORDERS", 2, 2).result!!
        assertThat(listOf(last.total, last.rows.size, last.nextOffset)).containsExactly(3, 1, null)
        // A referenced table the dictionary did not read: the key and its columns by name.
        val currency = index.entity("db:HR.KY_LINES.CURRENCY").result!!
        assertThat(currency.keys.map(::text)).containsExactly(
            "HR.FK_KY_LINES_CURRENCY FOREIGN_KEY HR.KY_LINES db:HR.KY_LINES db:HR.KY_LINES.CURRENCY NO ACTION -> FIN.PK_CURRENCIES CODE"
        )
        assertThat(currency.declared?.type).isEqualTo("CHAR(3 BYTE)")
        assertThat(currency.referencedBy).isEmpty()
        // The table's column list carries each column's type.
        assertThat(orders.children.map { "${it.entity.name} ${it.declared?.type}" }).contains("CODE VARCHAR2(30 CHAR) NOT NULL", "SHAPE HR.TY_SHAPE")
        assertThat(index.entity("db:HR.COMPANY").result!!.keys).isEmpty()
    }
}
