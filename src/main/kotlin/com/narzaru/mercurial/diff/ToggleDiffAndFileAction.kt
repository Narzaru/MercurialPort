package com.narzaru.mercurial.diff

import com.narzaru.mercurial.changes.CaretTransfer
import com.narzaru.mercurial.changes.DiffSide
import com.narzaru.mercurial.changes.HgChangesService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager

class ToggleDiffAndFileAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = ToggleDiffContext.targetOf(e.dataContext) != ToggleDiffTarget.None
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = ToggleDiffContext.targetOf(e.dataContext)
        LOG.debug("Toggle diff and file: $target")
        NavigationHistory.record(project) { perform(project, target, e) }
    }

    private fun perform(project: Project, target: ToggleDiffTarget, e: AnActionEvent) {
        when (target) {
            is ToggleDiffTarget.ShowFile ->
                showFile(project, target.sourcePath, ToggleDiffContext.diffFileOf(e.dataContext))
            is ToggleDiffTarget.ShowDiff -> showDiff(project, target.tabKey, e)
            ToggleDiffTarget.LoadChanges -> loadChanges(project, e)
            ToggleDiffTarget.None -> return
        }
    }

    private fun showFile(project: Project, sourcePath: String, diffFile: HgDiffVirtualFile?) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(sourcePath) ?: return
        val line = CaretTransfer.fileTarget(
            CaretTransfer.toFile(DiffSide.RIGHT, diffFile?.rightCaretLine()),
            0
        )
        project.service<HgChangesService>().openAsFile(file.path)
        if (line != null) {
            OpenFileDescriptor(project, file, line, 0).navigate(true)
        } else {
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    private fun showDiff(project: Project, tabKey: String, e: AnActionEvent) {
        val file = ToggleDiffContext.editedFileOf(e.dataContext) ?: return
        project.service<HgChangesService>().openDiffTab(
            file.path,
            tabKey,
            null,
            CaretTransfer.toDiff(ToggleDiffContext.caretLineOf(e.dataContext)),
            requestFocus = true
        )
    }

    private fun loadChanges(project: Project, e: AnActionEvent) {
        val file = ToggleDiffContext.editedFileOf(e.dataContext) ?: return
        project.service<HgChangesService>()
            .requestDiff(file.path, CaretTransfer.toDiff(ToggleDiffContext.caretLineOf(e.dataContext)))
        ToolWindowManager.getInstance(project)
            .getToolWindow(HgChangesService.TOOL_WINDOW_ID)
            ?.activate(null, false)
    }

    private companion object {
        val LOG = Logger.getInstance(ToggleDiffAndFileAction::class.java)
    }
}
