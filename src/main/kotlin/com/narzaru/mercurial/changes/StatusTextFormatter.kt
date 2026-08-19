package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem

/** Texts of the status line under the Hg Changes toolbar. */
object StatusTextFormatter {

    /** The `hg log` template a revision description is built from. */
    const val REVISION_TEMPLATE = "{branch}|{node|short}|{desc|firstline}"

    /** What `hg log` gives when a revision cannot be described: same shape, three fields. */
    const val UNKNOWN_REVISION = "Unknown|?|?"

    /** `Branch: feature (a1b2c3) "Title" vs Branch point: default (…) "…"`. */
    fun branchInfo(mode: HgDisplayMode, currentInfo: String, baseInfo: String): String {
        if (mode == HgDisplayMode.UNCOMMITTED) return "Uncommitted in: ${revision(currentInfo)}"
        val relation = when (mode) {
            HgDisplayMode.CUSTOM_BRANCH -> " vs Branch: "
            // Not just "Parent": the base is the branching point, not the parent's head.
            else -> " vs Branch point: "
        }
        return "Branch: ${revision(currentInfo)}$relation${revision(baseInfo)}"
    }

    /** Summary on the right: file count, total +/- and how many are reviewed. */
    fun summary(files: List<HgFileItem>, reviewed: Int, statsPending: Boolean): String = when {
        files.isEmpty() -> " "
        statsPending -> "${files.size} files  ·  counting ±…  ·  $reviewed/${files.size} reviewed"
        else -> {
            val added = files.sumOf { it.added }
            val removed = files.sumOf { it.removed }
            "${files.size} files  +$added  −$removed  ·  $reviewed/${files.size} reviewed"
        }
    }

    private fun revision(raw: String): String {
        val parts = raw.split('|', limit = 3)
        return if (parts.size >= 3) "${parts[0]} (${parts[1]}) \"${parts[2]}\"" else raw
    }
}
