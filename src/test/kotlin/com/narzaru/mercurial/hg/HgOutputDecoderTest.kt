package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class HgOutputDecoderTest {

    private val cp1251: Charset = Charset.forName("windows-1251")

    private fun decode(bytes: ByteArray) = HgOutputDecoder.decode(bytes, cp1251)

    @Test
    fun `корректный UTF-8 декодируется как UTF-8`() {
        assertEquals("привет", decode("привет".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `строка в кодировке отката декодируется ею`() {
        assertEquals("привет", decode("привет".toByteArray(cp1251)))
    }

    @Test
    fun `кодировка выбирается для каждой строки отдельно`() {
        // hg легко смешивает: сообщение коммита в cp1251, а имя файла — в UTF-8.
        val bytes = "утф8".toByteArray(Charsets.UTF_8) +
            '\n'.code.toByte() +
            "ср1251".toByteArray(cp1251)

        assertEquals("утф8\nср1251", decode(bytes))
    }

    @Test
    fun `BOM в начале снимается`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        assertEquals("текст", decode(bom + "текст".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `BOM снимается только в начале вывода`() {
        val bom = "﻿"

        assertEquals("a\n$bom", decode("a\n$bom".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `CRLF приводится к LF`() {
        assertEquals("a\nb", decode("a\r\nb".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `завершающий перевод строки сохраняется`() {
        assertEquals("a\n", decode("a\n".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `пустой ввод даёт пустую строку`() {
        assertEquals("", decode(ByteArray(0)))
    }
}
