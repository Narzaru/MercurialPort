package com.narzaru.mercurial.diff

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToggleDiffPromotionTest {

    @Test
    fun `a changed file takes the shortcut away from jump to source`() {
        assertTrue(ToggleDiffPromotion.winsOverJumpToSource(ToggleDiffTarget.ShowDiff("a.txt")))
    }

    @Test
    fun `a diff shown in the file's own tab takes the shortcut away from jump to source`() {
        assertTrue(ToggleDiffPromotion.winsOverJumpToSource(ToggleDiffTarget.SwapInline))
    }

    @Test
    fun `a diff tab takes the shortcut away from jump to source`() {
        assertTrue(ToggleDiffPromotion.winsOverJumpToSource(ToggleDiffTarget.ShowFile("/repo/a.txt")))
    }

    @Test
    fun `a diff tab with a source file replaces jump to source`() {
        assertTrue(ToggleDiffPromotion.replacesJumpToSourceIn("/repo/a.txt"))
    }

    @Test
    fun `a diff tab without a source file keeps jump to source`() {
        assertFalse(ToggleDiffPromotion.replacesJumpToSourceIn(null))
    }

    @Test
    fun `a diff shown in the file's own tab replaces jump to source`() {
        assertTrue(ToggleDiffPromotion.replacesJumpToSourceInInlineDiff())
    }

    @Test
    fun `a file with unknown changes leaves the shortcut to jump to source`() {
        assertFalse(ToggleDiffPromotion.winsOverJumpToSource(ToggleDiffTarget.LoadChanges))
    }

    @Test
    fun `an unrelated place leaves the shortcut to jump to source`() {
        assertFalse(ToggleDiffPromotion.winsOverJumpToSource(ToggleDiffTarget.None))
    }
}
