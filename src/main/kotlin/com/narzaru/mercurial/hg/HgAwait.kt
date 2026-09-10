package com.narzaru.mercurial.hg

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object HgAwait {

    const val TIMEOUT_SECONDS = 120L

    private const val POLL_MILLIS = 100L

    fun <T> start(name: String, work: () -> T): FutureTask<T> {
        val task = FutureTask(work)
        val pool = runCatching {
            if (ApplicationManager.getApplication() == null) null else AppExecutorUtil.getAppExecutorService()
        }.getOrNull()
        if (pool != null && runCatching { pool.execute(task) }.isSuccess) return task
        val thread = Thread(task, name)
        thread.isDaemon = true
        thread.start()
        return task
    }

    fun <T : Any> finish(task: FutureTask<T>): T? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (true) {
            checkCanceled()
            try {
                return task.get(POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                if (System.nanoTime() >= deadline) return null
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    fun checkCanceled() {
        if (ApplicationManager.getApplication() == null) return
        ProgressManager.checkCanceled()
    }
}
