package com.narzaru.mercurial.hg

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal sealed interface HgServerCall {

    class Answered(val response: HgServerResponse) : HgServerCall

    data object TimedOut : HgServerCall

    data object Interrupted : HgServerCall

    data object Unavailable : HgServerCall

    class Failed(val reason: String?) : HgServerCall
}

@Service(Service.Level.APP)
class HgCommandServers : Disposable {

    private val pool = HgServerPool()

    private val wait = HgServerWait()

    private val sweep: ScheduledFuture<*> = AppExecutorUtil.getAppScheduledExecutorService()
        .scheduleWithFixedDelay({ closeLater(pool.evictIdle()) }, SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS)

    internal fun call(workDir: File, args: List<String>): HgServerCall {
        val lease = try {
            lease(workDir)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            return HgServerCall.Failed(e.message)
        }
        return lease?.let { callOn(it, args) } ?: HgServerCall.Unavailable
    }

    fun applySettings() = closeLater(pool.resize(HgSettings.commandServerCount))

    private fun lease(workDir: File): Lease? {
        applySettings()
        return wait.untilReady { attemptLease(workDir) }
    }

    private fun callOn(lease: Lease, args: List<String>): HgServerCall {
        val call = HgAwait.start(CALL_THREAD) { lease.server.run(args) }
        var settlement = Settlement.DISCARD
        try {
            val response = HgAwait.finish(call) ?: return HgServerCall.TimedOut
            settlement = Settlement.RELEASE
            return HgServerCall.Answered(response)
        } catch (e: ProcessCanceledException) {
            settlement = Settlement.DRAIN
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return HgServerCall.Interrupted
        } catch (e: Exception) {
            return HgServerCall.Failed(e.message)
        } finally {
            settle(settlement, lease, call)
        }
    }

    private fun settle(settlement: Settlement, lease: Lease, call: FutureTask<HgServerResponse>) =
        when (settlement) {
            Settlement.RELEASE -> giveBack(lease)
            Settlement.DISCARD -> throwAway(lease)
            Settlement.DRAIN -> drainThenSettle(lease, call)
        }

    private fun drainThenSettle(lease: Lease, call: FutureTask<HgServerResponse>) {
        HgAwait.start(DRAIN_THREAD) {
            val drained = runCatching { call.get(HgAwait.TIMEOUT_SECONDS, TimeUnit.SECONDS) }.isSuccess
            if (drained) giveBack(lease) else throwAway(lease)
        }
    }

    private fun attemptLease(workDir: File): HgWaitAttempt<Lease> {
        val claim = pool.claim(keyOf(workDir))
        closeLater(claim.stale)
        claim.server?.let { return HgWaitAttempt.ready(Lease(claim, it)) }
        if (!claim.startNeeded) {
            return if (claim.refused) HgWaitAttempt.unavailable() else HgWaitAttempt.busy()
        }
        val started = try {
            startUnderDeadline(workDir)
        } catch (e: ProcessCanceledException) {
            pool.abandonStart(claim)
            throw e
        }
        if (started == null) {
            pool.started(claim, null)
            return HgWaitAttempt.unavailable()
        }
        if (!pool.started(claim, started)) {
            closeLater(listOf(started))
            return HgWaitAttempt.busy()
        }
        return HgWaitAttempt.ready(Lease(claim, started))
    }

    fun projectClosing(closing: Project) {
        val open = ProjectManager.getInstance().openProjects
            .filter { it !== closing && !it.isDisposed }
            .mapNotNull { it.basePath }
            .map { keyOf(File(it)) }
        val unused = HgServerOwnership.unusedRoots(
            pool.roots(),
            open,
            File.separatorChar,
            !SystemInfo.isFileSystemCaseSensitive
        )
        closeLater(pool.close(unused))
    }

    fun shutdownAll() = closeLater(pool.closeAll())

    override fun dispose() {
        sweep.cancel(false)
        pool.closeAll().forEach { closeQuietly(it) }
    }

    private fun giveBack(lease: Lease) {
        if (!pool.release(lease.claim, lease.server)) closeLater(listOf(lease.server))
    }

    private fun throwAway(lease: Lease) {
        pool.discard(lease.claim, lease.server)
        closeLater(listOf(lease.server))
    }

    private fun startUnderDeadline(workDir: File): HgCommandServer? {
        val startup = HgServerStartup.launch(workDir) ?: return null
        val started = try {
            HgAwait.finish(HgAwait.start("hg command server hello") { startup.awaitHello() })
        } catch (e: ProcessCanceledException) {
            startup.kill()
            throw e
        } catch (e: Throwable) {
            startup.kill()
            LOG.info("An hg command server in $workDir did not start: ${e.message}")
            return null
        }
        if (started == null) {
            LOG.warn("An hg command server in $workDir did not greet within ${HgAwait.TIMEOUT_SECONDS} s")
            startup.kill()
        }
        return started
    }

    private fun closeLater(servers: Collection<HgServer>) {
        if (servers.isEmpty()) return
        val closing = servers.toList()
        val submitted = runCatching {
            AppExecutorUtil.getAppExecutorService().execute { closing.forEach { closeQuietly(it) } }
        }
        if (submitted.isFailure) closing.forEach { closeQuietly(it) }
    }

    private fun closeQuietly(server: HgServer) {
        runCatching { server.close() }
    }

    private fun keyOf(dir: File): String = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)

    private class Lease(val claim: HgServerClaim, val server: HgServer)

    private enum class Settlement { RELEASE, DISCARD, DRAIN }

    companion object {

        private const val SWEEP_SECONDS = 60L
        private const val CALL_THREAD = "hg command server call"
        private const val DRAIN_THREAD = "hg command server drain"

        private val LOG = Logger.getInstance(HgCommandServers::class.java)

        fun getInstanceOrNull(): HgCommandServers? =
            runCatching { service<HgCommandServers>() }.getOrNull()
    }
}
