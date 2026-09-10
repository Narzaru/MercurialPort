package com.narzaru.mercurial.status

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class HgEditorTabTitleProvider : EditorTabTitleProvider {

    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        val status = project.service<HgFileStatusService>().statusOf(file) ?: return null
        return "${file.presentableName} [$status]"
    }
}
