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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class HgServerLease internal constructor(
    private val servers: HgCommandServers,
    private val claim: HgServerClaim,
    val server: HgServer
) {

    fun release() = servers.giveBack(claim, server)

    fun discard() = servers.throwAway(claim, server)
}

@Service(Service.Level.APP)
class HgCommandServers : Disposable {

    private val pool = HgServerPool()

    private val wait = HgServerWait()

    private val sweep: ScheduledFuture<*> = AppExecutorUtil.getAppScheduledExecutorService()
        .scheduleWithFixedDelay({ closeLater(pool.evictIdle()) }, SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS)

    fun lease(workDir: File): HgServerLease? {
        applySettings()
        return wait.untilReady { attemptLease(workDir) }
    }

    fun applySettings() = closeLater(pool.resize(HgSettings.commandServerCount))

    private fun attemptLease(workDir: File): HgWaitAttempt<HgServerLease> {
        val claim = pool.claim(keyOf(workDir))
        closeLater(claim.stale)
        claim.server?.let { return HgWaitAttempt.ready(HgServerLease(this, claim, it)) }
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
        return HgWaitAttempt.ready(HgServerLease(this, claim, started))
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

    internal fun giveBack(claim: HgServerClaim, server: HgServer) {
        if (!pool.release(claim, server)) closeLater(listOf(server))
    }

    internal fun throwAway(claim: HgServerClaim, server: HgServer) {
        pool.discard(claim, server)
        closeLater(listOf(server))
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

    companion object {

        private const val SWEEP_SECONDS = 60L

        private val LOG = Logger.getInstance(HgCommandServers::class.java)

        fun getInstanceOrNull(): HgCommandServers? =
            runCatching { service<HgCommandServers>() }.getOrNull()
    }
}
