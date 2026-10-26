package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgFileItem

class ReviewState(private val marks: ReviewMarks) {

    private var branch = ""
    private var context = ""
    private val hashes = HashMap<String, String>()
    private val provisional = HashSet<String>()
    private val intents = HashMap<String, Boolean>()

    private val branchKnown: Boolean get() = branch.isNotEmpty()

    fun reload() {
        marks.reload()
        intents.clear()
    }

    fun key(item: HgFileItem): String = HgPaths.key(item.path)

    fun startLoad(branch: String, context: String) {
        if (branch != this.branch || context != this.context) {
            hashes.clear()
            provisional.clear()
            intents.clear()
        } else {
            provisional.addAll(hashes.keys)
        }
        this.branch = branch
        this.context = context
        if (branchKnown) marks.touch(branch)
    }

    fun suspend(keys: Collection<String>) {
        for (key in keys) if (key in hashes) provisional.add(key)
    }

    fun learn(learned: Map<String, String>): Boolean {
        var changed = false
        for ((key, hash) in learned) {
            if (hashes.put(key, hash) != hash) changed = true
            provisional.add(key)
        }
        val reviewed = ArrayList<ReviewMark>()
        val unreviewed = ArrayList<ReviewMark>()
        for (key in provisional) {
            val intent = intents.remove(key) ?: continue
            val mark = ReviewMark(key, hashes.getValue(key))
            if (intent) reviewed.add(mark) else unreviewed.add(mark)
        }
        provisional.clear()
        store(reviewed, unreviewed)
        return changed
    }

    fun isReviewed(item: HgFileItem): Boolean {
        if (!branchKnown) return false
        val key = key(item)
        intents[key]?.let { return it }
        val hash = hashes[key] ?: return false
        return marks.contains(branch, key, hash)
    }

    fun set(items: List<HgFileItem>, reviewed: Boolean): Boolean {
        if (!branchKnown) return false
        var changed = false
        val confirmed = ArrayList<ReviewMark>()
        for (item in items.distinctBy { key(it) }) {
            if (isReviewed(item) != reviewed) changed = true
            val key = key(item)
            val hash = hashes[key]
            if (hash != null && key !in provisional) {
                confirmed.add(ReviewMark(key, hash))
            } else {
                intents[key] = reviewed
            }
        }
        if (reviewed) store(confirmed, emptyList()) else store(emptyList(), confirmed)
        return changed
    }

    fun toggle(items: List<HgFileItem>): Boolean {
        if (items.isEmpty()) return false
        return set(items, items.any { !isReviewed(it) })
    }

    fun clear(): Boolean {
        if (!branchKnown) return false
        val shownIntents = intents.values.any { it }
        intents.clear()
        return marks.clear(branch) || shownIntents
    }

    private fun store(reviewed: List<ReviewMark>, unreviewed: List<ReviewMark>) {
        if (reviewed.isNotEmpty()) marks.add(branch, reviewed)
        if (unreviewed.isNotEmpty()) marks.remove(branch, unreviewed)
    }
}
