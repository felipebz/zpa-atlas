//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.zpa.api.project.ProjectCallKind
import com.felipebz.zpa.api.project.ProjectQualifiedName
import com.felipebz.zpa.atlas.model.*

/** What a dotted value name resolves to from its segments alone, for [SegmentValues.segmentValue]. */
internal sealed interface SegmentValue {
    /** A package member or external member; [kind] null for a member whose kind is unknown (read as a value). */
    class Member(val status: Status, val entityKey: String?, val reason: String?, val candidates: List<String>, val kind: MemberKind?) : SegmentValue
    class Call(val outcome: CallOutcome.Call) : SegmentValue
}

/** Value names resolved from their segments, shared by the value references and the condition names. */
internal class SegmentValues(private val calls: CallResolver, private val values: PackageValues) {
    /**
     * The part of [PlSqlNames.valueReference] that needs only the segments of a name: a package value, exception or cursor
     * ([valueReadable]); else a project function called without parentheses ([callable], a call only when
     * [callAllowed]), a member of a package or type a synonym names, or a missing member of a project package. Null
     * when the name is none of these.
     */
    fun segmentValue(
        segments: List<String>, context: Context, packageName: ProjectQualifiedName?, valueReadable: Boolean, callable: Boolean, callAllowed: Boolean
    ): SegmentValue? {
        if (valueReadable) {
            values.packageMember(segments, context.schema, packageName)?.let {
                return SegmentValue.Member(it.status, it.entityKey, it.reason, it.candidateKeys, it.kind)
            }
        }
        if (callable) {
            when (val outcome = calls.resolve(segments, ProjectCallKind.FUNCTION, emptyList(), context.schema, context.enclosingPackage, withoutParentheses = true)) {
                is CallOutcome.Value -> return SegmentValue.Member(outcome.status, outcome.entityKey, outcome.reason, outcome.candidateKeys, outcome.kind)
                // Only a name the project knows is a call: anything else may be a value outside the project.
                is CallOutcome.Call -> if (callAllowed && outcome.reason != "NOT_IN_PROJECT" && !(outcome.reason in INCONCLUSIVE && outcome.entityKey == null)) {
                    return SegmentValue.Call(outcome)
                }
                // A member of a package or type a synonym names, kind unknown: a read of the owner.
                is CallOutcome.ExternalMember -> return SegmentValue.Member(Status.UNRESOLVED, outcome.entityKey, "NOT_IN_PROJECT", emptyList(), null)
                CallOutcome.NotACall -> Unit
            }
        }
        if (segments.size >= 2 && values.isPackageMember(segments, context.schema)) {
            return SegmentValue.Member(Status.UNRESOLVED, null, "UNKNOWN_MEMBER", emptyList(), null)
        }
        return null
    }
}

/** Call statuses that say nothing about a name when no declaration was selected. */
private val INCONCLUSIVE = setOf("INCOMPLETE_INDEX", "PARTIAL_DECLARATIONS")
