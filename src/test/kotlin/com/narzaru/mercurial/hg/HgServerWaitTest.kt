package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HgServerWaitTest {

    private class Canceled : RuntimeException()

    private var nanos = 0L
    private val sleeps = ArrayList<Long>()
    private var cancelAfter = Int.MAX_VALUE
    private var checks = 0

    private fun wait(limitSeconds: Long = 30, pollMillis: Long = 2): HgServerWait = HgServerWait(
        limitNanos = TimeUnit.SECONDS.toNanos(limitSeconds),
        pollMillis = pollMillis,
        clock = { nanos },
        sleeper = {
            sleeps.add(it)
            nanos += TimeUnit.MILLISECONDS.toNanos(it)
        },
        checkCanceled = { if (checks++ >= cancelAfter) throw Canceled() },
        waitAllowed = { true }
    )

    @Test
    fun `a busy pool is retried until a server comes free`() {
        var attempts = 0

        val leased = wait().untilReady {
            attempts++
            if (attempts < 4) HgWaitAttempt.busy() else HgWaitAttempt.ready("server")
        }

        assertEquals("server", leased)
        assertEquals(4, attempts)
        assertEquals(listOf(2L, 2L, 2L), sleeps)
    }

    @Test
    fun `waiting past the limit gives up so that the caller can run a plain process`() {
        var attempts = 0

        val leased = wait(limitSeconds = 1, pollMillis = 100).untilReady {
            attempts++
            HgWaitAttempt.busy<String>()
        }

        assertNull(leased)
        assertEquals(11, attempts)
        assertEquals(TimeUnit.SECONDS.toNanos(1), nanos)
    }

    @Test
    fun `a pool that has no server to offer at all is not waited for`() {
        var attempts = 0

        val leased = wait().untilReady {
            attempts++
            HgWaitAttempt.unavailable<String>()
        }

        assertNull(leased)
        assertEquals(1, attempts)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `cancellation while waiting is passed on to the caller`() {
        cancelAfter = 3
        var attempts = 0

        val thrown = runCatching {
            wait().untilReady {
                attempts++
                HgWaitAttempt.busy<String>()
            }
        }.exceptionOrNull()

        assertTrue(thrown is Canceled)
        assertEquals(3, attempts)
    }

    @Test
    fun `cancellation is checked before the first attempt is even made`() {
        cancelAfter = 0
        var attempts = 0

        val thrown = runCatching {
            wait().untilReady {
                attempts++
                HgWaitAttempt.ready("server")
            }
        }.exceptionOrNull()

        assertTrue(thrown is Canceled)
        assertEquals(0, attempts)
    }

    @Test
    fun `a thread that must not block takes a plain process instead of waiting`() {
        var attempts = 0
        val blocking = HgServerWait(
            clock = { nanos },
            sleeper = { sleeps.add(it) },
            checkCanceled = {},
            waitAllowed = { false }
        )

        val leased = blocking.untilReady {
            attempts++
            HgWaitAttempt.busy<String>()
        }

        assertNull(leased)
        assertEquals(1, attempts)
        assertTrue(sleeps.isEmpty())
    }
}
