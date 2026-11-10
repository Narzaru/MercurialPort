package com.narzaru.mercurial.diff

import com.narzaru.mercurial.changes.DiffCaret
import com.narzaru.mercurial.history.HgFileHistoryService
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import java.awt.Component

private const val SHOW_DIFF_AS_EDITOR_TAB_SETTING = "show.diff.as.editor.tab"

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

    fun sourcePathOf(file: VirtualFile): String? = when {
        file.isDirectory -> null
        file.isInLocalFileSystem -> file.path
        else -> (file as? HgDiffVirtualFile)?.sourcePath
    }

    fun openTab(
        key: String,
        title: String,
        sourcePath: String?,
        focusBack: Component?,
        requestFocus: Boolean = false,
        chain: () -> DiffRequestChain
    ) = open(HgDiffTabKeys.changes(key), title, sourcePath, focusBack, requestFocus, chain)

    fun show(key: String, title: String, request: DiffRequest, focusBack: Component?, sourcePath: String?) =
        open(HgDiffTabKeys.history(key), title, sourcePath, focusBack, requestFocus = false) {
            SimpleDiffRequestChain(listOf(request))
        }

    private fun open(
        tabKey: String,
        title: String,
        sourcePath: String?,
        focusBack: Component?,
        requestFocus: Boolean,
        chain: () -> DiffRequestChain
    ) {
        val diffFile = synchronized(tabs) {
            tabs.getOrPut(tabKey) { HgDiffVirtualFile(chain(), title, sourcePath) }
        }
        project.service<HgFileHistoryService>().suppressFollow {
            showTab(diffFile, requestFocus)
        }
        restoreFocus(focusBack)
    }

    fun placeCaretInShownChangesTab(key: String, caret: DiffCaret): Boolean {
        val diffFile = synchronized(tabs) { tabs[HgDiffTabKeys.changes(key)] } ?: return false
        return diffFile.placeRightCaret(caret)
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

    private fun restoreFocus(component: Component?) {
        if (component == null) return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !component.isShowing) return@invokeLater
            val focus = IdeFocusManager.getInstance(project)
            if (focus.focusOwner === component) return@invokeLater
            focus.requestFocus(component, true)
        }, project.disposed)
    }

    private fun showTab(diffFile: VirtualFile, requestFocus: Boolean) {
        if (!opensAsEditorTab()) {
            DiffEditorTabFilesManager.getInstance(project).showDiffFile(diffFile, requestFocus)
            return
        }
        val target = OpenFileDescriptor(project, diffFile).setUsePreviewTab(true)
        FileEditorManager.getInstance(project).openFileEditor(target, requestFocus)
    }

    private fun opensAsEditorTab(): Boolean =
        AdvancedSettings.getBoolean(SHOW_DIFF_AS_EDITOR_TAB_SETTING)
}
