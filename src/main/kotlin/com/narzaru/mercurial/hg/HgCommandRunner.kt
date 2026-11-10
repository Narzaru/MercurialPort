package com.narzaru.mercurial.hg

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import java.io.File
import java.io.InputStream
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

data class HgResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val failedToStart: Boolean = false
) {
    val success: Boolean get() = exitCode == 0
}

class HgBytesResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: String,
    val failedToStart: Boolean = false
)

interface HgCallWatch {

    fun callFinished(nanos: Long, viaServer: Boolean)
}

class HgCommandRunner(private val workDir: File, private val watch: HgCallWatch? = null) {

    fun run(vararg args: String): HgResult = runToText(args.toList())

    fun runToText(args: List<String>): HgResult {
        val bytes = runToBytesDetailed(args)
        return HgResult(
            bytes.exitCode,
            HgOutputDecoder.decode(bytes.stdout),
            bytes.stderr,
            bytes.failedToStart
        )
    }

    fun runToBytesDetailed(args: List<String>): HgBytesResult {
        val watch = watch ?: return execWherePossible(args).result
        val began = System.nanoTime()
        var viaServer = false
        try {
            val outcome = execWherePossible(args)
            viaServer = outcome.viaServer
            return outcome.result
        } finally {
            watch.callFinished(System.nanoTime() - began, viaServer)
        }
    }

    private fun execWherePossible(args: List<String>): Outcome {
        val served = execOnServer(args)
        if (served != null) return Outcome(served, true)
        if (Thread.currentThread().isInterrupted) return Outcome(canceled(args), false)
        return Outcome(execToResult(args), false)
    }

    private fun execOnServer(args: List<String>): HgBytesResult? {
        if (!HgSettings.useCommandServer || !HgServableCommands.isServable(args)) return null
        val servers = HgCommandServers.getInstanceOrNull() ?: return null
        return when (val call = servers.call(workDir, args)) {
            is HgServerCall.Answered -> HgBytesResult(
                call.response.exitCode,
                call.response.stdout,
                HgOutputDecoder.decode(call.response.stderr)
            )
            HgServerCall.TimedOut -> {
                LOG.warn("${describe(args)} was left unfinished by the command server after $TIMEOUT_SECONDS s")
                timedOut(args)
            }
            HgServerCall.Interrupted -> canceled(args)
            HgServerCall.Unavailable -> null
            is HgServerCall.Failed -> {
                LOG.info("${describe(args)} is being run as a plain process: ${call.reason}")
                null
            }
        }
    }

    private fun execToResult(args: List<String>): HgBytesResult = try {
        exec(args)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        canceled(args)
    } catch (e: Exception) {
        val message = e.message ?: e.toString()
        LOG.warn("${describe(args)} could not be started: $message", e)
        HgFailureKind.NOT_STARTED.result(message)
    }

    private fun exec(args: List<String>): HgBytesResult {
        val process = GeneralCommandLine(listOf(HG) + args)
            .withEnvironment(PLAIN_ENVIRONMENT)
            .withWorkingDirectory(workDir.toPath())
            .createProcess()
        try {
            process.outputStream.close()
            val stdout = readInBackground(process.inputStream)
            val stderr = readInBackground(process.errorStream)
            if (!awaitExit(process)) {
                process.destroyForcibly()
                LOG.warn("${describe(args)} was killed after $TIMEOUT_SECONDS s without finishing")
                return timedOut(args)
            }
            return HgBytesResult(process.exitValue(), collect(stdout), HgOutputDecoder.decode(collect(stderr)))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun awaitExit(process: Process): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (true) {
            HgAwait.checkCanceled()
            if (process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) return true
            if (System.nanoTime() >= deadline) return false
        }
    }

    private fun readInBackground(stream: InputStream): Future<ByteArray> =
        HgAwait.start("hg output reader") { stream.use { it.readBytes() } }

    private fun collect(reader: Future<ByteArray>): ByteArray = try {
        reader.get(READ_TAIL_SECONDS, TimeUnit.SECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        reader.cancel(true)
        ByteArray(0)
    } catch (e: Exception) {
        reader.cancel(true)
        LOG.warn("An hg output stream could not be read to the end", e)
        ByteArray(0)
    }

    private fun describe(args: List<String>): String = (listOf(HG) + args).joinToString(" ")

    private fun canceled(args: List<String>): HgBytesResult =
        HgFailureKind.CANCELED.result("'${describe(args)}' was canceled before it finished.")

    private fun timedOut(args: List<String>): HgBytesResult = HgFailureKind.TIMED_OUT.result(
        "'${describe(args)}' did not finish within $TIMEOUT_SECONDS seconds and was terminated. " +
            "Mercurial may be waiting for input or for a lock in $workDir."
    )

    private class Outcome(val result: HgBytesResult, val viaServer: Boolean)

    companion object {
        private const val HG = "hg"
        private const val TIMEOUT_SECONDS = HgAwait.TIMEOUT_SECONDS
        private const val POLL_MILLIS = 100L
        private const val READ_TAIL_SECONDS = 30L

        internal val PLAIN_ENVIRONMENT = mapOf("HGPLAIN" to "1")

        private val LOG = Logger.getInstance(HgCommandRunner::class.java)

        fun findRepoRoot(start: File?): File? {
            var dir = start
            while (dir != null) {
                if (File(dir, ".hg").isDirectory) return dir
                dir = dir.parentFile
            }
            return null
        }
    }
}
