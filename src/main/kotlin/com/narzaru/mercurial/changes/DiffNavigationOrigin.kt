package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths

object DiffNavigationOrigin {

    fun cameFromItsDiff(diffSourcePath: String?, filePath: String): Boolean =
        diffSourcePath != null && HgPaths.key(diffSourcePath) == HgPaths.key(filePath)
}
