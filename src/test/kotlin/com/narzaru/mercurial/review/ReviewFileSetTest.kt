package com.narzaru.mercurial.review

import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgFileStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewFileSetTest {

    private fun revision(rev: Int, vararg changes: HgFileItem, p2: String? = null) =
        ReviewRevision(rev, "n$rev", "n${rev - 1}", p2, "feature", changes = changes.toList())

    private fun modified(path: String) = HgFileItem(HgFileStatus.MODIFIED, path)
    private fun added(path: String, from: String = "") = HgFileItem(HgFileStatus.ADDED, path, copiedFrom = from)
    private fun removed(path: String) = HgFileItem(HgFileStatus.REMOVED, path)
    private fun renamed(path: String, from: String) = HgFileItem(HgFileStatus.RENAMED, path, copiedFrom = from)

    private fun describe(entries: List<ReviewEntry>) = entries.map { "${it.oldPath}>${it.path}" }.sorted()

    @Test
    fun `every file a non-merge revision changes is listed once`() {
        val entries = ReviewFileSet.entries(
            listOf(revision(1, modified("a"), added("b")), revision(2, modified("a"), removed("c")))
        )

        assertEquals(listOf("a>a", "b>b", "c>c"), describe(entries))
    }

    @Test
    fun `merges do not add files by themselves`() {
        val entries = ReviewFileSet.entries(listOf(revision(1, modified("a")), revision(2, modified("z"), p2 = "x")))

        assertEquals(listOf("a>a"), describe(entries))
    }

    @Test
    fun `a rename keeps the path the file had before the review`() {
        val entries = ReviewFileSet.entries(
            listOf(revision(1, modified("old")), revision(2, renamed("mid", "old")), revision(3, renamed("new", "mid")))
        )

        val entry = entries.single()
        assertEquals("old>new", "${entry.oldPath}>${entry.path}")
        assertEquals(ReviewCopy.RENAME, entry.copy)
        assertEquals(listOf("old", "mid", "new"), entry.names)
        assertEquals(listOf(ReviewRename("n2", "old", "mid"), ReviewRename("n3", "mid", "new")), entry.renames)
    }

    @Test
    fun `a copy is compared with its source`() {
        val entries = ReviewFileSet.entries(listOf(revision(1, added("copy", "source"))))

        assertEquals(listOf("source>copy"), describe(entries))
        assertEquals(ReviewCopy.COPY, entries.single().copy)
    }

    @Test
    fun `revisions are taken in topological order whatever order they come in`() {
        val entries = ReviewFileSet.entries(listOf(revision(2, renamed("new", "old")), revision(1, added("old"))))

        assertEquals(listOf("old>new"), describe(entries))
    }

    @Test
    fun `a merge from the base branch touches only what differs from both parents`() {
        assertEquals(setOf("both"), ReviewFileSet.mergeTouches(listOf("both", "fromBase"), listOf("both", "ours")))
    }

    @Test
    fun `a merge from another branch touches everything that differs from its first parent`() {
        assertEquals(setOf("both", "fromRelease"), ReviewFileSet.mergeTouches(listOf("both", "fromRelease"), null))
    }

    @Test
    fun `only a merge whose second parent is on the base branch is compared with it`() {
        val fromBase = ReviewRevision(5, "m", "a", "b", "feature", p2Branch = "default")
        val fromRelease = fromBase.copy(p2Branch = "release")

        assertTrue(ReviewFileSet.comparesSecondParent(fromBase, "default"))
        assertFalse(ReviewFileSet.comparesSecondParent(fromRelease, "default"))
    }

    @Test
    fun `a file is touched by the revisions that change any of its names`() {
        val touches = listOf(
            ReviewTouches("n1", setOf("old")),
            ReviewTouches("n2", setOf("other")),
            ReviewTouches("m3", setOf("new")),
            ReviewTouches("n4", setOf("new", "old"))
        )

        assertEquals(listOf("n1", "m3", "n4"), ReviewFileSet.touching(listOf("old", "new"), touches))
    }

    @Test
    fun `a split rename becomes a removed file and an added one`() {
        val rename = ReviewRename("n2", "old", "new")
        val entry = ReviewEntry("new", "old", ReviewCopy.RENAME, listOf("old", "new"), listOf(rename))

        val parts = ReviewFileSet.split(entry, 0)

        assertEquals(listOf("old>old", "new>new"), parts.map { "${it.oldPath}>${it.path}" })
        assertEquals(listOf(listOf("old"), listOf("new")), parts.map { it.names })
        assertEquals(listOf(null, null), parts.map { it.copy })
    }

    @Test
    fun `a chain of renames splits at the weak link and keeps the others`() {
        val renames = listOf(ReviewRename("n1", "a", "b"), ReviewRename("n2", "b", "c"), ReviewRename("n3", "c", "d"))
        val entry = ReviewEntry("d", "a", ReviewCopy.RENAME, listOf("a", "b", "c", "d"), renames)

        val parts = ReviewFileSet.split(entry, 1)

        assertEquals(listOf("a>b", "c>d"), parts.map { "${it.oldPath}>${it.path}" })
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), parts.map { it.names })
        assertEquals(listOf(renames.take(1), renames.drop(2)), parts.map { it.renames })
        assertEquals(listOf(ReviewCopy.RENAME, ReviewCopy.RENAME), parts.map { it.copy })
    }

    @Test
    fun `a file renamed away and back splits without looping`() {
        val renames = listOf(ReviewRename("n1", "a", "b"), ReviewRename("n2", "b", "a"))
        val entry = ReviewEntry("a", "a", ReviewCopy.RENAME, listOf("a", "b"), renames)

        val parts = ReviewFileSet.split(entry, 1)

        assertEquals(listOf("a>b", "a>a"), parts.map { "${it.oldPath}>${it.path}" })
        assertEquals(listOf(1, 0), parts.map { it.renames.size })
    }
}
