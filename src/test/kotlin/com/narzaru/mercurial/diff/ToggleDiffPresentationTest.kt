package com.narzaru.mercurial.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToggleDiffPresentationTest {

    @Test
    fun `a diff tab offers the file`() {
        assertEquals(
            ToggleDiffDirection.TO_FILE,
            ToggleDiffPresentation.directionOf(ToggleDiffTarget.ShowFile("/repo/a.txt"), false)
        )
    }

    @Test
    fun `a diff in the file's own tab offers the file`() {
        assertEquals(ToggleDiffDirection.TO_FILE, ToggleDiffPresentation.directionOf(ToggleDiffTarget.SwapInline, true))
    }

    @Test
    fun `a file in its own tab with a diff behind offers the diff`() {
        assertEquals(ToggleDiffDirection.TO_DIFF, ToggleDiffPresentation.directionOf(ToggleDiffTarget.SwapInline, false))
    }

    @Test
    fun `a changed file offers its diff`() {
        assertEquals(ToggleDiffDirection.TO_DIFF, ToggleDiffPresentation.directionOf(ToggleDiffTarget.ShowDiff("a.txt"), false))
    }

    @Test
    fun `a file with unknown changes offers its diff`() {
        assertEquals(ToggleDiffDirection.TO_DIFF, ToggleDiffPresentation.directionOf(ToggleDiffTarget.LoadChanges, false))
    }

    @Test
    fun `an unrelated place offers nothing`() {
        assertNull(ToggleDiffPresentation.directionOf(ToggleDiffTarget.None, false))
    }
}
