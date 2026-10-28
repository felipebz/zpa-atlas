//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.atlas.index.IndexOptions
import com.felipebz.zpa.atlas.index.Indexer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** A name ending in `.nextval` or `.currval` whose first segment is a value of the code is a field read, not a sequence. */
class SequenceReferenceTest {
    @TempDir
    lateinit var temp: Path

    /** The index of [files] (name to text): the occurrences that read a sequence, and the sequence entities. */
    private fun sequences(vararg files: Pair<String, String>): List<String> {
        val sources = Files.createDirectories(temp.resolve("src"))
        files.forEach { (name, text) -> Files.writeString(sources.resolve(name), text.trimIndent() + "\n") }
        val index = temp.resolve("seq.db")
        Indexer().index(IndexOptions(sources, index, compilationSchema = "HR", threads = 1))
        return Fixture.query(index, "SELECT 'entity ' || key FROM entity WHERE kind = 'SEQUENCE'") +
            Fixture.query(index, "SELECT 'occurrence ' || o.written FROM occurrence o JOIN entity e ON e.id = o.entity_id WHERE e.kind = 'SEQUENCE'")
    }

    @Test
    fun `a field of a local record is read, not a sequence`() {
        assertThat(sequences("proc_rec.prc" to """
            create or replace procedure proc_rec is
              type t_counter is record (nextval number, currval number);
              r t_counter;
              v number;
            begin
              r.nextval := 1;
              r.currval := 2;
              v := r.nextval + r.currval;
            end;
            /
            """)).isEmpty()
    }

    @Test
    fun `an attribute of an object type parameter or variable is read, not a sequence`() {
        assertThat(sequences(
            "ty_counter.tps" to """
                create or replace type ty_counter as object (nextval number, currval number);
                /
                """,
            "proc_obj.prc" to """
                create or replace procedure proc_obj(p in ty_counter) is
                  o ty_counter := ty_counter(1, 2);
                  v number;
                begin
                  v := p.nextval + p.currval + o.nextval + o.currval;
                end;
                /
                """
        )).isEmpty()
    }

    @Test
    fun `a column of a cursor FOR loop record is read, not a sequence`() {
        assertThat(sequences("proc_loop.prc" to """
            create or replace procedure proc_loop is
              v number;
            begin
              for rec in (select 1 as nextval, 2 as currval from dual) loop
                v := rec.nextval + rec.currval;
              end loop;
            end;
            /
            """)).isEmpty()
    }

    @Test
    fun `a sequence is still read`() {
        assertThat(sequences("proc_seq.prc" to """
            create or replace procedure proc_seq is
              v number;
            begin
              v := seq_order.nextval;
            end;
            /
            """)).contains("entity db:~.SEQ_ORDER", "occurrence seq_order")
    }
}
