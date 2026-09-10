package com.narzaru.mercurial.hg

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

class HgServerFrame(val channel: Char, val length: Int, val data: ByteArray)

class HgServerResponse(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray)

class HgServerHello(val capabilities: Set<String>, val encoding: String?) {

    val supportsRunCommand: Boolean get() = HgServerProtocol.RUN_COMMAND in capabilities
}

class HgServerProtocolException(message: String) : Exception(message)

object HgServerProtocol {

    const val RUN_COMMAND = "runcommand"
    const val MAX_FRAME_BYTES = 256 * 1024 * 1024

    private const val OUTPUT_CHANNEL = 'o'
    private const val ERROR_CHANNEL = 'e'
    private const val RESULT_CHANNEL = 'r'
    private const val LINE_CHANNEL = 'L'
    private const val INPUT_CHANNEL = 'I'
    private const val HEADER_SIZE = 5
    private const val LENGTH_SIZE = 4
    private const val CAPABILITIES_FIELD = "capabilities"
    private const val ENCODING_FIELD = "encoding"

    private val ARGUMENT_SEPARATOR = String(charArrayOf(Char(0)))
    private val COMMAND_TERMINATOR = String(charArrayOf(Char(10)))

    fun encodeRunCommand(args: List<String>, charset: Charset): ByteArray {
        val payload = args.joinToString(ARGUMENT_SEPARATOR).toByteArray(charset)
        val out = ByteArrayOutputStream()
        out.write((RUN_COMMAND + COMMAND_TERMINATOR).toByteArray(Charsets.US_ASCII))
        out.write(encodeLength(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    fun encodeLength(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    fun parseHello(hello: ByteArray): HgServerHello {
        val fields = String(hello, Charsets.US_ASCII).lineSequence()
            .filter { it.contains(':') }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        val capabilities = fields[CAPABILITIES_FIELD]
            ?.split(' ')
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        return HgServerHello(capabilities, fields[ENCODING_FIELD]?.takeIf { it.isNotEmpty() })
    }

    fun argumentCharset(hello: HgServerHello, fallback: Charset): Charset {
        val name = hello.encoding ?: return fallback
        return runCatching { Charset.forName(name) }.getOrDefault(fallback)
    }

    fun readFrame(input: InputStream): HgServerFrame {
        val header = readExactly(input, HEADER_SIZE, "a channel header")
        val channel = (header[0].toInt() and 0xFF).toChar()
        val length = decodeLength(header, 1)
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw HgServerProtocolException("Channel '$channel' announced an unusable length of $length bytes")
        }
        if (channel == INPUT_CHANNEL || channel == LINE_CHANNEL) {
            return HgServerFrame(channel, length, ByteArray(0))
        }
        val data = readExactly(input, length, "$length bytes of channel '$channel'")
        return HgServerFrame(channel, length, data)
    }

    fun readResponse(input: InputStream, toServer: OutputStream): HgServerResponse {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        while (true) {
            val frame = readFrame(input)
            when (frame.channel) {
                OUTPUT_CHANNEL -> stdout.write(frame.data)
                ERROR_CHANNEL -> stderr.write(frame.data)
                RESULT_CHANNEL -> return finish(frame, stdout, stderr)
                INPUT_CHANNEL, LINE_CHANNEL -> refuseInput(toServer)
                else -> if (frame.channel.isUpperCase()) {
                    throw HgServerProtocolException("Channel '${frame.channel}' is required but unknown")
                }
            }
        }
    }

    private fun finish(
        frame: HgServerFrame,
        stdout: ByteArrayOutputStream,
        stderr: ByteArrayOutputStream
    ): HgServerResponse {
        if (frame.data.size != LENGTH_SIZE) {
            throw HgServerProtocolException(
                "The result channel carried ${frame.data.size} bytes instead of $LENGTH_SIZE"
            )
        }
        return HgServerResponse(decodeLength(frame.data, 0), stdout.toByteArray(), stderr.toByteArray())
    }

    private fun refuseInput(toServer: OutputStream) {
        toServer.write(encodeLength(0))
        toServer.flush()
    }

    private fun decodeLength(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF shl 24) or
            (bytes[offset + 1].toInt() and 0xFF shl 16) or
            (bytes[offset + 2].toInt() and 0xFF shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readExactly(input: InputStream, count: Int, what: String): ByteArray {
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = input.read(buffer, filled, count - filled)
            if (read < 0) throw HgServerProtocolException("The command server ended while sending $what")
            filled += read
        }
        return buffer
    }
}
