package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class HgOutputDecoderTest {

    private val cp1251: Charset = Charset.forName("windows-1251")

    private fun decode(bytes: ByteArray) = HgOutputDecoder.decode(bytes, cp1251)

    @Test
    fun `valid UTF-8 is decoded as UTF-8`() {
        assertEquals("привет", decode("привет".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a line in the fallback charset is decoded with it`() {
        assertEquals("привет", decode("привет".toByteArray(cp1251)))
    }

    @Test
    fun `the charset is chosen per line`() {
        val bytes = "утф8".toByteArray(Charsets.UTF_8) +
            '\n'.code.toByte() +
            "ср1251".toByteArray(cp1251)

        assertEquals("утф8\nср1251", decode(bytes))
    }

    @Test
    fun `a leading BOM is dropped`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        assertEquals("текст", decode(bom + "текст".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a BOM is dropped only at the start of the output`() {
        val bom = "﻿"

        assertEquals("a\n$bom", decode("a\n$bom".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `CRLF becomes LF`() {
        assertEquals("a\nb", decode("a\r\nb".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a lone carriage return is kept as text`() {
        assertEquals("a\rb", decode("a\rb".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a trailing carriage return is dropped`() {
        assertEquals("a", decode("a\r".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a trailing line separator is kept`() {
        assertEquals("a\n", decode("a\n".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `empty input gives an empty string`() {
        assertEquals("", decode(ByteArray(0)))
    }
}
