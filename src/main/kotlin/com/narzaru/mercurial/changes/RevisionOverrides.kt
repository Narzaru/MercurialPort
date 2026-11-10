package com.narzaru.mercurial.changes

interface RevisionOverridesStore {
    fun branchOrder(): List<String>
    fun saveBranchOrder(branches: List<String>)
    fun overrides(branch: String): List<String>
    fun saveOverrides(branch: String, values: List<String>)
}

class RevisionOverrides(
    private val store: RevisionOverridesStore,
    private val maxBranches: Int = MAX_BRANCHES
) {

    @Synchronized
    fun get(branch: String): List<String> {
        val values = store.overrides(branch)
        if (values.isNotEmpty()) promote(branch)
        return values
    }

    @Synchronized
    fun set(branch: String, values: List<String>) {
        store.saveOverrides(branch, values)
        if (values.isNotEmpty()) {
            promote(branch)
            return
        }
        val order = store.branchOrder()
        if (branch in order) store.saveBranchOrder(order.filter { it != branch })
    }

    private fun promote(branch: String) {
        val order = store.branchOrder()
        val promoted = RecentKeys.promote(order, branch, maxBranches)
        if (promoted.kept == order) return
        for (evicted in promoted.evicted) store.saveOverrides(evicted, emptyList())
        store.saveBranchOrder(promoted.kept)
    }

    companion object {

        const val MAX_BRANCHES = 8
    }
}
