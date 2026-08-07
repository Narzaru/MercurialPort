package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgFileItem

/** Хранилище отметок «просмотрено» — отделено от состояния, чтобы логику можно было проверить без IDE. */
interface ReviewedPathsStore {
    fun load(): List<String>
    fun save(paths: List<String>)
}

/**
 * Отметки «просмотрено» по файлам. Ключ — путь, нормализованный и в нижнем регистре:
 * `hg` отдаёт пути через `/`, редактор и диалоги платформы — через `\`, а на Windows
 * ещё и регистр может отличаться.
 */
class ReviewState(private val store: ReviewedPathsStore) {

    private val reviewed = HashSet<String>()

    fun reload() {
        reviewed.clear()
        reviewed.addAll(store.load())
    }

    fun key(item: HgFileItem): String = HgPaths.key(item.path)

    fun isReviewed(item: HgFileItem): Boolean = reviewed.contains(key(item))

    fun isEmpty(): Boolean = reviewed.isEmpty()

    /** @return true, если состав отметок изменился и дерево нужно перерисовать. */
    fun set(items: List<HgFileItem>, reviewed: Boolean): Boolean {
        var changed = false
        for (item in items) {
            val key = key(item)
            changed = (if (reviewed) this.reviewed.add(key) else this.reviewed.remove(key)) || changed
        }
        if (changed) store.save(this.reviewed.toList())
        return changed
    }

    /** Переключает отметку у группы: снимает, только если просмотрено уже всё. */
    fun toggle(items: List<HgFileItem>): Boolean {
        if (items.isEmpty()) return false
        return set(items, items.any { !isReviewed(it) })
    }

    fun clear(): Boolean {
        if (reviewed.isEmpty()) return false
        reviewed.clear()
        store.save(emptyList())
        return true
    }
}
