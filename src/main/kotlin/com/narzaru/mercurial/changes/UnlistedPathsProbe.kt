package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgFailure
import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.hg.HgResult
import com.narzaru.mercurial.hg.HgStatusParser
import com.narzaru.mercurial.model.HgFileStatus

sealed interface UnlistedPathsAction {

    data object Ignore : UnlistedPathsAction

    data object Reload : UnlistedPathsAction

    data class Ask(val keys: List<String>) : UnlistedPathsAction
}

sealed interface UnlistedAnswer {

    data class Failed(val details: String) : UnlistedAnswer

    data object Listable : UnlistedAnswer

    data object Quiet : UnlistedAnswer
}

object UnlistedPathsProbe {

    const val MAX_ASKED_PATHS = DiffStatsPlan.MAX_DIFF_PATHS

    const val MAX_WAITING_PATHS = 5000

    const val MAX_QUIET_PATHS = 2000

    const val UNFINISHED = "hg status did not finish"

    private val LISTABLE = setOf(
        HgFileStatus.MODIFIED,
        HgFileStatus.ADDED,
        HgFileStatus.REMOVED,
        HgFileStatus.MISSING
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

    fun answer(result: HgResult?, untracked: Boolean): UnlistedAnswer = when {
        result == null -> UnlistedAnswer.Failed(UNFINISHED)
        !result.success -> UnlistedAnswer.Failed(HgFailure.message(result))
        listable(result.stdout, untracked) -> UnlistedAnswer.Listable
        else -> UnlistedAnswer.Quiet
    }

    fun remembered(quiet: Set<String>, keys: Collection<String>): Set<String> {
        val next = LinkedHashSet(quiet)
        next.addAll(keys)
        if (next.size <= MAX_QUIET_PATHS) return next
        return next.asSequence().drop(next.size - MAX_QUIET_PATHS).toCollection(LinkedHashSet())
    }

    private fun matters(status: HgFileStatus, untracked: Boolean): Boolean =
        status in LISTABLE || (untracked && status == HgFileStatus.UNTRACKED)
}
