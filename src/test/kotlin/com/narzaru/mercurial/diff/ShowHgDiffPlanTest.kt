package com.narzaru.mercurial.diff

import org.junit.Assert.assertEquals
import org.junit.Test

class ShowHgDiffPlanTest {

    @Test
    fun `a changed file shows its diff`() {
        assertEquals(ShowHgDiffTarget.ShowDiff("/r/a.txt", "a.txt"), ShowHgDiffPlan.of("/r/a.txt", true, "a.txt"))
    }

    @Test
    fun `an unchanged file does nothing`() {
        assertEquals(ShowHgDiffTarget.None, ShowHgDiffPlan.of("/r/a.txt", true, null))
    }

    @Test
    fun `a file loads the changes when they are not there yet`() {
        assertEquals(ShowHgDiffTarget.LoadChanges("/r/a.txt"), ShowHgDiffPlan.of("/r/a.txt", false, null))
    }

    @Test
    fun `a file loads the changes even if a stale key is known`() {
        assertEquals(ShowHgDiffTarget.LoadChanges("/r/a.txt"), ShowHgDiffPlan.of("/r/a.txt", false, "a.txt"))
    }
}
