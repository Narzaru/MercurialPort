package com.narzaru.mercurial.changes

import com.narzaru.mercurial.diff.HgDiffTabManager
import com.narzaru.mercurial.diff.HgInlineDiffEditor
import com.narzaru.mercurial.diff.HgInlineDiffProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class HgDiffRedirect(private val project: Project) : FileEditorManagerListener {

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val diffTabSource = event.newFile?.let { project.service<HgDiffTabManager>().sourcePathOf(it) }
        val inlinePath = (event.newEditor as? HgInlineDiffEditor)?.sourcePath
        project.service<HgChangesService>().selectionChanged(diffTabSource, inlinePath)
        if (diffTabSource != null || inlinePath != null) {
            LOG.info("$TRACE selection file=${event.newFile?.path} diffTab=$diffTabSource inline=$inlinePath")
        }
    }

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val changes = project.service<HgChangesService>()
        changes.takeOpenedAsFile(file.path)
        if (changes.inlineDiffArming.take() != null) {
            LOG.info("$TRACE disarm on open file=${file.path}")
        }
        if (source.getEditors(file).any { it is HgInlineDiffEditor && it.showingDiff() }) {
            source.setSelectedEditor(file, HgInlineDiffProvider.EDITOR_TYPE_ID)
            LOG.info("$TRACE select inline diff file=${file.path}")
        }
    }

    companion object {
        const val TRACE = "hg-redirect:"

        private val LOG = Logger.getInstance(HgDiffRedirect::class.java)
    }
}
