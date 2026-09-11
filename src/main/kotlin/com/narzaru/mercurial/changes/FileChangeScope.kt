package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgFileItem

data class FileChangeReaction(
    val changedKeys: List<String>,
    val diffKeys: List<String>,
    val unlistedKeys: List<String> = emptyList(),
    val repoStateTouched: Boolean = false,
    val ignoreRulesChanged: Boolean = false
) {

    val rescanTodos: Boolean get() = changedKeys.isNotEmpty()

    val isEmpty: Boolean
        get() = changedKeys.isEmpty() && diffKeys.isEmpty() && unlistedKeys.isEmpty() &&
            !repoStateTouched && !ignoreRulesChanged

    companion object {
        val NONE = FileChangeReaction(emptyList(), emptyList())
    }
}

object FileChangeScope {

    const val HG_DIR = ".hg"

    const val IGNORE_RULES = ".hgignore"

    val NOISY_DIRECTORIES = setOf(".gradle", ".idea", "build", "out", "target", "node_modules")

    val TEMPORARY_SUFFIXES = listOf("___jb_tmp___", "___jb_old___", "~")

    fun of(
        repoRoot: String,
        changedPaths: Collection<String>,
        files: List<HgFileItem>,
        openDiffKeys: Set<String>
    ): FileChangeReaction {
        if (changedPaths.isEmpty()) return FileChangeReaction.NONE
        val root = HgPaths.normalize(repoRoot).trimEnd('/')
        val listed = files.asSequence().map { HgPaths.key(it.path) }.toSet()
        val touched = LinkedHashSet<String>()
        val unlisted = LinkedHashSet<String>()
        var repoState = false
        var ignoreRules = false
        for (path in changedPaths) {
            val relative = HgPaths.relativize(HgPaths.normalize(path), root) ?: continue
            if (relative.isEmpty()) continue
            val key = HgPaths.key(relative)
            if (isIgnoreRules(key)) ignoreRules = true
            when {
                key in listed -> touched.add(key)
                key == HG_DIR || key.startsWith("$HG_DIR/") -> if (isRepoState(key)) repoState = true
                isNoise(key) -> Unit
                else -> unlisted.add(key)
            }
        }
        return FileChangeReaction(
            touched.toList(),
            touched.filter { it in openDiffKeys },
            unlisted.toList(),
            repoState,
            ignoreRules
        )
    }

    private fun isIgnoreRules(key: String): Boolean =
        key == IGNORE_RULES || key.endsWith("/$IGNORE_RULES")

    private fun isRepoState(key: String): Boolean {
        val name = key.removePrefix("$HG_DIR/")
        if (name.isEmpty() || name.contains('/')) return false
        return name.startsWith("dirstate") || name == "branch" || name.startsWith("bookmarks")
    }

    private fun isNoise(key: String): Boolean {
        if (TEMPORARY_SUFFIXES.any { key.endsWith(it) }) return true
        return key.split('/').any { it in NOISY_DIRECTORIES }
    }
}
