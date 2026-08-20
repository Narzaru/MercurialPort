package com.narzaru.mercurial.status

import com.narzaru.mercurial.changes.ChangesSettings
import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgFileItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * File statuses from the last Hg Changes refresh, made available outside the tool window:
 * [HgEditorTabTitleProvider] reads them to put a status letter into the editor tab title.
 *
 * The panel pushes a ready list here; the service never runs `hg` itself — a tab title is
 * computed on every repaint, and nothing may be launched from there.
 */
@Service(Service.Level.PROJECT)
class HgFileStatusService(private val project: Project) {

    private val settings = ChangesSettings(project)

    /** Absolute path ([HgPaths.key]) to status letter. Read from EDT and from background threads. */
    @Volatile
    private var statuses: Map<String, String> = emptyMap()

    /** The file's status letter, or `null` when it is unchanged or the feature is off. */
    fun statusOf(file: VirtualFile): String? {
        if (!settings.statusInTabs) return null
        val snapshot = statuses
        if (snapshot.isEmpty()) return null
        return snapshot[HgPaths.key(file.path)]
    }

    /** Takes a fresh list from the panel and repaints the titles of already open tabs. */
    fun update(repoRoot: File, items: List<HgFileItem>) {
        val next = HashMap<String, String>(items.size)
        for (item in items) {
            // TODO rows are not files: many of them share one path and the source file's status.
            if (item.isTodoItem) continue
            next[HgPaths.key(File(repoRoot, item.path).path)] = item.status
        }
        if (next == statuses) return
        statuses = next
        refreshOpenTabs()
    }

    /** Titles are cached by the platform: without an explicit update a tab keeps the old letter. */
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
