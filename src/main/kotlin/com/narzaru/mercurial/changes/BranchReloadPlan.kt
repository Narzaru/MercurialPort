package com.narzaru.mercurial.changes

class BranchReloadPlan {

    private var loadedBranch: String? = null

    fun loadStarted(usesBranch: Boolean, branch: String) {
        if (usesBranch) loadedBranch = branch.trim()
    }

    fun needsReload(usesBranch: Boolean, text: String): Boolean {
        val branch = text.trim()
        return usesBranch && branch.isNotEmpty() && branch != loadedBranch
    }

    fun reloadsWhileTyping(usesBranch: Boolean, text: String, knownBranches: Collection<String>): Boolean =
        needsReload(usesBranch, text) && text.trim() in knownBranches
}
