//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** `%TYPE` and `%ROWTYPE` anchored on an object behind a database link (`hr/pack_remote.pks`). */
class RemoteAnchorTest {
    @TempDir
    lateinit var temp: Path

    private val db by lazy { Fixture.index(temp.resolve("fixture.db")) }

    private fun line(line: Int) = Fixture.query(db,
        """SELECT o.written || ' ' || e.key || ' ' || o.roles || ' ' || o.status || ' ' || coalesce(o.reason, '-')
           FROM occurrence o JOIN source_file f ON f.id = o.file_id JOIN entity e ON e.id = o.entity_id
           WHERE f.path = 'hr/pack_remote.pks' AND o.start_line = $line AND o.roles <> 'DECLARATION' ORDER BY o.id""")

    @Test
    fun `a remote ROWTYPE names the relation behind the link`() {
        // Two segments and a link with a domain; one segment and a link without one; a collection element.
        assertThat(line(2)).containsExactly("desenv.remote_req@far.example.com db:DESENV.REMOTE_REQ@FAR.EXAMPLE.COM TYPE_USE EXTERNAL DB_LINK")
        assertThat(line(3)).containsExactly("remote_req@far db:REMOTE_REQ@FAR TYPE_USE EXTERNAL DB_LINK")
        assertThat(line(6)).containsExactly("desenv.remote_req@far.example.com db:DESENV.REMOTE_REQ@FAR.EXAMPLE.COM TYPE_USE EXTERNAL DB_LINK")
    }

    @Test
    fun `a remote TYPE names the column of the relation behind the link, as static SQL does`() {
        assertThat(line(4)).containsExactly("desenv.remote_req.cd_req@far.example.com db:DESENV.REMOTE_REQ@FAR.EXAMPLE.COM.CD_REQ TYPE_USE EXTERNAL DB_LINK")
        assertThat(line(5)).containsExactly("remote_req.cd_req@far db:REMOTE_REQ@FAR.CD_REQ TYPE_USE EXTERNAL DB_LINK")
        assertThat(line(7)).contains("cd_req db:DESENV.REMOTE_REQ@FAR.EXAMPLE.COM.CD_REQ READ EXTERNAL DB_LINK")
        // A parameter and a RETURN type.
        assertThat(line(8)).containsExactly(
            "remote_req.cd_req@far.example.com db:REMOTE_REQ@FAR.EXAMPLE.COM.CD_REQ TYPE_USE EXTERNAL DB_LINK",
            "remote_req.ds_subject@far.example.com db:REMOTE_REQ@FAR.EXAMPLE.COM.DS_SUBJECT TYPE_USE EXTERNAL DB_LINK"
        )
        assertThat(Fixture.query(db, "SELECT c.message FROM coverage_note c JOIN source_file f ON f.id = c.file_id WHERE f.path = 'hr/pack_remote.pks'")).isEmpty()
    }
}
