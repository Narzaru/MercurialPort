package com.github.narzaru.hgrider.history

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Держит ссылку на панель истории, чтобы другие окна плагина (например, Hg Changes)
 * могли показать в ней историю выбранного файла.
 */
@Service(Service.Level.PROJECT)
class HgFileHistoryService(val project: Project) {
    var panel: HgFileHistoryPanel? = null

    /** Показать историю файла, если панель открыта и не закреплена на другом файле. */
    fun syncTo(path: String) {
        panel?.syncTo(path)
    }

    /**
     * Выполнить операции с вкладками редактора так, чтобы панель истории не пошла
     * за сменой выбора: закрытие дифф-вкладки переключает редактор на соседний файл,
     * и история успевала перезагрузиться под него (список моргал и пустел).
     */
    fun suppressFollow(block: () -> Unit) {
        val p = panel
        if (p == null) block() else p.withoutFollow(block)
    }
}
