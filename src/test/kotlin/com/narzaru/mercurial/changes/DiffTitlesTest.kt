package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffTitlesTest {

    @Test
    fun `a file missing from the base says so`() {
        assertEquals(
            "Not in base",
            DiffTitles.left(hasBaseContent = false, rightIsRevision = false, onlyLast = false, baseRev = "42", copiedFrom = "")
        )
    }

    @Test
    fun `a local comparison names the base revision`() {
        assertEquals(
            "Hg Base (42)",
            DiffTitles.left(hasBaseContent = true, rightIsRevision = false, onlyLast = false, baseRev = "42", copiedFrom = "")
        )
    }

    @Test
    fun `a revision comparison names the state before the branch`() {
        assertEquals(
            "Before this branch's changes",
            DiffTitles.left(hasBaseContent = true, rightIsRevision = true, onlyLast = false, baseRev = "42", copiedFrom = "")
        )
    }

    @Test
    fun `the last change only falls back to the base revision`() {
        assertEquals(
            "Hg Base (p1(9))",
            DiffTitles.left(hasBaseContent = true, rightIsRevision = true, onlyLast = true, baseRev = "p1(9)", copiedFrom = "")
        )
    }

    @Test
    fun `a renamed file shows its source path`() {
        assertEquals(
            "Hg Base (42) src/a.kt",
            DiffTitles.left(hasBaseContent = true, rightIsRevision = false, onlyLast = false, baseRev = "42", copiedFrom = "src/a.kt")
        )
    }

    private fun right(
        rightIsRevision: Boolean = true,
        onlyLast: Boolean = false,
        hasHeadContent: Boolean = true,
        rightIsLocalFile: Boolean = true,
        hasLocalFile: Boolean = true,
        headRev: String = "9",
        skippedHunks: Int = 0
    ) = DiffTitles.right(
        rightIsRevision, onlyLast, hasHeadContent, rightIsLocalFile, hasLocalFile, headRev, skippedHunks
    )

    @Test
    fun `the local file is named as such`() {
        assertEquals("Local Version", right(rightIsRevision = false))
    }

    @Test
    fun `a file gone from disk is named as such`() {
        assertEquals("Deleted locally", right(rightIsRevision = false, hasLocalFile = false))
    }

    @Test
    fun `a revision matching the disk file is named without a warning`() {
        assertEquals("Hg Rev (9)", right())
    }

    @Test
    fun `a revision that is not the disk file warns about the lost editor features`() {
        val title = right(rightIsLocalFile = false)

        assertTrue(title.startsWith("Hg Rev (9)"))
        assertTrue(title.contains(DiffTitles.REVISION_SIDE_NOTE))
    }

    @Test
    fun `a file missing from the revision says so`() {
        assertEquals("Not in 9", right(hasHeadContent = false, rightIsLocalFile = false))
    }

    @Test
    fun `the last change only is spelled out`() {
        assertEquals("Hg Rev (9) — last change only", right(onlyLast = true))
    }

    @Test
    fun `skipped hunks are reported`() {
        assertEquals(
            "Hg Rev (9) — 3 change(s) over merged code not shown",
            right(skippedHunks = 3)
        )
    }

    @Test
    fun `the last change only wins over skipped hunks`() {
        assertEquals("Hg Rev (9) — last change only", right(onlyLast = true, skippedHunks = 3))
    }

    @Test
    fun `a synthetic right side never claims to be the local file`() {
        for (onlyLast in listOf(true, false)) {
            for (skipped in listOf(0, 2)) {
                val title = right(onlyLast = onlyLast, skippedHunks = skipped, rightIsLocalFile = false)
                assertTrue(title, title.contains(DiffTitles.REVISION_SIDE_NOTE))
            }
        }
    }

    @Test
    fun `the local right side carries no revision note`() {
        assertFalse(right().contains(DiffTitles.REVISION_SIDE_NOTE))
    }
}
