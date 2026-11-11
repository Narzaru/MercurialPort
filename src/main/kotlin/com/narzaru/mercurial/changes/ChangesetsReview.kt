package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgHash
import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgDiffStat
import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgFileStatus
import com.narzaru.mercurial.review.ReviewChangeType
import com.narzaru.mercurial.review.ReviewChanges
import com.narzaru.mercurial.review.ReviewFile
import com.narzaru.mercurial.review.ReviewFileDiff
import com.narzaru.mercurial.review.ReviewLoad
import java.util.concurrent.ConcurrentHashMap

class ChangesetsReview(private val changes: ReviewChanges, val load: ReviewLoad) {

    val items: List<HgFileItem>

    private val filesByKey: Map<String, ReviewFile>

    private val leftSides = ConcurrentHashMap<String, String>()

    init {
        val listed = load.files.map { ReviewFileItems.of(it) to it }.distinctBy { HgPaths.key(it.first.path) }
        items = listed.map { it.first }
        filesByKey = listed.associate { (item, file) -> HgPaths.key(item.path) to file }
    }

    fun leftSide(item: HgFileItem): String? {
        val key = HgPaths.key(item.path)
        val file = filesByKey[key]?.takeUnless { it.binary } ?: return null
        leftSides[key]?.let { return it }
        val text = changes.diff(load, file).leftSide().joinToString("\n")
        return leftSides.putIfAbsent(key, text) ?: text
    }

    fun stats(): Map<String, HgDiffStat?> {
        val keys = filesByKey.keys.toList()
        val diffs = changes.diffs(load, keys.map { filesByKey.getValue(it) })
        val stats = HashMap<String, HgDiffStat?>()
        for ((key, diff) in keys.zip(diffs)) stats[key] = ReviewStats.of(diff)
        return stats
    }
}

object ReviewFileItems {

    fun of(file: ReviewFile): HgFileItem {
        val baseRev = file.baseRev ?: BranchRevisions.NO_REVISION
        return when (file.type) {
            ReviewChangeType.ADDED -> HgFileItem(HgFileStatus.ADDED, file.path, baseRev = baseRev, headRev = file.headRev)
            ReviewChangeType.DELETED ->
                HgFileItem(HgFileStatus.REMOVED, file.oldPath, baseRev = baseRev, headRev = file.headRev)
            ReviewChangeType.MODIFIED ->
                HgFileItem(HgFileStatus.MODIFIED, file.path, baseRev = baseRev, headRev = file.headRev)
            ReviewChangeType.RENAMED -> HgFileItem(
                HgFileStatus.RENAMED, file.path, copiedFrom = file.oldPath, baseRev = baseRev, headRev = file.headRev
            )
            ReviewChangeType.COPIED -> HgFileItem(
                HgFileStatus.ADDED, file.path, copiedFrom = file.oldPath, baseRev = baseRev, headRev = file.headRev
            )
        }
    }
}

object ReviewStats {

    fun of(diff: ReviewFileDiff): HgDiffStat? {
        val digest = HgHash.newDigest()
        fun hash(line: String) = digest.update((line + "\n").toByteArray(Charsets.UTF_8))

        val file = diff.file
        if (file.type == ReviewChangeType.RENAMED || file.type == ReviewChangeType.COPIED) {
            hash("${file.type} ${file.oldPath}")
        }
        if (file.binary) {
            hash("binary")
            diff.oldLines.forEach { hash("-$it") }
            diff.newLines.forEach { hash("+$it") }
            return HgDiffStat(0, 0, HgHash.text(digest.digest()))
        }
        val shown = diff.shownHunks
        if (shown.isEmpty()) return null
        var added = 0
        var removed = 0
        for (hunk in shown) {
            val left = hunk.fragment.left
            val right = hunk.fragment.right
            hash("@@")
            for (i in left.start until left.end) hash("-${diff.oldLines[i]}")
            for (j in right.start until right.end) hash("+${diff.newLines[j]}")
            removed += countedLines(left, diff.oldLines)
            added += countedLines(right, diff.newLines)
        }
        return HgDiffStat(added, removed, HgHash.text(digest.digest()))
    }

    fun countedLines(range: LineRange, side: List<String>): Int {
        val size = (range.end - range.start).coerceAtLeast(0)
        val endsOnFinalBreak = size > 0 && range.end == side.size && side.last().isEmpty()
        return if (endsOnFinalBreak) size - 1 else size
    }

    fun apply(items: List<HgFileItem>, stats: Map<String, HgDiffStat?>): List<HgFileItem> = items.map { item ->
        val key = HgPaths.key(item.path)
        if (!stats.containsKey(key)) return@map item
        val stat = stats[key]
        val status = if (item.isUnchanged) HgFileStatus.MODIFIED else item.status
        val unchanged = status == HgFileStatus.MODIFIED && stat == null
        item.copy(
            status = if (unchanged) HgFileStatus.UNCHANGED else status,
            isUnchanged = unchanged,
            added = stat?.added ?: 0,
            removed = stat?.removed ?: 0
        )
    }

    fun hashes(stats: Map<String, HgDiffStat?>): Map<String, String> =
        stats.mapValues { (_, stat) -> stat?.hash ?: DiffStatsPlan.NO_DIFF_HASH }
}
