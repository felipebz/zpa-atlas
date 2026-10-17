//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli.oracle

import com.felipebz.zpa.atlas.index.DatabaseVersionOrigin
import com.felipebz.zpa.squid.DatabaseVersion
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types

/**
 * The version of a database, from [DatabaseVersionOrigin.DATABASE] or [DatabaseVersionOrigin.JDBC]; [failure] is why
 * `DBMS_DB_VERSION` could not be read, non-null exactly when the origin is [DatabaseVersionOrigin.JDBC].
 */
class DetectedDatabaseVersion(val version: DatabaseVersion, val origin: DatabaseVersionOrigin, val failure: SQLException?)

/** Reads the version a database compiles conditional compilation against. Prints nothing: callers warn. */
object DatabaseVersionReader {
    private const val DBMS_DB_VERSION_SQL = "begin ? := dbms_db_version.version; ? := dbms_db_version.release; end;"

    /**
     * `DBMS_DB_VERSION.VERSION` and `RELEASE`, which any user can read. When the call fails, the JDBC driver's major
     * and minor database version: the minor can differ from `RELEASE` (on 23ai and later it is the release update,
     * e.g. 23.26 where `DBMS_DB_VERSION` gives 23.0).
     *
     * @throws SQLException if the JDBC driver cannot give the version either
     */
    fun read(connection: Connection): DetectedDatabaseVersion {
        val failure = try {
            connection.prepareCall(DBMS_DB_VERSION_SQL).use { call ->
                call.registerOutParameter(1, Types.INTEGER)
                call.registerOutParameter(2, Types.INTEGER)
                call.execute()
                return DetectedDatabaseVersion(DatabaseVersion(call.getInt(1), call.getInt(2)), DatabaseVersionOrigin.DATABASE, null)
            }
        } catch (e: SQLException) {
            e
        }
        val metaData = connection.metaData
        return DetectedDatabaseVersion(DatabaseVersion(metaData.databaseMajorVersion, metaData.databaseMinorVersion), DatabaseVersionOrigin.JDBC, failure)
    }
}
