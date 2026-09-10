package com.narzaru.mercurial.model

import java.io.File

enum class ChangesetsFragments(val title: String, val hint: String) {

    PLATFORM_TRIMMED(
        "IDE, ignoring whitespace at line ends",
        "Closest to a review server's own diff."
    ),

    PLATFORM(
        "IDE",
        "The IDE's own comparison engine, the one that draws the diff."
    ),

    PLATFORM_SETTINGS(
        "IDE, as the diff viewer is set",
        "The 'Do not ignore / Trim whitespaces / …' list above a comparison decides. " +
            "The left side is rebuilt when that setting changes."
    ),

    HG(
        "hg diff hunks",
        "The engine that counts ± as well. Disagrees with the IDE about which of two identical " +
            "braces counts as changed."
    );

    override fun toString(): String = title
}

enum class HgDisplayMode(
    val title: String,
    val hint: String
) {
    UNCOMMITTED(
        "Uncommitted",
        "Uncommitted edits in the working directory."
    ),
    MERGE(
        "Merge",
        "What this branch wrote, compared against the last revision of the parent branch merged " +
            "into it. Everything the parent branch did up to that point stands on both sides and " +
            "never enters the diff, so a branch kept up to date with repeated merges reviews as " +
            "cleanly as one without them. A file a merge brought in but the branch never touched " +
            "is not listed at all. The parent branch is named in the field on the right."
    ),
    BRANCH(
        "Base",
        "What the branch looks like now, compared against the revision it started from. The base " +
            "does not move, so commits other people make in the parent branch do not affect the " +
            "list, and the working copy is the right side — uncommitted edits are shown. Only " +
            "files touched by the branch's own revisions are listed, merges aside; the strip " +
            "under the list says which revisions are in and lets you change that. What a merge " +
            "brought into those files counts here as the branch's own work — use " +
            "'Changesets' for the review diff."
    ),
    CHANGESETS(
        "Changesets",
        "What the selected changesets did, rather than what the branch looks like now. Every file " +
            "is compared on its own — from the state right before its first change to what the " +
            "changesets made of it — so a merge from another branch stays out of the list and out " +
            "of the diff. The right side is a revision, not the file on disk: uncommitted edits " +
            "are not shown. How the left side is built is set in Settings → Tools → Mercurial Port."
    ),
    CUSTOM_BRANCH(
        "VS branch",
        "Compared against the branch named in the field on the right, as a whole: the file list is " +
            "not restricted to the branch's own files."
    );

    val isChangesets: Boolean get() = this == CHANGESETS

    val usesRevisions: Boolean get() = this == BRANCH || isChangesets

    val usesBranchField: Boolean get() = this == MERGE || this == CUSTOM_BRANCH
}

enum class HgListMode { FILES, TODO }

data class HgFileItem(
    val status: String,
    val path: String,
    val isUnchanged: Boolean = false,
    val todoText: String? = null,
    val lineNumber: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val copiedFrom: String = "",

    val baseRev: String = "",
    val headRev: String = ""
) {

    val basePath: String get() = copiedFrom.ifEmpty { path }

    val isTodoItem: Boolean get() = lineNumber > 0

    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\')

    val displayPath: String
        get() = if (isTodoItem) "${File(path).name}:$lineNumber" else path
}

data class HgDiffStat(val added: Int, val removed: Int)

data class HgHistoryItem(
    val revision: String,
    val node: String,
    val author: String,
    val date: String,
    val message: String,
    val path: String = "",
    val parentRev: String = ""
)
