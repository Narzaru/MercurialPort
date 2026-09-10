package com.narzaru.mercurial.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStatusTextTest {

    @Test
    fun `the title names the file`() {
        assertEquals("History: App.kt", HistoryStatusText.title("App.kt"))
    }

    @Test
    fun `the loaded count is counted in commits`() {
        assertEquals("Loaded 0 commits.", HistoryStatusText.loaded(0))
        assertEquals("Loaded 1 commit.", HistoryStatusText.loaded(1))
        assertEquals("Loaded 42 commits.", HistoryStatusText.loaded(42))
    }

    @Test
    fun `an hg error is trimmed`() {
        assertEquals("HG Error: abort: unknown revision", HistoryStatusText.hgError("  abort: unknown revision \n"))
    }

    @Test
    fun `a missing hg is reported apart from an error returned by hg`() {
        assertEquals(
            "Cannot run hg: no such file",
            HistoryStatusText.hgError("no such file", failedToStart = true)
        )
    }

    @Test
    fun `a long error is cut down to one line of status`() {
        val status = HistoryStatusText.hgError("x".repeat(500))

        assertTrue(status.length < 250)
        assertTrue(status.endsWith("…"))
    }

    @Test
    fun `without an error there is simply nothing to compare`() {
        assertEquals("Nothing to compare for this revision.", HistoryStatusText.nothingToCompare(null))
        assertEquals("Nothing to compare for this revision.", HistoryStatusText.nothingToCompare("  "))
    }

    @Test
    fun `a cat error is shown instead of nothing to compare`() {
        assertEquals("Hg Error: no such file", HistoryStatusText.nothingToCompare("no such file"))
    }

    @Test
    fun `both sides present keep their labels`() {
        val titles = HistoryStatusText.diffTitles(
            "Rev 4 (parent)", "Rev 5 (bob)", hasLeftContent = true, hasRightContent = true
        )

        assertEquals("Rev 4 (parent)", titles.left)
        assertEquals("Rev 5 (bob)", titles.right)
        assertEquals("Rev 4 (parent) → Rev 5 (bob)", titles.status)
    }

    @Test
    fun `a missing left side means the file was added`() {
        val titles = HistoryStatusText.diffTitles(
            "Rev 4 (parent)", "Rev 5 (bob)", hasLeftContent = false, hasRightContent = true
        )

        assertEquals("Rev 4 (parent) — file added", titles.left)
        assertEquals("Rev 5 (bob)", titles.right)
        assertEquals("Rev 4 (parent) — file added → Rev 5 (bob)", titles.status)
    }

    @Test
    fun `a missing right side means the file was deleted`() {
        val titles = HistoryStatusText.diffTitles(
            "Rev 4 (parent)", "Rev 5 (bob)", hasLeftContent = true, hasRightContent = false
        )

        assertEquals("Rev 4 (parent)", titles.left)
        assertEquals("Rev 5 (bob) — file deleted", titles.right)
    }

    @Test
    fun `side labels name the revision and the author`() {
        assertEquals("Rev 4 (parent)", HistoryStatusText.parentLabel("4"))
        assertEquals("Rev 5 (bob)", HistoryStatusText.revisionLabel("5", "bob"))
    }

    @Test
    fun `a single revision is not counted in the label`() {
        assertEquals("", HistoryStatusText.revisionsSuffix(1))
        assertEquals("  ·  3 revisions", HistoryStatusText.revisionsSuffix(3))
    }

    @Test
    fun `the rename suffix keeps only the previous file name`() {
        assertEquals(" — Old.kt", HistoryStatusText.renamedSuffix("src/main/Old.kt"))
    }
}
