package com.narzaru.mercurial.export

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * Копирует открытые файлы в выбранную папку. Порт FileCopyCommand.
 * Удержание Shift при вызове форсирует повторный выбор папки.
 */
class ExportOpenFilesAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val forceChoose = (e.modifiers and java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0
        FileExporter.dumpOpenFiles(project, forceChoose)
    }
}
