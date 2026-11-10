package com.narzaru.mercurial.hg

import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface HgServer : AutoCloseable {

    val isUsable: Boolean

    val idleSinceNanos: Long

    fun run(args: List<String>): HgServerResponse

    override fun close()
}

class HgServerClaim internal constructor(
    val root: String,
    internal val generation: Int,
    val server: HgServer?,
    val startNeeded: Boolean,
    val stale: List<HgServer>,
    val refused: Boolean = false
) {
    val busy: Boolean get() = server == null && !startNeeded && !refused
}

class HgServerLease internal constructor(val claim: HgServerClaim, val server: HgServer)

class HgServerTicket internal constructor(
    private val pool: HgServerPool,
    val root: String,
    internal val wake: Condition
) : HgServerTurn, AutoCloseable {

    internal var queued = false
    internal var notified = false

    override fun await(nanos: Long): Boolean = pool.await(this, nanos)

    override fun close() = pool.leave(this)
}

class HgServerPool(
    limit: Int = DEFAULT_SERVERS,
    private val retryNanos: Long = DEFAULT_RETRY_NANOS,
    private val idleNanos: Long = DEFAULT_IDLE_NANOS,
    private val clock: () -> Long = System::nanoTime
) {

    private val lock = ReentrantLock()
    private val pools = HashMap<String, Repository>()
    private val queues = HashMap<String, ArrayDeque<HgServerTicket>>()
    private var nextGeneration = 1
    private var limit = limit.coerceIn(MIN_SERVERS, MAX_SERVERS)
    private var shutDown = false

    internal fun limit(): Int = lock.withLock { limit }

    val isShutDown: Boolean get() = lock.withLock { shutDown }

    fun shutDown(): List<HgServer> = lock.withLock {
        shutDown = true
        val closing = closeAll()
        queues.keys.forEach { notifyHead(it) }
        closing
    }

    fun lease(
        ticket: HgServerTicket,
        start: () -> HgServer?,
        close: (Collection<HgServer>) -> Unit
    ): HgWaitAttempt<HgServerLease> {
        val claim = claim(ticket)
        close(claim.stale)
        claim.server?.let { return HgWaitAttempt.ready(HgServerLease(claim, it)) }
        if (!claim.startNeeded) {
            return if (claim.refused) HgWaitAttempt.unavailable() else HgWaitAttempt.busy()
        }
        val server = try {
            start()
        } catch (e: Throwable) {
            abandonStart(claim)
            throw e
        }
        if (server == null) {
            started(claim, null)
            return HgWaitAttempt.unavailable()
        }
        if (!started(claim, server)) {
            close(listOf(server))
            rejoinFirst(ticket)
            return HgWaitAttempt.busy()
        }
        return HgWaitAttempt.ready(HgServerLease(claim, server))
    }

    fun resize(servers: Int): List<HgServer> {
        lock.withLock {
            val wanted = servers.coerceIn(MIN_SERVERS, MAX_SERVERS)
            if (wanted == limit) return emptyList()
            limit = wanted
            val evicted = trimIdle()
            queues.keys.forEach { notifyHead(it) }
            return evicted
        }
    }

    fun queue(root: String): HgServerTicket = lock.withLock {
        HgServerTicket(this, root, lock.newCondition()).also { enqueue(it) }
    }

    fun claim(root: String): HgServerClaim = lock.withLock {
        if (queues.containsKey(root)) busy(root) else claimFree(root)
    }

    fun claim(ticket: HgServerTicket): HgServerClaim {
        lock.withLock {
            ticket.notified = false
            if (!ticket.queued) enqueue(ticket)
            if (queues[ticket.root]?.peekFirst() !== ticket) return busy(ticket.root)
            val claim = claimFree(ticket.root)
            if (!claim.busy) leave(ticket)
            return claim
        }
    }

    internal fun await(ticket: HgServerTicket, nanos: Long): Boolean = lock.withLock {
        var remaining = nanos
        while (ticket.queued && !ticket.notified && remaining > 0) {
            remaining = ticket.wake.awaitNanos(remaining)
        }
        val woken = ticket.notified || !ticket.queued
        ticket.notified = false
        woken
    }

    internal fun leave(ticket: HgServerTicket) {
        lock.withLock {
            if (!ticket.queued) return
            ticket.queued = false
            val queue = queues[ticket.root] ?: return
            val wasHead = queue.peekFirst() === ticket
            queue.remove(ticket)
            if (queue.isEmpty()) queues.remove(ticket.root)
            if (wasHead) notifyHead(ticket.root)
        }
    }

    internal fun hasParkedWaiter(ticket: HgServerTicket): Boolean = lock.withLock { lock.hasWaiters(ticket.wake) }

    fun started(claim: HgServerClaim, server: HgServer?): Boolean {
        lock.withLock {
            val repository = pools[claim.root]
            val current = !shutDown && repository != null && repository.generation == claim.generation
            if (current) repository!!.pendingStarts--
            notifyHead(claim.root)
            if (server == null) {
                if (current) repository!!.failedAtNanos = clock()
                prune(claim.root)
                return false
            }
            if (!current) {
                prune(claim.root)
                return false
            }
            repository!!.failedAtNanos = null
            repository.borrowed.add(server)
            return true
        }
    }

    fun abandonStart(claim: HgServerClaim) {
        lock.withLock {
            val repository = pools[claim.root] ?: return
            if (repository.generation == claim.generation) repository.pendingStarts--
            prune(claim.root)
            notifyHead(claim.root)
        }
    }

    fun release(claim: HgServerClaim, server: HgServer): Boolean {
        lock.withLock {
            val repository = pools[claim.root]
            if (repository == null || repository.generation != claim.generation) return false
            repository.borrowed.remove(server)
            notifyHead(claim.root)
            if (!server.isUsable || repository.live >= limit) {
                prune(claim.root)
                return false
            }
            repository.idle.addLast(server)
            return true
        }
    }

    fun discard(claim: HgServerClaim, server: HgServer) {
        lock.withLock {
            val repository = pools[claim.root] ?: return
            if (repository.generation == claim.generation) repository.borrowed.remove(server)
            prune(claim.root)
            notifyHead(claim.root)
        }
    }

    fun roots(): List<String> = lock.withLock { pools.keys.toList() }

    internal fun liveCount(root: String): Int = lock.withLock { pools[root]?.live ?: 0 }

    fun close(roots: Collection<String>): List<HgServer> = lock.withLock {
        val closing = ArrayList<HgServer>()
        for (root in roots.toList()) {
            val repository = pools[root] ?: continue
            closing.addAll(repository.idle)
            closing.addAll(repository.borrowed)
            repository.idle.clear()
            repository.borrowed.clear()
            repository.pendingStarts = 0
            repository.failedAtNanos = null
            repository.generation = nextGeneration++
            pools.remove(root)
            notifyHead(root)
        }
        closing
    }

    fun closeAll(): List<HgServer> = lock.withLock { close(pools.keys.toList()) }

    fun evictIdle(): List<HgServer> = lock.withLock {
        val now = clock()
        val evicted = ArrayList<HgServer>()
        for (root in pools.keys.toList()) {
            val repository = pools.getValue(root)
            val kept = repository.idle.filter { now - it.idleSinceNanos < idleNanos && it.isUsable }
            if (kept.size == repository.idle.size) continue
            evicted.addAll(repository.idle.filterNot { server -> kept.any { it === server } })
            repository.idle.clear()
            kept.forEach { repository.idle.addLast(it) }
            prune(root)
            notifyHead(root)
        }
        evicted
    }

    private fun claimFree(root: String): HgServerClaim {
        if (shutDown) return HgServerClaim(root, 0, null, false, emptyList(), refused = true)
        val repository = pools.getOrPut(root) { Repository(nextGeneration++) }
        val failedAt = repository.failedAtNanos
        if (failedAt != null && clock() - failedAt < retryNanos) {
            return HgServerClaim(root, repository.generation, null, false, emptyList(), refused = true)
        }
        val stale = ArrayList<HgServer>()
        while (true) {
            val server = repository.idle.pollFirst() ?: break
            if (server.isUsable) return HgServerClaim(root, repository.generation, server, false, stale)
                .also { repository.borrowed.add(server) }
            stale.add(server)
        }
        if (repository.live >= limit) return HgServerClaim(root, repository.generation, null, false, stale)
        repository.pendingStarts++
        return HgServerClaim(root, repository.generation, null, true, stale)
    }

    private fun busy(root: String): HgServerClaim =
        HgServerClaim(root, pools[root]?.generation ?: 0, null, false, emptyList())

    private fun enqueue(ticket: HgServerTicket) {
        queues.getOrPut(ticket.root) { ArrayDeque() }.addLast(ticket)
        ticket.queued = true
    }

    private fun rejoinFirst(ticket: HgServerTicket) {
        lock.withLock {
            if (ticket.queued) return
            queues.getOrPut(ticket.root) { ArrayDeque() }.addFirst(ticket)
            ticket.queued = true
            ticket.notified = true
        }
    }

    private fun notifyHead(root: String) {
        val head = queues[root]?.peekFirst() ?: return
        head.notified = true
        head.wake.signal()
    }

    private fun trimIdle(): List<HgServer> {
        val evicted = ArrayList<HgServer>()
        for (root in pools.keys.toList()) {
            val repository = pools.getValue(root)
            while (repository.live > limit && repository.idle.isNotEmpty()) {
                evicted.add(repository.idle.pollLast())
            }
            prune(root)
        }
        return evicted
    }

    private fun prune(root: String) {
        val repository = pools[root] ?: return
        if (repository.live == 0 && repository.failedAtNanos == null) pools.remove(root)
    }

    private class Repository(var generation: Int) {

        val idle = ArrayDeque<HgServer>()
        val borrowed: MutableSet<HgServer> = Collections.newSetFromMap(IdentityHashMap())
        var pendingStarts = 0
        var failedAtNanos: Long? = null

        val live: Int get() = idle.size + borrowed.size + pendingStarts
    }

    companion object {

        const val MIN_SERVERS = 1
        const val MAX_SERVERS = 6
        const val DEFAULT_SERVERS = 1

        val DEFAULT_RETRY_NANOS: Long = TimeUnit.MINUTES.toNanos(5)
        val DEFAULT_IDLE_NANOS: Long = TimeUnit.MINUTES.toNanos(5)
    }
}
