package com.narzaru.mercurial.review

import com.narzaru.mercurial.changes.HgCommands
import com.narzaru.mercurial.hg.HgOutputDecoder
import com.narzaru.mercurial.hg.HgResult
import com.narzaru.mercurial.hg.HgServerProtocol
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

class CommandServerHg(repo: File, hgArgs: List<String>, private val fallback: Charset) : HgCommands, AutoCloseable {

    private val process: Process = ProcessBuilder(listOf("hg") + hgArgs + listOf("serve", "--cmdserver", "pipe"))
        .directory(repo)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .also { it.environment()["HGRCPATH"] = ""; it.environment()["HGPLAIN"] = "1" }
        .start()

    private val input: InputStream = process.inputStream.buffered()

    private val output: OutputStream = process.outputStream.buffered()

    private val raw = HashMap<List<String>, ByteArray>()

    var calls = 0
        private set

    var nanos = 0L
        private set

    val callsByCommand = LinkedHashMap<String, Int>()

    init {
        HgServerProtocol.readFrame(input)
    }

    @Synchronized
    override fun run(args: List<String>): HgResult {
        val began = System.nanoTime()
        output.write(HgServerProtocol.encodeRunCommand(args, Charsets.UTF_8))
        output.flush()
        val response = HgServerProtocol.readResponse(input, output)
        nanos += System.nanoTime() - began
        calls++
        callsByCommand.merge(args.first(), 1, Int::plus)
        if (args.first() == "cat" && response.exitCode == 0) raw[args] = response.stdout
        return HgResult(
            response.exitCode,
            HgOutputDecoder.decode(response.stdout, fallback),
            String(response.stderr, Charsets.UTF_8)
        )
    }

    @Synchronized
    fun rawContent(rev: String, path: String): ByteArray? = raw[listOf("cat", "-r", rev, "path:$path")]

    @Synchronized
    fun resetCounters() {
        calls = 0
        nanos = 0L
        callsByCommand.clear()
        raw.clear()
    }

    override fun close() {
        runCatching { output.close() }
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
    }
}
