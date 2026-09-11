package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlistedPathsProbeTest {

    @Test
    fun `paths never seen before are asked about`() {
        val action = UnlistedPathsProbe.of(listOf("obj/a.dll", "bin/b.pdb"), emptySet())

        assertEquals(UnlistedPathsAction.Ask(listOf("obj/a.dll", "bin/b.pdb")), action)
    }

    @Test
    fun `paths already known to be quiet are not asked about again`() {
        val action = UnlistedPathsProbe.of(listOf("obj/a.dll"), setOf("obj/a.dll"))

        assertEquals(UnlistedPathsAction.Ignore, action)
    }

    @Test
    fun `a quiet path does not hide a fresh one in the same batch`() {
        val action = UnlistedPathsProbe.of(listOf("obj/a.dll", "src/new.kt"), setOf("obj/a.dll"))

        assertEquals(UnlistedPathsAction.Ask(listOf("src/new.kt")), action)
    }

    @Test
    fun `the same path in two spellings is asked about once`() {
        val action = UnlistedPathsProbe.of(listOf("Obj/A.dll", "obj/a.dll"), emptySet())

        assertEquals(UnlistedPathsAction.Ask(listOf("obj/a.dll")), action)
    }

    @Test
    fun `a batch too big for one question is asked about in parts`() {
        val keys = (1..UnlistedPathsProbe.MAX_ASKED_PATHS + 10).map { "obj/file$it.dll" }

        val action = UnlistedPathsProbe.of(keys, emptySet()) as UnlistedPathsAction.Ask

        assertEquals(keys.take(UnlistedPathsProbe.MAX_ASKED_PATHS), action.keys)
    }

    @Test
    fun `a batch right at the limit is one question`() {
        val keys = (1..UnlistedPathsProbe.MAX_ASKED_PATHS).map { "obj/file$it.dll" }

        assertEquals(UnlistedPathsAction.Ask(keys), UnlistedPathsProbe.of(keys, emptySet()))
    }

    @Test
    fun `a batch beyond any reasonable waiting reloads the list instead`() {
        val keys = (0..UnlistedPathsProbe.MAX_WAITING_PATHS).map { "obj/file$it.dll" }

        assertEquals(UnlistedPathsAction.Reload, UnlistedPathsProbe.of(keys, emptySet()))
    }

    @Test
    fun `the question is a status of exactly the asked paths`() {
        assertEquals(
            listOf("status", "-mard", "path:obj/a.dll", "path:src/new.kt"),
            UnlistedPathsProbe.arguments(listOf("obj/a.dll", "src/new.kt"), false)
        )
        assertEquals(
            listOf("status", "-mardu", "path:src/new.kt"),
            UnlistedPathsProbe.arguments(listOf("src/new.kt"), true)
        )
    }

    @Test
    fun `silence from mercurial means the paths do not belong in the list`() {
        assertFalse(UnlistedPathsProbe.listable("", false))
        assertFalse(UnlistedPathsProbe.listable("\n", true))
    }

    @Test
    fun `a tracked change makes the list outdated`() {
        for (line in listOf("M src/a.kt", "A src/a.kt", "R src/a.kt", "! src/a.kt")) {
            assertTrue(line, UnlistedPathsProbe.listable(line, false))
        }
    }

    @Test
    fun `an untracked file matters only when untracked files are shown`() {
        assertFalse(UnlistedPathsProbe.listable("? src/new.kt", false))
        assertTrue(UnlistedPathsProbe.listable("? src/new.kt", true))
    }

    @Test
    fun `quiet paths are remembered`() {
        val quiet = UnlistedPathsProbe.remembered(setOf("obj/a.dll"), listOf("bin/b.pdb"))

        assertEquals(setOf("obj/a.dll", "bin/b.pdb"), quiet)
    }

    @Test
    fun `the oldest quiet paths are forgotten once there are too many`() {
        val first = (1..UnlistedPathsProbe.MAX_QUIET_PATHS).map { "obj/file$it.dll" }
        val quiet = UnlistedPathsProbe.remembered(
            UnlistedPathsProbe.remembered(emptySet(), first),
            listOf("bin/fresh.pdb")
        )

        assertEquals(UnlistedPathsProbe.MAX_QUIET_PATHS, quiet.size)
        assertFalse("obj/file1.dll" in quiet)
        assertTrue("bin/fresh.pdb" in quiet)
    }
}
