package com.narzaru.mercurial.changes

class StatsLoad(
    val id: Int,
    val ranges: List<DiffRange>,
    val paths: Map<DiffRange, List<String>>
)

class PartialStatsRun(
    val id: Int,
    val refreshStamp: Int,
    val loadStamp: Int,
    val action: PartialStatsAction.Count
)

sealed interface StatsCommand {

    data object Wait : StatsCommand

    data class CountAll(val load: StatsLoad) : StatsCommand

    data class Count(val run: PartialStatsRun) : StatsCommand
}

enum class PartialStatsOutcome { SUPERSEDED, OUTDATED, CURRENT }

class StatsScheduler {

    var loading = false
        private set

    private var loadId = 0
    private var ranges: List<DiffRange> = emptyList()
    private var paths: Map<DiffRange, List<String>> = emptyMap()
    private var partialRunning = false
    private var partialId = 0
    private val pendingKeys = LinkedHashSet<String>()

    fun reset() {
        loadId++
        loading = false
        partialId++
        partialRunning = false
        pendingKeys.clear()
        ranges = emptyList()
        paths = emptyMap()
    }

    fun startLoad(ranges: List<DiffRange>, paths: Map<DiffRange, List<String>>): StatsLoad {
        loading = true
        this.ranges = ranges
        this.paths = paths
        return StatsLoad(++loadId, ranges, paths)
    }

    fun isCurrent(load: StatsLoad): Boolean = load.id == loadId

    fun finishLoad(load: StatsLoad): Boolean {
        if (!isCurrent(load)) return false
        loading = false
        return true
    }

    fun abandonLoad(load: StatsLoad): Boolean {
        if (!isCurrent(load) || !loading) return false
        loading = false
        return true
    }

    fun recount(
        keys: Collection<String>,
        ready: Boolean,
        refreshStamp: Int,
        plan: (Set<String>) -> PartialStatsAction
    ): StatsCommand {
        if (keys.isEmpty() && pendingKeys.isEmpty()) return StatsCommand.Wait
        if (!ready || loading || partialRunning) {
            pendingKeys.addAll(keys)
            return StatsCommand.Wait
        }
        val batch = LinkedHashSet(pendingKeys)
        batch.addAll(keys)
        pendingKeys.clear()
        return when (val action = plan(batch)) {
            PartialStatsAction.Skip -> StatsCommand.Wait
            PartialStatsAction.CountAll -> StatsCommand.CountAll(startLoad(ranges, paths))
            is PartialStatsAction.Count -> {
                partialRunning = true
                StatsCommand.Count(PartialStatsRun(++partialId, refreshStamp, loadId, action))
            }
        }
    }

    fun finishPartial(run: PartialStatsRun, refreshStamp: Int): PartialStatsOutcome {
        if (run.id != partialId) return PartialStatsOutcome.SUPERSEDED
        partialRunning = false
        val current = run.refreshStamp == refreshStamp && run.loadStamp == loadId
        return if (current) PartialStatsOutcome.CURRENT else PartialStatsOutcome.OUTDATED
    }
}
