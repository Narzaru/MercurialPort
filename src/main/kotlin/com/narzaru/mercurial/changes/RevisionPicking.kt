package com.narzaru.mercurial.changes

import java.awt.event.ActionEvent
import java.awt.event.InputEvent

enum class RevisionClickKind { TOGGLE, RANGE }

object RevisionGesture {

    private const val SHIFT_MASKS = ActionEvent.SHIFT_MASK or InputEvent.SHIFT_DOWN_MASK

    fun kindOf(modifiers: Int): RevisionClickKind =
        if ((modifiers and SHIFT_MASKS) != 0) RevisionClickKind.RANGE else RevisionClickKind.TOGGLE
}

data class RevisionPick(val selection: RevisionSelection, val anchor: String)

data class RevisionsState(
    val branch: String = "",
    val selection: RevisionSelection = RevisionSelection.DEFAULT,
    val anchor: String = ""
)

object RevisionsSync {

    fun isBranchKnown(branch: String): Boolean = branch.isNotEmpty()

    fun afterLoad(state: RevisionsState, branch: String, selection: RevisionSelection): RevisionsState =
        if (!isBranchKnown(branch) || branch == state.branch) state
        else RevisionsState(branch, selection, "")
}

object RevisionPicking {

    fun apply(
        revisions: List<HgRevision>,
        selection: RevisionSelection,
        anchor: String,
        clicked: HgRevision,
        kind: RevisionClickKind,
        desired: Boolean
    ): RevisionPick {
        val clickedIndex = revisions.indexOfFirst { it.node == clicked.node }
        if (clickedIndex < 0) return RevisionPick(selection, anchor)
        val anchorIndex = revisions.indexOfFirst { it.node == anchor }
        if (kind == RevisionClickKind.TOGGLE || anchorIndex < 0) {
            return RevisionPick(selection.with(revisions[clickedIndex], desired), clicked.node)
        }
        var picked = selection
        for (index in minOf(anchorIndex, clickedIndex)..maxOf(anchorIndex, clickedIndex)) {
            picked = picked.with(revisions[index], desired)
        }
        return RevisionPick(picked, anchor)
    }
}
