package com.narzaru.mercurial.diff

import org.junit.Assert.assertEquals
import org.junit.Test

class JumpToSourceSlotTest {

    @Test
    fun `the toggle takes the place of jump to source`() {
        assertEquals(
            listOf("PreviousDiff", "NextDiff", "Toggle", "-", "Diff.PrevChange"),
            JumpToSourceSlot.fill(
                listOf("PreviousDiff", "NextDiff", "Diff.OpenInEditor", "-", "Diff.PrevChange"),
                { it == "Diff.OpenInEditor" },
                "Toggle"
            )
        )
    }

    @Test
    fun `without jump to source the toggle goes last`() {
        assertEquals(
            listOf("PreviousDiff", "NextDiff", "Toggle"),
            JumpToSourceSlot.fill(listOf("PreviousDiff", "NextDiff"), { it == "Diff.OpenInEditor" }, "Toggle")
        )
    }
}
