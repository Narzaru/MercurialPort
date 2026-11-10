package com.narzaru.mercurial.changes

import java.util.Collections

sealed interface FileChangeCommand {

    data class ReloadDiffTabs(val keys: List<String>) : FileChangeCommand

    data object Refresh : FileChangeCommand

    data class Recount(val keys: List<String>) : FileChangeCommand

    data object RescanTodos : FileChangeCommand

    data class ProbeRepoState(val stamp: Int) : FileChangeCommand

    data class AskUnlisted(val keys: List<String>, val stamp: Int) : FileChangeCommand

    data class ReportUnchecked(val details: String) : FileChangeCommand

    data object RetryLater : FileChangeCommand
}

data class PanelConditions(
    val busy: Boolean,
    val hasRepo: Boolean,
    val listsTodos: Boolean = false
)

class FileChangeCoordinator {

    private val changedPaths: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile
    private var todoRescanPending = false

    private var refreshPending = false

    private var repoStateProbePending = false

    private var repoStateRunning = false

    private var probeRunning = false

    private var probeId = 0

    private var knownRepoState = RepoState.UNKNOWN

    private var quietKeys: Set<String> = emptySet()

    private val pendingUnlistedKeys = LinkedHashSet<String>()

    val workPending: Boolean
        get() = refreshPending || repoStateProbePending || todoRescanPending ||
            pendingUnlistedKeys.isNotEmpty() || changedPaths.isNotEmpty()

    fun pathsChanged(paths: Collection<String>) {
        changedPaths.addAll(paths)
    }

    fun todoRescanRequested() {
        todoRescanPending = true
    }

    fun takeChangedPaths(): List<String> = synchronized(changedPaths) {
        val paths = ArrayList(changedPaths)
        changedPaths.clear()
        paths
    }

    fun loaded(state: RepoState) {
        knownRepoState = state
    }

    fun reset() {
        probeId++
        probeRunning = false
        repoStateRunning = false
        repoStateProbePending = false
        refreshPending = false
        todoRescanPending = false
        pendingUnlistedKeys.clear()
        quietKeys = emptySet()
        changedPaths.clear()
    }

    fun react(reaction: FileChangeReaction, conditions: PanelConditions): List<FileChangeCommand> {
        val out = ArrayList<FileChangeCommand>()
        val rescanRequested = todoRescanPending
        todoRescanPending = false
        if (reaction.diffKeys.isNotEmpty()) out.add(FileChangeCommand.ReloadDiffTabs(reaction.diffKeys))
        if (reaction.ignoreRulesChanged) {
            quietKeys = emptySet()
            refreshPending = true
        }
        if (reaction.repoStateTouched) repoStateProbePending = true
        pendingUnlistedKeys.addAll(reaction.unlistedKeys)
        if (refreshPending) {
            refreshPending = false
            if (requestRefresh(conditions.busy, out)) return out
        }
        out.add(FileChangeCommand.Recount(reaction.changedKeys))
        if ((rescanRequested || reaction.rescanTodos) && conditions.listsTodos) {
            if (conditions.busy) todoRescanPending = true else out.add(FileChangeCommand.RescanTodos)
        }
        probeRepoState(conditions, out)
        probeUnlistedPaths(conditions, out)
        if (workPending) retryLater(out)
        return out
    }

    fun repoStateProbed(
        probe: FileChangeCommand.ProbeRepoState,
        state: RepoState,
        busy: Boolean
    ): List<FileChangeCommand> {
        val out = ArrayList<FileChangeCommand>()
        repoStateRunning = false
        if (probe.stamp != probeId || !state.isKnown) return out
        val moved = RepoState.changed(knownRepoState, state)
        knownRepoState = state
        if (moved) requestRefresh(busy, out)
        return out
    }

    fun unlistedAnswered(
        ask: FileChangeCommand.AskUnlisted,
        answer: UnlistedAnswer,
        conditions: PanelConditions
    ): List<FileChangeCommand> {
        val out = ArrayList<FileChangeCommand>()
        probeRunning = false
        if (ask.stamp != probeId) return out
        when (answer) {
            is UnlistedAnswer.Failed -> {
                out.add(FileChangeCommand.ReportUnchecked(answer.details))
                requestRefresh(conditions.busy, out)
            }
            UnlistedAnswer.Listable -> requestRefresh(conditions.busy, out)
            UnlistedAnswer.Quiet -> {
                quietKeys = UnlistedPathsProbe.remembered(quietKeys, ask.keys)
                probeUnlistedPaths(conditions, out)
            }
        }
        return out
    }

    private fun requestRefresh(busy: Boolean, out: MutableList<FileChangeCommand>): Boolean {
        if (busy) {
            refreshPending = true
            retryLater(out)
            return false
        }
        reset()
        out.add(FileChangeCommand.Refresh)
        return true
    }

    private fun retryLater(out: MutableList<FileChangeCommand>) {
        if (FileChangeCommand.RetryLater !in out) out.add(FileChangeCommand.RetryLater)
    }

    private fun probeRepoState(conditions: PanelConditions, out: MutableList<FileChangeCommand>) {
        if (!repoStateProbePending || repoStateRunning || conditions.busy || !conditions.hasRepo) return
        repoStateProbePending = false
        repoStateRunning = true
        out.add(FileChangeCommand.ProbeRepoState(probeId))
    }

    private fun probeUnlistedPaths(conditions: PanelConditions, out: MutableList<FileChangeCommand>) {
        if (probeRunning || conditions.busy || pendingUnlistedKeys.isEmpty() || !conditions.hasRepo) return
        when (val action = UnlistedPathsProbe.of(pendingUnlistedKeys, quietKeys)) {
            UnlistedPathsAction.Ignore -> pendingUnlistedKeys.clear()
            UnlistedPathsAction.Reload -> {
                pendingUnlistedKeys.clear()
                requestRefresh(conditions.busy, out)
            }
            is UnlistedPathsAction.Ask -> {
                pendingUnlistedKeys.removeAll(action.keys.toSet())
                probeRunning = true
                out.add(FileChangeCommand.AskUnlisted(action.keys, probeId))
            }
        }
    }
}
