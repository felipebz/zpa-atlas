//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.dictionary

import com.felipebz.zpa.api.project.ProjectExternalDatatype
import com.felipebz.zpa.atlas.extract.exact

/** The datatype of a dictionary column, written as a `CREATE TABLE` declares it. */
object ColumnDatatypes {
    private val CHARACTER = setOf("CHAR", "VARCHAR2")
    private val NATIONAL = setOf("NCHAR", "NVARCHAR2")

    /**
     * [datatype] as a `CREATE TABLE` writes it: `VARCHAR2(30 CHAR)` or `CHAR(1 BYTE)` (always with the length
     * semantics: the default depends on the session), `NVARCHAR2(30)` (always characters, Oracle accepts no unit there),
     * `RAW(16)`, `NUMBER`, `NUMBER(10)`, `NUMBER(10,2)`, `NUMBER(*,0)` (an `INTEGER`), `FLOAT(126)`, an object type as
     * `OWNER.NAME`, or its name alone when the dictionary gives it the owner `PUBLIC` (11g writes `XMLTYPE` so, and
     * `CREATE TABLE` takes no `PUBLIC.` there). Any other type is its `DATA_TYPE` as the dictionary writes it: `DATE`,
     * `TIMESTAMP(6)`, `INTERVAL DAY(2) TO SECOND(6)`, `CLOB`.
     */
    fun text(datatype: ProjectExternalDatatype): String {
        val name = datatype.name
        if (datatype.typeOwner.isPresent) {
            val owner = datatype.typeOwner.get().exact()
            return if (owner == AtlasDictionary.PUBLIC) name else "$owner.$name"
        }
        val length = datatype.length.takeIf { it.isPresent }?.asInt
        val precision = datatype.precision.takeIf { it.isPresent }?.asInt
        val scale = datatype.scale.takeIf { it.isPresent }?.asInt
        return when {
            name in CHARACTER && length != null -> "$name($length${datatype.lengthSemantics.map { " $it" }.orElse("")})"
            (name in NATIONAL || name == "RAW") && length != null -> "$name($length)"
            name == "NUMBER" -> when {
                precision == null && scale == null -> name
                precision == null -> "$name(*,$scale)"
                scale == null || scale == 0 -> "$name($precision)"
                else -> "$name($precision,$scale)"
            }
            name == "FLOAT" && precision != null -> "$name($precision)"
            else -> name
        }
    }
}
