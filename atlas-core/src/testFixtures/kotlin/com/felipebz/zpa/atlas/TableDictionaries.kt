//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import com.felipebz.zpa.api.project.ProjectExternalColumn
import com.felipebz.zpa.api.project.ProjectExternalDatatype
import com.felipebz.zpa.api.project.ProjectExternalDatatype.LengthSemantics
import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionaryRelation
import com.felipebz.zpa.atlas.dictionary.DictionaryRelationKind

/** A table and its columns, in order, each with its dictionary datatype (null when the dictionary does not give it). */
typealias TableColumns = Pair<String, List<Pair<String, ProjectExternalDatatype?>>>

/** `VARCHAR2(length unit)` of a dictionary column; without [unit], a length written without one. */
fun varchar2(length: Int, unit: LengthSemantics? = LengthSemantics.BYTE): ProjectExternalDatatype =
    ProjectExternalDatatype.of("VARCHAR2", null, length, unit, null, null)

/** `NUMBER(precision, scale)` of a dictionary column; `NUMBER` without [precision]. */
fun number(precision: Int? = null, scale: Int? = null): ProjectExternalDatatype =
    ProjectExternalDatatype.of("NUMBER", null, null, null, precision, scale)

/** `DATE` of a dictionary column. */
fun date(): ProjectExternalDatatype = ProjectExternalDatatype.of("DATE", null, null, null, null, null)

/**
 * A hand-built dictionary of the tables of [schema] the sources of a test create, complete for [schema]: zpa decides
 * which column a name of static SQL reads only from the columns a dictionary lists.
 */
fun tableDictionary(schema: String, vararg tables: TableColumns): AtlasDictionary =
    AtlasDictionary.of("tables", listOf(schema), tables.map { (name, columns) ->
        DictionaryRelation(ProjectExternalRelation.of(schema, name, true, columns.mapIndexed { index, (column, type) ->
            ProjectExternalColumn.of(column, index + 1, type)
        }), DictionaryRelationKind.TABLE)
    })

/** [tableDictionary] of `SHOP`. */
fun shopTables(vararg tables: TableColumns): AtlasDictionary = tableDictionary("SHOP", *tables)

/**
 * The columns of the tables of [schema] for lookups only, as an acquired dictionary gives them: a table becomes an
 * entity only when the code names it, and the entity of a table the sources create stays theirs.
 */
fun lookupTables(schema: String, vararg tables: TableColumns): AtlasDictionary =
    AtlasDictionary.lookupOnly(tableDictionary(schema, *tables).metadata, setOf(schema))
