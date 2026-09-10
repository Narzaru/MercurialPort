package com.narzaru.mercurial.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HgDiffTabKeysTest {

    @Test
    fun `the same changed file always maps to the same tab`() {
        assertEquals(HgDiffTabKeys.changes("src/App.kt"), HgDiffTabKeys.changes("src/App.kt"))
    }

    @Test
    fun `different changed files map to different tabs`() {
        assertNotEquals(HgDiffTabKeys.changes("src/App.kt"), HgDiffTabKeys.changes("src/Main.kt"))
    }

    @Test
    fun `the same revision range of a file always maps to the same tab`() {
        assertEquals(
            HgDiffTabKeys.history("range|2|4|src/App.kt"),
            HgDiffTabKeys.history("range|2|4|src/App.kt")
        )
    }

    @Test
    fun `different revision ranges of a file map to different tabs`() {
        assertNotEquals(
            HgDiffTabKeys.history("range|2|4|src/App.kt"),
            HgDiffTabKeys.history("range|3|4|src/App.kt")
        )
    }

    @Test
    fun `a history tab never collides with a changes tab`() {
        assertNotEquals(HgDiffTabKeys.changes("src/App.kt"), HgDiffTabKeys.history("src/App.kt"))
    }

    @Test
    fun `a changes tab gives back the file it was opened for`() {
        assertEquals("src/App.kt", HgDiffTabKeys.changesKeyOf(HgDiffTabKeys.changes("src/App.kt")))
    }

    @Test
    fun `a history tab is not a changes tab`() {
        assertEquals(null, HgDiffTabKeys.changesKeyOf(HgDiffTabKeys.history("src/App.kt")))
    }
}
