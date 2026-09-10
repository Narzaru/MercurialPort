package com.narzaru.mercurial.hg

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import java.io.File
import java.io.InputStream
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
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
        return if (served != null) Outcome(served, true) else Outcome(execToResult(args), false)
    }

    private fun execOnServer(args: List<String>): HgBytesResult? {
        if (!HgSettings.useCommandServer || !HgServableCommands.isServable(args)) return null
        val servers = HgCommandServers.getInstanceOrNull() ?: return null
        val lease = leaseOrNull(servers, args) ?: return null
        val call = HgAwait.start(CALL_THREAD) { lease.server.run(args) }
        var settlement = Settlement.DISCARD
        try {
            val response = HgAwait.finish(call)
            if (response == null) {
                LOG.warn("${describe(args)} is being run as a plain process: the command server was " +
                    "left unfinished after $TIMEOUT_SECONDS s")
                return null
            }
            settlement = Settlement.RELEASE
            return HgBytesResult(response.exitCode, response.stdout, HgOutputDecoder.decode(response.stderr))
        } catch (e: ProcessCanceledException) {
            settlement = Settlement.DRAIN
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: Exception) {
            LOG.info("${describe(args)} is being run as a plain process: ${e.message}")
            return null
        } finally {
            settle(settlement, lease, call)
        }
    }

    private fun settle(
        settlement: Settlement,
        lease: HgServerLease,
        call: FutureTask<HgServerResponse>
    ) = when (settlement) {
        Settlement.RELEASE -> lease.release()
        Settlement.DISCARD -> lease.discard()
        Settlement.DRAIN -> drainThenSettle(lease, call)
    }

    private fun drainThenSettle(lease: HgServerLease, call: FutureTask<HgServerResponse>) {
        HgAwait.start(DRAIN_THREAD) {
            val drained = runCatching { call.get(HgAwait.TIMEOUT_SECONDS, TimeUnit.SECONDS) }.isSuccess
            if (drained) lease.release() else lease.discard()
        }
    }

    private fun leaseOrNull(servers: HgCommandServers, args: List<String>): HgServerLease? = try {
        servers.lease(workDir)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        LOG.info("${describe(args)} is being run as a plain process: ${e.message}")
        null
    }

    private fun execToResult(args: List<String>): HgBytesResult {
        return try {
            val output = exec(args)
            HgBytesResult(output.exitCode, output.stdout, HgOutputDecoder.decode(output.stderr))
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            HgBytesResult(FAILED_EXIT_CODE, ByteArray(0), canceledMessage(args), failedToStart = true)
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            LOG.warn("${describe(args)} could not be started: $message", e)
            HgBytesResult(FAILED_EXIT_CODE, ByteArray(0), message, failedToStart = true)
        }
    }

    private fun exec(args: List<String>): ProcessOutput {
        val process = GeneralCommandLine(listOf(HG) + args)
            .withWorkingDirectory(workDir.toPath())
            .createProcess()
        try {
            process.outputStream.close()
            val stdout = readInBackground(process.inputStream)
            val stderr = readInBackground(process.errorStream)
            if (!awaitExit(process)) {
                process.destroyForcibly()
                LOG.warn("${describe(args)} was killed after $TIMEOUT_SECONDS s without finishing")
                return ProcessOutput(
                    TIMEOUT_EXIT_CODE,
                    ByteArray(0),
                    timedOutMessage(args).toByteArray()
                )
            }
            return ProcessOutput(process.exitValue(), collect(stdout), collect(stderr))
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

    private fun canceledMessage(args: List<String>): String =
        "'${describe(args)}' was canceled before it finished."

    private fun timedOutMessage(args: List<String>): String =
        "'${describe(args)}' did not finish within $TIMEOUT_SECONDS seconds and was terminated. " +
            "Mercurial may be waiting for input or for a lock in $workDir."

    private enum class Settlement { RELEASE, DISCARD, DRAIN }

    private class Outcome(val result: HgBytesResult, val viaServer: Boolean)

    private class ProcessOutput(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray)

    companion object {
        private const val HG = "hg"
        private const val CALL_THREAD = "hg command server call"
        private const val DRAIN_THREAD = "hg command server drain"
        private const val TIMEOUT_SECONDS = HgAwait.TIMEOUT_SECONDS
        private const val POLL_MILLIS = 100L
        private const val READ_TAIL_SECONDS = 30L
        const val FAILED_EXIT_CODE = -1
        const val TIMEOUT_EXIT_CODE = -2

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
