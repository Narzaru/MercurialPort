package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HgServerQueueTest {

    private class FakeServer(private val name: String) : HgServer {

        override val isUsable: Boolean = true
        override val idleSinceNanos: Long = 0L

        override fun run(args: List<String>): HgServerResponse = throw UnsupportedOperationException()

        override fun close() {}

        override fun toString(): String = name
    }

    private class Lease(val claim: HgServerClaim, val server: HgServer)

    private val root = "D:\\repo"
    private val pool = HgServerPool(limit = 1, clock = { 0L })

    private fun occupy(): Lease {
        val claim = pool.claim(root)
        assertTrue(claim.startNeeded)
        val server = FakeServer("busy")
        assertTrue(pool.started(claim, server))
        return Lease(claim, server)
    }

    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + PATIENCE_NANOS
        while (!condition()) {
            assertTrue("the condition was not reached in time", System.nanoTime() < deadline)
            Thread.yield()
        }
    }

    private inner class Waiter(private val ticket: HgServerTicket) {

        val woke = AtomicReference<Boolean>()
        val claim = AtomicReference<HgServerClaim>()
        val failure = AtomicReference<Throwable>()

        private val thread = Thread {
            try {
                ticket.use {
                    assertTrue(pool.claim(ticket).busy)
                    woke.set(ticket.await(PATIENCE_NANOS))
                    claim.set(pool.claim(ticket))
                }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }.also {
            it.isDaemon = true
            it.start()
        }

        fun parked(): Waiter {
            until { pool.hasParkedWaiter(ticket) }
            return this
        }

        fun interrupt() = thread.interrupt()

        fun finish(): Waiter {
            thread.join(TimeUnit.NANOSECONDS.toMillis(PATIENCE_NANOS))
            assertFalse("the waiter is still blocked", thread.isAlive)
            return this
        }

        fun rethrow(): Waiter {
            failure.get()?.let { throw it }
            return this
        }
    }

    @Test
    fun `a server that comes free goes to the ticket that queued first`() {
        val busy = occupy()
        val first = pool.queue(root)
        val second = pool.queue(root)
        assertTrue(pool.claim(first).busy)
        assertTrue(pool.claim(second).busy)

        pool.release(busy.claim, busy.server)

        assertTrue(pool.claim(second).busy)
        val served = pool.claim(first)
        assertSame(busy.server, served.server)
        pool.release(served, served.server!!)
        assertSame(busy.server, pool.claim(second).server)
    }

    @Test
    fun `a claim made without a ticket does not jump the queue`() {
        val busy = occupy()
        val queued = pool.queue(root)
        assertTrue(pool.claim(queued).busy)

        pool.release(busy.claim, busy.server)

        assertTrue(pool.claim(root).busy)
        assertSame(busy.server, pool.claim(queued).server)
    }

    @Test
    fun `only the head of the queue is woken when a server comes free`() {
        val busy = occupy()
        val first = pool.queue(root)
        val second = pool.queue(root)
        pool.claim(first)
        pool.claim(second)

        pool.release(busy.claim, busy.server)

        assertFalse(second.await(0))
        assertTrue(first.await(0))
    }

    @Test
    fun `a waiter that leaves hands the turn to the next one`() {
        occupy()
        val first = pool.queue(root)
        val second = pool.queue(root)
        pool.claim(first)
        pool.claim(second)

        first.close()

        assertTrue(second.await(0))
    }

    @Test
    fun `a release between the busy claim and the wait is not lost`() {
        val busy = occupy()
        val ticket = pool.queue(root)
        assertTrue(pool.claim(ticket).busy)

        pool.release(busy.claim, busy.server)

        assertTrue(ticket.await(PATIENCE_NANOS))
        assertSame(busy.server, pool.claim(ticket).server)
    }

    @Test
    fun `a wait with nothing released ends when its time is up`() {
        occupy()
        val ticket = pool.queue(root)
        assertTrue(pool.claim(ticket).busy)

        assertFalse(ticket.await(TimeUnit.MILLISECONDS.toNanos(20)))
        assertTrue(pool.claim(ticket).busy)
    }

    @Test
    fun `a parked waiter wakes when a server is released`() {
        val busy = occupy()
        val waiter = Waiter(pool.queue(root)).parked()

        pool.release(busy.claim, busy.server)

        waiter.finish().rethrow()
        assertTrue(waiter.woke.get())
        assertSame(busy.server, waiter.claim.get().server)
    }

    @Test
    fun `a parked waiter wakes when a server is discarded`() {
        val busy = occupy()
        val waiter = Waiter(pool.queue(root)).parked()

        pool.discard(busy.claim, busy.server)

        waiter.finish().rethrow()
        assertTrue(waiter.woke.get())
        assertTrue(waiter.claim.get().startNeeded)
    }

    @Test
    fun `a parked waiter learns at once that a server failed to start`() {
        val starting = pool.claim(root)
        assertTrue(starting.startNeeded)
        val waiter = Waiter(pool.queue(root)).parked()

        pool.started(starting, null)

        waiter.finish().rethrow()
        assertTrue(waiter.woke.get())
        assertTrue(waiter.claim.get().refused)
    }

    @Test
    fun `a parked waiter wakes when the repository's servers are closed`() {
        occupy()
        val waiter = Waiter(pool.queue(root)).parked()

        pool.closeAll()

        waiter.finish().rethrow()
        assertTrue(waiter.woke.get())
        assertTrue(waiter.claim.get().startNeeded)
    }

    @Test
    fun `a parked waiter wakes when the pool grows`() {
        occupy()
        val waiter = Waiter(pool.queue(root)).parked()

        pool.resize(2)

        waiter.finish().rethrow()
        assertTrue(waiter.woke.get())
        assertTrue(waiter.claim.get().startNeeded)
    }

    @Test
    fun `an interrupted waiter returns at once and gives up its place`() {
        val busy = occupy()
        val waiter = Waiter(pool.queue(root)).parked()
        val next = pool.queue(root)

        waiter.interrupt()

        waiter.finish()
        assertTrue(waiter.failure.get() is InterruptedException)
        pool.release(busy.claim, busy.server)
        assertSame(busy.server, pool.claim(next).server)
    }

    @Test
    fun `parked waiters are served in the order they queued, not the order they started`() {
        val busy = occupy()
        val tickets = (1..4).map { pool.queue(root) }
        val served = Collections.synchronizedList(ArrayList<Int>())
        val failure = AtomicReference<Throwable>()
        val threads = tickets.withIndex().reversed().map { (index, ticket) ->
            Thread {
                try {
                    ticket.use {
                        while (true) {
                            val claim = pool.claim(ticket)
                            val server = claim.server
                            if (server != null) {
                                served.add(index)
                                pool.release(claim, server)
                                break
                            }
                            ticket.await(PATIENCE_NANOS)
                        }
                    }
                } catch (e: Throwable) {
                    failure.set(e)
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
        }
        tickets.forEach { ticket -> until { pool.hasParkedWaiter(ticket) } }

        pool.release(busy.claim, busy.server)

        threads.forEach {
            it.join(TimeUnit.NANOSECONDS.toMillis(PATIENCE_NANOS))
            assertFalse("a waiter is still blocked", it.isAlive)
        }
        failure.get()?.let { throw it }
        assertEquals(listOf(0, 1, 2, 3), served.toList())
    }

    @Test
    fun `a parked waiter wakes and is refused when the pool shuts down`() {
        occupy()
        val waiter = Waiter(pool.queue(root)).parked()

        pool.shutDown()

        waiter.finish().rethrow()
        assertTrue(waiter.woke.get())
        assertTrue(waiter.claim.get().refused)
    }

    @Test
    fun `the wait leases a server that another thread gives back`() {
        val busy = occupy()
        val wait = HgServerWait(checkCanceled = {}, waitAllowed = { true })
        val leased = AtomicReference<HgServer>()
        val ticket = pool.queue(root)
        val thread = Thread {
            ticket.use {
                leased.set(wait.untilReady(ticket) {
                    val claim = pool.claim(ticket)
                    claim.server?.let { HgWaitAttempt.ready(it) } ?: HgWaitAttempt.busy()
                })
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
        until { pool.hasParkedWaiter(ticket) }

        pool.release(busy.claim, busy.server)

        thread.join(TimeUnit.NANOSECONDS.toMillis(PATIENCE_NANOS))
        assertFalse("the waiter is still blocked", thread.isAlive)
        assertNotNull(leased.get())
        assertSame(busy.server, leased.get())
    }

    companion object {

        private val PATIENCE_NANOS = TimeUnit.SECONDS.toNanos(10)
    }
}
