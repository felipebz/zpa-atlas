//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.api.project.ProjectIdentifier
import com.felipebz.zpa.api.project.ProjectSnapshot

/**
 * The project as seen from a unit: [of] gives [snapshot] preferring the unit's schema ([ProjectSnapshot.preferring]),
 * where zpa reads an unqualified name first and through which `S.x` reaches an unowned `x` compiling in `S`. Every
 * lookup by name of the extraction goes through it. One preferred snapshot per schema, built on first use and reused
 * by every unit of that schema.
 */
class UnitSnapshots(private val snapshot: ProjectSnapshot) {
    private val bySchema = HashMap<String, ProjectSnapshot>()

    /**
     * The snapshot for a unit compiling in [unitSchema], an exact dictionary name (the unit's written owner, else its
     * file's schema, as `ProjectExternalContext.compilationSchema` gives it); the plain snapshot for null.
     */
    fun of(unitSchema: String?): ProjectSnapshot =
        if (unitSchema == null) snapshot
        else synchronized(bySchema) { bySchema.getOrPut(unitSchema) { snapshot.preferring(ProjectIdentifier.fromSource("\"$unitSchema\"")) } }
}
