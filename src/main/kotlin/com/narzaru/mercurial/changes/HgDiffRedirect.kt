package com.narzaru.mercurial.changes

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class HgDiffRedirect(private val project: Project) : FileEditorManagerListener {

    private var leftBehind: VirtualFile? = null
    private var restored: MutableSet<String>? = null

    override fun selectionChanged(event: FileEditorManagerEvent) {
        leftBehind = event.oldFile
    }

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        HgDiffRedirector(project).redirect(
            HgDiffRedirector.OpeningFile(
                file,
                leftBehind,
                restoredBySession(source, file),
                caretLine(source, file)
            )
        )
    }

    private fun caretLine(source: FileEditorManager, file: VirtualFile): Int? =
        source.getEditors(file).filterIsInstance<TextEditor>()
            .firstNotNullOfOrNull { it.editor.caretModel.logicalPosition.line }

    private fun restoredBySession(source: FileEditorManager, file: VirtualFile): Boolean {
        val known = restored ?: source.openFiles.mapTo(HashSet()) { it.path }.also { restored = it }
        return known.remove(file.path)
    }
}
