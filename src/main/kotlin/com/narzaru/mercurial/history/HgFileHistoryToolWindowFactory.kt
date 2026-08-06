package com.narzaru.mercurial.history

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class HgFileHistoryToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HgFileHistoryPanel(project)
        val service = project.service<HgFileHistoryService>()
        service.panel = panel

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        // Отпишет панель от событий редактора, когда окно закроют.
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true
}
