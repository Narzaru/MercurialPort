package com.narzaru.mercurial.history

import com.narzaru.mercurial.model.HgHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryDiffSelectionTest {

    private fun item(revision: String, parentRev: String, path: String = "src/App.kt") = HgHistoryItem(
        revision = revision,
        node = "node$revision",
        author = "bob",
        date = "2026-01-01 12:00 +0300",
        message = "msg",
        path = path,
        parentRev = parentRev
    )

    private val items = listOf(
        item("5", "4"),
        item("4", "3"),
        item("3", "2"),
        item("2", "1")
    )

    @Test
    fun `an empty selection has nothing to diff`() {
        assertNull(HistoryDiffSelection.of(items, emptyList()))
        assertNull(HistoryDiffSelection.of(emptyList(), listOf(0)))
        assertNull(HistoryDiffSelection.of(items, listOf(7)))
    }

    @Test
    fun `a single row is compared against its own parent`() {
        val selection = HistoryDiffSelection.of(items, listOf(1))!!

        assertEquals("4", selection.newest.revision)
        assertEquals("4", selection.oldest.revision)
        assertEquals("3", selection.parentRevision)
        assertEquals(1, selection.selectedCount)
        assertEquals("Rev 3 (parent)", selection.leftLabel("src/App.kt", "src/App.kt"))
        assertEquals("Rev 4 (bob)", selection.rightLabel())
    }

    @Test
    fun `a range spans from the parent of the oldest row to the newest`() {
        val selection = HistoryDiffSelection.of(items, listOf(2, 0, 1))!!

        assertEquals("5", selection.newest.revision)
        assertEquals(0, selection.newestRow)
        assertEquals("3", selection.oldest.revision)
        assertEquals(2, selection.oldestRow)
        assertEquals("2", selection.parentRevision)
        assertEquals("Rev 5 (bob)  ·  3 revisions", selection.rightLabel())
    }

    @Test
    fun `a revision without a parent has no left side`() {
        val selection = HistoryDiffSelection.of(listOf(item("0", "")), listOf(0))!!

        assertFalse(selection.hasParent)
        assertEquals("No parent revision", selection.leftLabel("src/App.kt", "src/App.kt"))
    }

    @Test
    fun `the first revision of a repository has a parent of minus one`() {
        val selection = HistoryDiffSelection.of(listOf(item("0", "-1")), listOf(0))!!

        assertFalse(selection.hasParent)
    }

    @Test
    fun `a renamed left side names the previous file`() {
        val selection = HistoryDiffSelection.of(items, listOf(1))!!

        assertTrue(selection.hasParent)
        assertEquals(
            "Rev 3 (parent) — Old.kt",
            selection.leftLabel("src/Old.kt", "src/App.kt")
        )
    }

    @Test
    fun `the tab key holds both revisions and the path`() {
        val selection = HistoryDiffSelection.of(items, listOf(1, 2))!!

        assertEquals("range|2|4|src/App.kt", selection.tabKey("src/App.kt"))
    }
}
