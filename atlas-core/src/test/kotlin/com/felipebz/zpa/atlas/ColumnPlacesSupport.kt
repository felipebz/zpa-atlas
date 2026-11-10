//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas

import java.nio.file.Path

private const val RUN = "db:SHOP.PK_ORD.RUN(P_CODE OUT VARCHAR2)@BODY"

/**
 * Line, column, source, target (and field), class, declared type, length and unit (and scale), type source, reason,
 * and parameter mode, of the places in [path] (all when null).
 */
internal fun places(db: Path, path: String? = null) = Fixture.query(db, """
    SELECT p.start_line || ' ' || replace(c.key, 'db:SHOP.', '') || ' ' || p.source || (CASE WHEN p.bulk = 1 THEN ' BULK' ELSE '' END) || ' ' ||
           p.target_written || coalesce('.' || p.field, '') || ' ' || p.class || ' ' || coalesce(p.declared_type, '-') || ' ' ||
           coalesce(p.length || ' ' || p.length_unit || coalesce(' scale ' || p.scale, ''), '-') || ' ' ||
           coalesce(replace(replace(s.key, '$RUN.', ''), 'db:SHOP.', ''), '-') || ' ' || coalesce(p.reason, '-') || coalesce(' ' || p.parameter_mode, '') || coalesce(' via ' || p.functions, '')
    FROM column_place p JOIN entity c ON c.id = p.column_id LEFT JOIN entity s ON s.id = p.type_source_id
    JOIN source_file f ON f.id = p.file_id ${if (path == null) "" else "WHERE f.path = '$path'"} ORDER BY p.id""")

/** Each sized place's target, its length, unit and where the unit comes from (with the unit compiled so). */
internal fun units(db: Path) = Fixture.query(db, """
    SELECT p.target_written || ' ' || p.length || ' ' || p.length_unit || ' ' || coalesce(p.unit_source, '-') ||
           coalesce(' ' || p.unit_compiled_type || ' ' || p.unit_compiled_owner || '.' || p.unit_compiled_name, '')
    FROM column_place p WHERE p.length IS NOT NULL ORDER BY p.id""")
