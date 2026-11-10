package com.narzaru.mercurial.history

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    private val root = File("D:/repos/app")

    @Test
    fun `the tab key holds the repository, both ends of the range by node and the path`() {
        val selection = HistoryDiffSelection.of(items, listOf(1, 2))!!

        assertEquals(
            "range|${HgPaths.key(root.absolutePath)}|node3|node4|src/App.kt",
            selection.tabKey(root, "src/App.kt")
        )
    }

    @Test
    fun `the same range of the same file gives the same tab key`() {
        assertEquals(
            HistoryDiffSelection.of(items, listOf(1, 2))!!.tabKey(root, "src/App.kt"),
            HistoryDiffSelection.of(items, listOf(2, 1))!!.tabKey(File("D:\\Repos\\App"), "src/App.kt")
        )
    }

    @Test
    fun `the same range in another repository gets its own tab`() {
        val selection = HistoryDiffSelection.of(items, listOf(1, 2))!!

        assertNotEquals(
            selection.tabKey(File("D:/repos/app"), "src/App.kt"),
            selection.tabKey(File("D:/repos/lib"), "src/App.kt")
        )
    }

    @Test
    fun `a local revision number reused by another changeset gets its own tab`() {
        val before = HistoryDiffSelection.of(items, listOf(1))!!
        val reused = HistoryDiffSelection.of(listOf(item("4", "3").copy(node = "other4")), listOf(0))!!

        assertNotEquals(before.tabKey(root, "src/App.kt"), reused.tabKey(root, "src/App.kt"))
    }

    @Test
    fun `a revision without a node is keyed by its number`() {
        val selection = HistoryDiffSelection.of(listOf(item("4", "3").copy(node = "")), listOf(0))!!

        assertTrue(selection.tabKey(root, "src/App.kt").endsWith("|4|4|src/App.kt"))
    }
}
