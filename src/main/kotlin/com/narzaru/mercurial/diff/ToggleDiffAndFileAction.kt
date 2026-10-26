package com.narzaru.mercurial.diff

import com.narzaru.mercurial.changes.CaretTransfer
import com.narzaru.mercurial.changes.HgChangesService
import com.intellij.icons.AllIcons
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
        val target = ToggleDiffContext.targetOf(e.dataContext)
        val inlineShowsDiff = ToggleDiffContext.inlineEditorOf(e.dataContext)?.showingDiff() == true
        val direction = ToggleDiffPresentation.directionOf(target, inlineShowsDiff)
        e.presentation.isEnabledAndVisible = direction != null
        when (direction) {
            ToggleDiffDirection.TO_FILE -> {
                e.presentation.text = "Show File"
                e.presentation.description = "Open the file at the caret position in the preview tab"
                e.presentation.icon = AllIcons.Actions.EditSource
            }
            ToggleDiffDirection.TO_DIFF -> {
                e.presentation.text = "Show Hg Diff"
                e.presentation.description = "Open the Hg diff of the file at the caret line"
                e.presentation.icon = AllIcons.Actions.Diff
            }
            null -> return
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = ToggleDiffContext.targetOf(e.dataContext)
        LOG.debug("Toggle diff and file: $target")
        NavigationHistory.record(project) { perform(project, target, e) }
    }

    private fun perform(project: Project, target: ToggleDiffTarget, e: AnActionEvent) {
        when (target) {
            is ToggleDiffTarget.ShowFile -> showFile(project, target.sourcePath, e)
            is ToggleDiffTarget.ShowDiff -> showDiff(project, target.tabKey, e)
            ToggleDiffTarget.SwapInline -> ToggleDiffContext.inlineEditorOf(e.dataContext)?.swap()
            ToggleDiffTarget.LoadChanges -> loadChanges(project, e)
            ToggleDiffTarget.None -> return
        }
    }

    private fun showFile(project: Project, sourcePath: String, e: AnActionEvent) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(sourcePath) ?: return
        val offset = DiffCaretInFile.offsetIn(file, ToggleDiffContext.diffViewerOf(e.dataContext))
        project.service<HgChangesService>().openAsFile(file.path)
        val target = if (offset != null) OpenFileDescriptor(project, file, offset) else OpenFileDescriptor(project, file)
        FileEditorManager.getInstance(project).openFileEditor(target.setUsePreviewTab(true), true)
            .filterIsInstance<HgInlineDiffEditor>()
            .forEach { it.revealFile() }
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
