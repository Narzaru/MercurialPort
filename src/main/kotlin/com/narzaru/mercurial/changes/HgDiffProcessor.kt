package com.narzaru.mercurial.changes

import com.narzaru.mercurial.diff.JumpToSourceSlot
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.impl.CacheDiffRequestChainProcessor
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

class HgDiffProcessor(project: Project, chain: DiffRequestChain, replacesJumpToSource: Boolean = false) :
    CacheDiffRequestChainProcessor(project, chain) {

    init {
        if (replacesJumpToSource) putContextUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE, true)
    }

    override fun getNavigationActions(): List<AnAction> {
        val actions = super.getNavigationActions()
        if (getContextUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE) != true) return actions
        val manager = ActionManager.getInstance()
        val toggle = manager.getAction(TOGGLE_DIFF_AND_FILE_ID) ?: return actions
        val expanded = actions.flatMap { action ->
            val group = action as? DefaultActionGroup
            if (group != null && manager.getId(group) == NAVIGATION_ACTIONS_ID) group.getChildren(manager).toList()
            else listOf(action)
        }
        return JumpToSourceSlot.fill(expanded, { manager.getId(it) == OPEN_IN_EDITOR_ID }, toggle)
    }

    fun reload() {
        dropCaches()
        updateRequest(true)
    }

    fun scrollToRightLine(line: Int): Boolean {
        val unified = activeViewer as? UnifiedDiffViewer
        if (unified != null) {
            val target = unified.transferLineToOneside(Side.RIGHT, line).takeIf { it >= 0 } ?: return false
            DiffUtil.scrollEditor(unified.editor, target, false)
            return true
        }
        val editor = rightEditor() ?: return false
        DiffUtil.scrollEditor(editor, line, false)
        return true
    }

    private fun rightEditor(): Editor? = when (val viewer = activeViewer) {
        is TwosideTextDiffViewer -> viewer.getEditor(Side.RIGHT)
        is OnesideTextDiffViewer -> viewer.editor
        else -> null
    }

    private companion object {
        const val TOGGLE_DIFF_AND_FILE_ID = "mercurial.ToggleDiffAndFile"
        const val NAVIGATION_ACTIONS_ID = "Diff.NavigationActions"
        const val OPEN_IN_EDITOR_ID = "Diff.OpenInEditor"
    }
}
