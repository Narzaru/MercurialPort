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
    fun `a diff tab leaves the shortcut to jump to source`() {
        assertFalse(ToggleDiffPromotion.winsOverJumpToSource(ToggleDiffTarget.ShowFile("/repo/a.txt")))
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
