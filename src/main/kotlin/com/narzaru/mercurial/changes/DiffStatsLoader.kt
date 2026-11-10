package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgBytesResult
import com.narzaru.mercurial.hg.HgCommandRunner
import com.narzaru.mercurial.hg.HgDiffStatParser
import com.narzaru.mercurial.hg.HgFailure
import com.narzaru.mercurial.model.HgDiffStat

fun interface HgDiffCommands {

    fun run(args: List<String>): HgBytesResult

    companion object {

        fun of(runner: HgCommandRunner): HgDiffCommands = HgDiffCommands { runner.runToBytesDetailed(it) }
    }
}

class DiffStatsLoader(
    private val commands: HgDiffCommands,
    private val jobs: ParallelJobs,
    private val hashOf: (String) -> String?,
    private val warn: (String) -> Unit
) {

    fun countAll(
        targetRev: String,
        ranges: List<DiffRange>,
        paths: Map<DiffRange, List<String>>,
        ignoreEolChanges: Boolean
    ): Map<DiffRange, Map<String, HgDiffStat>?> {
        val stats = LinkedHashMap<DiffRange, Map<String, HgDiffStat>?>()
        val counted = ranges.ifEmpty { listOf(DiffRange(targetRev, "")) }
        jobs.map(counted) { range -> range to count(range, paths[range].orEmpty(), ignoreEolChanges) }
            .forEach { (range, byPath) -> stats[range] = byPath }
        return stats
    }

    fun count(range: DiffRange, paths: List<String>, ignoreEolChanges: Boolean): Map<String, HgDiffStat>? {
        val res = commands.run(DiffStatsPlan.arguments(range, paths, ignoreEolChanges))
        if (res.exitCode == 0) return HgDiffStatParser.parse(res.stdout)
        warn("Diff stats are unavailable: ${HgFailure.message(res.failedToStart, res.stderr)}")
        return null
    }

    fun contentHashes(paths: List<String>): Map<String, String> = DiffStatsPlan.contentHashes(paths, hashOf)
}
