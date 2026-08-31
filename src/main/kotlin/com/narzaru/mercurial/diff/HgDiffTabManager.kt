package com.narzaru.mercurial.diff

import com.narzaru.mercurial.hg.HgSettings
import com.narzaru.mercurial.history.HgFileHistoryService
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import java.awt.Component

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
     * Дифф-вкладка → файл на диске, который она показывает. Дифф файлом на диске не является,
     * и без этой связи окна плагина не знают, чью строку подсветить, пока выбрана дифф-вкладка.
     * Ключи слабые: вкладки закрываются, а держать их ради карты незачем.
     */
    private val sources = java.util.WeakHashMap<VirtualFile, String>()

    /** Путь файла, дифф которого показан во вкладке [file], или `null` — если вкладка не наша. */
    fun sourcePathOf(file: VirtualFile): String? = sources[file]

    /**
     * Показать дифф. [owner] — идентификатор окна («changes», «history»), [key] описывает
     * содержимое (файл + ревизии): при совпадении ключа вкладка просто активируется.
     *
     * [focusBack] — список, из которого дифф заказан: туда возвращается фокус. Ориентироваться
     * на текущего владельца фокуса нельзя — заказать дифф можно и кнопкой тулбара, когда фокус
     * стоит в редакторе (кнопки его не забирают), и тогда «вернуть как было» означало бы отдать
     * фокус открытому файлу — ровно тому, от чего уходим.
     *
     * [sourcePath] — файл на диске, который показывает дифф (см. [sourcePathOf]).
     */
    fun show(
        owner: String,
        key: String,
        title: String,
        request: DiffRequest,
        focusBack: Component? = null,
        sourcePath: String? = null
    ) {
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

            val diffFile = ChainDiffVirtualFile(SimpleDiffRequestChain(listOf(request)), title)
            slot.file = diffFile
            slot.key = fullKey
            if (sourcePath != null) sources[diffFile] = sourcePath
            // Новую вкладку показываем ДО закрытия старой. Закрытие выбранной вкладки переводит
            // редактор на соседнюю, и если рядом лежит сам этот файл — он успевал моргнуть и
            // забрать фокус. Когда выбран уже новый дифф, закрытие прежнего ничего не двигает.
            showTab(fem, diffFile)
            // Платформа дифф-вкладки не переиспользует — закрываем предыдущую сами,
            // иначе они плодятся по одной на файл.
            if (previous != null && fem.isFileOpen(previous)) fem.closeFile(previous)
        }

        restoreFocus(focusBack)
    }

    /**
     * Ставит фокус в список файлов. Дифф открывается без фокуса
     * (`showDiffFile(..., focusEditor = false)`), но закрытие вкладок и создание области
     * редактора двигают его сами; платформа делает это отложенно, поэтому и мы отложенно.
     */
    private fun restoreFocus(component: Component?) {
        if (component == null) return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !component.isShowing) return@invokeLater
            val focus = IdeFocusManager.getInstance(project)
            if (focus.focusOwner === component) return@invokeLater
            focus.requestFocus(component, true)
        }, project.disposed)
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
