package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LoadTraceTest {

    private class FakeClock {

        var nanos = 0L

        fun advance(millis: Long) {
            nanos += millis * 1_000_000L
        }

        fun read(): Long = nanos
    }

    @Test
    fun `an empty trace does not divide by zero`() {
        val clock = FakeClock()
        val trace = LoadTrace("Changesets load", clock::read)
        clock.advance(40)

        assertEquals("Changesets load: 40 ms wall, 0 hg calls, 0 ms in hg, 0 ms per call", trace.summary())
    }

    @Test
    fun `the summary carries the phases, the calls and the average call`() {
        val clock = FakeClock()
        val trace = LoadTrace("Changesets load", clock::read)
        trace.phase("branch revisions") { clock.advance(10) }
        trace.phase("range files") { clock.advance(30) }
        trace.callFinished(20_000_000L, false)
        trace.callFinished(40_000_000L, false)
        clock.advance(60)

        assertEquals(
            "Changesets load: 100 ms wall, 2 hg calls, 60 ms in hg, 30 ms per call; " +
                "phases 40 ms [branch revisions 10 ms, range files 30 ms]",
            trace.summary()
        )
    }

    @Test
    fun `a phase entered twice is summed up and keeps the place of its first run`() {
        val clock = FakeClock()
        val trace = LoadTrace("Changesets load", clock::read)
        trace.phase("render tree") { clock.advance(5) }
        trace.phase("diff stats") { clock.advance(7) }
        trace.phase("render tree") { clock.advance(15) }

        assertTrue(trace.summary().endsWith("phases 27 ms [render tree 20 ms, diff stats 7 ms]"))
    }

    @Test
    fun `a phase is measured even when the work throws`() {
        val clock = FakeClock()
        val trace = LoadTrace("Changesets load", clock::read)

        try {
            trace.phase("range files") {
                clock.advance(12)
                throw IllegalStateException("hg failed")
            }
        } catch (_: IllegalStateException) {
        }

        assertTrue(trace.summary().endsWith("phases 12 ms [range files 12 ms]"))
    }

    @Test
    fun `calls taken by the command server are counted apart`() {
        val clock = FakeClock()
        val trace = LoadTrace("Changesets load", clock::read)
        trace.callFinished(20_000_000L, true)
        trace.callFinished(20_000_000L, false)
        clock.advance(50)

        assertEquals(
            "Changesets load: 50 ms wall, 2 hg calls, 40 ms in hg, 20 ms per call, " +
                "1 of them on the command server",
            trace.summary()
        )
    }

    @Test
    fun `time in hg is summed across threads and may exceed the time that passed`() {
        val clock = FakeClock()
        val trace = LoadTrace("Changesets load", clock::read)
        val threads = 8
        val calls = 100
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            Thread {
                ready.countDown()
                start.await()
                repeat(calls) { trace.callFinished(1_000_000L, false) }
                done.countDown()
            }.start()
        }
        ready.await(10, TimeUnit.SECONDS)
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        clock.advance(100)

        assertEquals(
            "Changesets load: 100 ms wall, 800 hg calls, 800 ms in hg, 1 ms per call",
            trace.summary()
        )
    }
}
