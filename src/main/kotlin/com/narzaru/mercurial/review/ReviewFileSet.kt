package com.narzaru.mercurial.review

import com.narzaru.mercurial.model.HgFileStatus

enum class ReviewCopy { COPY, RENAME }

data class ReviewRename(val node: String, val from: String, val to: String)

data class ReviewEntry(
    val path: String,
    val oldPath: String,
    val copy: ReviewCopy? = null,
    val names: List<String> = listOfNotNull(oldPath, path.takeIf { it != oldPath }),
    val renames: List<ReviewRename> = emptyList()
)

class ReviewTouches(val node: String, val paths: Set<String>)

object ReviewFileSet {

    fun entries(revisions: List<ReviewRevision>): List<ReviewEntry> {
        val entries = LinkedHashMap<String, ReviewEntry>()
        for (revision in revisions.filterNot { it.isMerge }.sortedBy { it.rev }) {
            val removed = LinkedHashSet<String>()
            val renamedSources = HashSet<String>()
            for (item in revision.changes) {
                when (item.status) {
                    HgFileStatus.REMOVED -> removed.add(item.path)
                    HgFileStatus.RENAMED -> removed.add(item.copiedFrom)
                    else -> Unit
                }
            }
            for (item in revision.changes) {
                if (item.status != HgFileStatus.ADDED && item.status != HgFileStatus.RENAMED) continue
                val source = item.copiedFrom
                if (source.isEmpty()) {
                    entries.putIfAbsent(item.path, ReviewEntry(item.path, item.path))
                    continue
                }
                val isRename = item.status == HgFileStatus.RENAMED
                val origin = entries[source] ?: ReviewEntry(source, source)
                entries[item.path] = ReviewEntry(
                    path = item.path,
                    oldPath = origin.oldPath,
                    copy = if (isRename) ReviewCopy.RENAME else ReviewCopy.COPY,
                    names = (origin.names + item.path).distinct(),
                    renames = if (isRename) origin.renames + ReviewRename(revision.node, source, item.path)
                    else origin.renames
                )
                if (isRename) renamedSources.add(source)
            }
            for (item in revision.changes) {
                if (item.status == HgFileStatus.MODIFIED) entries.putIfAbsent(item.path, ReviewEntry(item.path, item.path))
            }
            for (path in removed) {
                if (path in renamedSources) entries.remove(path)
                else entries.putIfAbsent(path, ReviewEntry(path, path))
            }
        }
        return entries.values.toList()
    }

    fun mergeTouches(changedFromP1: Collection<String>, changedFromP2: Collection<String>?): Set<String> {
        if (changedFromP2 == null) return changedFromP1.toSet()
        val fromP2 = changedFromP2.toHashSet()
        return changedFromP1.filterTo(LinkedHashSet()) { it in fromP2 }
    }

    fun comparesSecondParent(merge: ReviewRevision, baseBranch: String): Boolean = merge.p2Branch == baseBranch

    fun touching(names: Collection<String>, touches: List<ReviewTouches>): List<String> {
        val wanted = names.toHashSet()
        return touches.filter { touch -> touch.paths.any { it in wanted } }.map { it.node }
    }

    fun split(entry: ReviewEntry, failed: Int): List<ReviewEntry> {
        val link = entry.renames[failed]
        val renamesBefore = entry.renames.subList(0, failed)
        val renamesAfter = entry.renames.subList(failed + 1, entry.renames.size)
        val before = (listOf(entry.oldPath) + renamesBefore.map { it.to }).distinct()
        val after = (listOf(link.to) + renamesAfter.map { it.to } + entry.path).distinct()
        return listOf(
            ReviewEntry(link.from, entry.oldPath, ReviewCopy.RENAME.takeIf { renamesBefore.isNotEmpty() }, before, renamesBefore),
            ReviewEntry(entry.path, link.to, kindAfter(entry, renamesAfter), after, renamesAfter)
        )
    }

    private fun kindAfter(entry: ReviewEntry, renamesAfter: List<ReviewRename>): ReviewCopy? = when {
        renamesAfter.lastOrNull()?.to == entry.path -> ReviewCopy.RENAME
        renamesAfter.isEmpty() && entry.path == entry.renames.last().to -> null
        else -> entry.copy
    }
}
