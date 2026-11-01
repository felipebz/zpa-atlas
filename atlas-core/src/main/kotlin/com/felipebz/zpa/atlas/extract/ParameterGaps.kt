//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlGrammar
import com.felipebz.zpa.api.semantics.UsageEvidence
import com.felipebz.zpa.api.semantics.UsageUncertainty
import com.felipebz.zpa.atlas.model.UsesGapFact

/**
 * Whether a parameter's recorded uses may be incomplete, as [BodyGlobals.gaps] decides it for a body global, its scope
 * being its subprogram: the reasons of its [UsageEvidence.Unknown], and what `regionUncertainty` says the analysis
 * cannot see in the subprogram's body, the regions inside it included. A heading without a body has no uses and no
 * gap. A cursor's parameter has only its evidence: the region of a cursor is the whole package around it.
 */
internal object ParameterGaps {
    private val SUBPROGRAMS = setOf(PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION, PlSqlGrammar.CREATE_PROCEDURE, PlSqlGrammar.CREATE_FUNCTION)

    /** The gap of the parameter [entityKey] declared by [node] (a `PARAMETER_DECLARATION` or `CURSOR_PARAMETER_DECLARATION`), or null. */
    fun of(
        entityKey: String, node: AstNode, usageEvidence: (AstNode) -> UsageEvidence, regionUncertainty: (AstNode) -> Set<UsageUncertainty>?
    ): UsesGapFact? {
        val reasons = sortedSetOf<UsageUncertainty>()
        (usageEvidence(node) as? UsageEvidence.Unknown)?.let { reasons += it.reasons }
        if (node.type == PlSqlGrammar.PARAMETER_DECLARATION) {
            var subprogram = node.parentOrNull
            while (subprogram != null && subprogram.type !in SUBPROGRAMS) subprogram = subprogram.parentOrNull
            if (subprogram != null && subprogram.hasDirectChildren(PlSqlGrammar.STATEMENTS_SECTION)) reasons += regionUncertainty(subprogram).orEmpty()
        }
        reasons -= BodyGlobals.NOT_GAPS
        return if (reasons.isEmpty()) null else UsesGapFact(entityKey, reasons.map { it.name })
    }
}
