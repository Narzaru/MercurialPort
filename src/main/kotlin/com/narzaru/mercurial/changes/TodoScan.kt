package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgFileItem

object TodoScan {

    fun changed(previous: List<HgFileItem>, current: List<HgFileItem>): Boolean =
        signature(previous) != signature(current)

    fun covers(files: List<HgFileItem>, repoRoot: String, changedPath: String): Boolean {
        val root = HgPaths.normalize(repoRoot).trimEnd('/')
        val relative = HgPaths.relativize(HgPaths.normalize(changedPath), root) ?: return false
        val key = HgPaths.key(relative)
        return files.any { HgPaths.key(it.path) == key }
    }

    private fun signature(items: List<HgFileItem>): List<String> = items.map {
        listOf(HgPaths.key(it.path), it.lineNumber.toString(), it.status, it.todoText.orEmpty())
            .joinToString("|")
    }
}
