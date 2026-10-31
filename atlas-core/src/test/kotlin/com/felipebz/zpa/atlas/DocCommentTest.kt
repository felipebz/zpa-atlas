//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Which comment is a declaration's documentation, and the text kept of it: `doc_comment` rows of the fixture. */
class DocCommentTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy { Fixture.index(temp.resolve("fixture.db")) }

    /** The rows of [key]: in body, shape, first line and text; empty without one. */
    private fun docs(key: String): List<String> = Fixture.query(
        db, """SELECT d.in_body || ' ' || d.shape || ' ' || d.start_line || ' ' || d.text FROM doc_comment d JOIN entity e ON e.id = d.entity_id
               WHERE e.key = '$key' ORDER BY d.in_body"""
    )

    private val pack = "db:HR.PACK_DOC"
    private val post = "$pack.POST_ENTRY(P_ACCOUNT IN NUMBER,P_AMOUNT IN NUMBER)"

    @Test
    fun `the block right above, its lines and their indentation kept, the markers left out`() {
        assertThat(docs(post)).containsExactly("0 ABOVE 2 Posts ledger entries.\n  One entry per account and day.")
        assertThat(docs("$pack.SPEC_ONLY()")).containsExactly("0 ABOVE 42 Only the spec says this.\n  Indented under a star.")
    }

    @Test
    fun `separator lines and annotation lines are dropped from the text`() {
        assertThat(docs("$pack.CLOSE_PERIOD()")).containsExactly("0 ABOVE 28 Closes the period.")
    }

    @Test
    fun `a block of annotations or separators only, with license words, read as code, or before the file's first token is never taken`() {
        for (name in listOf("ONLY_ANNOTATED", "ONLY_SEPARATORS", "LICENSED", "COMMENTED_CODE")) assertThat(docs("$pack.$name()")).describedAs(name).isEmpty()
        // The header before the file's first token is not the first procedure's; the block after its AS is.
        assertThat(docs("db:HR.PROC_DOC_REPORT(P_DAY IN DATE)")).containsExactly("0 AFTER_IS 5 Prints the day's ledger report.")
        // A block above a second unit of the file, after the first one's slash, is its documentation.
        assertThat(docs("db:HR.FN_DOC_BALANCE()")).containsExactly("0 ABOVE 11 Returns the ledger balance.")
    }

    @Test
    fun `a block one blank line above is not taken`() {
        assertThat(docs("$pack.GAPPED()")).isEmpty()
    }

    @Test
    fun `after IS and after BEGIN count only with a blank line after the block`() {
        assertThat(docs("$pack.BODY_ONLY()@BODY")).containsExactly("1 AFTER_IS 44 Only the body says this,\n  indented on its second line.")
        assertThat(docs("$pack.DIFFER_BOTH()@BODY")).containsExactly("1 AFTER_BEGIN 35 The body says that.")
        // Glued to the first statement: it comments that statement.
        assertThat(docs("$pack.GLUED_BEGIN()@BODY")).isEmpty()
    }

    @Test
    fun `a block glued after a package's IS goes to its first member, a block with a blank line after it to the package`() {
        assertThat(docs(pack)).containsExactly("1 AFTER_IS 3 Purpose:  Ledger posting.\n\nWho     Date        Description\nABC     01.02.2024  Created")
    }

    @Test
    fun `the comment ending the declaration's line, never the previous line's`() {
        assertThat(docs("$post.P_ACCOUNT")).containsExactly("0 END_OF_LINE 4 the account to post to")
        assertThat(docs("$post.P_AMOUNT")).containsExactly("0 END_OF_LINE 5 the amount, in cents")
        assertThat(docs("$pack.G_RATE")).containsExactly("0 END_OF_LINE 24 the rate, in percent")
        assertThat(docs("$pack.G_LIMIT")).containsExactly("0 END_OF_LINE 25 the limit")
        assertThat(docs("$pack.G_NEXT")).isEmpty()
        assertThat(docs("$pack.R_ENTRY.AMOUNT")).containsExactly("0 END_OF_LINE 50 the entry amount")
    }
}
