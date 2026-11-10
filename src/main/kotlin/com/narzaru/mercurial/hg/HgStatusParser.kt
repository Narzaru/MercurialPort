package com.narzaru.mercurial.hg

import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgFileStatus

object HgStatusParser {

    fun parse(stdout: String): List<HgFileItem> {
        val items = ArrayList<HgFileItem>()
        for (line in stdout.split('\r', '\n')) {
            if (!isStatusLine(line)) continue
            if (line.startsWith("  ")) {
                val last = items.lastOrNull() ?: continue
                items[items.size - 1] = last.copy(copiedFrom = line.trim())
                continue
            }
            val status = HgFileStatus.ofLetter(line[0]) ?: continue
            items.add(HgFileItem(status = status, path = line.substring(2).trim()))
        }
        return items
    }

    private fun isStatusLine(line: String): Boolean = line.length > 2 && line[1] == ' '

    fun foldRenames(items: List<HgFileItem>): List<HgFileItem> {
        val removed = items
            .filter { it.status == HgFileStatus.REMOVED || it.status == HgFileStatus.MISSING }
            .map { HgPaths.key(it.path) }
            .toSet()
        val renameSources = HashSet<String>()

        val folded = items.map { item ->
            val source = item.copiedFrom
            if (item.status != HgFileStatus.ADDED || source.isEmpty() || HgPaths.key(source) !in removed) {
                item
            } else {
                renameSources.add(HgPaths.key(source))
                item.copy(status = HgFileStatus.RENAMED)
            }
        }
        return folded.filter { HgPaths.key(it.path) !in renameSources || it.status == HgFileStatus.RENAMED }
    }

    fun statusFlags(includeUntracked: Boolean): String = if (includeUntracked) "-mardu" else "-mard"

    const val COPIES_FLAG = "--copies"
}
