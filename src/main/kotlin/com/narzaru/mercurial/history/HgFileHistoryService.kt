package com.narzaru.mercurial.history

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class HgFileHistoryService(project: Project) {
    var panel: HgFileHistoryPanel? = null

    fun syncTo(path: String) {
        panel?.syncTo(path)
    }

    fun suppressFollow(block: () -> Unit) {
        val p = panel
        if (p == null) block() else p.withoutFollow(block)
    }
}
