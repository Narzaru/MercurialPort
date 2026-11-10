package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HgServerLeaseTest {

    private class FakeServer(private val name: String) : HgServer {

        var alive = true

        override val isUsable: Boolean get() = alive
        override val idleSinceNanos: Long = 0L

        override fun run(args: List<String>): HgServerResponse = throw UnsupportedOperationException()

        override fun close() {
            alive = false
        }

        override fun toString(): String = name
    }

    private val root = "D:\\repo"
    private val pool = HgServerPool(limit = 1, clock = { 0L })
    private val closed = ArrayList<HgServer>()

    private fun lease(ticket: HgServerTicket, start: () -> HgServer?): HgWaitAttempt<HgServerLease> =
        pool.lease(ticket, start) { closed.addAll(it) }

    private fun noStart(): HgServer = throw AssertionError("no server should be started")

    @Test
    fun `a started server is leased and counted`() {
        val server = FakeServer("fresh")

        val leased = lease(pool.queue(root)) { server }.value

        assertSame(server, leased?.server)
        assertEquals(1, pool.liveCount(root))
    }

    @Test
    fun `a server standing idle is leased without starting another`() {
        val first = lease(pool.queue(root)) { FakeServer("first") }.value!!
        pool.release(first.claim, first.server)

        val again = lease(pool.queue(root), ::noStart).value

        assertSame(first.server, again?.server)
    }

    @Test
    fun `an interrupted start frees its slot without the failure back-off`() {
        val ticket = pool.queue(root)

        val thrown = runCatching { lease(ticket) { throw InterruptedException() } }.exceptionOrNull()

        assertTrue(thrown is InterruptedException)
        assertEquals(0, pool.liveCount(root))
        val retry = pool.claim(root)
        assertFalse(retry.refused)
        assertTrue(retry.startNeeded)
    }

    @Test
    fun `an interrupted start lets the next waiter through`() {
        val ticket = pool.queue(root)
        val next = pool.queue(root)

        runCatching { lease(ticket) { throw InterruptedException() } }
        ticket.close()

        assertTrue(next.await(0))
        assertTrue(pool.claim(next).startNeeded)
    }

    @Test
    fun `a start that fails keeps the repository off servers for the back-off window`() {
        val attempt = lease(pool.queue(root)) { null }

        assertNull(attempt.value)
        assertFalse(attempt.busy)
        assertTrue(pool.claim(root).refused)
    }

    @Test
    fun `a start that loses to closing keeps its place at the head of the queue`() {
        val ticket = pool.queue(root)
        val late = FakeServer("late")
        lateinit var later: HgServerTicket

        val attempt = lease(ticket) {
            later = pool.queue(root)
            pool.closeAll()
            late
        }

        assertTrue(attempt.busy)
        assertEquals(listOf<HgServer>(late), closed)
        assertTrue(ticket.await(0))
        assertTrue(pool.claim(later).busy)
        assertTrue(pool.claim(ticket).startNeeded)
    }

    @Test
    fun `a server whose start completes after shutdown is closed rather than pooled`() {
        val late = FakeServer("late")

        val ticket = pool.queue(root)

        val attempt = lease(ticket) {
            pool.shutDown()
            late
        }

        assertNull(attempt.value)
        assertEquals(listOf<HgServer>(late), closed)
        assertEquals(0, pool.liveCount(root))
        val retry = lease(ticket, ::noStart)
        assertNull(retry.value)
        assertFalse(retry.busy)
    }

    @Test
    fun `a pool that was shut down refuses every claim`() {
        val held = lease(pool.queue(root)) { FakeServer("held") }.value!!

        val closing = pool.shutDown()

        assertEquals(listOf(held.server), closing)
        assertTrue(pool.isShutDown)
        val attempt = lease(pool.queue(root), ::noStart)
        assertNull(attempt.value)
        assertFalse(attempt.busy)
        assertFalse(pool.release(held.claim, held.server))
        assertEquals(emptyList<String>(), pool.roots())
    }

    @Test
    fun `closing every server does not shut the pool down`() {
        pool.closeAll()

        assertFalse(pool.isShutDown)
        assertTrue(pool.claim(root).startNeeded)
    }
}
