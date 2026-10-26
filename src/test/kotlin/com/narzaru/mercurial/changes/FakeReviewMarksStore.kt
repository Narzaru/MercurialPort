package com.narzaru.mercurial.changes

class FakeReviewMarksStore : ReviewMarksStore {

    var order: List<String> = emptyList()
    val orders = ArrayList<List<String>>()
    val saved = LinkedHashMap<String, List<String>>()

    override fun branchOrder(): List<String> = order

    override fun saveBranchOrder(branches: List<String>) {
        order = branches
        orders.add(branches)
    }

    override fun marks(branch: String): List<String> = saved[branch].orEmpty()

    override fun saveMarks(branch: String, entries: List<String>) {
        if (entries.isEmpty()) saved.remove(branch) else saved[branch] = entries
    }
}
