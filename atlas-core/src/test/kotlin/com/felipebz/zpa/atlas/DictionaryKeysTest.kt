//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.api.project.ProjectExternalColumn
import com.felipebz.zpa.api.project.ProjectExternalDatatype
import com.felipebz.zpa.api.project.ProjectExternalDatatype.LengthSemantics
import com.felipebz.zpa.api.project.ProjectExternalMetadata
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.ColumnDatatypes
import com.felipebz.zpa.atlas.dictionary.DictionaryKey
import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import com.felipebz.zpa.atlas.model.KeyKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The keys of an acquired dictionary, which lists no relation: only the relations code names are entities, and those at
 * the other end of one of their keys.
 */
class DictionaryKeysTest {
    @TempDir
    lateinit var temp: Path

    private fun number(precision: Int? = null, scale: Int? = null) = ProjectExternalDatatype.of("NUMBER", null, null, null, precision, scale)

    private fun table(name: String, vararg columns: String) =
        ProjectExternalRelation.of("SALES", name, true, columns.mapIndexed { i, c -> ProjectExternalColumn.of(c, i + 1, number(10, 0)) })

    @Test
    fun `the relation at the other end of a key of a named relation becomes an entity, one hop only, and the run says when keys may be missing`() {
        val sources = Files.createDirectories(temp.resolve("src"))
        Files.writeString(sources.resolve("count_orders.prc"),
            "create or replace procedure count_orders is\n  n number;\nbegin\n  select count(*) into n from orders;\nend;\n/\n")
        val metadata = ProjectExternalMetadata.builder("test").completeSchema("SALES").apply {
            relation(table("ORDERS", "ID"))
            relation(table("ORDER_LINES", "ORDER_ID", "PRODUCT_ID"))
            relation(table("PRODUCTS", "ID"))
        }.build()
        val dictionary = AtlasDictionary.lookupOnly(metadata, setOf("SALES"), keys = listOf(
            DictionaryKey("SALES", "PK_ORDERS", KeyKind.PRIMARY_KEY, "SALES", "ORDERS", listOf("ID"), enabled = true),
            DictionaryKey("SALES", "PK_PRODUCTS", KeyKind.PRIMARY_KEY, "SALES", "PRODUCTS", listOf("ID"), enabled = true),
            DictionaryKey("SALES", "FK_LINES_ORDER", KeyKind.FOREIGN_KEY, "SALES", "ORDER_LINES", listOf("ORDER_ID"), enabled = true,
                "CASCADE", "SALES", "PK_ORDERS"),
            DictionaryKey("SALES", "FK_LINES_PRODUCT", KeyKind.FOREIGN_KEY, "SALES", "ORDER_LINES", listOf("PRODUCT_ID"), enabled = true,
                "NO ACTION", "SALES", "PK_PRODUCTS")
        ), notNull = mapOf(("SALES" to "ORDERS") to setOf("ID")))
        val db = temp.resolve("keys.db")
        Indexer().index(IndexOptions(sources, db, compilationSchema = "SALES", dictionary = dictionary, threads = 1))

        // ORDERS is named; ORDER_LINES references it; PRODUCTS is two keys away.
        assertThat(Fixture.query(db, "SELECT key FROM entity WHERE kind = 'TABLE' ORDER BY 1")).containsExactly("db:SALES.ORDERS", "db:SALES.ORDER_LINES")
        // Every key of an entity relation, and the key at the other end of each, by name when its relation is no entity.
        assertThat(Fixture.query(db, """SELECT k.name || ' ' || coalesce(e.key, '-') || ' ' || coalesce(r.name, '-') FROM dictionary_key k
            LEFT JOIN entity e ON e.id = k.relation_id LEFT JOIN dictionary_key r ON r.id = k.referenced_id ORDER BY k.id""")).containsExactly(
            "FK_LINES_ORDER db:SALES.ORDER_LINES PK_ORDERS",
            "FK_LINES_PRODUCT db:SALES.ORDER_LINES PK_PRODUCTS",
            "PK_ORDERS db:SALES.ORDERS -",
            "PK_PRODUCTS - -"
        )
        assertThat(Fixture.query(db, "SELECT e.key || ' ' || d.datatype FROM declared_text d JOIN entity e ON e.id = d.entity_id ORDER BY 1"))
            .contains("db:SALES.ORDERS.ID NUMBER(10) NOT NULL", "db:SALES.ORDER_LINES.ORDER_ID NUMBER(10)")
        // The loading user saw every table: nothing says the referencing keys may be more.
        val note = "SELECT kind || ' ' || substr(message, 1, instr(message, ':') - 1) FROM coverage_note WHERE kind = 'REFERENCED_BY_INCOMPLETE'"
        assertThat(Fixture.query(db, note)).isEmpty()
        // One that did not: the run says so, with the reason.
        val partial = temp.resolve("partial.db")
        Indexer().index(IndexOptions(sources, partial, compilationSchema = "SALES", threads = 1, dictionary = AtlasDictionary.lookupOnly(
            metadata, setOf("SALES"), keys = dictionary.keys, notNull = dictionary.notNull, seesEveryTable = false)))
        assertThat(Fixture.query(partial, note)).containsExactly("REFERENCED_BY_INCOMPLETE TABLES_NOT_VISIBLE")
    }

    @Test
    fun `a datatype is written as CREATE TABLE declares it`() {
        fun text(name: String, length: Int? = null, unit: LengthSemantics? = null, precision: Int? = null, scale: Int? = null, owner: String? = null) =
            ColumnDatatypes.text(ProjectExternalDatatype.of(name, owner, length, unit, precision, scale))
        assertThat(listOf(
            text("VARCHAR2", 30, LengthSemantics.CHAR), text("CHAR", 1, LengthSemantics.BYTE), text("NVARCHAR2", 20, LengthSemantics.CHAR),
            text("RAW", 16, LengthSemantics.BYTE), text("NUMBER"), text("NUMBER", precision = 10, scale = 0), text("NUMBER", precision = 10, scale = 2),
            text("NUMBER", scale = 0), text("FLOAT", precision = 126), text("DATE"), text("TIMESTAMP(6)", scale = 6), text("T_ADDRESS", owner = "SALES"),
            text("XMLTYPE", owner = "PUBLIC")
        )).containsExactly(
            "VARCHAR2(30 CHAR)", "CHAR(1 BYTE)", "NVARCHAR2(20)", "RAW(16)", "NUMBER", "NUMBER(10)", "NUMBER(10,2)", "NUMBER(*,0)", "FLOAT(126)",
            "DATE", "TIMESTAMP(6)", "SALES.T_ADDRESS", "XMLTYPE"
        )
    }
}
