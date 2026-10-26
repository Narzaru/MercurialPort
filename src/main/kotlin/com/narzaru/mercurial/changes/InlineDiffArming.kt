package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import java.util.concurrent.atomic.AtomicReference

class InlineDiffArming(private val clock: () -> Long = System::currentTimeMillis) {

    class Jump(val diffSourcePath: String, private val openPathsBefore: Set<String>) {

        fun wasOpenBefore(path: String): Boolean = openPathsBefore.contains(HgPaths.key(path))
    }

    private class Intent(val jump: Jump, val deadline: Long)

    private val intent = AtomicReference<Intent?>()

    fun arm(diffSourcePath: String, openPaths: Collection<String>) {
        val snapshot = openPaths.mapTo(HashSet()) { HgPaths.key(it) }
        intent.set(Intent(Jump(diffSourcePath, snapshot), clock() + DEADLINE_MS))
    }

    fun peek(): Jump? = live()?.jump

    fun take(): Jump? {
        val found = live()
        intent.set(null)
        return found?.jump
    }

    fun disarm() {
        intent.set(null)
    }

    private fun live(): Intent? {
        val found = intent.get() ?: return null
        if (found.deadline >= clock()) return found
        intent.compareAndSet(found, null)
        return null
    }

    companion object {
        private const val DEADLINE_MS = 10_000L
    }
}
