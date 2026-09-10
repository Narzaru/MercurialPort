package com.narzaru.mercurial.changes

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ParallelJobsTest {

    private class PoolLauncher(threads: Int) : TaskLauncher {
        private val pool = Executors.newFixedThreadPool(threads)
        override fun <T> launch(task: Callable<T>): Future<T> = pool.submit(task)
        fun shutdown() = pool.shutdownNow()
    }

    @Test
    fun `results keep the order of the keys`() {
        val launcher = PoolLauncher(4)
        try {
            val keys = (1..50).toList()
            val result = ParallelJobs(launcher, 4).map(keys) { key ->
                if (key % 2 == 0) Thread.sleep(2)
                key * 10
            }
            assertEquals(keys.map { it * 10 }, result)
        } finally {
            launcher.shutdown()
        }
    }

    @Test
    fun `the same result comes back on a single thread`() {
        val keys = listOf("a", "b", "c")
        assertEquals(listOf("A", "B", "C"), ParallelJobs(TaskLauncher.SAME_THREAD).map(keys) { it.uppercase() })
    }

    @Test
    fun `a failure ends the run and comes back last`() {
        val started = AtomicInteger()
        val result = ParallelJobs(TaskLauncher.SAME_THREAD, 2).map(
            (1..10).toList(),
            { it < 0 }
        ) { key ->
            started.incrementAndGet()
            if (key == 3) -1 else key
        }
        assertEquals(listOf(1, 2, -1), result)
        assertEquals(3, started.get())
    }

    @Test
    fun `a failure in a parallel batch does not report later keys`() {
        val launcher = PoolLauncher(4)
        try {
            val result = ParallelJobs(launcher, 4).map(
                (1..8).toList(),
                { it < 0 }
            ) { key -> if (key == 2) -1 else key }
            assertEquals(listOf(1, -1), result)
        } finally {
            launcher.shutdown()
        }
    }

    @Test
    fun `an exception from a job is rethrown as is`() {
        val launcher = PoolLauncher(2)
        try {
            val error = runCatching {
                ParallelJobs(launcher, 2).map(listOf(1, 2)) { key ->
                    if (key == 2) throw IllegalStateException("boom") else key
                }
            }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals("boom", error?.message)
        } finally {
            launcher.shutdown()
        }
    }

    @Test
    fun `a canceled process is reported by its own exception`() {
        val launcher = PoolLauncher(2)
        try {
            val error = runCatching {
                ParallelJobs(launcher, 2).map(listOf(1, 2)) { key ->
                    if (key == 2) throw ProcessCanceledException() else key
                }
            }.exceptionOrNull()
            assertTrue(error is ProcessCanceledException)
        } finally {
            launcher.shutdown()
        }
    }

    @Test
    fun `an empty key list runs nothing`() {
        val calls = AtomicInteger()
        val result = ParallelJobs(TaskLauncher.SAME_THREAD).map(emptyList<String>()) { calls.incrementAndGet() }
        assertTrue(result.isEmpty())
        assertEquals(0, calls.get())
    }

    @Test
    fun `jobs of one window run at the same time`() {
        val launcher = PoolLauncher(4)
        try {
            val allStarted = CountDownLatch(4)
            val result = ParallelJobs(launcher, 4).map((1..4).toList()) {
                allStarted.countDown()
                allStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)
            }
            assertEquals(List(4) { true }, result)
        } finally {
            launcher.shutdown()
        }
    }

    @Test
    fun `a slow job does not keep the free slots idle`() {
        val launcher = PoolLauncher(4)
        try {
            val laterStarted = CountDownLatch(3)
            val running = AtomicInteger()
            val peak = AtomicInteger()
            val result = ParallelJobs(launcher, 2).map((1..4).toList()) { key ->
                val atOnce = running.incrementAndGet()
                peak.accumulateAndGet(atOnce) { seen, now -> maxOf(seen, now) }
                try {
                    if (key == 1) {
                        laterStarted.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    } else {
                        laterStarted.countDown()
                        true
                    }
                } finally {
                    running.decrementAndGet()
                }
            }
            assertEquals(List(4) { true }, result)
            assertTrue("more than 2 jobs ran at once: ${peak.get()}", peak.get() <= 2)
        } finally {
            launcher.shutdown()
        }
    }

    @Test
    fun `a failure cancels the jobs that are still running`() {
        val launcher = PoolLauncher(4)
        try {
            val bothRunning = CountDownLatch(2)
            val canceled = CountDownLatch(2)
            val ranToTheEnd = AtomicBoolean(false)
            val result = ParallelJobs(launcher, 4).map((1..3).toList(), { it < 0 }) { key ->
                if (key == 1) {
                    bothRunning.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    -1
                } else {
                    bothRunning.countDown()
                    try {
                        Thread.sleep(TimeUnit.SECONDS.toMillis(WAIT_SECONDS * 3))
                        ranToTheEnd.set(true)
                    } catch (e: InterruptedException) {
                        canceled.countDown()
                    }
                    key
                }
            }
            assertEquals(listOf(-1), result)
            assertTrue(canceled.await(WAIT_SECONDS, TimeUnit.SECONDS))
            assertFalse(ranToTheEnd.get())
        } finally {
            launcher.shutdown()
        }
    }

    @Test
    fun `an interrupted collector stops and cancels the jobs`() {
        val launcher = PoolLauncher(2)
        try {
            val started = CountDownLatch(1)
            val canceled = CountDownLatch(1)
            val thrown = AtomicReference<Throwable?>()
            val flagKept = AtomicBoolean(false)
            val collector = Thread {
                try {
                    ParallelJobs(launcher, 2).map(listOf(1)) { key ->
                        started.countDown()
                        try {
                            Thread.sleep(TimeUnit.SECONDS.toMillis(WAIT_SECONDS * 3))
                        } catch (e: InterruptedException) {
                            canceled.countDown()
                        }
                        key
                    }
                } catch (e: Throwable) {
                    thrown.set(e)
                    flagKept.set(Thread.currentThread().isInterrupted)
                }
            }
            collector.start()
            assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS))
            collector.interrupt()
            collector.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
            assertTrue(canceled.await(WAIT_SECONDS, TimeUnit.SECONDS))
            assertTrue(thrown.get() is InterruptedException)
            assertTrue(flagKept.get())
        } finally {
            launcher.shutdown()
        }
    }

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
