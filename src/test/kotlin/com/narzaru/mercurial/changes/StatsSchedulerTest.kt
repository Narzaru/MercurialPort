package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsSchedulerTest {

    private val scheduler = StatsScheduler()

    private val range = DiffRange(".", "")

    private val batches = ArrayList<Set<String>>()

    private fun count(keys: Set<String>): PartialStatsAction {
        batches.add(keys)
        return PartialStatsAction.Count(range, keys.toList(), keys)
    }

    private fun recount(vararg keys: String, ready: Boolean = true, refreshStamp: Int = 0): StatsCommand =
        scheduler.recount(keys.toList(), ready, refreshStamp, ::count)

    private fun started(command: StatsCommand): PartialStatsRun = (command as StatsCommand.Count).run

    @Test
    fun `nothing to recount does not plan`() {
        assertEquals(StatsCommand.Wait, recount())
        assertEquals(emptyList<Set<String>>(), batches)
    }

    @Test
    fun `changed keys are counted at once when idle`() {
        val run = started(recount("a.kt", "b.kt"))

        assertEquals(setOf("a.kt", "b.kt"), run.action.keys)
        assertEquals(listOf(setOf("a.kt", "b.kt")), batches)
    }

    @Test
    fun `keys wait while the panel is not ready`() {
        assertEquals(StatsCommand.Wait, recount("a.kt", ready = false))

        assertEquals(setOf("a.kt"), started(recount()).action.keys)
    }

    @Test
    fun `runs do not overlap and queued keys are batched`() {
        val first = started(recount("a.kt"))

        assertEquals(StatsCommand.Wait, recount("b.kt"))
        assertEquals(StatsCommand.Wait, recount("c.kt", "b.kt"))
        assertEquals(PartialStatsOutcome.CURRENT, scheduler.finishPartial(first, 0))

        assertEquals(setOf("b.kt", "c.kt"), started(recount()).action.keys)
    }

    @Test
    fun `a run superseded by a reset is ignored`() {
        val run = started(recount("a.kt"))

        scheduler.reset()

        assertEquals(PartialStatsOutcome.SUPERSEDED, scheduler.finishPartial(run, 0))
    }

    @Test
    fun `a superseded run does not release the current one`() {
        val stale = started(recount("a.kt"))
        scheduler.reset()
        started(recount("b.kt"))

        scheduler.finishPartial(stale, 0)

        assertEquals(StatsCommand.Wait, recount("c.kt"))
    }

    @Test
    fun `a run outdated by a refresh is not applied`() {
        val run = started(recount("a.kt", refreshStamp = 1))

        assertEquals(PartialStatsOutcome.OUTDATED, scheduler.finishPartial(run, 2))
    }

    @Test
    fun `a run outdated by a full load is not applied`() {
        val run = started(recount("a.kt"))

        scheduler.startLoad(listOf(range), emptyMap())

        assertEquals(PartialStatsOutcome.OUTDATED, scheduler.finishPartial(run, 0))
    }

    @Test
    fun `keys changed during a load are counted after it`() {
        val load = scheduler.startLoad(listOf(range), emptyMap())
        assertTrue(scheduler.loading)

        assertEquals(StatsCommand.Wait, recount("a.kt"))
        assertTrue(scheduler.finishLoad(load))
        assertFalse(scheduler.loading)
        assertTrue(scheduler.isCurrent(load))

        assertEquals(setOf("a.kt"), started(recount()).action.keys)
    }

    @Test
    fun `a finished load is not abandoned`() {
        val load = scheduler.startLoad(listOf(range), emptyMap())

        scheduler.finishLoad(load)

        assertFalse(scheduler.abandonLoad(load))
    }

    @Test
    fun `a load that never finished is abandoned once`() {
        val load = scheduler.startLoad(listOf(range), emptyMap())

        assertTrue(scheduler.abandonLoad(load))
        assertFalse(scheduler.loading)
        assertFalse(scheduler.abandonLoad(load))
    }

    @Test
    fun `a stale load is neither finished nor abandoned`() {
        val stale = scheduler.startLoad(listOf(range), emptyMap())
        scheduler.startLoad(listOf(range), emptyMap())

        assertFalse(scheduler.finishLoad(stale))
        assertFalse(scheduler.abandonLoad(stale))
        assertFalse(scheduler.isCurrent(stale))
        assertTrue(scheduler.loading)
    }

    @Test
    fun `counting everything reuses the ranges of the last load`() {
        val ranges = listOf(DiffRange("p1", "n1"), DiffRange("n1", "n2"))
        val paths = mapOf(ranges[0] to listOf("a.kt"))
        scheduler.finishLoad(scheduler.startLoad(ranges, paths))

        val command = scheduler.recount(listOf("a.kt"), true, 0) { PartialStatsAction.CountAll }

        val load = (command as StatsCommand.CountAll).load
        assertSame(ranges, load.ranges)
        assertSame(paths, load.paths)
        assertTrue(scheduler.loading)
        assertTrue(scheduler.isCurrent(load))
    }

    @Test
    fun `a skipped batch is dropped`() {
        assertEquals(StatsCommand.Wait, scheduler.recount(listOf("a.kt"), true, 0) { PartialStatsAction.Skip })

        assertEquals(StatsCommand.Wait, recount())
    }

    @Test
    fun `a reset drops queued keys and stored ranges`() {
        scheduler.startLoad(listOf(range), mapOf(range to listOf("a.kt")))
        recount("a.kt")

        scheduler.reset()

        assertFalse(scheduler.loading)
        assertEquals(StatsCommand.Wait, recount())
        val command = scheduler.recount(listOf("b.kt"), true, 0) { PartialStatsAction.CountAll }
        val load = (command as StatsCommand.CountAll).load
        assertEquals(emptyList<DiffRange>(), load.ranges)
        assertEquals(emptyMap<DiffRange, List<String>>(), load.paths)
    }
}
