package com.narzaru.mercurial.hg

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener

class HgCommandServerCleanup : ProjectCloseListener {

    override fun projectClosing(project: Project) {
        HgCommandServers.getInstanceOrNull()?.projectClosing(project)
    }
}
