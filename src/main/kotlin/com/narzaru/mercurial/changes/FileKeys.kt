package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgChangeRow
import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgTodoItem

object FileKeys {

    fun of(row: HgChangeRow): String {
        val path = HgPaths.key(row.file.path)
        return when (row) {
            is HgFileItem -> path
            is HgTodoItem -> "$path:${row.lineNumber}"
        }
    }

    fun relativeTo(repoRoot: String, path: String): String? =
        HgPaths.relativize(HgPaths.normalize(path), HgPaths.normalize(repoRoot))?.let { HgPaths.key(it) }
}
