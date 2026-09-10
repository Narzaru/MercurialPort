package com.narzaru.mercurial.hg

import com.narzaru.mercurial.model.HgFileItem

object HgStatusParser {

    const val RENAMED_STATUS = "→"

    const val ADDED_STATUS = "A"

    const val MODIFIED_STATUS = "M"

    const val UNTRACKED_STATUS = "?"

    const val REMOVED_STATUS = "R"

    const val MISSING_STATUS = "!"

    fun parse(stdout: String): List<HgFileItem> {
        val items = ArrayList<HgFileItem>()
        for (line in stdout.split('\r', '\n')) {
            if (!isStatusLine(line)) continue
            if (line.startsWith("  ")) {
                val last = items.lastOrNull() ?: continue
                items[items.size - 1] = last.copy(copiedFrom = line.trim())
                continue
            }
            items.add(HgFileItem(status = line.substring(0, 1), path = line.substring(2).trim()))
        }
        return items
    }

    private fun isStatusLine(line: String): Boolean = line.length > 2 && line[1] == ' '

    fun foldRenames(items: List<HgFileItem>): List<HgFileItem> {
        val removed = items
            .filter { it.status == REMOVED_STATUS || it.status == MISSING_STATUS }
            .map { HgPaths.key(it.path) }
            .toSet()
        val renameSources = HashSet<String>()

        val folded = items.map { item ->
            val source = item.copiedFrom
            if (item.status != ADDED_STATUS || source.isEmpty() || HgPaths.key(source) !in removed) {
                item
            } else {
                renameSources.add(HgPaths.key(source))
                item.copy(status = RENAMED_STATUS)
            }
        }
        return folded.filter { HgPaths.key(it.path) !in renameSources || it.status == RENAMED_STATUS }
    }

    fun statusFlags(includeUntracked: Boolean): String = if (includeUntracked) "-mardu" else "-mard"

    const val COPIES_FLAG = "--copies"
}
