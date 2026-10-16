package com.felipebz.zpa.atlas.index

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decodes source files. `auto` (the default) works per file: bytes that decode as strict UTF-8 are UTF-8, anything else
 * is windows-1252 (a superset of the printable ISO-8859-1 range, plus curly quotes and the euro sign). Accented text in
 * a legacy charset is practically never valid UTF-8, and pure ASCII decodes the same either way; which legacy charset
 * a file uses cannot be detected reliably, so a forced charset covers that case. A leading BOM is dropped.
 */
object SourceDecoding {
    const val AUTO = "auto"

    val LEGACY: Charset = Charset.forName("windows-1252")

    data class Decoded(val text: String, val charset: Charset)

    /** The option value as given: `auto`, or a charset name `Charset.forName` accepts. */
    fun validate(option: String): String {
        if (option == AUTO) return option
        return try {
            Charset.forName(option).name()
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Unknown encoding: $option (use auto or a Java charset name such as ISO-8859-1)", e)
        }
    }

    fun decode(bytes: ByteArray, option: String): Decoded {
        if (option != AUTO) return Decoded(withoutBom(String(bytes, Charset.forName(option))), Charset.forName(option))
        val strict = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            Decoded(withoutBom(strict.decode(ByteBuffer.wrap(bytes)).toString()), StandardCharsets.UTF_8)
        } catch (e: CharacterCodingException) {
            Decoded(String(bytes, LEGACY), LEGACY)
        }
    }

    /** Decodes with the charset recorded for a file in the index. */
    fun decode(bytes: ByteArray, charset: Charset): String = withoutBom(String(bytes, charset))

    private fun withoutBom(text: String) = if (text.startsWith('\uFEFF')) text.substring(1) else text
}
