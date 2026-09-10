package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem

object RevertPrompt {

    const val PREVIEW_LIMIT = 15

    fun text(files: List<HgFileItem>, mode: HgDisplayMode, baseRev: String): String = buildString {
        if (mode == HgDisplayMode.UNCOMMITTED) {
            appendLine("Revert uncommitted changes in ${files.size} file(s)?")
        } else {
            appendLine("Restore ${files.size} file(s) to the base revision ($baseRev)?")
            appendLine("Every change made since then, committed or not, will be lost.")
        }
        appendLine()
        files.take(PREVIEW_LIMIT).forEach { appendLine(it.path) }
        if (files.size > PREVIEW_LIMIT) appendLine("... and ${files.size - PREVIEW_LIMIT} more")
    }
}

object RevertPlan {

    fun arguments(files: List<HgFileItem>, mode: HgDisplayMode, baseRev: String): List<String> {
        val args = arrayListOf("revert", "--no-backup")
        if (mode != HgDisplayMode.UNCOMMITTED) {
            args.add("-r")
            args.add(baseRev)
        }
        args.addAll(affectedPaths(files))
        return args
    }

    fun affectedPaths(files: List<HgFileItem>): List<String> = files.flatMap { item ->
        listOfNotNull(item.path, item.copiedFrom.takeIf { it.isNotEmpty() })
    }
}
