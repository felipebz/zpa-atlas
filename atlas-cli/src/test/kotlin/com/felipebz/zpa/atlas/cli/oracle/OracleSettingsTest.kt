//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli.oracle

import com.felipebz.zpa.atlas.cli.CliValidationException
import com.felipebz.zpa.atlas.cli.IndexArguments
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OracleSettingsTest {
    private fun arguments(schemas: String = "", relations: String = "", compilationSchema: String = "") = IndexArguments().apply {
        oracle.oracleUrl = "jdbc:oracle:thin:@//localhost:1521/freepdb1"
        oracle.oracleUser = "test"
        oracle.oracleSchemas = schemas
        oracle.oracleRelations = relations
        this.compilationSchema = compilationSchema
    }

    private fun settings(args: IndexArguments) = OracleSettings.from(args.oracle) { "secret" }!!

    @Test
    fun `unquoted names are case-insensitive and quoted names keep their case`() {
        val args = arguments(schemas = "test, \"MixedCase\", TEST", relations = "hr.employees, \"Hr\".\"Emp\"", compilationSchema = " test ")
        val settings = settings(args)

        assertThat(settings.schemas).containsExactly("TEST", "MixedCase")
        assertThat(settings.relations).containsExactly("HR" to "EMPLOYEES", "Hr" to "Emp")
        assertThat(OracleSettings.compilationSchema(args)).isEqualTo("TEST")
        assertThat(OracleSettings.compilationSchema(arguments(schemas = "test", compilationSchema = "\"test\""))).isEqualTo("test")
    }

    @Test
    fun `malformed quoted names are rejected`() {
        assertThatThrownBy { settings(arguments(schemas = "\"\"")) }.isInstanceOf(CliValidationException::class.java)
        assertThatThrownBy { settings(arguments(schemas = "te\"st")) }.isInstanceOf(CliValidationException::class.java)
        assertThatThrownBy { settings(arguments(relations = "hr.\"a\"b\"")) }.isInstanceOf(CliValidationException::class.java)
        assertThatThrownBy { OracleSettings.compilationSchema(arguments(schemas = "ut3", compilationSchema = "\"test")) }
            .isInstanceOf(CliValidationException::class.java)
    }
}
