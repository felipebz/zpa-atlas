package com.felipebz.zpa.atlas.extract

import com.felipebz.flr.api.AstNode
import com.felipebz.flr.api.GenericTokenType
import com.felipebz.flr.api.Token
import com.felipebz.zpa.api.PlSqlTokenType
import com.felipebz.zpa.api.project.ProjectIdentifier
import com.felipebz.zpa.atlas.model.SourceSpan
import java.security.MessageDigest
import java.util.Locale

/** Placeholder schema used in keys when a declaration has neither a written owner nor a compilation schema. */
const val UNKNOWN_SCHEMA = "~"

/** Oracle identifier identity, as `OracleIdentifier`: unquoted is upper-cased, quoted keeps its contents. */
fun normalizeIdentifier(spelling: String): String =
    if (spelling.length >= 2 && spelling.startsWith('"') && spelling.endsWith('"')) spelling.substring(1, spelling.length - 1)
    else spelling.uppercase(Locale.ROOT)

/** The exact name of an identifier: dictionary names (always quoted internally) come back verbatim. */
fun ProjectIdentifier.exact(): String = normalizeIdentifier(originalSpelling)

fun dbKey(schema: String, vararg segments: String): String = "db:" + (listOf(schema) + segments).joinToString(".")

fun lastToken(node: AstNode): Token? {
    var current = node
    while (current.hasChildren()) current = current.lastChild
    return current.tokenOrNull
}

fun spanOf(node: AstNode): SourceSpan {
    val first = node.token
    val last = lastToken(node) ?: first
    return SourceSpan(first.line, first.column, last.endLine, last.endColumn)
}

fun spanOf(first: AstNode, last: AstNode): SourceSpan {
    val start = first.token
    val end = lastToken(last) ?: start
    return SourceSpan(start.line, start.column, end.endLine, end.endColumn)
}

/** Tokens of [node] in source order, without EOF. */
fun tokensOf(node: AstNode): List<Token> = node.tokens.filter { it.type != GenericTokenType.EOF }

/** The written text of a short reference (a name), tokens concatenated without spaces. */
fun writtenText(node: AstNode): String = tokensOf(node).joinToString("") { it.originalValue }

/** Token values joined by single spaces, upper-cased outside string literals and quoted identifiers. */
fun normalizedSql(node: AstNode): String = tokensOf(node).joinToString(" ") { token ->
    val value = token.originalValue
    if (token.type == PlSqlTokenType.STRING_LITERAL || value.startsWith('"')) value else value.uppercase(Locale.ROOT)
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
