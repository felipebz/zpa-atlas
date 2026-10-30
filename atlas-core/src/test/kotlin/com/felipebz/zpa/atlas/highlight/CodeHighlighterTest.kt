//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.highlight

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CodeHighlighterTest {
    private val text = """
        |/** Ledger helpers. */
        |create or replace package body ledger_pkg as
        |  /* the default
        |     note */
        |  function fmt_amount(type in varchar2) return varchar2 is
        |  begin
        |    return 'it''s ' || q'[a 'quoted' word]' || type || 42; -- done
        |  end;
        |end;
        |""".trimMargin()

    /** The text of each span of [code], as `COLOR:text`, in source order. */
    private fun read(code: String, spans: List<HighlightSpan>): List<String> {
        val lines = code.lines()
        return spans.map { s ->
            val body = if (s.startLine == s.endLine) lines[s.startLine - 1].substring(s.startCol, s.endCol)
            else (listOf(lines[s.startLine - 1].substring(s.startCol)) + lines.subList(s.startLine, s.endLine - 1) +
                lines[s.endLine - 1].substring(0, s.endCol)).joinToString("\n")
            "${s.highlight}:$body"
        }
    }

    @Test
    fun `colors keywords, literals and comments the way zpa's highlighter does`() {
        val spans = read(text, CodeHighlighter.spans(text)!!)
        assertThat(spans).startsWith("DOC_COMMENT:/** Ledger helpers. */", "KEYWORD:create", "KEYWORD:or", "KEYWORD:replace", "KEYWORD:package", "KEYWORD:body")
        // A comment over several lines is one span, from its first line to its last.
        assertThat(spans).contains("COMMENT:/* the default\n     note */", "COMMENT:-- done")
        // A doubled quote stays inside its string; a q-quoted string ends at its own closing bracket and quote.
        assertThat(spans).contains("LITERAL:'it''s '", "LITERAL:q'[a 'quoted' word]'", "LITERAL:42")
        // A keyword word used as a name is still the lexer's keyword: on the page, an index link shows over it.
        assertThat(spans.filter { it == "KEYWORD:type" }).hasSize(2)
        assertThat(spans).doesNotContain("KEYWORD:fmt_amount", "KEYWORD:ledger_pkg")
    }

    @Test
    fun `reads the branch the run's conditional compilation keeps`() {
        val code = "begin\n  \$if \$\$trace \$then\n    log('kept');\n  \$else\n    log('other');\n  \$end\nend;"
        val on = read(code, CodeHighlighter.spans(code, "trace:TRUE", null)!!)
        assertThat(on).contains("LITERAL:'kept'").doesNotContain("LITERAL:'other'")
        val off = read(code, CodeHighlighter.spans(code, "trace:FALSE", null)!!)
        assertThat(off).contains("LITERAL:'other'").doesNotContain("LITERAL:'kept'")
    }
}
