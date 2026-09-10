package com.narzaru.mercurial.hg

import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.TimeUnit

class HgWaitAttempt<T : Any> private constructor(val value: T?, val busy: Boolean) {

    companion object {

        fun <T : Any> ready(value: T): HgWaitAttempt<T> = HgWaitAttempt(value, false)

        fun <T : Any> busy(): HgWaitAttempt<T> = HgWaitAttempt(null, true)

        fun <T : Any> unavailable(): HgWaitAttempt<T> = HgWaitAttempt(null, false)
    }
}

class HgServerWait(
    private val limitNanos: Long = DEFAULT_LIMIT_NANOS,
    private val pollMillis: Long = POLL_MILLIS,
    private val clock: () -> Long = System::nanoTime,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val checkCanceled: () -> Unit = HgAwait::checkCanceled,
    private val waitAllowed: () -> Boolean = ::offTheEventThread
) {

    fun <T : Any> untilReady(attempt: () -> HgWaitAttempt<T>): T? {
        val deadline = clock() + limitNanos
        var mayWait = waitAllowed()
        while (true) {
            checkCanceled()
            val outcome = attempt()
            outcome.value?.let { return it }
            if (!outcome.busy || !mayWait || clock() >= deadline) return null
            sleeper(pollMillis)
            mayWait = waitAllowed()
        }
    }

    companion object {

        const val POLL_MILLIS = 2L

        val DEFAULT_LIMIT_NANOS: Long = TimeUnit.SECONDS.toNanos(30)
    }
}

private fun offTheEventThread(): Boolean {
    val application = ApplicationManager.getApplication() ?: return true
    return !application.isDispatchThread
}
