package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgFileItem

object FileKeys {

    fun of(item: HgFileItem): String {
        val path = HgPaths.key(item.path)
        return if (item.isTodoItem) "$path:${item.lineNumber}" else path
    }

    fun relativeTo(repoRoot: String, path: String): String? =
        HgPaths.relativize(HgPaths.normalize(path), HgPaths.normalize(repoRoot))?.let { HgPaths.key(it) }
}
