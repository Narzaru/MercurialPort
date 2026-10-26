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

    const val NO_DIFF_HASH = "none"

    fun applyStats(
        files: List<HgFileItem>,
        stats: Map<DiffRange, Map<String, HgDiffStat>>,
        targetRev: String
    ): List<HgFileItem> = files.map { counted(it, stats, targetRev) }

    fun applyStatsToKeys(
        files: List<HgFileItem>,
        stats: Map<DiffRange, Map<String, HgDiffStat>>,
        targetRev: String,
        keys: Set<String>
    ): List<HgFileItem> = files.map { item ->
        if (HgPaths.key(item.path) in keys) counted(item, stats, targetRev) else item
    }

    fun diffHashes(
        files: List<HgFileItem>,
        stats: Map<DiffRange, Map<String, HgDiffStat>>,
        targetRev: String,
        contentHashes: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val hashes = HashMap<String, String>()
        for (item in files) {
            val key = HgPaths.key(item.path)
            if (isHashedByContent(item)) {
                contentHashes[key]?.let { hashes[key] = it }
                continue
            }
            val byPath = stats[rangeOf(item, targetRev)] ?: continue
            hashes[key] = statOf(item, byPath)?.hash?.ifEmpty { null } ?: NO_DIFF_HASH
        }
        return hashes
    }

    fun isHashedByContent(item: HgFileItem): Boolean =
        item.status == HgStatusParser.UNTRACKED_STATUS && item.headRev.isEmpty()

    fun contentHashedPaths(files: List<HgFileItem>): List<String> =
        files.filter { isHashedByContent(it) }.map { it.path }

    fun contentHashes(paths: List<String>, hashOf: (String) -> String?): Map<String, String> {
        val hashes = HashMap<String, String>()
        for (path in paths) hashOf(path)?.let { hashes[HgPaths.key(path)] = it }
        return hashes
    }

    private fun statOf(item: HgFileItem, byPath: Map<String, HgDiffStat>): HgDiffStat? =
        byPath[HgPaths.normalize(item.path)]
            ?: item.copiedFrom.takeIf { it.isNotEmpty() }?.let { byPath[HgPaths.normalize(it)] }

    private fun counted(
        item: HgFileItem,
        stats: Map<DiffRange, Map<String, HgDiffStat>>,
        targetRev: String
    ): HgFileItem {
        val stat = statOf(item, stats[rangeOf(item, targetRev)].orEmpty())
        val unchanged = statusOf(item) == HgStatusParser.MODIFIED_STATUS && stat == null
        return item.copy(
            status = if (unchanged) UNCHANGED_STATUS else statusOf(item),
            isUnchanged = unchanged,
            added = stat?.added ?: 0,
            removed = stat?.removed ?: 0
        )
    }

    private fun statusOf(item: HgFileItem): String =
        if (item.isUnchanged) HgStatusParser.MODIFIED_STATUS else item.status

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
