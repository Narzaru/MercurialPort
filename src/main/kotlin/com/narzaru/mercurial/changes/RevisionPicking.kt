package com.narzaru.mercurial.changes

data class RevisionsState(
    val branch: String = "",
    val selection: RevisionSelection = RevisionSelection.DEFAULT
)

object RevisionsSync {

    fun isBranchKnown(branch: String): Boolean = branch.isNotEmpty()

    fun afterLoad(state: RevisionsState, branch: String, selection: RevisionSelection): RevisionsState =
        if (!isBranchKnown(branch) || branch == state.branch) state
        else RevisionsState(branch, selection)
}

data class RevisionToggle(val nodes: List<String>, val restoresSelection: Boolean) {
    companion object {
        val NONE = RevisionToggle(emptyList(), false)
    }
}

object RevisionToggling {

    fun targets(
        shownNodes: List<String>,
        toggledNode: String,
        nodesBeforeGesture: List<String>,
        nodesAfterGesture: List<String>,
        gestureUsedModifiers: Boolean
    ): RevisionToggle {
        if (!shownNodes.contains(toggledNode)) return RevisionToggle.NONE
        val selection = if (gestureUsedModifiers) nodesAfterGesture else nodesBeforeGesture
        if (!selection.contains(toggledNode)) return RevisionToggle(listOf(toggledNode), false)
        return RevisionToggle(selection.filter { shownNodes.contains(it) }, !gestureUsedModifiers)
    }
}

object RevisionPicking {

    fun apply(
        revisions: List<HgRevision>,
        selection: RevisionSelection,
        picked: List<HgRevision>,
        desired: Boolean
    ): RevisionSelection {
        val known = revisions.associateBy { it.node }
        var result = selection
        for (revision in picked) {
            val listed = known[revision.node] ?: continue
            result = result.with(listed, desired)
        }
        return result
    }
}
