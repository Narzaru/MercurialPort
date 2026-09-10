package com.narzaru.mercurial.hg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

class HgServerProtocolTest {

    private val nul = String(charArrayOf(Char(0)))

    private fun frame(channel: Char, data: ByteArray): ByteArray =
        byteArrayOf(channel.code.toByte()) + HgServerProtocol.encodeLength(data.size) + data

    private fun result(code: Int): ByteArray = frame('r', HgServerProtocol.encodeLength(code))

    private fun read(vararg parts: ByteArray): HgServerResponse {
        val stream = ByteArrayInputStream(parts.reduce { a, b -> a + b })
        return HgServerProtocol.readResponse(stream, ByteArrayOutputStream())
    }

    @Test
    fun `output and exit code are read from their channels`() {
        val response = read(frame('o', "M file.txt\n".toByteArray()), result(0))

        assertEquals(0, response.exitCode)
        assertEquals("M file.txt\n", String(response.stdout))
        assertEquals(0, response.stderr.size)
    }

    @Test
    fun `output split across frames is joined in order`() {
        val response = read(
            frame('o', "first".toByteArray()),
            frame('o', "".toByteArray()),
            frame('o', "second".toByteArray()),
            result(0)
        )

        assertEquals("firstsecond", String(response.stdout))
    }

    @Test
    fun `a command with no output answers with the result frame alone`() {
        val response = read(result(0))

        assertEquals(0, response.exitCode)
        assertEquals(0, response.stdout.size)
    }

    @Test
    fun `the error channel is kept apart from the output channel`() {
        val response = read(
            frame('o', "kept".toByteArray()),
            frame('e', "abort: unknown revision".toByteArray()),
            result(255)
        )

        assertEquals(255, response.exitCode)
        assertEquals("kept", String(response.stdout))
        assertEquals("abort: unknown revision", String(response.stderr))
    }

    @Test
    fun `binary output survives byte for byte`() {
        val bytes = ByteArray(256) { it.toByte() }

        val response = read(frame('o', bytes), result(0))

        assertArrayEquals(bytes, response.stdout)
    }

    @Test(expected = HgServerProtocolException::class)
    fun `a stream that ends inside a frame is reported`() {
        val truncated = frame('o', "complete payload".toByteArray()).copyOfRange(0, 8)
        read(truncated)
    }

    @Test(expected = HgServerProtocolException::class)
    fun `a stream that ends between frames is reported`() {
        read(frame('o', "no result frame".toByteArray()))
    }

    @Test(expected = HgServerProtocolException::class)
    fun `an unknown required channel is reported`() {
        read(frame('X', "must be understood".toByteArray()), result(0))
    }

    @Test
    fun `an unknown optional channel is skipped`() {
        val response = read(
            frame('d', "debug noise".toByteArray()),
            frame('o', "payload".toByteArray()),
            result(0)
        )

        assertEquals("payload", String(response.stdout))
    }

    @Test(expected = HgServerProtocolException::class)
    fun `a result frame of the wrong size is reported`() {
        read(frame('r', byteArrayOf(0, 0)))
    }

    @Test
    fun `a request for input is answered with nothing`() {
        val toServer = ByteArrayOutputStream()
        val stream = ByteArrayInputStream(
            frame('L', ByteArray(0)).copyOfRange(0, 1) +
                HgServerProtocol.encodeLength(4096) +
                result(0)
        )

        val response = HgServerProtocol.readResponse(stream, toServer)

        assertEquals(0, response.exitCode)
        assertArrayEquals(HgServerProtocol.encodeLength(0), toServer.toByteArray())
    }

    @Test
    fun `a run command request is length prefixed and null separated`() {
        val encoded = HgServerProtocol.encodeRunCommand(listOf("status", "--rev", "tip"), Charsets.UTF_8)

        val payload = "status${nul}--rev${nul}tip".toByteArray()
        val expected = "runcommand\n".toByteArray() + HgServerProtocol.encodeLength(payload.size) + payload
        assertArrayEquals(expected, encoded)
    }

    @Test
    fun `arguments are encoded with the charset the launcher uses`() {
        val charset = Charset.forName("windows-1251")

        val encoded = HgServerProtocol.encodeRunCommand(listOf("cat", "файл.txt"), charset)

        val payload = "cat${nul}файл.txt".toByteArray(charset)
        assertArrayEquals(payload, encoded.copyOfRange(encoded.size - payload.size, encoded.size))
    }

    @Test
    fun `a hello without runcommand is refused`() {
        val full = HgServerProtocol.parseHello(
            "capabilities: getencoding runcommand\nencoding: cp1251\npid: 1\n".toByteArray()
        )

        assertTrue(full.supportsRunCommand)
        assertEquals("cp1251", full.encoding)
        assertFalse(HgServerProtocol.parseHello("capabilities: getencoding\n".toByteArray()).supportsRunCommand)
        assertFalse(HgServerProtocol.parseHello("encoding: cp1251\n".toByteArray()).supportsRunCommand)
    }

    @Test
    fun `arguments are encoded with the encoding the server announced`() {
        val hello = HgServerProtocol.parseHello("capabilities: runcommand\nencoding: cp1251\n".toByteArray())

        assertEquals(Charset.forName("windows-1251"), HgServerProtocol.argumentCharset(hello, Charsets.UTF_8))
    }

    @Test
    fun `an unusable announced encoding leaves the fallback in place`() {
        val unknown = HgServerProtocol.parseHello("capabilities: runcommand\nencoding: mojibake\n".toByteArray())
        val missing = HgServerProtocol.parseHello("capabilities: runcommand\n".toByteArray())

        assertEquals(Charsets.UTF_8, HgServerProtocol.argumentCharset(unknown, Charsets.UTF_8))
        assertEquals(Charsets.UTF_8, HgServerProtocol.argumentCharset(missing, Charsets.UTF_8))
    }

    @Test(expected = HgServerProtocolException::class)
    fun `a frame too large to hold in memory is reported`() {
        val header = byteArrayOf('o'.code.toByte()) + HgServerProtocol.encodeLength(Int.MAX_VALUE)
        HgServerProtocol.readFrame(ByteArrayInputStream(header))
    }

    @Test(expected = HgServerProtocolException::class)
    fun `a channel byte above the ascii range is reported rather than mistaken for a letter`() {
        read(byteArrayOf(0xC0.toByte()) + HgServerProtocol.encodeLength(0), result(0))
    }
}
