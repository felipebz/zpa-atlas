//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SqlLiteralsTest {
    @Test
    fun `a plain literal loses its quotes and its doubled quotes, keeping case`() {
        assertThat(SqlLiterals.decodeString("'Abc'")).isEqualTo("Abc")
        assertThat(SqlLiterals.decodeString("'it''s'")).isEqualTo("it's")
        assertThat(SqlLiterals.decodeString("''''")).isEqualTo("'")
        assertThat(SqlLiterals.decodeString("''")).isEqualTo("")
    }

    @Test
    fun `the N prefix is dropped`() {
        assertThat(SqlLiterals.decodeString("n'x'")).isEqualTo("x")
        assertThat(SqlLiterals.decodeString("N'it''s'")).isEqualTo("it's")
    }

    @Test
    fun `q quoting keeps the text between its delimiters, quotes included`() {
        assertThat(SqlLiterals.decodeString("q'[it's]'")).isEqualTo("it's")
        assertThat(SqlLiterals.decodeString("Q'{a}b}'")).isEqualTo("a}b")
        assertThat(SqlLiterals.decodeString("q'<x>'")).isEqualTo("x")
        assertThat(SqlLiterals.decodeString("q'(x'')'")).isEqualTo("x''")
        assertThat(SqlLiterals.decodeString("q'!don't!'")).isEqualTo("don't")
        assertThat(SqlLiterals.decodeString("nq'[x]'")).isEqualTo("x")
    }

    @Test
    fun `text that is not a character literal decodes to nothing`() {
        assertThat(SqlLiterals.decodeString("abc")).isNull()
        assertThat(SqlLiterals.decodeString("q'[x)'")).isNull()
    }

    @Test
    fun `the same number always has the same text`() {
        assertThat(SqlLiterals.canonicalNumber("1.50")).isEqualTo("1.5")
        assertThat(SqlLiterals.canonicalNumber("+1.5")).isEqualTo("1.5")
        assertThat(SqlLiterals.canonicalNumber("1e3")).isEqualTo("1000")
        assertThat(SqlLiterals.canonicalNumber("1.2E-2")).isEqualTo("0.012")
        assertThat(SqlLiterals.canonicalNumber("100")).isEqualTo("100")
        assertThat(SqlLiterals.canonicalNumber("-0")).isEqualTo("0")
        assertThat(SqlLiterals.canonicalNumber("0.000")).isEqualTo("0")
        assertThat(SqlLiterals.canonicalNumber(".5")).isEqualTo("0.5")
    }

    @Test
    fun `a number that cannot be read exactly has no canonical text`() {
        assertThat(SqlLiterals.canonicalNumber("1.5f")).isNull()
        assertThat(SqlLiterals.canonicalNumber("2d")).isNull()
    }
}
