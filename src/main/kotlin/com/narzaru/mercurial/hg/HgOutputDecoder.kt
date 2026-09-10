package com.narzaru.mercurial.hg

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

object HgOutputDecoder {

    fun decode(bytes: ByteArray): String = decode(bytes, HgSettings.fallbackCharset())

    fun decode(bytes: ByteArray, fallback: Charset): String {
        if (bytes.isEmpty()) return ""

        val result = StringBuilder(bytes.size)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val chars = CharBuffer.allocate(bytes.size)

        var lineStart = if (startsWithUtf8Bom(bytes)) 3 else 0
        var i = lineStart
        while (i <= bytes.size) {
            if (i == bytes.size || bytes[i] == '\n'.code.toByte()) {
                var lineEnd = i
                if (lineEnd > lineStart && bytes[lineEnd - 1] == '\r'.code.toByte()) {
                    lineEnd--
                }

                if (lineEnd > lineStart) {
                    result.append(decodeLine(bytes, lineStart, lineEnd, decoder, chars, fallback))
                }

                if (i < bytes.size) result.append('\n')
                lineStart = i + 1
            }
            i++
        }
        return result.toString()
    }

    private fun startsWithUtf8Bom(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()

    private fun decodeLine(
        bytes: ByteArray,
        from: Int,
        to: Int,
        decoder: CharsetDecoder,
        chars: CharBuffer,
        fallback: Charset
    ): String {
        decoder.reset()
        chars.clear()
        val input = ByteBuffer.wrap(bytes, from, to - from)
        if (decoder.decode(input, chars, true).isError || decoder.flush(chars).isError) {
            return String(bytes, from, to - from, fallback)
        }
        chars.flip()
        return chars.toString()
    }
}
