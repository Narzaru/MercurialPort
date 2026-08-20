package com.narzaru.mercurial.status

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Appends the file's status letter to its editor tab title — `Foo.cs [M]`. While reviewing,
 * navigation takes you into other classes, and the tab name alone does not tell whether the
 * file is part of the branch changes.
 *
 * `null` means "keep the default title": we have no formatting of our own to impose on files
 * outside the change list.
 */
class HgEditorTabTitleProvider : EditorTabTitleProvider {

    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        val status = project.service<HgFileStatusService>().statusOf(file) ?: return null
        return "${file.presentableName} [$status]"
    }
}
