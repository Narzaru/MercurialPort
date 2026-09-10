package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgCallWatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LoadTrace(
    private val label: String,
    private val clock: () -> Long = { System.nanoTime() }
) : HgCallWatch {

    private val startedAt = clock()
    private val phaseNanos = LinkedHashMap<String, Long>()
    private val calls = AtomicInteger()
    private val servedCalls = AtomicInteger()
    private val callNanos = AtomicLong()

    fun <T> phase(name: String, work: () -> T): T {
        val began = clock()
        try {
            return work()
        } finally {
            addPhase(name, clock() - began)
        }
    }

    override fun callFinished(nanos: Long, viaServer: Boolean) {
        calls.incrementAndGet()
        if (viaServer) servedCalls.incrementAndGet()
        callNanos.addAndGet(nanos)
    }

    @Synchronized
    fun summary(): String {
        val wall = millis(clock() - startedAt)
        val hgCalls = calls.get()
        val hgMillis = millis(callNanos.get())
        val perCall = if (hgCalls == 0) 0L else hgMillis / hgCalls
        val served = servedCalls.get()
        val serverPart = if (served == 0) "" else ", $served of them on the command server"
        val head = "$label: $wall ms wall, $hgCalls hg calls, $hgMillis ms in hg, " +
            "$perCall ms per call$serverPart"
        if (phaseNanos.isEmpty()) return head
        val measured = millis(phaseNanos.values.sum())
        val details = phaseNanos.entries.joinToString(", ") { (name, nanos) -> "$name ${millis(nanos)} ms" }
        return "$head; phases $measured ms [$details]"
    }

    @Synchronized
    private fun addPhase(name: String, nanos: Long) {
        phaseNanos[name] = (phaseNanos[name] ?: 0L) + nanos
    }

    private fun millis(nanos: Long): Long = nanos / NANOS_PER_MILLI

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
