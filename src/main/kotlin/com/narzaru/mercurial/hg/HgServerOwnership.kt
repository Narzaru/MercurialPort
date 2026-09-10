package com.narzaru.mercurial.hg

object HgServerOwnership {

    fun unusedRoots(
        roots: Collection<String>,
        openBasePaths: Collection<String>,
        separator: Char,
        ignoreCase: Boolean
    ): List<String> = roots.filter { root ->
        openBasePaths.none { isRelated(root, it, separator, ignoreCase) }
    }

    private fun isRelated(root: String, basePath: String, separator: Char, ignoreCase: Boolean): Boolean =
        contains(root, basePath, separator, ignoreCase) || contains(basePath, root, separator, ignoreCase)

    private fun contains(ancestor: String, path: String, separator: Char, ignoreCase: Boolean): Boolean =
        path.equals(ancestor, ignoreCase) || path.startsWith(ancestor + separator, ignoreCase)
}
