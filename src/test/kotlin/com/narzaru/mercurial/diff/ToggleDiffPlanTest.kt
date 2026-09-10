package com.narzaru.mercurial.diff

import org.junit.Assert.assertEquals
import org.junit.Test

class ToggleDiffPlanTest {

    @Test
    fun `a diff tab switches to its source file`() {
        assertEquals(
            ToggleDiffTarget.ShowFile("/repo/a.txt"),
            ToggleDiffPlan.of(true, "/repo/a.txt", false, false, null)
        )
    }

    @Test
    fun `a diff tab without a source file does nothing`() {
        assertEquals(
            ToggleDiffTarget.None,
            ToggleDiffPlan.of(true, null, false, false, null)
        )
    }

    @Test
    fun `a changed file switches to its diff`() {
        assertEquals(
            ToggleDiffTarget.ShowDiff("a.txt"),
            ToggleDiffPlan.of(false, null, true, true, "a.txt")
        )
    }

    @Test
    fun `an unchanged file does nothing`() {
        assertEquals(
            ToggleDiffTarget.None,
            ToggleDiffPlan.of(false, null, true, true, null)
        )
    }

    @Test
    fun `a file loads the changes when they are not there yet`() {
        assertEquals(
            ToggleDiffTarget.LoadChanges,
            ToggleDiffPlan.of(false, null, true, false, null)
        )
    }

    @Test
    fun `a file outside an editor does nothing`() {
        assertEquals(
            ToggleDiffTarget.None,
            ToggleDiffPlan.of(false, null, false, false, null)
        )
    }
}
