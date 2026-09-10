package com.narzaru.mercurial.status

import com.narzaru.mercurial.hg.HgSettings
import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgFileItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

@Service(Service.Level.PROJECT)
class HgFileStatusService(private val project: Project) {

    @Volatile
    private var statuses: Map<String, String> = emptyMap()

    fun statusOf(file: VirtualFile): String? {
        if (!HgSettings.statusInTabs) return null
        val snapshot = statuses
        if (snapshot.isEmpty()) return null
        return snapshot[HgPaths.key(file.path)]
    }

    fun update(repoRoot: File, items: List<HgFileItem>) {
        val next = HashMap<String, String>(items.size)
        for (item in items) {
            if (item.isTodoItem) continue
            next[HgPaths.key(File(repoRoot, item.path).path)] = item.status
        }
        if (next == statuses) return
        statuses = next
        refreshOpenTabs()
    }

    fun refreshOpenTabs() {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val manager = FileEditorManagerEx.getInstanceEx(project)
            for (file in FileEditorManager.getInstance(project).openFiles) {
                manager.updateFilePresentation(file)
            }
        }, project.disposed)
    }
}
