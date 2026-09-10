package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgFailure
import com.narzaru.mercurial.hg.HgResult
import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem

object StatusTextFormatter {

    const val REVISION_TEMPLATE = "{branch}|{node|short}|{desc|firstline}"

    const val UNKNOWN_REVISION = "Unknown|?|?"

    private val ROOT_BRANCH_MARKERS = listOf("revision 0", "unknown revision", "empty revision")

    fun statusError(mode: HgDisplayMode, result: HgResult): String {
        if (!isRootBranchFailure(mode, result)) return HgFailure.message(result)
        return "ROOT BRANCH DETECTED (No Parent). Use '${HgDisplayMode.UNCOMMITTED.title}'.\nDetails: " +
            result.stderr.trim()
    }

    private fun isRootBranchFailure(mode: HgDisplayMode, result: HgResult): Boolean =
        !result.failedToStart && mode.usesRevisions &&
            ROOT_BRANCH_MARKERS.any { result.stderr.contains(it) }

    fun branchInfo(mode: HgDisplayMode, currentInfo: String, baseInfo: String): String {
        if (mode == HgDisplayMode.UNCOMMITTED) return "Uncommitted in: ${revision(currentInfo)}"
        val relation = when (mode) {
            HgDisplayMode.CUSTOM_BRANCH -> " vs Branch: "
            HgDisplayMode.MERGE -> " vs Last merged in: "
            else -> " vs Branch point: "
        }
        return "Branch: ${revision(currentInfo)}$relation${revision(baseInfo)}"
    }

    fun mergeBaseProblem(parentBranch: String, currentInfo: String, baseInfo: String): String? = when {
        baseInfo.isBlank() || baseInfo == UNKNOWN_REVISION ->
            "No revision of '$parentBranch' is an ancestor of this branch — nothing has been " +
                "merged in. Use '${HgDisplayMode.BRANCH.title}', or name the right parent branch."
        node(currentInfo) == node(baseInfo) ->
            "This branch is '$parentBranch' itself, or carries nothing of its own on top of it. " +
                "Use '${HgDisplayMode.UNCOMMITTED.title}'."
        else -> null
    }

    private fun node(raw: String): String = raw.split('|', limit = 3).getOrElse(1) { raw }

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
