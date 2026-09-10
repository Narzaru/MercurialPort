package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HgServerPoolTest {

    private class FakeServer(private val name: String) : HgServer {

        var closeCalls = 0
        var alive = true
        override var idleSinceNanos = 0L

        override val isUsable: Boolean get() = alive

        override fun run(args: List<String>): HgServerResponse = throw UnsupportedOperationException()

        override fun close() {
            closeCalls++
            alive = false
        }

        override fun toString(): String = name
    }

    private class FakeClock {

        var nanos = 0L

        fun advance(minutes: Long) {
            nanos += TimeUnit.MINUTES.toNanos(minutes)
        }

        fun read(): Long = nanos
    }

    private val root = "D:\\repo"
    private val clock = FakeClock()
    private val pool = HgServerPool(limit = 3, clock = clock::read)

    private fun fill(count: Int): List<Pair<HgServerClaim, FakeServer>> = (1..count).map {
        val claim = pool.claim(root)
        assertTrue("claim $it should start a server", claim.startNeeded)
        val server = FakeServer("server $it")
        assertTrue(pool.started(claim, server))
        claim to server
    }

    @Test
    fun `the pool starts servers up to the limit and then refuses`() {
        fill(3)

        val extra = pool.claim(root)

        assertNull(extra.server)
        assertFalse(extra.startNeeded)
        assertEquals(3, pool.liveCount(root))
    }

    @Test
    fun `a released server is handed out again instead of a new one`() {
        val (claim, server) = fill(1).single()
        assertTrue(pool.release(claim, server))

        val again = pool.claim(root)

        assertSame(server, again.server)
        assertFalse(again.startNeeded)
        assertEquals(1, pool.liveCount(root))
    }

    @Test
    fun `a released server that died is not pooled`() {
        val (claim, server) = fill(1).single()
        server.alive = false

        assertFalse(pool.release(claim, server))
        assertEquals(0, pool.liveCount(root))
    }

    @Test
    fun `a dead idle server is handed back for closing rather than reused`() {
        val (claim, server) = fill(1).single()
        pool.release(claim, server)
        server.alive = false

        val again = pool.claim(root)

        assertNull(again.server)
        assertTrue(again.startNeeded)
        assertEquals(listOf<HgServer>(server), again.stale)
    }

    @Test
    fun `closing a repository takes servers that are in use as well`() {
        val leases = fill(3)
        pool.release(leases[0].first, leases[0].second)

        val closing = pool.closeAll()

        assertEquals(3, closing.size)
        assertEquals(0, pool.liveCount(root))
    }

    @Test
    fun `a server released after its repository was closed is not resurrected`() {
        val leases = fill(3)
        pool.closeAll()

        assertFalse(pool.release(leases[0].first, leases[0].second))
        pool.discard(leases[1].first, leases[1].second)

        assertEquals(0, pool.liveCount(root))
        assertEquals(emptyList<String>(), pool.roots())
    }

    @Test
    fun `the limit still holds after a repository was closed while servers were in use`() {
        val leases = fill(3)
        pool.closeAll()
        leases.forEach { pool.release(it.first, it.second) }
        leases.forEach { pool.discard(it.first, it.second) }

        fill(3)

        assertEquals(3, pool.liveCount(root))
        assertFalse(pool.claim(root).startNeeded)
    }

    @Test
    fun `a start that failed keeps the repository off servers for the backoff window`() {
        val claim = pool.claim(root)
        assertFalse(pool.started(claim, null))

        val refused = pool.claim(root)
        assertFalse(refused.startNeeded)

        clock.advance(6)

        assertTrue(pool.claim(root).startNeeded)
    }

    @Test
    fun `a start that was abandoned frees the slot without a backoff`() {
        val claim = pool.claim(root)

        pool.abandonStart(claim)

        assertEquals(0, pool.liveCount(root))
        assertTrue(pool.claim(root).startNeeded)
    }

    @Test
    fun `a server that arrives after its repository was closed is refused`() {
        val claim = pool.claim(root)
        pool.closeAll()

        assertFalse(pool.started(claim, FakeServer("late")))
        assertEquals(0, pool.liveCount(root))
    }

    @Test
    fun `idle servers are evicted once they have stood unused long enough`() {
        val leases = fill(2)
        leases.forEach { pool.release(it.first, it.second) }
        leases[0].second.idleSinceNanos = clock.read()
        clock.advance(6)
        leases[1].second.idleSinceNanos = clock.read()

        val evicted = pool.evictIdle()

        assertEquals(listOf<HgServer>(leases[0].second), evicted)
        assertEquals(1, pool.liveCount(root))
    }

    @Test
    fun `eviction also removes idle servers whose process is gone`() {
        val (claim, server) = fill(1).single()
        pool.release(claim, server)
        server.idleSinceNanos = clock.read()
        server.alive = false

        assertEquals(listOf<HgServer>(server), pool.evictIdle())
        assertEquals(0, pool.liveCount(root))
    }

    @Test
    fun `discarding a server of an older generation leaves the current one untouched`() {
        val (staleClaim, staleServer) = fill(1).single()
        pool.closeAll()
        val (freshClaim, freshServer) = fill(1).single()

        pool.discard(staleClaim, staleServer)

        assertEquals(1, pool.liveCount(root))
        assertTrue(pool.release(freshClaim, freshServer))
        assertSame(freshServer, pool.claim(root).server)
    }

    @Test
    fun `claiming, starting and closing from many threads keeps the count sound`() {
        val busy = HgServerPool(limit = 3, clock = clock::read)
        val threads = 8
        val rounds = 200
        val ready = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        val failures = AtomicInteger()

        repeat(threads) { index ->
            Thread {
                ready.countDown()
                repeat(rounds) { round ->
                    try {
                        val claim = busy.claim(root)
                        val reused = claim.server
                        when {
                            reused != null -> busy.release(claim, reused)
                            claim.startNeeded -> {
                                val server = FakeServer("thread $index round $round")
                                if (busy.started(claim, server)) busy.release(claim, server)
                            }
                        }
                        if (round % 25 == index) busy.closeAll()
                    } catch (e: Throwable) {
                        failures.incrementAndGet()
                    }
                }
                done.countDown()
            }.start()
        }
        ready.await(10, TimeUnit.SECONDS)
        assertTrue(done.await(30, TimeUnit.SECONDS))

        assertEquals(0, failures.get())
        assertTrue("live count went out of range", busy.liveCount(root) in 0..3)
        busy.closeAll()
        assertEquals(0, busy.liveCount(root))
        assertEquals(emptyList<String>(), busy.roots())
    }

    @Test
    fun `shrinking the pool closes the servers that stand idle`() {
        val leases = fill(3)
        leases.forEach { pool.release(it.first, it.second) }

        val closing = pool.resize(1)

        assertEquals(2, closing.size)
        assertEquals(1, pool.liveCount(root))
        assertEquals(1, pool.limit())
    }

    @Test
    fun `shrinking the pool while servers are busy closes them as they come back`() {
        val leases = fill(3)

        assertEquals(emptyList<HgServer>(), pool.resize(1))
        assertEquals(3, pool.liveCount(root))

        assertFalse(pool.release(leases[0].first, leases[0].second))
        assertFalse(pool.release(leases[1].first, leases[1].second))
        assertTrue(pool.release(leases[2].first, leases[2].second))

        assertEquals(1, pool.liveCount(root))
        assertSame(leases[2].second, pool.claim(root).server)
    }

    @Test
    fun `a server returned to a shrunken pool does not bring the pool back over the limit`() {
        val (claim, server) = fill(1).single()
        pool.resize(1)

        pool.release(claim, server)
        val extra = pool.claim(root)

        assertSame(server, extra.server)
        assertFalse(pool.claim(root).startNeeded)
    }

    @Test
    fun `growing the pool lets the next claim start another server`() {
        val small = HgServerPool(limit = 1, clock = clock::read)
        val claim = small.claim(root)
        small.started(claim, FakeServer("only"))
        assertFalse(small.claim(root).startNeeded)

        small.resize(3)

        assertTrue(small.claim(root).startNeeded)
    }

    @Test
    fun `the pool size is kept inside the bounds it offers`() {
        pool.resize(0)
        assertEquals(HgServerPool.MIN_SERVERS, pool.limit())

        pool.resize(99)
        assertEquals(HgServerPool.MAX_SERVERS, pool.limit())
    }

    @Test
    fun `a claim refused during the backoff window says so`() {
        val claim = pool.claim(root)
        pool.started(claim, null)

        val refused = pool.claim(root)

        assertTrue(refused.refused)

        clock.advance(6)

        assertFalse(fill(1).single().first.refused)
    }

    @Test
    fun `a claim that only ran out of servers is not a refusal`() {
        fill(3)

        val full = pool.claim(root)

        assertFalse(full.refused)
        assertFalse(full.startNeeded)
    }

    @Test
    fun `closing one repository leaves the other alone`() {
        val other = "D:\\other"
        fill(1)
        val otherClaim = pool.claim(other)
        pool.started(otherClaim, FakeServer("other"))

        pool.close(listOf(root))

        assertEquals(0, pool.liveCount(root))
        assertEquals(1, pool.liveCount(other))
        assertEquals(listOf(other), pool.roots())
    }
}
