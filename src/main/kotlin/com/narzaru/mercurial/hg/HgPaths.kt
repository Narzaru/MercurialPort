package com.narzaru.mercurial.hg

import java.io.File

object HgPaths {

    fun normalize(path: String): String = path.replace('\\', '/')

    fun key(path: String): String = normalize(path).lowercase()

    fun keyRelativeTo(path: String, repoRoot: File): String? =
        relativize(normalize(path), normalize(repoRoot.absolutePath))?.let { key(it) }

    fun relativize(file: File, root: File): String = relativize(file.absolutePath, root.absolutePath)
        ?: file.name

    fun relativize(path: String, root: String): String? {
        val rootWithoutTrailingSeparator = root.trimEnd('\\', '/')
        if (!path.startsWith(rootWithoutTrailingSeparator, ignoreCase = true)) return null
        val rest = path.substring(rootWithoutTrailingSeparator.length)
        if (rest.isNotEmpty() && !isSeparator(rest[0])) return null
        return rest.trimStart('\\', '/')
    }

    private fun isSeparator(char: Char): Boolean = char == '/' || char == '\\'
}
