package com.narzaru.mercurial.changes

import com.narzaru.mercurial.changes.FileChangeCommand.AskUnlisted
import com.narzaru.mercurial.changes.FileChangeCommand.ProbeRepoState
import com.narzaru.mercurial.changes.FileChangeCommand.Recount
import com.narzaru.mercurial.changes.FileChangeCommand.Refresh
import com.narzaru.mercurial.changes.FileChangeCommand.ReloadDiffTabs
import com.narzaru.mercurial.changes.FileChangeCommand.ReportUnchecked
import com.narzaru.mercurial.changes.FileChangeCommand.RescanTodos
import com.narzaru.mercurial.changes.FileChangeCommand.RetryLater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileChangeCoordinatorTest {

    private val coordinator = FileChangeCoordinator()

    private val idle = PanelConditions(busy = false, hasRepo = true)
    private val busy = PanelConditions(busy = true, hasRepo = true)

    private fun changed(vararg keys: String) = FileChangeReaction(keys.toList(), emptyList())

    private fun unlisted(vararg keys: String) = FileChangeReaction(emptyList(), emptyList(), keys.toList())

    private val ignoreRules = FileChangeReaction(emptyList(), emptyList(), ignoreRulesChanged = true)

    private val repoState = FileChangeReaction(emptyList(), emptyList(), repoStateTouched = true)

    private val nothing = FileChangeReaction.NONE

    private fun ask(reaction: FileChangeReaction): AskUnlisted =
        coordinator.react(reaction, idle).filterIsInstance<AskUnlisted>().single()

    @Test
    fun `changed files are recounted and their open diffs reloaded first`() {
        val commands = coordinator.react(FileChangeReaction(listOf("a.kt", "b.kt"), listOf("a.kt")), idle)

        assertEquals(listOf(ReloadDiffTabs(listOf("a.kt")), Recount(listOf("a.kt", "b.kt"))), commands)
        assertFalse(coordinator.workPending)
    }

    @Test
    fun `a quiet reaction still flushes the stats waiting for a recount`() {
        assertEquals(listOf(Recount(emptyList())), coordinator.react(nothing, idle))
    }

    @Test
    fun `a refresh is deferred while busy and retried`() {
        val deferred = coordinator.react(ignoreRules, busy)

        assertEquals(listOf(RetryLater, Recount(emptyList())), deferred)
        assertTrue(coordinator.workPending)

        assertEquals(listOf(Refresh), coordinator.react(nothing, idle))
        assertFalse(coordinator.workPending)
    }

    @Test
    fun `a refresh supersedes the rest of the reaction`() {
        val reaction = FileChangeReaction(listOf("a.kt"), emptyList(), ignoreRulesChanged = true)

        assertEquals(listOf(Refresh), coordinator.react(reaction, idle))
    }

    @Test
    fun `a todo rescan waits until the panel is idle`() {
        val todos = PanelConditions(busy = true, hasRepo = true, listsTodos = true)
        coordinator.todoRescanRequested()

        assertEquals(listOf(Recount(emptyList()), RetryLater), coordinator.react(nothing, todos))
        assertEquals(listOf(Recount(emptyList()), RescanTodos), coordinator.react(nothing, todos.copy(busy = false)))
        assertFalse(coordinator.workPending)
    }

    @Test
    fun `changed files rescan todos only in the todo list`() {
        val todos = PanelConditions(busy = false, hasRepo = true, listsTodos = true)

        assertEquals(listOf(Recount(listOf("a.kt")), RescanTodos), coordinator.react(changed("a.kt"), todos))
        assertEquals(listOf(Recount(listOf("a.kt"))), coordinator.react(changed("a.kt"), idle))
    }

    @Test
    fun `a todo rescan requested outside the todo list is dropped`() {
        coordinator.todoRescanRequested()

        coordinator.react(nothing, idle)

        assertFalse(coordinator.workPending)
    }

    @Test
    fun `a moved working directory reloads the changes`() {
        coordinator.loaded(RepoState("default", "aaa"))
        val probe = coordinator.react(repoState, idle).filterIsInstance<ProbeRepoState>().single()

        assertEquals(listOf(Refresh), coordinator.repoStateProbed(probe, RepoState("default", "bbb"), false))
    }

    @Test
    fun `an unmoved working directory changes nothing`() {
        coordinator.loaded(RepoState("default", "aaa"))
        val probe = coordinator.react(repoState, idle).filterIsInstance<ProbeRepoState>().single()

        assertEquals(emptyList<FileChangeCommand>(), coordinator.repoStateProbed(probe, RepoState("default", "aaa"), false))
    }

    @Test
    fun `a repo state probe is not started twice`() {
        coordinator.react(repoState, idle)

        assertEquals(listOf(Recount(emptyList()), RetryLater), coordinator.react(repoState, idle))
    }

    @Test
    fun `the repo state is probed only with a repository`() {
        val noRepo = PanelConditions(busy = false, hasRepo = false)

        assertEquals(listOf(Recount(emptyList()), RetryLater), coordinator.react(repoState, noRepo))
    }

    @Test
    fun `a stale repo state probe is ignored after a reset`() {
        coordinator.loaded(RepoState("default", "aaa"))
        val probe = coordinator.react(repoState, idle).filterIsInstance<ProbeRepoState>().single()

        coordinator.reset()

        assertEquals(emptyList<FileChangeCommand>(), coordinator.repoStateProbed(probe, RepoState("default", "bbb"), false))
    }

    @Test
    fun `a probe that moves the working directory while busy defers the refresh`() {
        coordinator.loaded(RepoState("default", "aaa"))
        val probe = coordinator.react(repoState, idle).filterIsInstance<ProbeRepoState>().single()

        assertEquals(listOf(RetryLater), coordinator.repoStateProbed(probe, RepoState("default", "bbb"), true))
        assertTrue(coordinator.workPending)
    }

    @Test
    fun `unlisted keys that arrive during a probe are asked about in the next batch`() {
        val first = ask(unlisted("obj/a", "obj/b"))
        assertEquals(listOf("obj/a", "obj/b"), first.keys)

        assertEquals(listOf(Recount(emptyList()), RetryLater), coordinator.react(unlisted("obj/c"), idle))

        val next = coordinator.unlistedAnswered(first, UnlistedAnswer.Quiet, idle)
        assertEquals(listOf("obj/c"), next.filterIsInstance<AskUnlisted>().single().keys)
    }

    @Test
    fun `quiet keys are not asked about again`() {
        val first = ask(unlisted("obj/a"))
        coordinator.unlistedAnswered(first, UnlistedAnswer.Quiet, idle)

        assertEquals(listOf(Recount(emptyList())), coordinator.react(unlisted("obj/a"), idle))
        assertFalse(coordinator.workPending)
    }

    @Test
    fun `quiet keys are forgotten when the ignore rules change`() {
        coordinator.unlistedAnswered(ask(unlisted("obj/a")), UnlistedAnswer.Quiet, idle)
        val running = ask(unlisted("obj/c"))

        val reaction = FileChangeReaction(emptyList(), emptyList(), listOf("obj/a"), ignoreRulesChanged = true)
        coordinator.react(reaction, busy)
        val next = coordinator.unlistedAnswered(running, UnlistedAnswer.Quiet, idle)

        assertEquals(listOf("obj/a"), next.filterIsInstance<AskUnlisted>().single().keys)
    }

    @Test
    fun `a listable answer reloads the changes`() {
        val running = ask(unlisted("src/New.kt"))

        assertEquals(listOf(Refresh), coordinator.unlistedAnswered(running, UnlistedAnswer.Listable, idle))
    }

    @Test
    fun `a failed answer is reported and reloads the changes`() {
        val answer = UnlistedAnswer.Failed("boom")

        assertEquals(
            listOf(ReportUnchecked("boom"), Refresh),
            coordinator.unlistedAnswered(ask(unlisted("src/New.kt")), answer, idle)
        )
    }

    @Test
    fun `a stale answer is ignored after a reset`() {
        val first = ask(unlisted("src/New.kt"))

        coordinator.reset()

        assertEquals(emptyList<FileChangeCommand>(), coordinator.unlistedAnswered(first, UnlistedAnswer.Listable, idle))
    }

    @Test
    fun `too many unlisted paths reload the changes`() {
        val keys = (0..UnlistedPathsProbe.MAX_WAITING_PATHS).map { "gen/f$it.txt" }.toTypedArray()

        assertEquals(listOf(Recount(emptyList()), Refresh), coordinator.react(unlisted(*keys), idle))
    }

    @Test
    fun `unlisted paths wait while busy`() {
        assertEquals(listOf(Recount(emptyList()), RetryLater), coordinator.react(unlisted("obj/a"), busy))
        assertEquals("obj/a", ask(nothing).keys.single())
    }

    @Test
    fun `changed paths are handed over once`() {
        coordinator.pathsChanged(listOf("/r/a.kt", "/r/b.kt"))
        assertTrue(coordinator.workPending)

        assertEquals(listOf("/r/a.kt", "/r/b.kt"), coordinator.takeChangedPaths())
        assertEquals(emptyList<String>(), coordinator.takeChangedPaths())
    }

    @Test
    fun `a reset drops all pending work`() {
        coordinator.pathsChanged(listOf("/r/a.kt"))
        coordinator.todoRescanRequested()
        coordinator.react(ignoreRules, busy)
        coordinator.react(unlisted("obj/a"), busy)

        coordinator.reset()

        assertFalse(coordinator.workPending)
        assertEquals(emptyList<String>(), coordinator.takeChangedPaths())
    }
}
