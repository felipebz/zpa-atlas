//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.zpa.api.*
import com.felipebz.zpa.api.dynamicsql.DynamicSqlText
import com.felipebz.zpa.api.project.ProjectParameterMode
import com.felipebz.zpa.api.semantics.NameBinding
import com.felipebz.zpa.api.semantics.NameDeclarationKind
import com.felipebz.zpa.api.semantics.ScalarFamily
import com.felipebz.zpa.api.semantics.ValueCategory

/**
 * The SQL text of a dynamic statement written as a plain variable (`EXECUTE IMMEDIATE v_sql`), read only when it can
 * be only one text: the variable is a local character variable of the file, and exactly one value is ever stored in it
 * (its default value, a constant, or one `:=` assignment). Anything else gives no text and a reason that says why.
 *
 * A variable written several times gives the value that reaches the statement ([reach]), when one can be told from the
 * code alone: its last whole value before the statement, followed by the appends between them.
 *
 * Writes are found by name, not by binding: every occurrence of the variable's name in the region that declares it
 * (the block or subprogram, nested blocks and subprograms included) is taken as the variable, even where an inner
 * declaration hides it. That counts too many writes, never too few: a missed text is better than a wrong one.
 */
internal class VariableSqlTexts(
    private val context: PlSqlVisitorContext,
    private val state: FileState,
    /** The text nodes of the file's dynamic statements: reads, even where they are a call's actual (`DBMS_SQL.PARSE`). */
    private val statementTexts: Set<AstNode>
) {

    /**
     * What became of a variable's text: the [reason] of the `DYNAMIC` occurrence, and, when it was parsed, the [text]
     * and the [value] it was read from (the same node for every statement that runs the variable).
     */
    class Outcome(
        val reason: String,
        val text: DynamicSqlText? = null,
        /** The nodes the text was read from: the same list for every statement that runs the same value. */
        val value: List<AstNode>? = null,
        /** How a text was read from a variable written several times; null for a variable written once. */
        val read: Read? = null
    )

    /** How the text of a variable written several times was read. */
    enum class Read(val note: String, val description: String) {
        /** The last whole value before the statement, with no append after it. */
        WHOLE("VARIABLE_TEXT_WHOLE", "the last whole value before it"),
        /** A whole value and the appends that follow it, all of them run. */
        BUILD("VARIABLE_TEXT_BUILD", "a whole value and the appends after it"),
        /** As [BUILD], but some appends sit in an IF or CASE branch: they do not always run. */
        OPTIONAL("VARIABLE_TEXT_OPTIONAL", "a whole value and the appends after it, some in an IF or CASE branch that does not always run")
    }

    /** The variable declarations of the file, by the position of their name. */
    private val declarations: Map<Pair<Int, Int>, AstNode> by lazy {
        context.rootTree()?.getDescendants(PlSqlGrammar.VARIABLE_DECLARATION).orEmpty()
            .associateBy { it.firstChild.token.line to it.firstChild.token.column }
    }

    /** The outcome for [name], the variable a dynamic statement runs. */
    fun outcome(name: AstNode): Outcome {
        val declaration = when (val binding = context.nameBinding(name)) {
            is NameBinding.Local -> binding.declaration
            is NameBinding.NothingLocal -> return Outcome(NOT_LOCAL)
            else -> return Outcome(UNRESOLVED)
        }
        if (declaration.kind != NameDeclarationKind.VARIABLE || declaration.fileId != null) return Outcome(NOT_LOCAL)
        val node = declarations[declaration.line to declaration.column] ?: return Outcome(UNRESOLVED)
        val region = node.parentOrNull?.takeIf { it.type == PlSqlGrammar.DECLARE_SECTION }?.parentOrNull
        if (region == null || region.type !in LOCAL_REGIONS) return Outcome(NOT_LOCAL)
        val category = context.valueCategory(name)
        if (category !is ValueCategory.Scalar || category.family != ScalarFamily.CHARACTER && category.family != ScalarFamily.CLOB) {
            return Outcome(NOT_CHARACTER)
        }
        val uncertainty = context.regionUncertainty(region)
        if (uncertainty == null || uncertainty.isNotEmpty()) return Outcome(UNSEEN_WRITE)

        val variable = normalizeIdentifier(node.firstChild.tokenOriginalValue)
        val writes = ArrayList<Write>()
        node.getFirstChildOrNull(PlSqlGrammar.DEFAULT_VALUE_ASSIGNMENT)?.let { writes += Write.Value(it.lastChild) }
        for (written in region.getDescendants(PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.IDENTIFIER_NAME)) {
            if (written === node.firstChild || normalizeIdentifier(written.tokenOriginalValue) != variable) continue
            write(written)?.let { writes += it }
        }
        return when {
            writes.any { it is Write.Unseen } -> Outcome(UNSEEN_WRITE)
            writes.size > 1 -> reach(name, node, region, writes, variable) ?: Outcome(SEVERAL_WRITES)
            writes.isEmpty() -> Outcome(NEVER_WRITTEN)
            writes.single() is Write.NotInCode -> Outcome(VALUE_NOT_IN_CODE)
            else -> parse((writes.single() as Write.Value).value, variable)
        }
    }

    private fun parse(value: AstNode, variable: String): Outcome {
        // `v := v || ' and x = 1'` builds on an earlier value: not one text.
        val reads = listOf(value) + value.getDescendants(PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.IDENTIFIER_NAME)
        if (reads.any { (it.type == PlSqlGrammar.VARIABLE_NAME || it.type == PlSqlGrammar.IDENTIFIER_NAME) && normalizeIdentifier(it.tokenOriginalValue) == variable }) {
            return Outcome(SEVERAL_WRITES)
        }
        return parsed(parts(value), listOf(value), null)
    }

    /** What zpa makes of the text joined from [parts], read from the nodes [value]. */
    private fun parsed(parts: List<AstNode>, value: List<AstNode>, read: Read?): Outcome {
        val text = context.dynamicSql(parts)
        return when {
            text == null -> Outcome(if (parts.any { hasStringLiteral(it) }) NOT_PARSED else NO_LITERAL, read = read)
            text.holesDropped -> Outcome(PARSED_HOLES_DROPPED, text, value, read)
            text.holes.isNotEmpty() -> Outcome(PARSED_WITH_HOLES, text, value, read)
            else -> Outcome(PARSED, text, value, read)
        }
    }

    /**
     * The value of the variable that reaches the statement running [name], or null when the code alone cannot tell it.
     *
     * From the statement outward, list by list, the first list holding the variable's last whole value before the
     * statement (a `:=` whose right side does not read the variable, or the default value at the top of [region]) gives
     * the text: that value, then every append (`v := v || …` or `v := … || v`) between it and the statement, in source
     * order. The walk climbs out of a block's body (not out of its exception handler, which any point of the block can
     * reach), out of an `IF` or `CASE` branch, and out of a loop that does not write the variable. A block, `IF` or
     * `CASE` that writes the variable outside the list the walk leaves (a condition, a selector, a declaration) stops it.
     *
     * Every write between the whole value and the statement must be an append, written directly in a list of the
     * walk, or inside `IF` and `CASE` branches standing in such a list (no loop or block in between) with at most one
     * branch of each `IF` or `CASE` writing the variable: two branches would join alternatives that never run together.
     * Any other write there (a whole value in a branch, a write in a nested block or a loop, an `INTO`, an `OUT` actual)
     * gives no text, and so do, anywhere in [region], an inner declaration of the name, a write in a nested
     * subprogram, and a `GOTO`, which can skip the whole value.
     */
    private fun reach(name: AstNode, declaration: AstNode, region: AstNode, writes: List<Write>, variable: String): Outcome? {
        if (excluded(declaration, region, writes, variable)) return null
        return Reach(region, writes, variable).from(statementOf(name) ?: return null)
    }

    /** Whether something in [region] can change the variable's value unseen by [reach]: a `GOTO`, a hiding declaration, a write in a nested subprogram. */
    private fun excluded(declaration: AstNode, region: AstNode, writes: List<Write>, variable: String): Boolean =
        region.hasDescendant(PlSqlGrammar.GOTO_STATEMENT) ||
            region.getDescendants(PlSqlGrammar.VARIABLE_DECLARATION, PlSqlGrammar.PARAMETER_DECLARATION, PlSqlGrammar.ITERAND_DECLARATION)
                .any { it !== declaration && normalizeIdentifier(it.firstChild.tokenOriginalValue) == variable } ||
            writes.any { write -> generateSequence(write.node.parentOrNull) { it.parentOrNull }.takeWhile { it !== region }.any { it.type in NESTED_SUBPROGRAMS } }

    /** One walk of [reach], from a statement outward; it gathers the appends met on the way. */
    private inner class Reach(private val region: AstNode, writes: List<Write>, private val variable: String) {
        private val default = writes.firstOrNull { it is Write.Value && it.value.parent.type == PlSqlGrammar.DEFAULT_VALUE_ASSIGNMENT } as Write.Value?
        private val inCode = writes.filter { it !== default }
        private val appends = ArrayDeque<Write.Value>()
        private var optional = false

        /** The outcome for the statement [start], or null when no text can be told. */
        fun from(start: AstNode): Outcome? {
            var current = start
            while (true) {
                when (val found = scan(current)) {
                    is Scan.Found -> return text(found.value)
                    Scan.Fail -> return null
                    Scan.Continue -> current = climb(current.parent) ?: return if (atTop(current.parent)) top(current.parent) else null
                }
            }
        }

        private fun writesIn(node: AstNode) = inCode.filter { write -> generateSequence(write.node) { it.parentOrNull }.any { it === node } }

        /** The statements before [current] in its list, last first, until the last whole value. */
        private fun scan(current: AstNode): Scan {
            val list = current.parent
            for (index in list.children.indexOf(current) - 1 downTo 0) {
                val statement = list.children[index]
                val inside = writesIn(statement)
                if (inside.isEmpty()) continue
                if (unwrap(statement).type == PlSqlGrammar.ASSIGNMENT_STATEMENT && inside.size == 1) {
                    val write = inside.single() as? Write.Value ?: return Scan.Fail
                    when (shape(write.value, variable)) {
                        Shape.WHOLE -> return Scan.Found(write)
                        Shape.APPEND -> appends.addFirst(write)
                        Shape.OTHER -> return Scan.Fail
                    }
                } else {
                    val fragments = optionalAppends(statement, inside, variable) ?: return Scan.Fail
                    optional = true
                    fragments.asReversed().forEach { appends.addFirst(it) }
                }
            }
            return Scan.Continue
        }

        private fun atTop(list: AstNode) = list.parent.type == PlSqlGrammar.STATEMENTS_SECTION && list.parent.parent === region

        /** The statement whose list the walk goes on in after [list], or null when it may not climb. */
        private fun climb(list: AstNode): AstNode? {
            val owner = list.parent
            val container = when (owner.type) {
                PlSqlGrammar.STATEMENTS_SECTION -> owner.parent.takeIf { it !== region && it.type == PlSqlGrammar.BLOCK_STATEMENT }
                PlSqlGrammar.IF_STATEMENT, PlSqlGrammar.CASE_STATEMENT -> owner
                PlSqlGrammar.ELSIF_CLAUSE, PlSqlGrammar.ELSE_CLAUSE -> owner.parent
                in LOOPS -> owner.takeIf { writesIn(it).isEmpty() }
                else -> null
            } ?: return null
            return if (writesOnlyIn(container, list)) statementOf(container) else null
        }

        /**
         * Whether no write in [container] (a block, `IF`, `CASE` or loop the walk leaves through [list]) may run before
         * the statement: a write in [list] was seen by the walk, and one in another branch or in an exception handler
         * does not run before it. Any other write (a condition, a selector, a `WHEN` expression, a declaration) does,
         * or may.
         */
        private fun writesOnlyIn(container: AstNode, list: AstNode) = writesIn(container).all { write ->
            generateSequence(write.node) { it.parentOrNull }.takeWhile { it !== container }
                .any { it === list || it.type == PlSqlGrammar.STATEMENTS || it.type == PlSqlGrammar.EXCEPTION_HANDLER }
        }

        /** At the top of [region], whose body is [list]: its default value, unless a declaration writes the variable. */
        private fun top(list: AstNode): Outcome? = default?.takeIf { writesOnlyIn(region, list) }?.let { text(it) }

        /** The text of the whole value [start] and the appends after it; null when [start] is not a whole value. */
        private fun text(start: Write.Value): Outcome? {
            if (shape(start.value, variable) != Shape.WHOLE) return null
            val read = when {
                appends.isEmpty() -> Read.WHOLE
                optional -> Read.OPTIONAL
                else -> Read.BUILD
            }
            // `v := v || …` adds after the text so far, `v := … || v` before it.
            val parts = ArrayDeque(parts(start.value))
            for (append in appends) {
                val added = appendParts(append.value, variable)
                if (plain(parts(append.value).last(), variable)) parts.addAll(0, added) else parts.addAll(added)
            }
            return parsed(parts, listOf(start.value) + appends.map { it.value }, read)
        }
    }

    private sealed interface Scan {
        class Found(val value: Write.Value) : Scan
        object Continue : Scan
        object Fail : Scan
    }

    /**
     * The appends inside the `IF` or `CASE` [statement] (its writes are [inside]), in source order, when each is an
     * append reached through `IF` and `CASE` branches only and each `IF` or `CASE` has at most one branch that writes;
     * else null.
     */
    private fun optionalAppends(statement: AstNode, inside: List<Write>, variable: String): List<Write.Value>? {
        val top = unwrap(statement)
        if (top.type != PlSqlGrammar.IF_STATEMENT && top.type != PlSqlGrammar.CASE_STATEMENT) return null
        val branches = HashMap<AstNode, MutableSet<AstNode>>()
        val values = ArrayList<Write.Value>()
        for (write in inside) {
            if (write !is Write.Value || shape(write.value, variable) != Shape.APPEND) return null
            val path = branchPath(write.value.parent, top) ?: return null
            path.forEach { (owner, list) -> branches.getOrPut(owner) { identitySet() } += list }
            values += write
        }
        if (branches.values.any { it.size > 1 }) return null
        return values.sortedWith(compareBy({ it.value.token.line }, { it.value.token.column }))
    }

    /** The `IF` or `CASE` nodes from [assignment] up to [top], each with the branch list taken; null when something else is in between. */
    private fun branchPath(assignment: AstNode, top: AstNode): List<Pair<AstNode, AstNode>>? {
        val path = ArrayList<Pair<AstNode, AstNode>>()
        var node = assignment
        while (node !== top) {
            val parent = node.parent
            if (node.type == PlSqlGrammar.STATEMENTS) {
                val owner = if (parent.type == PlSqlGrammar.ELSIF_CLAUSE || parent.type == PlSqlGrammar.ELSE_CLAUSE) parent.parent else parent
                if (owner.type != PlSqlGrammar.IF_STATEMENT && owner.type != PlSqlGrammar.CASE_STATEMENT) return null
                path += owner to node
            } else if (node.type !in BRANCH_PATH) {
                return null
            }
            node = parent
        }
        return path
    }

    private enum class Shape { WHOLE, APPEND, OTHER }

    /** Whether [value] does not read [variable] (a whole value), reads it once as its first or last operand (an append), or else. */
    private fun shape(value: AstNode, variable: String): Shape {
        val reads = (listOf(value) + value.getDescendants(PlSqlGrammar.VARIABLE_NAME, PlSqlGrammar.IDENTIFIER_NAME))
            .filter { isVariable(it, variable) }.map { it.token }.distinct().size
        if (reads == 0) return Shape.WHOLE
        val parts = parts(value)
        return if (reads == 1 && parts.size >= 2 && (plain(parts.first(), variable) || plain(parts.last(), variable))) Shape.APPEND else Shape.OTHER
    }

    /** The operands of the append [value] without the read of [variable]. */
    private fun appendParts(value: AstNode, variable: String) = parts(value).filterNot { plain(it, variable) }

    /** Whether [part] is the plain name [variable], through wrappers of one child. */
    private fun plain(part: AstNode, variable: String): Boolean {
        var node = part
        while (true) {
            if (isVariable(node, variable)) return true
            if (node.numberOfChildren != 1) return false
            node = node.firstChild
        }
    }

    private fun isVariable(node: AstNode, variable: String) =
        (node.type == PlSqlGrammar.VARIABLE_NAME || node.type == PlSqlGrammar.IDENTIFIER_NAME) && normalizeIdentifier(node.tokenOriginalValue) == variable

    /** The statement of a statement list that holds [node], or null outside one. */
    private fun statementOf(node: AstNode): AstNode? {
        var current = node
        while (true) {
            val parent = current.parentOrNull ?: return null
            if (parent.type == PlSqlGrammar.STATEMENTS) return current
            current = parent
        }
    }

    /** [statement] without the `STATEMENT` node that may wrap it. */
    private fun unwrap(statement: AstNode): AstNode =
        if (statement.type == PlSqlGrammar.STATEMENT && statement.numberOfChildren == 1) statement.firstChild else statement

    private fun identitySet(): MutableSet<AstNode> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    /** The operands of [value] as zpa takes those of a dynamic statement's text: a concatenation flattened, parentheses unwrapped. */
    private fun parts(value: AstNode): List<AstNode> = when {
        value.type == PlSqlGrammar.CONCATENATION_EXPRESSION ->
            value.children.filter { it.type != PlSqlGrammar.CONCATENATION_OPERATOR }.flatMap { parts(it) }
        value.type == PlSqlGrammar.BRACKED_EXPRESSION && value.numberOfChildren == 3 -> parts(value.children[1])
        else -> listOf(value)
    }

    private sealed interface Write {
        /** Where the write is in the tree. */
        val node: AstNode

        /** A value written in the code: the default value, or the right side of `:=`. */
        class Value(val value: AstNode) : Write {
            override val node get() = value
        }

        /** A value that comes from a query or a call: an `INTO` target, an `OUT` or `IN OUT` actual. */
        class NotInCode(override val node: AstNode) : Write

        /** Maybe a write: an actual of a call whose formal's mode is unknown, or a part of the variable written. */
        class Unseen(override val node: AstNode) : Write
    }

    /** What the occurrence [name] of the variable's name does to it: null for a read. */
    private fun write(name: AstNode): Write? {
        var expression = name
        while (true) {
            val parent = expression.parentOrNull ?: return null
            val climbs = parent.firstChild === expression && parent.type in COMPOSITES ||
                parent.type == PlSqlGrammar.MEMBER_EXPRESSION && parent.lastChild === expression
            if (!climbs) break
            expression = parent
        }
        val parent = expression.parentOrNull ?: return null
        val plain = expression === name || expression.type == PlSqlGrammar.MEMBER_EXPRESSION && expression.lastChild === name ||
            expression.type == PlSqlGrammar.OBJECT_REFERENCE && expression.numberOfChildren == 1
        return when (parent.type) {
            PlSqlGrammar.ASSIGNMENT_STATEMENT -> {
                val assignment = parent.children.indexOfFirst { it.type == PlSqlPunctuator.ASSIGNMENT }
                when {
                    parent.children.indexOf(expression) > assignment -> null
                    plain -> Write.Value(parent.children[assignment + 1])
                    else -> Write.Unseen(name)
                }
            }
            DmlGrammar.INTO_CLAUSE -> if (plain) Write.NotInCode(name) else Write.Unseen(name)
            PlSqlGrammar.UNNAMED_ACTUAL_PAMETER -> when {
                parent.lastChild !== expression -> null
                parent.hasDirectChildren(PlSqlKeyword.OUT) -> Write.NotInCode(name)
                else -> null
            }
            PlSqlGrammar.ARGUMENT -> when {
                parent.lastChild !== expression || expression in statementTexts -> null
                else -> when (state.argumentModes[expression] ?: state.argumentModes[parent]) {
                    ProjectParameterMode.IN -> null
                    ProjectParameterMode.OUT, ProjectParameterMode.IN_OUT -> Write.NotInCode(name)
                    null -> Write.Unseen(name)
                }
            }
            else -> null
        }
    }

    companion object {
        /** The plain variable [sql] is, or null when it is another expression. */
        fun variable(sql: AstNode): AstNode? {
            var node = sql
            while (node.numberOfChildren == 1 && node.type != PlSqlGrammar.VARIABLE_NAME) node = node.firstChild
            return node.takeIf { it.type == PlSqlGrammar.VARIABLE_NAME }
        }

        /** The regions whose declarations are local: a block or a subprogram, standalone, nested or a method. */
        private val LOCAL_REGIONS = setOf(
            PlSqlGrammar.BLOCK_STATEMENT, PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION,
            PlSqlGrammar.CREATE_PROCEDURE, PlSqlGrammar.CREATE_FUNCTION, PlSqlGrammar.TIMING_POINT_SECTION
        )

        /** The nodes a name is the first part of, as the target of a write: `v(i)`, `v.f`, an `INTO` item. */
        private val NESTED_SUBPROGRAMS = setOf(PlSqlGrammar.PROCEDURE_DECLARATION, PlSqlGrammar.FUNCTION_DECLARATION)

        private val LOOPS = setOf(PlSqlGrammar.LOOP_STATEMENT, PlSqlGrammar.FOR_STATEMENT, PlSqlGrammar.WHILE_STATEMENT)

        /** The nodes between an append and the `IF` or `CASE` that holds it, besides its statement lists. */
        private val BRANCH_PATH = setOf(
            PlSqlGrammar.ASSIGNMENT_STATEMENT, PlSqlGrammar.STATEMENT, PlSqlGrammar.IF_STATEMENT, PlSqlGrammar.CASE_STATEMENT,
            PlSqlGrammar.ELSIF_CLAUSE, PlSqlGrammar.ELSE_CLAUSE
        )

        private val COMPOSITES = setOf(PlSqlGrammar.MEMBER_EXPRESSION, PlSqlGrammar.METHOD_CALL, PlSqlGrammar.OBJECT_REFERENCE)

        const val PARSED = "VARIABLE_PARSED"
        const val PARSED_WITH_HOLES = "VARIABLE_PARSED_WITH_HOLES"
        const val PARSED_HOLES_DROPPED = "VARIABLE_PARSED_HOLES_DROPPED"
        const val NOT_PARSED = "VARIABLE_NOT_PARSED"
        const val NO_LITERAL = "VARIABLE_NO_LITERAL"
        const val SEVERAL_WRITES = "VARIABLE_SEVERAL_WRITES"
        const val VALUE_NOT_IN_CODE = "VARIABLE_VALUE_NOT_IN_CODE"
        const val NEVER_WRITTEN = "VARIABLE_NEVER_WRITTEN"
        const val UNSEEN_WRITE = "VARIABLE_UNSEEN_WRITE"
        const val NOT_CHARACTER = "VARIABLE_NOT_CHARACTER"
        const val NOT_LOCAL = "VARIABLE_NOT_LOCAL"
        const val UNRESOLVED = "VARIABLE_UNRESOLVED"
    }
}

/**
 * Whether a part of [sql] is a string literal, its operands taken as zpa takes them (a concatenation flattened,
 * parentheses unwrapped): a text zpa could not parse is then `NOT_PARSED`, not an `EXPRESSION`.
 */
internal fun hasStringLiteral(sql: AstNode): Boolean = when {
    sql.type == PlSqlGrammar.CONCATENATION_EXPRESSION ->
        sql.children.any { it.type != PlSqlGrammar.CONCATENATION_OPERATOR && hasStringLiteral(it) }
    sql.type == PlSqlGrammar.BRACKED_EXPRESSION && sql.numberOfChildren == 3 -> hasStringLiteral(sql.children[1])
    sql.type == PlSqlGrammar.CHARACTER_LITERAL -> true
    else -> sql.type == PlSqlGrammar.LITERAL && sql.numberOfChildren == 1 && sql.firstChild.type == PlSqlGrammar.CHARACTER_LITERAL
}
