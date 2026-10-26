package com.narzaru.mercurial.diff

import com.narzaru.mercurial.changes.DiffRedirectDecision
import com.narzaru.mercurial.changes.HgChangesService
import com.narzaru.mercurial.changes.HgDiffProcessor
import com.narzaru.mercurial.changes.HgDiffRedirect
import com.narzaru.mercurial.changes.HgDiffRedirector
import com.intellij.diff.editor.DiffEditorViewerFileEditor
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jdom.Element

class HgInlineDiffProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        HgDiffRedirector(project).decisionFor(file) is DiffRedirectDecision.ShowDiff

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val changes = project.service<HgChangesService>()
        val showDiff = changes.inlineDiffArming.take() != null
        val processor = HgDiffProcessor(
            project,
            changes.diffChainFor(file.path),
            ToggleDiffPromotion.replacesJumpToSourceInInlineDiff()
        )
        val diffEditor = DiffEditorViewerFileEditor(project, file, processor)
        val fileEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        LOG.info("${HgDiffRedirect.TRACE} createEditor file=${file.path} showDiff=$showDiff")
        return HgInlineDiffEditor(project, fileEditor, diffEditor, processor, file.path, showDiff)
    }

    override fun writeState(state: FileEditorState, project: Project, targetElement: Element) {
        val textState = (state as? TextEditorWithPreview.MyFileEditorState)?.firstState ?: return
        TextEditorProvider.getInstance().writeState(textState, project, targetElement)
    }

    override fun readState(sourceElement: Element, project: Project, file: VirtualFile): FileEditorState =
        TextEditorWithPreview.MyFileEditorState(
            null,
            TextEditorProvider.getInstance().readState(sourceElement, project, file),
            null,
            false
        )

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    companion object {
        const val EDITOR_TYPE_ID = "hg-inline-diff"

        private val LOG = Logger.getInstance(HgInlineDiffProvider::class.java)
    }
}
