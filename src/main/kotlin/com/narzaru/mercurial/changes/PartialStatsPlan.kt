package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem

sealed interface PartialStatsAction {

    data object Skip : PartialStatsAction

    data object CountAll : PartialStatsAction

    data class Count(
        val range: DiffRange,
        val paths: List<String>,
        val keys: Set<String>,
        val contentPaths: List<String> = emptyList()
    ) : PartialStatsAction {

        val needsDiff: Boolean get() = paths.isNotEmpty()
    }
}

object PartialStatsPlan {

    fun of(
        mode: HgDisplayMode,
        targetRev: String,
        files: List<HgFileItem>,
        changedKeys: Collection<String>
    ): PartialStatsAction {
        if (mode.isChangesets) return PartialStatsAction.Skip
        if (changedKeys.isEmpty() || files.isEmpty()) return PartialStatsAction.Skip

        val wanted = changedKeys.mapTo(HashSet()) { HgPaths.key(it) }
        val touched = files.filter { item ->
            HgPaths.key(item.path) in wanted ||
                (item.copiedFrom.isNotEmpty() && HgPaths.key(item.copiedFrom) in wanted)
        }
        if (touched.isEmpty()) return PartialStatsAction.Skip

        val ranges = touched.mapTo(LinkedHashSet()) { DiffStatsPlan.rangeOf(it, targetRev) }
        if (ranges.any { it.head.isNotEmpty() }) return PartialStatsAction.Skip
        val range = ranges.singleOrNull() ?: return PartialStatsAction.CountAll
        if (touched.size > DiffStatsPlan.MAX_DIFF_PATHS) return PartialStatsAction.CountAll

        val (hashed, diffed) = touched.partition { DiffStatsPlan.isHashedByContent(it) }
        val paths = if (diffed.isEmpty()) {
            emptyList()
        } else {
            DiffStatsPlan.pathsByRange(listOf(range), diffed, targetRev)[range] ?: return PartialStatsAction.CountAll
        }
        return PartialStatsAction.Count(
            range,
            paths,
            touched.mapTo(LinkedHashSet()) { HgPaths.key(it.path) },
            hashed.map { it.path }
        )
    }
}
