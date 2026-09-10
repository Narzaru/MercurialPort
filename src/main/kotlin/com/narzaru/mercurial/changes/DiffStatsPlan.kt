package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.hg.HgStatusParser
import com.narzaru.mercurial.model.HgDiffStat
import com.narzaru.mercurial.model.HgFileItem

data class DiffRange(val base: String, val head: String)

object DiffStatsPlan {

    const val MAX_DIFF_PATHS = 200

    const val IGNORE_EOL_FLAG = "--ignore-space-at-eol"

    const val UNCHANGED_STATUS = "♦"

    fun applyStats(
        files: List<HgFileItem>,
        stats: Map<DiffRange, Map<String, HgDiffStat>>,
        targetRev: String
    ): List<HgFileItem> = files.map { item ->
        val byPath = stats[rangeOf(item, targetRev)].orEmpty()
        val stat = byPath[HgPaths.normalize(item.path)]
            ?: item.copiedFrom.takeIf { it.isNotEmpty() }?.let { byPath[HgPaths.normalize(it)] }
        val unchanged = item.status == HgStatusParser.MODIFIED_STATUS && stat == null
        item.copy(
            status = if (unchanged) UNCHANGED_STATUS else item.status,
            isUnchanged = unchanged,
            added = stat?.added ?: 0,
            removed = stat?.removed ?: 0
        )
    }

    fun rangeOf(item: HgFileItem, targetRev: String): DiffRange =
        DiffRange(item.baseRev.ifEmpty { targetRev }, item.headRev)

    fun pathsByRange(
        ranges: List<DiffRange>,
        files: List<HgFileItem>,
        targetRev: String
    ): Map<DiffRange, List<String>> {
        if (files.size > MAX_DIFF_PATHS) return emptyMap()
        val byRange = HashMap<DiffRange, MutableList<String>>()
        for (item in files) {
            val range = rangeOf(item, targetRev)
            if (range !in ranges) continue
            val paths = byRange.getOrPut(range) { ArrayList() }
            paths.add(item.path)
            if (item.copiedFrom.isNotEmpty()) paths.add(item.copiedFrom)
        }
        return byRange
    }

    fun arguments(range: DiffRange, paths: List<String>, ignoreEolChanges: Boolean): List<String> {
        val args = arrayListOf("diff", "--git", "--rev", range.base)
        if (range.head.isNotEmpty()) {
            args.add("--rev")
            args.add(range.head)
        }
        if (ignoreEolChanges) args.add(IGNORE_EOL_FLAG)
        paths.mapTo(args) { "path:$it" }
        return args
    }
}
