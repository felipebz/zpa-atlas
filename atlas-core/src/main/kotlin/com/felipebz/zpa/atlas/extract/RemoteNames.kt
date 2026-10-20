//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.PlSqlPunctuator
import com.felipebz.zpa.api.project.ProjectCallKind
import com.felipebz.zpa.atlas.model.*

/** Calls and values through a database link: never resolved, keyed as remote relations are. */
internal class RemoteNames(private val state: FileState, private val scopes: SqlScopes, private val roles: NameRoles) {
    /**
     * A call through a database link (`p@l(…)`, `pkg.p@l(…)`, `s.pkg.p@l(…)`): never resolved, the project cannot know
     * the remote code. Keyed as remote relations are: `db:P@L`; `db:~.A@L.B` under package `db:~.A@L` (`A` a package
     * or a schema on the remote side); `db:S.A@L.B` under `db:S.A@L`. `EXTERNAL`, reason `DB_LINK`, its arguments
     * recorded with their values and no formal.
     */
    fun remoteCall(callee: AstNode, remote: RemoteName, kind: ProjectCallKind, actuals: List<AstNode>, context: Context, statementId: Int?) {
        val (names, link) = remote
        val calledKind = if (kind == ProjectCallKind.PROCEDURE) EntityKind.PROCEDURE else EntityKind.FUNCTION
        fun entity(key: String, entityKind: EntityKind, schema: String?, name: String, parent: String?) {
            state.entities += EntityFact(key, entityKind, schema, name, parent, key.removePrefix("db:"), external = true, precedence = Precedence.MENTIONED)
            if (parent != null) state.relations += RelationFact(parent, RelationKind.CONTAINS, key)
        }
        val key = if (names.size == 1) {
            "db:${names[0]}@$link".also { entity(it, calledKind, null, "${names[0]}@$link", null) }
        } else {
            val schema = if (names.size == 3) names[0] else null
            val owner = names[names.size - 2]
            val parent = dbKey(schema ?: UNKNOWN_SCHEMA, "$owner@$link")
            entity(parent, EntityKind.PACKAGE, schema, "$owner@$link", null)
            "$parent.${names.last()}".also { entity(it, calledKind, schema, names.last(), parent) }
        }
        val arguments = actuals.mapIndexed { index, argument ->
            val children = argument.children
            val named = children.size >= 3 && children[1].tokenValue == "=>"
            val expression = if (named) children.last() else argument
            val value = ExpressionValues.of(expression, context::hasHoles)
            CallArgumentFact(index + 1, if (named) normalizeIdentifier(children[0].tokenOriginalValue) else null, null, spanOf(expression), value.kind, value.value)
        }
        state.occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, spanOf(callee), writtenText(callee), key,
            setOf(Role.CALL), status(Status.EXTERNAL, context.partial), context.origin, "DB_LINK", emptyList(), arguments)
    }

    /**
     * A name through a database link without parentheses: Oracle rejects a remote package variable or cursor
     * (PLS-00512), so it is a remote sequence (`[s.]seq.NEXTVAL@l`, a `READ` of `db:[S.]SEQ@L` at the sequence's name)
     * or a remote function called without parentheses (the `CALL` of [remoteCall]). Never looked up. Nothing for a part
     * of a remote source of the statement (`t.col@l` with `t@l` in `FROM`), a write, an attribute (`%`) or four segments
     * or more: Oracle rejects those, or they are not a program.
     */
    fun remoteValue(name: AstNode, remote: RemoteName, context: Context, statementId: Int?) {
        if (name.hasDirectChildren(PlSqlPunctuator.MOD)) return
        val (names, link) = remote
        if (statementId != null && scopes.isSqlQualified(name, names)) return
        if (Role.WRITE in roles.valueRoles(name, names)) return
        val sequence = names.size in 2..3 && names.last() in PSEUDOCOLUMNS
        when {
            sequence -> {
                val schema = if (names.size == 3) names[0] else null
                val sequenceName = names[names.size - 2]
                val key = "db:" + listOfNotNull(schema, sequenceName).joinToString(".") + "@" + link
                state.entities += EntityFact(key, EntityKind.SEQUENCE, schema, "$sequenceName@$link", null, key.removePrefix("db:"),
                    external = true, precedence = Precedence.MENTIONED)
                // The span is the sequence's name, before `.NEXTVAL`, as for a local sequence; the link is in the key.
                val children = name.children
                val remoteAt = children.indexOfFirst { it.type == PlSqlPunctuator.REMOTE }
                val lastDot = children.subList(0, remoteAt).indexOfLast { it.type == PlSqlPunctuator.DOT }
                val parts = children.subList(0, lastDot)
                state.occurrences += OccurrenceFact(context.unitIndex, context.container, statementId, spanOf(parts.first(), parts.last()),
                    parts.joinToString("") { writtenText(it) }, key, setOf(Role.READ), status(Status.EXTERNAL, context.partial), context.origin, "DB_LINK")
            }
            names.size in 1..3 -> remoteCall(name, remote, ProjectCallKind.FUNCTION, emptyList(), context, statementId)
        }
    }
}
