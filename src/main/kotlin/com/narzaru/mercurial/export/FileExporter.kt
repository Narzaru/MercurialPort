package com.narzaru.mercurial.export

import com.narzaru.mercurial.hg.HgPaths
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Копирует открытые в редакторе файлы в подпапку с временной меткой, сохраняя
 * относительные пути. Порт FileDumpLogic.
 */
object FileExporter {

    private const val TARGET_DIR_KEY = "mercurial.export.targetDir"

    /** @return путь к созданной папке дампа или null, если экспорт отменён/не удался. */
    fun dumpOpenFiles(project: Project, forceChooseDir: Boolean): String? {
        val projectRoot = project.basePath?.let { File(it) }
        if (projectRoot == null) {
            Messages.showWarningDialog(project, "Проект не открыт.", "Экспорт")
            return null
        }

        val targetBase = chooseTargetDir(project, forceChooseDir) ?: return null

        val timestamp = SimpleDateFormat("yyyyMMddHHmmss").format(Date())
        val sessionDir = File(targetBase, timestamp)
        sessionDir.mkdirs()

        var count = 0
        val openFiles = FileEditorManager.getInstance(project).openFiles
        for (vf in openFiles) {
            val source = File(vf.path)
            if (!source.isFile) continue

            // Файлы вне проекта складываем отдельной папкой: относительного пути у них нет,
            // а свалить их в корень дампа — потерять те, чьи имена совпали.
            val relative = HgPaths.relativize(source.absolutePath, projectRoot.absolutePath)
                ?: File("_External", source.name).path

            val dest = File(sessionDir, relative)
            dest.parentFile?.mkdirs()
            try {
                source.copyTo(dest, overwrite = true)
                count++
            } catch (_: Exception) {
                // игнорируем ошибки копирования отдельных файлов
            }
        }

        Messages.showInfoMessage(
            project,
            "Скопировано файлов: $count\nПуть: ${sessionDir.absolutePath}",
            "Экспорт завершён"
        )
        return sessionDir.absolutePath
    }

    private fun chooseTargetDir(project: Project, forceChoose: Boolean): String? {
        val props = PropertiesComponent.getInstance()
        val stored = props.getValue(TARGET_DIR_KEY, "")

        if (!forceChoose && stored.isNotEmpty() && File(stored).isDirectory) {
            return stored
        }

        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Выберите базовую папку для копирования файлов")
        val toSelect = stored.takeIf { it.isNotEmpty() }
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val chosen = FileChooser.chooseFile(descriptor, project, toSelect) ?: return null
        props.setValue(TARGET_DIR_KEY, chosen.path)
        return chosen.path
    }
}
