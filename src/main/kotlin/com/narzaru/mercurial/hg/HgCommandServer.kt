package com.narzaru.mercurial.hg

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

class HgServerStartup private constructor(private val process: Process, private val workDir: File) {

    fun awaitHello(): HgCommandServer {
        val fromServer = BufferedInputStream(process.inputStream)
        val hello = HgServerProtocol.parseHello(HgServerProtocol.readFrame(fromServer).data)
        if (!hello.supportsRunCommand) {
            throw HgServerProtocolException("The hg command server in $workDir does not offer runcommand")
        }
        return HgCommandServer(
            process,
            fromServer,
            process.outputStream,
            HgServerProtocol.argumentCharset(hello, HgCommandServer.CHARSET)
        )
    }

    fun kill() {
        process.destroyForcibly()
    }

    companion object {

        private val LOG = Logger.getInstance(HgServerStartup::class.java)

        fun launch(workDir: File): HgServerStartup? = try {
            val process = GeneralCommandLine(HgCommandServer.COMMAND)
                .withWorkingDirectory(workDir.toPath())
                .createProcess()
            HgAwait.start("hg command server stderr") {
                runCatching { process.errorStream.use { it.readBytes() } }
            }
            HgServerStartup(process, workDir)
        } catch (e: Exception) {
            LOG.info("An hg command server could not be started in $workDir: ${e.message}")
            null
        }
    }
}

class HgCommandServer internal constructor(
    private val process: Process,
    private val fromServer: InputStream,
    private val toServer: OutputStream,
    private val argumentCharset: Charset
) : HgServer {

    @Volatile
    private var broken = false

    @Volatile
    override var idleSinceNanos: Long = System.nanoTime()
        private set

    override val isUsable: Boolean get() = !broken && process.isAlive

    override fun run(args: List<String>): HgServerResponse {
        try {
            toServer.write(HgServerProtocol.encodeRunCommand(args, argumentCharset))
            toServer.flush()
            val response = HgServerProtocol.readResponse(fromServer, toServer)
            idleSinceNanos = System.nanoTime()
            return response
        } catch (e: Throwable) {
            broken = true
            throw e
        }
    }

    override fun close() {
        broken = true
        runCatching { toServer.close() }
        runCatching { fromServer.close() }
        val reaped = runCatching {
            HgAwait.start("hg command server shutdown") {
                if (!process.waitFor(SHUTDOWN_MILLIS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
        }
        if (reaped.isFailure) process.destroyForcibly()
    }

    companion object {

        val CHARSET: Charset get() = HgSettings.nativeCharset()

        internal val COMMAND = listOf("hg", "serve", "--cmdserver", "pipe")

        private const val SHUTDOWN_MILLIS = 2000L
    }
}
