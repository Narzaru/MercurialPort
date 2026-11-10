package com.narzaru.mercurial.diff

import com.narzaru.mercurial.changes.HgChangesService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

class ShowHgDiffAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = ShowHgDiffContext.targetOf(e.dataContext) != ShowHgDiffTarget.None
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = ShowHgDiffContext.targetOf(e.dataContext)
        LOG.debug("Show Hg diff: $target")
        NavigationHistory.record(project) { perform(project, target, e) }
    }

    private fun perform(project: Project, target: ShowHgDiffTarget, e: AnActionEvent) {
        when (target) {
            is ShowHgDiffTarget.ShowDiff -> showDiff(project, target, e)
            is ShowHgDiffTarget.LoadChanges -> loadChanges(project, target.path, e)
            ShowHgDiffTarget.None -> return
        }
    }

    private fun showDiff(project: Project, target: ShowHgDiffTarget.ShowDiff, e: AnActionEvent) {
        project.service<HgChangesService>().openDiffTab(
            target.path,
            target.tabKey,
            null,
            ShowHgDiffContext.caretOf(e.dataContext),
            requestFocus = true
        )
    }

    private fun loadChanges(project: Project, path: String, e: AnActionEvent) {
        project.service<HgChangesService>().requestDiff(path, ShowHgDiffContext.caretOf(e.dataContext))
        ToolWindowManager.getInstance(project)
            .getToolWindow(HgChangesService.TOOL_WINDOW_ID)
            ?.activate(null, false)
    }

    private companion object {
        val LOG = Logger.getInstance(ShowHgDiffAction::class.java)
    }
}
