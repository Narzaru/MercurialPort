package com.narzaru.mercurial.changes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchReloadPlanTest {

    private val plan = BranchReloadPlan()

    @Test
    fun `a new branch name reloads`() {
        plan.loadStarted(true, "default")

        assertTrue(plan.needsReload(true, "stable"))
    }

    @Test
    fun `the loaded branch does not reload again`() {
        plan.loadStarted(true, "stable")

        assertFalse(plan.needsReload(true, "stable"))
        assertFalse(plan.needsReload(true, "  stable "))
    }

    @Test
    fun `an empty name never reloads`() {
        plan.loadStarted(true, "stable")

        assertFalse(plan.needsReload(true, ""))
        assertFalse(plan.needsReload(true, "   "))
    }

    @Test
    fun `modes without the branch field never reload`() {
        assertFalse(plan.needsReload(false, "stable"))
    }

    @Test
    fun `before any load a name reloads`() {
        assertTrue(plan.needsReload(true, "stable"))
    }

    @Test
    fun `a load without the branch field keeps the last loaded branch`() {
        plan.loadStarted(true, "stable")
        plan.loadStarted(false, "other")

        assertFalse(plan.needsReload(true, "stable"))
    }

    @Test
    fun `retyping the name after a load with an empty field reloads`() {
        plan.loadStarted(true, "stable")
        plan.loadStarted(true, "")

        assertTrue(plan.needsReload(true, "stable"))
    }

    @Test
    fun `typing back to the previous name reloads after another load`() {
        plan.loadStarted(true, "stable")
        plan.loadStarted(true, "stab")

        assertTrue(plan.needsReload(true, "stable"))
    }

    @Test
    fun `typing a known branch reloads while typing`() {
        plan.loadStarted(true, "default")

        assertTrue(plan.reloadsWhileTyping(true, " stable ", listOf("default", "stable")))
    }

    @Test
    fun `typing an unknown text waits for it to be applied`() {
        plan.loadStarted(true, "default")

        assertFalse(plan.reloadsWhileTyping(true, "stab", listOf("default", "stable")))
        assertFalse(plan.reloadsWhileTyping(true, "tip~3", listOf("default", "stable")))
        assertTrue(plan.needsReload(true, "tip~3"))
    }

    @Test
    fun `a known branch must match case exactly while typing`() {
        plan.loadStarted(true, "default")

        assertFalse(plan.reloadsWhileTyping(true, "Stable", listOf("default", "stable")))
    }

    @Test
    fun `before the branches are known typing never reloads`() {
        assertFalse(plan.reloadsWhileTyping(true, "stable", emptyList()))
    }

    @Test
    fun `typing the loaded branch does not reload`() {
        plan.loadStarted(true, "stable")

        assertFalse(plan.reloadsWhileTyping(true, "stable", listOf("default", "stable")))
    }

    @Test
    fun `typing in a mode without the branch field never reloads`() {
        assertFalse(plan.reloadsWhileTyping(false, "stable", listOf("stable")))
    }
}
