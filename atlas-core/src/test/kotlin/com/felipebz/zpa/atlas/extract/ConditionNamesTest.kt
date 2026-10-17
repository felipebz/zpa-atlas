//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConditionNamesTest {
    private fun opening(text: String) = ConditionNames.read(text, wholeSelection = false)?.map { it.segments.joinToString(".") }
    private fun whole(text: String) = ConditionNames.read(text, wholeSelection = true)?.map { it.segments.joinToString(".") }

    @Test
    fun `an opening text gives the conditions up to the kept branch`() {
        assertThat(opening("\$IF a.x \$THEN")).containsExactly("a.x")
        // Branch 2 kept: conditions 1 and 2, nothing from the skipped branch 1.
        assertThat(opening("\$IF a.x \$THEN p.c := 1; \$ELSIF b.y \$THEN")).containsExactly("a.x", "b.y")
        // The $ELSE branch kept: every condition before it.
        assertThat(opening("\$IF a.x \$THEN null; \$ELSIF b.y \$THEN null; \$ELSE")).containsExactly("a.x", "b.y")
    }

    @Test
    fun `a whole selection that keeps nothing gives every condition`() {
        assertThat(whole("\$IF a.x \$THEN null; \$ELSIF b.y \$THEN null; \$END")).containsExactly("a.x", "b.y")
    }

    @Test
    fun `a closing text and an error directive give nothing`() {
        assertThat(opening("\$ELSIF b.y \$THEN null; \$END")).isEmpty()
        assertThat(opening("\$ELSE null; \$END")).isEmpty()
        assertThat(opening("\$END")).isEmpty()
        assertThat(whole("\$ERROR 'no ' || c.x \$END")).isEmpty()
    }

    @Test
    fun `a selection nested in a skipped branch is not read`() {
        assertThat(opening("\$IF a.x \$THEN \$IF n.z \$THEN null; \$ELSIF n.w \$THEN null; \$END \$ELSIF b.y \$THEN")).containsExactly("a.x", "b.y")
        assertThat(whole("\$IF a.x \$THEN \$ERROR 'e' || e.v \$END \$END")).containsExactly("a.x")
    }

    @Test
    fun `comments, literals, quoted identifiers and inquiry directives are skipped`() {
        assertThat(opening("\$IF /* c.x \$THEN */ a.x -- d.y\n AND \$\$flag \$THEN")).containsExactly("a.x")
        assertThat(opening("\$IF a.x \$THEN s := '\$END x.y'; t := q'[\$END]'; u := n'\$ELSIF'; \"\$END\" := 1; v\$end := 2; \$ELSIF b.y \$THEN"))
            .containsExactly("a.x", "b.y")
    }

    @Test
    fun `names of two and three segments, keywords and single words left out`() {
        assertThat(opening("\$IF sys.dbms_db_version.ver_le_11_2 AND NOT dbms_db_version.ver_le_10 OR TRUE AND x IS NULL AND a.b.c.d \$THEN"))
            .containsExactly("sys.dbms_db_version.ver_le_11_2", "dbms_db_version.ver_le_10")
        assertThat(opening("\$IF dbms_db_version . version >= 12 \$THEN")).containsExactly("dbms_db_version.version")
    }

    @Test
    fun `offsets point at the name in the text`() {
        val name = ConditionNames.read("\$IF  pkg.c \$THEN", wholeSelection = false)!!.single()
        assertThat(name.start to name.end).isEqualTo(5 to 10)
    }

    @Test
    fun `a text without the expected shape is unreadable`() {
        assertThat(opening("\$IF a.x")).isNull()
        assertThat(opening("\$IF a.x \$THEN null; \$END")).isNull()
        assertThat(whole("\$IF a.x \$THEN null;")).isNull()
        assertThat(opening("\$IF a.x \$THEN s := 'open")).isNull()
        assertThat(opening("null;")).isNull()
    }
}
