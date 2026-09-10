package com.narzaru.mercurial.changes

object DiffTitles {

    const val REVISION_SIDE_NOTE = " · revision text, not the file: no Find Usages or refactorings"

    fun left(
        hasBaseContent: Boolean,
        rightIsRevision: Boolean,
        onlyLast: Boolean,
        baseRev: String,
        copiedFrom: String
    ): String {
        val source = if (copiedFrom.isNotEmpty()) " $copiedFrom" else ""
        return when {
            !hasBaseContent -> "Not in base"
            rightIsRevision && !onlyLast -> "Before this branch's changes$source"
            else -> "Hg Base ($baseRev)$source"
        }
    }

    fun right(
        rightIsRevision: Boolean,
        onlyLast: Boolean,
        hasHeadContent: Boolean,
        rightIsLocalFile: Boolean,
        hasLocalFile: Boolean,
        headRev: String,
        skippedHunks: Int
    ): String {
        val note = if (rightIsLocalFile) "" else REVISION_SIDE_NOTE
        return when {
            rightIsRevision && onlyLast -> "Hg Rev ($headRev) — last change only$note"
            rightIsRevision && skippedHunks > 0 ->
                "Hg Rev ($headRev) — $skippedHunks change(s) over merged code not shown$note"
            rightIsRevision -> if (hasHeadContent) "Hg Rev ($headRev)$note" else "Not in $headRev"
            hasLocalFile -> "Local Version"
            else -> "Deleted locally"
        }
    }
}
