package com.narzaru.mercurial.diff

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.project.Project

object NavigationHistory {

    fun record(project: Project, navigate: () -> Unit) {
        CommandProcessor.getInstance().executeCommand(project, {
            val history = IdeDocumentHistory.getInstance(project)
            history.includeCurrentCommandAsNavigation()
            history.setCurrentCommandHasMoves()
            navigate()
        }, null, null)
    }
}
