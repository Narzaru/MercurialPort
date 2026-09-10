package com.narzaru.mercurial.export

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object FileExporter {

    private const val TARGET_DIR_KEY = "mercurial.export.targetDir"
    private const val TITLE = "Export"

    private val SESSION_DIR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun dumpOpenFiles(project: Project, forceChooseDir: Boolean) {
        val projectRoot = project.basePath?.let { File(it) }
        if (projectRoot == null) {
            Messages.showWarningDialog(project, "No project is open.", TITLE)
            return
        }

        val targetBase = chooseTargetDir(project, forceChooseDir) ?: return
        val sessionDir = File(targetBase, LocalDateTime.now().format(SESSION_DIR_FORMAT))
        val sources = FileEditorManager.getInstance(project).openFiles
            .map { File(it.path) }
            .filter { it.isFile }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Exporting open files", true) {
            override fun run(indicator: ProgressIndicator) {
                if (!createDir(sessionDir)) {
                    showOnUi { showDirError(project, sessionDir) }
                    return
                }
                val failures = ArrayList<String>()
                var copied = 0
                for ((index, source) in sources.withIndex()) {
                    indicator.checkCanceled()
                    indicator.fraction = (index + 1).toDouble() / sources.size
                    indicator.text2 = source.name
                    if (copyFile(source, projectRoot, sessionDir, failures)) copied++
                }
                showOnUi {
                    Messages.showInfoMessage(
                        project,
                        ExportReport.message(copied, failures, sessionDir.absolutePath),
                        "Export finished"
                    )
                }
            }
        })
    }

    private fun copyFile(
        source: File,
        projectRoot: File,
        sessionDir: File,
        failures: MutableList<String>
    ): Boolean {
        val dest = File(sessionDir, ExportReport.destination(source.absolutePath, projectRoot.absolutePath))
        val parent = dest.parentFile
        if (parent != null && !createDir(parent)) {
            failures.add("${source.path} — cannot create ${parent.path}")
            return false
        }
        return try {
            source.copyTo(dest, overwrite = true)
            true
        } catch (e: Exception) {
            failures.add("${source.path} — ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun createDir(dir: File): Boolean = dir.mkdirs() || dir.isDirectory

    private fun showDirError(project: Project, sessionDir: File) = Messages.showErrorDialog(
        project,
        "Cannot create the target folder: ${sessionDir.absolutePath}",
        TITLE
    )

    private fun showOnUi(task: () -> Unit) = ApplicationManager.getApplication().invokeLater(task)

    private fun chooseTargetDir(project: Project, forceChoose: Boolean): String? {
        val props = PropertiesComponent.getInstance()
        val stored = props.getValue(TARGET_DIR_KEY, "")

        if (!forceChoose && stored.isNotEmpty() && File(stored).isDirectory) {
            return stored
        }

        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Choose the base folder to copy the files into")
        val toSelect = stored.takeIf { it.isNotEmpty() }
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val chosen = FileChooser.chooseFile(descriptor, project, toSelect) ?: return null
        props.setValue(TARGET_DIR_KEY, chosen.path)
        return chosen.path
    }
}
