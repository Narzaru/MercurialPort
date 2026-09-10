package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths

object BranchScope {

    fun ownFiles(logStdout: String): Set<String> = logStdout.split('\r', '\n')
        .mapNotNull { it.trim().ifEmpty { null } }
        .mapTo(HashSet()) { HgPaths.key(it) }
}
