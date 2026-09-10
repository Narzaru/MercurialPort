package com.narzaru.mercurial.history

import com.narzaru.mercurial.hg.HgFailure

object HistoryStatusText {

    const val READY = "Ready"
    const val NO_FILE_SELECTED = "No file selected"
    const val LOADING = "Loading history..."
    const val NO_REPOSITORY = "No repository found."
    const val NO_PARENT_REVISION = "No parent revision"
    const val NOTHING_TO_COMPARE = "Nothing to compare for this revision."

    private const val MAX_ERROR_LENGTH = 200

    fun title(fileName: String): String = "History: $fileName"

    fun loaded(count: Int): String =
        if (count == 1) "Loaded 1 commit." else "Loaded $count commits."

    fun hgError(stderr: String, failedToStart: Boolean = false): String =
        HgFailure.message(failedToStart, shorten(stderr))

    fun diffError(message: String): String = "Diff error: ${shorten(message)}"

    fun nothingToCompare(catError: String?): String {
        val error = catError?.trim().orEmpty()
        return if (error.isEmpty()) NOTHING_TO_COMPARE else "Hg Error: ${shorten(error)}"
    }

    fun parentLabel(parentRevision: String): String = "Rev $parentRevision (parent)"

    fun revisionLabel(revision: String, author: String): String = "Rev $revision ($author)"

    fun revisionsSuffix(count: Int): String = if (count > 1) "  ·  $count revisions" else ""

    fun renamedSuffix(previousPath: String): String = " — ${previousPath.substringAfterLast('/')}"

    fun diffTitles(
        leftLabel: String,
        rightLabel: String,
        hasLeftContent: Boolean,
        hasRightContent: Boolean
    ): DiffTitles {
        val left = if (hasLeftContent) leftLabel else "$leftLabel — file added"
        val right = if (hasRightContent) rightLabel else "$rightLabel — file deleted"
        return DiffTitles(left, right, "$left → $right")
    }

    private fun shorten(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= MAX_ERROR_LENGTH) return trimmed
        return trimmed.take(MAX_ERROR_LENGTH) + "…"
    }

    data class DiffTitles(val left: String, val right: String, val status: String)
}
