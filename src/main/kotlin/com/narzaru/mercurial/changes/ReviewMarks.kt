package com.narzaru.mercurial.changes

interface ReviewMarksStore {
    fun branchOrder(): List<String>
    fun saveBranchOrder(branches: List<String>)
    fun marks(branch: String): List<String>
    fun saveMarks(branch: String, entries: List<String>)
}

data class ReviewMark(val path: String, val hash: String)

class ReviewMarks(
    private val store: ReviewMarksStore,
    private val maxBranches: Int = MAX_BRANCHES,
    private val maxHashesPerPath: Int = MAX_HASHES_PER_PATH,
    private val maxMarksPerBranch: Int = MAX_MARKS_PER_BRANCH
) {

    private val order = ArrayList<String>()
    private val byBranch = HashMap<String, LinkedHashMap<String, MutableList<String>>>()

    fun reload() {
        order.clear()
        byBranch.clear()
        store.branchOrder().filter { it.isNotEmpty() }.distinct().take(maxBranches).forEach { order.add(it) }
    }

    fun branches(): List<String> = order.toList()

    fun contains(branch: String, path: String, hash: String): Boolean =
        branch in order && marksOf(branch)[path]?.contains(hash) == true

    fun touch(branch: String) {
        if (branch !in order || order.first() == branch) return
        order.remove(branch)
        order.add(0, branch)
        store.saveBranchOrder(order.toList())
    }

    fun add(branch: String, marks: Collection<ReviewMark>): Boolean {
        if (branch.isEmpty() || marks.isEmpty()) return false
        val byPath = marksOf(branch)
        var changed = false
        for (mark in marks) {
            val hashes = byPath.remove(mark.path) ?: ArrayList()
            if (!hashes.remove(mark.hash)) changed = true
            hashes.add(mark.hash)
            while (hashes.size > maxHashesPerPath) hashes.removeAt(0)
            byPath[mark.path] = hashes
        }
        trim(byPath)
        promote(branch)
        save(branch, byPath)
        return changed
    }

    fun remove(branch: String, marks: Collection<ReviewMark>): Boolean {
        if (branch !in order) return false
        val byPath = marksOf(branch)
        var changed = false
        for (mark in marks) {
            val hashes = byPath[mark.path] ?: continue
            if (!hashes.remove(mark.hash)) continue
            changed = true
            if (hashes.isEmpty()) byPath.remove(mark.path)
        }
        if (!changed) return false
        if (byPath.isEmpty()) forget(branch) else save(branch, byPath)
        return true
    }

    fun clear(branch: String): Boolean {
        if (branch !in order) return false
        forget(branch)
        return true
    }

    private fun marksOf(branch: String): LinkedHashMap<String, MutableList<String>> =
        byBranch.getOrPut(branch) {
            if (branch in order) decode(store.marks(branch)) else LinkedHashMap()
        }

    private fun trim(byPath: LinkedHashMap<String, MutableList<String>>) {
        var total = byPath.values.sumOf { it.size }
        val eldest = byPath.entries.iterator()
        while (total > maxMarksPerBranch && eldest.hasNext()) {
            val entry = eldest.next()
            while (total > maxMarksPerBranch && entry.value.isNotEmpty()) {
                entry.value.removeAt(0)
                total--
            }
            if (entry.value.isEmpty()) eldest.remove()
        }
    }

    private fun promote(branch: String) {
        order.remove(branch)
        order.add(0, branch)
        while (order.size > maxBranches) {
            val evicted = order.removeAt(order.size - 1)
            byBranch.remove(evicted)
            store.saveMarks(evicted, emptyList())
        }
        store.saveBranchOrder(order.toList())
    }

    private fun forget(branch: String) {
        order.remove(branch)
        byBranch.remove(branch)
        store.saveMarks(branch, emptyList())
        store.saveBranchOrder(order.toList())
    }

    private fun save(branch: String, byPath: Map<String, List<String>>) =
        store.saveMarks(branch, byPath.map { (path, hashes) -> hashes.joinToString(HASH_SEPARATOR) + PATH_SEPARATOR + path })

    private fun decode(entries: List<String>): LinkedHashMap<String, MutableList<String>> {
        val byPath = LinkedHashMap<String, MutableList<String>>()
        for (entry in entries) {
            val split = entry.indexOf(PATH_SEPARATOR)
            if (split <= 0 || split == entry.length - 1) continue
            val hashes = entry.substring(0, split).split(HASH_SEPARATOR).filter { it.isNotEmpty() }
            if (hashes.isEmpty()) continue
            byPath[entry.substring(split + 1)] = hashes.takeLast(maxHashesPerPath).toMutableList()
        }
        return byPath
    }

    companion object {

        const val MAX_BRANCHES = 4

        const val MAX_HASHES_PER_PATH = 3

        const val MAX_MARKS_PER_BRANCH = 5000

        private const val HASH_SEPARATOR = ","

        private const val PATH_SEPARATOR = " "

        fun storageName(branch: String): String =
            branch.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
