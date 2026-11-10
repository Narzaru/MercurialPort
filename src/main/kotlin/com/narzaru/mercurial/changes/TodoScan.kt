package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgTodoItem

object TodoScan {

    fun changed(previous: List<HgTodoItem>, current: List<HgTodoItem>): Boolean =
        signature(previous) != signature(current)

    fun covers(files: List<HgFileItem>, repoRoot: String, changedPath: String): Boolean {
        val key = FileKeys.relativeTo(repoRoot, changedPath) ?: return false
        return files.any { HgPaths.key(it.path) == key }
    }

    private fun signature(items: List<HgTodoItem>): List<String> = items.map {
        listOf(HgPaths.key(it.file.path), it.lineNumber.toString(), it.file.status.letter, it.text)
            .joinToString("|")
    }
}
