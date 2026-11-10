package com.narzaru.mercurial.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFollowPlanTest {

    private fun step(
        requested: String,
        shown: String?,
        fromEditor: Boolean = true,
        followSuppressed: Boolean = false,
        visible: Boolean = true
    ) = HistoryFollowPlan.step(requested, shown, fromEditor, followSuppressed, visible)

    @Test
    fun `an editor switch to another file is loaded after a pause`() {
        assertEquals(HistoryFollowStep.LOAD_DEBOUNCED, step("D:/repo/B.kt", "D:/repo/A.kt"))
    }

    @Test
    fun `a sync to another file is loaded at once`() {
        assertEquals(HistoryFollowStep.LOAD_NOW, step("D:/repo/B.kt", "D:/repo/A.kt", fromEditor = false))
    }

    @Test
    fun `returning to the shown file cancels the pending switch`() {
        val away = step("D:/repo/B.kt", "D:/repo/A.kt")
        val back = step("D:/repo/A.kt", "D:/repo/A.kt")

        assertEquals(HistoryFollowStep.LOAD_DEBOUNCED, away)
        assertEquals(HistoryFollowStep.STAY, back)
        assertTrue(back.cancelsPending)
    }

    @Test
    fun `a sync cancels a pending editor switch`() {
        assertTrue(step("D:/repo/C.kt", "D:/repo/A.kt", fromEditor = false).cancelsPending)
    }

    @Test
    fun `a sync to the shown file still cancels a pending editor switch`() {
        val step = step("D:/repo/A.kt", "D:/repo/A.kt", fromEditor = false)

        assertEquals(HistoryFollowStep.STAY, step)
        assertTrue(step.cancelsPending)
    }

    @Test
    fun `the shown file is matched regardless of case and separators`() {
        assertEquals(HistoryFollowStep.STAY, step("D:\\Repo\\a.kt", "d:/repo/A.kt"))
    }

    @Test
    fun `a suppressed editor switch is ignored and leaves the pending switch alone`() {
        val step = step("D:/repo/B.kt", "D:/repo/A.kt", followSuppressed = true)

        assertEquals(HistoryFollowStep.IGNORE, step)
        assertFalse(step.cancelsPending)
    }

    @Test
    fun `suppression does not block a sync`() {
        assertEquals(
            HistoryFollowStep.LOAD_NOW,
            step("D:/repo/B.kt", "D:/repo/A.kt", fromEditor = false, followSuppressed = true)
        )
    }

    @Test
    fun `nothing is loaded while the tool window is hidden`() {
        val step = step("D:/repo/B.kt", "D:/repo/A.kt", visible = false)

        assertEquals(HistoryFollowStep.DROP, step)
        assertTrue(step.cancelsPending)
    }

    @Test
    fun `the first file is loaded when nothing is shown yet`() {
        assertEquals(HistoryFollowStep.LOAD_NOW, step("D:/repo/A.kt", null, fromEditor = false))
    }
}
