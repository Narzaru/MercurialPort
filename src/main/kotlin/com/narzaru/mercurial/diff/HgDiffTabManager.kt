package com.narzaru.mercurial.diff

import com.narzaru.mercurial.history.HgFileHistoryService
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.editor.DiffEditorTabFilesUtil
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import java.awt.Component

@Service(Service.Level.PROJECT)
class HgDiffTabManager(private val project: Project) : Disposable {

    private val tabs = HashMap<String, HgDiffVirtualFile>()

    init {
        project.messageBus.connect(this)
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (file !is HgDiffVirtualFile) return
                    synchronized(tabs) { tabs.values.remove(file) }
                }
            })
    }

    override fun dispose() {
    }

    fun sourcePathOf(file: VirtualFile): String? = (file as? HgDiffVirtualFile)?.sourcePath

    private fun tabFor(key: String, title: String, sourcePath: String?, chain: () -> DiffRequestChain) =
        synchronized(tabs) {
            tabs.getOrPut(key) { HgDiffVirtualFile(chain(), title, sourcePath) }
        }

    fun openTab(
        key: String,
        title: String,
        sourcePath: String?,
        focusBack: Component?,
        requestFocus: Boolean = false,
        chain: () -> DiffRequestChain
    ) {
        val diffFile = tabFor(HgDiffTabKeys.changes(key), title, sourcePath, chain)
        project.service<HgFileHistoryService>().suppressFollow {
            showTab(diffFile, requestFocus)
        }
        restoreFocus(focusBack)
    }

    fun scrollShownChangesTab(key: String, line: Int): Boolean {
        val diffFile = synchronized(tabs) { tabs[HgDiffTabKeys.changes(key)] } ?: return false
        return diffFile.scrollToRightLine(line)
    }

    fun reloadOpenTabs() {
        reload(synchronized(tabs) { ArrayList(tabs.values) })
    }

    fun openChangesKeys(): Set<String> = synchronized(tabs) {
        tabs.keys.mapNotNull { HgDiffTabKeys.changesKeyOf(it) }.toSet()
    }

    fun reloadChangesTabs(keys: Collection<String>) {
        if (keys.isEmpty()) return
        reload(synchronized(tabs) { keys.mapNotNull { tabs[HgDiffTabKeys.changes(it)] } })
    }

    private fun reload(open: List<HgDiffVirtualFile>) {
        if (open.isEmpty()) return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            open.forEach { it.reload() }
        }, project.disposed)
    }

    fun show(
        key: String,
        title: String,
        request: DiffRequest,
        focusBack: Component? = null,
        sourcePath: String? = null
    ) {
        val diffFile = tabFor(HgDiffTabKeys.history(key), title, sourcePath) {
            SimpleDiffRequestChain(listOf(request))
        }
        project.service<HgFileHistoryService>().suppressFollow {
            showTab(diffFile)
        }
        restoreFocus(focusBack)
    }

    private fun restoreFocus(component: Component?) {
        if (component == null) return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !component.isShowing) return@invokeLater
            val focus = IdeFocusManager.getInstance(project)
            if (focus.focusOwner === component) return@invokeLater
            focus.requestFocus(component, true)
        }, project.disposed)
    }

    private fun showTab(diffFile: VirtualFile, requestFocus: Boolean = false) {
        if (!DiffEditorTabFilesUtil.isDiffInEditor) {
            DiffEditorTabFilesManager.getInstance(project).showDiffFile(diffFile, requestFocus)
            return
        }
        FileEditorManagerEx.getInstanceEx(project).openFile(
            diffFile,
            null,
            FileEditorOpenOptions(reuseOpen = true, usePreviewTab = true, requestFocus = requestFocus)
        )
    }
}
