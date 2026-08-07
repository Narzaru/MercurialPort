package com.narzaru.mercurial.hg

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Декодирует вывод hg построчно: сначала пробует строгий UTF-8, а при ошибке
 * откатывается на кодировку из настроек (по умолчанию — ANSI-кодировка системы,
 * на русской Windows это cp1251, в которой обычно и набраны сообщения коммитов).
 * Порт HgVs.Models.HgOutputDecoder.
 */
object HgOutputDecoder {

    // Кодировку читаем один раз на вызов: обращаться к настройке на каждую строку слишком дорого.
    fun decode(bytes: ByteArray): String = decode(bytes, HgSettings.fallbackCharset())

    /** Та же логика с явной кодировкой отката — без обращения к настройкам плагина. */
    fun decode(bytes: ByteArray, fallback: Charset): String {
        if (bytes.isEmpty()) return ""

        val result = StringBuilder(bytes.size)
        // hg cat отдаёт файл как есть, вместе с BOM. В редакторе платформа BOM снимает,
        // поэтому иначе первая строка диффа всегда «отличалась» на невидимый U+FEFF.
        var lineStart = if (startsWithUtf8Bom(bytes)) 3 else 0
        var i = lineStart
        while (i <= bytes.size) {
            if (i == bytes.size || bytes[i] == '\n'.code.toByte()) {
                var lineEnd = i
                if (lineEnd > lineStart && bytes[lineEnd - 1] == '\r'.code.toByte()) {
                    lineEnd--
                }

                val lineLen = lineEnd - lineStart
                if (lineLen > 0) {
                    val lineBytes = bytes.copyOfRange(lineStart, lineStart + lineLen)
                    result.append(decodeLine(lineBytes, fallback))
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

    private fun decodeLine(lineBytes: ByteArray, fallback: Charset): String {
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(lineBytes)).toString()
        } catch (_: Exception) {
            String(lineBytes, fallback)
        }
    }
}
