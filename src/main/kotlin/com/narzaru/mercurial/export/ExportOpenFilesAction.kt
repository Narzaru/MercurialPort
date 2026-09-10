package com.narzaru.mercurial.export

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import java.awt.event.InputEvent

class ExportOpenFilesAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val modifiers = e.inputEvent?.modifiersEx ?: 0
        val forceChoose = (modifiers and InputEvent.SHIFT_DOWN_MASK) != 0
        FileExporter.dumpOpenFiles(project, forceChoose)
    }
}
