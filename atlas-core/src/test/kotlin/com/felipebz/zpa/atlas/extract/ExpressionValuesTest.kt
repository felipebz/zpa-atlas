//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.atlas.model.ValueKind
import com.felipebz.zpa.parser.PlSqlParser
import com.felipebz.zpa.squid.PlSqlConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExpressionValuesTest {
    private val parser = PlSqlParser.create(PlSqlConfiguration(Charsets.UTF_8))

    /** The value of the single argument of `p(<argument>)`. */
    private fun of(argument: String): ExpressionValue =
        ExpressionValues.of(parser.parse("begin p($argument); end;").getFirstDescendant(PlSqlGrammar.ARGUMENT))

    @Test
    fun `signs apply to the number, however many there are`() {
        assertThat(of("- -1")).isEqualTo(ExpressionValue(ValueKind.NUMBER, "1"))
        assertThat(of("-(-2)")).isEqualTo(ExpressionValue(ValueKind.NUMBER, "2"))
        assertThat(of("-(+3)")).isEqualTo(ExpressionValue(ValueKind.NUMBER, "-3"))
        assertThat(of("-1.50")).isEqualTo(ExpressionValue(ValueKind.NUMBER, "-1.5"))
        assertThat(of("-0")).isEqualTo(ExpressionValue(ValueKind.NUMBER, "0"))
    }

    @Test
    fun `a name through a database link keeps its link`() {
        assertThat(of("seq.nextval@l")).isEqualTo(ExpressionValue(ValueKind.NAME, "SEQ.NEXTVAL@L"))
        assertThat(of("s.seq.nextval@l.example.com")).isEqualTo(ExpressionValue(ValueKind.NAME, "S.SEQ.NEXTVAL@L.EXAMPLE.COM"))
        assertThat(of("pkg.f@l")).isEqualTo(ExpressionValue(ValueKind.NAME, "PKG.F@L"))
    }

    @Test
    fun `an empty character literal passes NULL`() {
        for (empty in listOf("''", "n''", "q'[]'", "nq'{}'")) assertThat(of(empty)).describedAs(empty).isEqualTo(ExpressionValue(ValueKind.NULL, null))
        assertThat(of("' '")).isEqualTo(ExpressionValue(ValueKind.STRING, " "))
    }
}
