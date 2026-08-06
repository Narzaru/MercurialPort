package com.narzaru.mercurial.diff

import com.narzaru.mercurial.hg.HgSettings
import com.narzaru.mercurial.history.HgFileHistoryService
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Единственный владелец дифф-вкладок плагина.
 *
 * Обе панели (Hg Changes и Hg File History) раньше вели свою «переиспользуемую вкладку»
 * независимо, и на экране оказывалось два диффа сразу. Теперь вкладка одна на плагин;
 * поведение переключается настройкой [HgSettings.shareDiffTab] — с выключенной у каждого
 * окна снова своя вкладка (но всё так же одна, а не по одной на файл).
 */
@Service(Service.Level.PROJECT)
class HgDiffTabManager(private val project: Project) {

    /** Что сейчас показано в конкретной вкладке: сам файл и ключ его содержимого. */
    private class Slot {
        var file: VirtualFile? = null
        var key: String? = null
    }

    private val sharedSlot = Slot()
    private val ownSlots = HashMap<String, Slot>()

    /**
     * Показать дифф. [owner] — идентификатор окна («changes», «history»), [key] описывает
     * содержимое (файл + ревизии): при совпадении ключа вкладка просто активируется.
     */
    fun show(owner: String, key: String, title: String, request: DiffRequest) {
        val slot = if (HgSettings.shareDiffTab) sharedSlot else ownSlots.getOrPut(owner) { Slot() }
        val fullKey = "$owner|$key"
        val fem = FileEditorManager.getInstance(project)

        // Возня с вкладками меняет выбор в редакторе, а Hg File History за ним следует.
        project.service<HgFileHistoryService>().suppressFollow {
            val previous = slot.file
            if (fullKey == slot.key && previous != null && fem.isFileOpen(previous)) {
                showTab(fem, previous)
                return@suppressFollow
            }
            // Платформа дифф-вкладки не переиспользует — закрываем предыдущую сами,
            // иначе они плодятся по одной на файл.
            if (previous != null && fem.isFileOpen(previous)) fem.closeFile(previous)

            val diffFile = ChainDiffVirtualFile(SimpleDiffRequestChain(listOf(request)), title)
            slot.file = diffFile
            slot.key = fullKey
            showTab(fem, diffFile)
        }
    }

    /**
     * `showDiffFile(..., focusEditor = false)` ничего не показывает, когда в редакторе нет
     * ни одной вкладки: области редактора ещё не существует. Тогда открываем обычным путём.
     */
    private fun showTab(fem: FileEditorManager, diffFile: VirtualFile) {
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(diffFile, false)
        if (!fem.isFileOpen(diffFile)) fem.openFile(diffFile, true)
    }

    companion object {
        const val OWNER_CHANGES = "changes"
        const val OWNER_HISTORY = "history"
    }
}
