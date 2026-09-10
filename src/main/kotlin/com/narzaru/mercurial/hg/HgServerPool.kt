package com.narzaru.mercurial.hg

import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

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
)

class HgServerPool(
    limit: Int = DEFAULT_SERVERS,
    private val retryNanos: Long = DEFAULT_RETRY_NANOS,
    private val idleNanos: Long = DEFAULT_IDLE_NANOS,
    private val clock: () -> Long = System::nanoTime
) {

    private val pools = HashMap<String, Repository>()
    private var nextGeneration = 1
    private var limit = limit.coerceIn(MIN_SERVERS, MAX_SERVERS)

    @Synchronized
    fun limit(): Int = limit

    @Synchronized
    fun resize(servers: Int): List<HgServer> {
        val wanted = servers.coerceIn(MIN_SERVERS, MAX_SERVERS)
        if (wanted == limit) return emptyList()
        limit = wanted
        return trimIdle()
    }

    @Synchronized
    fun claim(root: String): HgServerClaim {
        val repository = pools.getOrPut(root) { Repository(nextGeneration++) }
        val failedAt = repository.failedAtNanos
        if (failedAt != null && clock() - failedAt < retryNanos) {
            return refused(root, repository)
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

    @Synchronized
    fun started(claim: HgServerClaim, server: HgServer?): Boolean {
        val repository = pools[claim.root]
        val current = repository != null && repository.generation == claim.generation
        if (current) repository!!.pendingStarts--
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

    @Synchronized
    fun abandonStart(claim: HgServerClaim) {
        val repository = pools[claim.root] ?: return
        if (repository.generation == claim.generation) repository.pendingStarts--
        prune(claim.root)
    }

    @Synchronized
    fun release(claim: HgServerClaim, server: HgServer): Boolean {
        val repository = pools[claim.root]
        if (repository == null || repository.generation != claim.generation) return false
        repository.borrowed.remove(server)
        if (!server.isUsable || repository.live >= limit) {
            prune(claim.root)
            return false
        }
        repository.idle.addLast(server)
        return true
    }

    @Synchronized
    fun discard(claim: HgServerClaim, server: HgServer) {
        val repository = pools[claim.root] ?: return
        if (repository.generation == claim.generation) repository.borrowed.remove(server)
        prune(claim.root)
    }

    @Synchronized
    fun roots(): List<String> = pools.keys.toList()

    @Synchronized
    fun liveCount(root: String): Int = pools[root]?.live ?: 0

    @Synchronized
    fun close(roots: Collection<String>): List<HgServer> {
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
        }
        return closing
    }

    @Synchronized
    fun closeAll(): List<HgServer> = close(pools.keys.toList())

    @Synchronized
    fun evictIdle(): List<HgServer> {
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
        }
        return evicted
    }

    private fun refused(root: String, repository: Repository): HgServerClaim =
        HgServerClaim(root, repository.generation, null, false, emptyList(), refused = true)

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
