package com.github.narzaru.hgrider.export

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import java.io.File

/**
 * Экспортирует открытые файлы и открывает папку дампа в Total Commander.
 * Порт TotalCmdDumpCommand.
 */
class ExportToTotalCommanderAction : DumbAwareAction() {

    private val totalCmdPath = "c:\\Program Files\\totalcmd\\TOTALCMD64.EXE"

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val forceChoose = (e.modifiers and java.awt.event.InputEvent.SHIFT_DOWN_MASK) != 0
        val dumpPath = FileExporter.dumpOpenFiles(project, forceChoose) ?: return

        try {
            // /O — активировать запущенный экземпляр, /T — новая вкладка, /L — путь в активной панели
            GeneralCommandLine(totalCmdPath, "/O", "/T", "/L=$dumpPath").createProcess()
        } catch (ex: Exception) {
            Messages.showWarningDialog(
                project,
                "Дамп создан в $dumpPath, но не удалось запустить Total Commander: ${ex.message}",
                "Предупреждение"
            )
        }
    }

    @Suppress("unused")
    private fun exists() = File(totalCmdPath).exists()
}
