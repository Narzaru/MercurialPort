package com.narzaru.mercurial.changes

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

interface TaskLauncher {

    fun <T> launch(task: Callable<T>): Future<T>

    companion object {

        val SAME_THREAD: TaskLauncher = object : TaskLauncher {
            override fun <T> launch(task: Callable<T>): Future<T> =
                CompletableFuture.completedFuture(task.call())
        }
    }
}

class ParallelJobs(private val launcher: TaskLauncher, private val limit: Int = MAX_PARALLEL) {

    fun <K, V> map(keys: List<K>, work: (K) -> V): List<V> = map(keys, { false }, work)

    fun <K, V> map(keys: List<K>, isFailure: (V) -> Boolean, work: (K) -> V): List<V> {
        if (keys.isEmpty()) return emptyList()
        val window = SlidingWindow(keys, limit.coerceAtLeast(1), launcher, work)
        val done = ArrayList<V>(keys.size)
        try {
            while (done.size < keys.size) {
                val value = window.next()
                done.add(value)
                if (isFailure(value)) break
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } finally {
            window.cancelPending()
        }
        return done
    }

    private class SlidingWindow<K, V>(
        private val keys: List<K>,
        private val limit: Int,
        private val launcher: TaskLauncher,
        private val work: (K) -> V
    ) {

        private val lock = Object()
        private val futures = MutableList<Future<V>?>(keys.size) { null }
        private var launched = 0
        private var inFlight = 0
        private var consumed = 0

        fun next(): V {
            var index = indexToLaunch()
            while (index != NOTHING_TO_LAUNCH) {
                futures[index] = start(index)
                index = indexToLaunch()
            }
            val value = await(futures[consumed]!!)
            futures[consumed] = null
            consumed++
            return value
        }

        fun cancelPending() {
            for (future in futures) future?.cancel(true)
        }

        private fun indexToLaunch(): Int {
            var index = NOTHING_TO_LAUNCH
            synchronized(lock) {
                while (index == NOTHING_TO_LAUNCH) {
                    if (futures[consumed]?.isDone == true) break
                    if (launched == keys.size) break
                    if (inFlight < limit) {
                        inFlight++
                        index = launched++
                    } else {
                        lock.wait()
                    }
                }
            }
            return index
        }

        private fun start(index: Int): Future<V> {
            val key = keys[index]
            return launcher.launch(
                Callable {
                    try {
                        work(key)
                    } finally {
                        synchronized(lock) {
                            inFlight--
                            lock.notifyAll()
                        }
                    }
                }
            )
        }

        private fun await(future: Future<V>): V = try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

        private companion object {
            const val NOTHING_TO_LAUNCH = -1
        }
    }

    companion object {
        const val MAX_PARALLEL = 6
    }
}
