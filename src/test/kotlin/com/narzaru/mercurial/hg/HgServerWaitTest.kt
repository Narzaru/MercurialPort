package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HgServerWaitTest {

    private class Canceled : RuntimeException()

    private val slice = TimeUnit.MILLISECONDS.toNanos(100)

    private var nanos = 0L
    private val awaits = ArrayList<Long>()
    private var cancelAfter = Int.MAX_VALUE
    private var checks = 0
    private var interruptOnAwait = false

    private val turn = object : HgServerTurn {
        override fun await(nanos: Long): Boolean {
            if (interruptOnAwait) throw InterruptedException()
            awaits.add(nanos)
            this@HgServerWaitTest.nanos += nanos
            return false
        }
    }

    private fun wait(limitMillis: Long = 30_000): HgServerWait = HgServerWait(
        limitNanos = TimeUnit.MILLISECONDS.toNanos(limitMillis),
        cancelCheckNanos = slice,
        clock = { nanos },
        checkCanceled = { if (checks++ >= cancelAfter) throw Canceled() },
        waitAllowed = { true }
    )

    @Test
    fun `a busy pool is asked again each time a wait ends`() {
        var attempts = 0

        val leased = wait().untilReady(turn) {
            attempts++
            if (attempts < 4) HgWaitAttempt.busy() else HgWaitAttempt.ready("server")
        }

        assertEquals("server", leased)
        assertEquals(4, attempts)
        assertEquals(listOf(slice, slice, slice), awaits)
    }

    @Test
    fun `waiting past the limit gives up so that the caller can run a plain process`() {
        var attempts = 0

        val leased = wait(limitMillis = 1_000).untilReady(turn) {
            attempts++
            HgWaitAttempt.busy<String>()
        }

        assertNull(leased)
        assertEquals(11, attempts)
        assertEquals(TimeUnit.SECONDS.toNanos(1), nanos)
    }

    @Test
    fun `the last wait is cut short to end at the limit`() {
        wait(limitMillis = 250).untilReady(turn) { HgWaitAttempt.busy<String>() }

        assertEquals(listOf(slice, slice, TimeUnit.MILLISECONDS.toNanos(50)), awaits)
    }

    @Test
    fun `a turn that comes before the slice ends is tried at once`() {
        var attempts = 0
        val early = object : HgServerTurn {
            override fun await(nanos: Long): Boolean = true
        }

        val leased = wait().untilReady(early) {
            attempts++
            if (attempts < 3) HgWaitAttempt.busy() else HgWaitAttempt.ready("server")
        }

        assertEquals("server", leased)
        assertEquals(0L, nanos)
    }

    @Test
    fun `a pool that has no server to offer at all is not waited for`() {
        var attempts = 0

        val leased = wait().untilReady(turn) {
            attempts++
            HgWaitAttempt.unavailable<String>()
        }

        assertNull(leased)
        assertEquals(1, attempts)
        assertTrue(awaits.isEmpty())
    }

    @Test
    fun `cancellation while waiting is passed on to the caller`() {
        cancelAfter = 3
        var attempts = 0

        val thrown = runCatching {
            wait().untilReady(turn) {
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
            wait().untilReady(turn) {
                attempts++
                HgWaitAttempt.ready("server")
            }
        }.exceptionOrNull()

        assertTrue(thrown is Canceled)
        assertEquals(0, attempts)
    }

    @Test
    fun `an interrupted wait is passed on to the caller without another attempt`() {
        interruptOnAwait = true
        var attempts = 0

        val thrown = runCatching {
            wait().untilReady(turn) {
                attempts++
                HgWaitAttempt.busy<String>()
            }
        }.exceptionOrNull()

        assertTrue(thrown is InterruptedException)
        assertEquals(1, attempts)
    }

    @Test
    fun `a thread that must not block takes a plain process instead of waiting`() {
        var attempts = 0
        val blocking = HgServerWait(
            clock = { nanos },
            checkCanceled = {},
            waitAllowed = { false }
        )

        val leased = blocking.untilReady(turn) {
            attempts++
            HgWaitAttempt.busy<String>()
        }

        assertNull(leased)
        assertEquals(1, attempts)
        assertTrue(awaits.isEmpty())
    }
}
