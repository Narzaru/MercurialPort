package com.narzaru.mercurial.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HgFileStatusTest {

    @Test
    fun `every status is shown with its own letter`() {
        assertEquals(
            listOf("M", "A", "R", "C", "!", "?", "I", "→", "♦"),
            listOf(
                HgFileStatus.MODIFIED, HgFileStatus.ADDED, HgFileStatus.REMOVED, HgFileStatus.CLEAN,
                HgFileStatus.MISSING, HgFileStatus.UNTRACKED, HgFileStatus.IGNORED, HgFileStatus.RENAMED,
                HgFileStatus.UNCHANGED
            ).map { it.letter }
        )
    }

    @Test
    fun `a status is found again by its letter`() {
        for (status in HgFileStatus.entries) {
            assertEquals(status, HgFileStatus.ofLetter(status.letter.single()))
        }
    }

    @Test
    fun `an unknown letter has no status`() {
        assertNull(HgFileStatus.ofLetter('X'))
        assertNull(HgFileStatus.ofLetter('m'))
        assertNull(HgFileStatus.ofLetter(' '))
    }
}
