package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgDisplayMode

class BranchRevisionsRead(val revisions: List<HgRevision>, val tooLarge: Boolean)

class BranchSizeGuard(val limit: Int) {

    private val isLimited: Boolean get() = limit > 0

    fun logArgs(): List<String> {
        val template = listOf("--template", BranchRevisions.TEMPLATE + "\\n")
        if (!isLimited) return listOf("log", "-r", BranchRevisions.OWN_REVS) + template
        return listOf("log", "-r", "reverse(${BranchRevisions.OWN_REVS})", "-l", (limit + 1).toString()) + template
    }

    fun read(fetched: List<HgRevision>): BranchRevisionsRead = when {
        !isLimited -> BranchRevisionsRead(fetched, tooLarge = false)
        fetched.size > limit -> BranchRevisionsRead(emptyList(), tooLarge = true)
        else -> BranchRevisionsRead(fetched.asReversed(), tooLarge = false)
    }

    fun tooLargeText(branch: String, mode: HgDisplayMode): String {
        val subject = if (branch.isEmpty()) "This branch" else "Branch '$branch'"
        return "$subject has more than $limit revisions, too many for '${mode.title}'. " +
            "Use '${HgDisplayMode.UNCOMMITTED.title}' or '${HgDisplayMode.MERGE.title}', " +
            "or raise the limit in Settings → Tools → Mercurial Port."
    }

    companion object {
        const val DEFAULT_LIMIT = 100
    }
}
