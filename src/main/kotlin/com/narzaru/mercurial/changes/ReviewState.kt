package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgFileItem

interface ReviewedPathsStore {
    fun load(): List<String>
    fun save(paths: List<String>)
}

class ReviewState(private val store: ReviewedPathsStore) {

    private val reviewed = HashSet<String>()

    fun reload() {
        reviewed.clear()
        reviewed.addAll(store.load())
    }

    fun key(item: HgFileItem): String = HgPaths.key(item.path)

    fun isReviewed(item: HgFileItem): Boolean = reviewed.contains(key(item))

    fun set(items: List<HgFileItem>, reviewed: Boolean): Boolean {
        var changed = false
        for (item in items) {
            val key = key(item)
            changed = (if (reviewed) this.reviewed.add(key) else this.reviewed.remove(key)) || changed
        }
        if (changed) store.save(this.reviewed.toList())
        return changed
    }

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
