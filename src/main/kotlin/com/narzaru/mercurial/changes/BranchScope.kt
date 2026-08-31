package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths

/**
 * Which files count as "changes of this branch" in the `Base` mode.
 *
 * The list starts as the files the branch's own revisions touched ([ownFiles]) — the same set
 * Upsource shows. That set is honest about revisions, but not about content: a merge revision
 * lists a file whenever both sides changed it, even when the merge took the parent's version
 * whole and the branch's own edits were reverted meanwhile. Such a file then shows the parent's
 * `+N −M` as if the branch had written them.
 *
 * [narrow] cuts those out by intersecting with the files that really differ from the merge base
 * ([contributedFiles], built from `hg diff --git --rev <merge base>`): if the content at the
 * branch head matches what the parent branch already had, the branch contributed nothing.
 */
object BranchScope {

    /** Paths from `hg log --template "{join(files, '\n')}"` as comparison keys. */
    fun ownFiles(logStdout: String): Set<String> = logStdout.split('\r', '\n')
        .mapNotNull { it.trim().ifEmpty { null } }
        .mapTo(HashSet()) { HgPaths.key(it) }

    /** The same keys for paths seen in a `hg diff --git` output. */
    fun contributedFiles(diffPaths: Collection<String>): Set<String> =
        diffPaths.mapTo(HashSet()) { HgPaths.key(it) }

    /** Own files that the branch really left content of its own in. */
    fun narrow(own: Set<String>, contributed: Set<String>): Set<String> =
        own.filterTo(HashSet()) { it in contributed }
}
