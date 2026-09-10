package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgFileItem

data class FileChangeReaction(val changedKeys: List<String>, val diffKeys: List<String>) {

    val rescanTodos: Boolean get() = changedKeys.isNotEmpty()

    val isEmpty: Boolean get() = changedKeys.isEmpty() && diffKeys.isEmpty()

    companion object {
        val NONE = FileChangeReaction(emptyList(), emptyList())
    }
}

object FileChangeScope {

    fun of(
        repoRoot: String,
        changedPaths: Collection<String>,
        files: List<HgFileItem>,
        openDiffKeys: Set<String>
    ): FileChangeReaction {
        if (changedPaths.isEmpty() || files.isEmpty()) return FileChangeReaction.NONE
        val root = HgPaths.normalize(repoRoot).trimEnd('/')
        val listed = files.asSequence().map { HgPaths.key(it.path) }.toSet()
        val touched = LinkedHashSet<String>()
        for (path in changedPaths) {
            val relative = HgPaths.relativize(HgPaths.normalize(path), root) ?: continue
            if (relative.isEmpty()) continue
            val key = HgPaths.key(relative)
            if (key in listed) touched.add(key)
        }
        if (touched.isEmpty()) return FileChangeReaction.NONE
        return FileChangeReaction(touched.toList(), touched.filter { it in openDiffKeys })
    }
}
