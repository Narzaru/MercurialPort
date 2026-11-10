package com.narzaru.mercurial.diff

import org.junit.Assert.assertEquals
import org.junit.Test

class ToggleDiffPlanTest {

    @Test
    fun `a diff leaves jump to source to the platform`() {
        assertEquals(ToggleDiffTarget.None, ToggleDiffPlan.of(true, true, true, "a.txt"))
    }

    @Test
    fun `a changed file switches to its diff`() {
        assertEquals(ToggleDiffTarget.ShowDiff("a.txt"), ToggleDiffPlan.of(false, true, true, "a.txt"))
    }

    @Test
    fun `an unchanged file does nothing`() {
        assertEquals(ToggleDiffTarget.None, ToggleDiffPlan.of(false, true, true, null))
    }

    @Test
    fun `a file loads the changes when they are not there yet`() {
        assertEquals(ToggleDiffTarget.LoadChanges, ToggleDiffPlan.of(false, true, false, null))
    }

    @Test
    fun `a file outside an editor does nothing`() {
        assertEquals(ToggleDiffTarget.None, ToggleDiffPlan.of(false, false, true, "a.txt"))
    }
}
