package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.hg.HgStatusParser

sealed interface UnlistedPathsAction {

    data object Ignore : UnlistedPathsAction

    data object Reload : UnlistedPathsAction

    data class Ask(val keys: List<String>) : UnlistedPathsAction
}

object UnlistedPathsProbe {

    const val MAX_ASKED_PATHS = DiffStatsPlan.MAX_DIFF_PATHS

    const val MAX_WAITING_PATHS = 5000

    const val MAX_QUIET_PATHS = 2000

    private val LISTABLE = setOf(
        HgStatusParser.MODIFIED_STATUS,
        HgStatusParser.ADDED_STATUS,
        HgStatusParser.REMOVED_STATUS,
        HgStatusParser.MISSING_STATUS
    )

    fun of(keys: Collection<String>, quiet: Set<String>): UnlistedPathsAction {
        val asked = keys.asSequence()
            .map { HgPaths.key(it) }
            .filter { it.isNotEmpty() && it !in quiet }
            .distinct()
            .toList()
        if (asked.isEmpty()) return UnlistedPathsAction.Ignore
        if (asked.size > MAX_WAITING_PATHS) return UnlistedPathsAction.Reload
        return UnlistedPathsAction.Ask(asked.take(MAX_ASKED_PATHS))
    }

    fun arguments(keys: List<String>, untracked: Boolean): List<String> {
        val args = arrayListOf("status", HgStatusParser.statusFlags(untracked))
        keys.mapTo(args) { "path:$it" }
        return args
    }

    fun listable(stdout: String, untracked: Boolean): Boolean =
        HgStatusParser.parse(stdout).any { matters(it.status, untracked) }

    fun remembered(quiet: Set<String>, keys: Collection<String>): Set<String> {
        val next = LinkedHashSet(quiet)
        next.addAll(keys)
        if (next.size <= MAX_QUIET_PATHS) return next
        return next.asSequence().drop(next.size - MAX_QUIET_PATHS).toCollection(LinkedHashSet())
    }

    private fun matters(status: String, untracked: Boolean): Boolean =
        status in LISTABLE || (untracked && status == HgStatusParser.UNTRACKED_STATUS)
}
