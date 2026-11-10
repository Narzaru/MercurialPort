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

interface HgServerTurn {

    fun await(nanos: Long): Boolean
}

class HgServerWait(
    private val limitNanos: Long = DEFAULT_LIMIT_NANOS,
    private val cancelCheckNanos: Long = DEFAULT_CANCEL_CHECK_NANOS,
    private val clock: () -> Long = System::nanoTime,
    private val checkCanceled: () -> Unit = HgAwait::checkCanceled,
    private val waitAllowed: () -> Boolean = ::offTheEventThread
) {

    fun <T : Any> untilReady(turn: HgServerTurn, attempt: () -> HgWaitAttempt<T>): T? {
        val deadline = clock() + limitNanos
        var mayWait = waitAllowed()
        while (true) {
            checkCanceled()
            val outcome = attempt()
            outcome.value?.let { return it }
            val remaining = deadline - clock()
            if (!outcome.busy || !mayWait || remaining <= 0) return null
            turn.await(minOf(remaining, cancelCheckNanos))
            mayWait = waitAllowed()
        }
    }

    companion object {

        val DEFAULT_LIMIT_NANOS: Long = TimeUnit.SECONDS.toNanos(30)
        val DEFAULT_CANCEL_CHECK_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(100)
    }
}

private fun offTheEventThread(): Boolean {
    val application = ApplicationManager.getApplication() ?: return true
    return !application.isDispatchThread
}
