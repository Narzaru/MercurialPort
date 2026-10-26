package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgFileItem

class UnreviewedDirs private constructor(private val keys: Set<String>) {

    fun allReviewed(dirPath: String): Boolean = HgPaths.key(dirPath) !in keys

    companion object {

        fun of(files: List<HgFileItem>, isReviewed: (HgFileItem) -> Boolean): UnreviewedDirs {
            val keys = HashSet<String>()
            for (file in files) {
                if (file.isUnchanged || isReviewed(file)) continue
                var dir = parentOf(HgPaths.key(file.path))
                while (dir.isNotEmpty() && keys.add(dir)) dir = parentOf(dir)
            }
            return UnreviewedDirs(keys)
        }

        private fun parentOf(path: String): String = path.substringBeforeLast('/', "")
    }
}
