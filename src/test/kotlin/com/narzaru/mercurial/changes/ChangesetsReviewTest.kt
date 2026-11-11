package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgDiffStat
import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgFileStatus
import com.narzaru.mercurial.review.FakeReviewRepo
import com.narzaru.mercurial.review.ReviewChangeType
import com.narzaru.mercurial.review.ReviewChanges
import com.narzaru.mercurial.review.ReviewFile
import com.narzaru.mercurial.review.ReviewFileDiff
import com.narzaru.mercurial.review.ReviewHunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChangesetsReviewTest {

    private val base = (1..10).map { "line $it" }

    private fun text(lines: List<String>) = lines.joinToString("\n", postfix = "\n")

    private fun edited(vararg changes: Pair<Int, String>): List<String> {
        val lines = base.toMutableList()
        for ((number, line) in changes) lines[number - 1] = line
        return lines
    }

    private fun file(
        type: ReviewChangeType,
        path: String = "src/A.kt",
        oldPath: String = path,
        baseRev: String? = "b0",
        binary: Boolean = false
    ) = ReviewFile(path, oldPath, type, baseRev, listOf("r1", "r2"), binary)

    private fun hunk(left: Pair<Int, Int>, right: Pair<Int, Int>, shown: Boolean = true) =
        ReviewHunk(DiffFragment(LineRange(left.first, left.second), LineRange(right.first, right.second)), shown)

    private fun describe(item: HgFileItem) =
        "${item.status} ${item.path}<${item.copiedFrom} ${item.baseRev}..${item.headRev}"

    @Test
    fun `every change type maps onto the model the diff machinery reads`() {
        assertEquals("MODIFIED src/A.kt< b0..r2", describe(ReviewFileItems.of(file(ReviewChangeType.MODIFIED))))
        assertEquals("ADDED src/A.kt< b0..r2", describe(ReviewFileItems.of(file(ReviewChangeType.ADDED))))
        assertEquals(
            "REMOVED src/Old.kt< b0..r2",
            describe(ReviewFileItems.of(file(ReviewChangeType.DELETED, path = "src/New.kt", oldPath = "src/Old.kt")))
        )
        assertEquals(
            "RENAMED src/New.kt<src/Old.kt b0..r2",
            describe(ReviewFileItems.of(file(ReviewChangeType.RENAMED, path = "src/New.kt", oldPath = "src/Old.kt")))
        )
        assertEquals(
            "ADDED src/Copy.kt<src/A.kt b0..r2",
            describe(ReviewFileItems.of(file(ReviewChangeType.COPIED, path = "src/Copy.kt", oldPath = "src/A.kt")))
        )
    }

    @Test
    fun `a file of the first revision has no base revision to read`() {
        val item = ReviewFileItems.of(file(ReviewChangeType.ADDED, baseRev = null))

        assertEquals(BranchRevisions.NO_REVISION, item.baseRev)
        assertEquals(false, DiffSidesPlan.of(item, "p1(r1)").needsBase)
    }

    @Test
    fun `the counts come from the shown hunks only`() {
        val old = listOf("a", "b", "c", "d", "")
        val new = listOf("a", "B", "c", "D", "E", "")
        val diff = ReviewFileDiff(
            file(ReviewChangeType.MODIFIED), old, new,
            listOf(hunk(1 to 2, 1 to 2), hunk(3 to 4, 3 to 5, shown = false))
        )

        val stat = ReviewStats.of(diff)!!

        assertEquals(1, stat.added)
        assertEquals(1, stat.removed)
    }

    @Test
    fun `the hash follows the shown hunks and ignores the hidden ones`() {
        val old = listOf("a", "b", "c", "d", "")
        fun hashOf(shownLine: String, hiddenLine: String) = ReviewStats.of(
            ReviewFileDiff(
                file(ReviewChangeType.MODIFIED), old, listOf("a", shownLine, "c", hiddenLine, ""),
                listOf(hunk(1 to 2, 1 to 2), hunk(3 to 4, 3 to 4, shown = false))
            )
        )!!.hash

        assertEquals(hashOf("B", "D"), hashOf("B", "other"))
        assertNotEquals(hashOf("B", "D"), hashOf("B2", "D"))
    }

    @Test
    fun `a file with every hunk hidden has no stat`() {
        val diff = ReviewFileDiff(
            file(ReviewChangeType.MODIFIED), listOf("a", ""), listOf("b", ""), listOf(hunk(0 to 1, 0 to 1, shown = false))
        )

        assertNull(ReviewStats.of(diff))
    }

    @Test
    fun `the empty line after the final line break is not counted`() {
        val diff = ReviewFileDiff(
            file(ReviewChangeType.ADDED), emptyList(), listOf("a", "b", "c", ""), listOf(hunk(0 to 0, 0 to 4))
        )

        assertEquals(HgDiffStat(3, 0), ReviewStats.of(diff)!!.copy(hash = ""))
        assertEquals(0, ReviewStats.countedLines(LineRange(0, 1), listOf("")))
        assertEquals(2, ReviewStats.countedLines(LineRange(0, 2), listOf("a", "b")))
    }

    @Test
    fun `a rename with the same hunks hashes apart from an edit in place`() {
        val hunks = listOf(hunk(0 to 1, 0 to 1))
        val inPlace = ReviewStats.of(ReviewFileDiff(file(ReviewChangeType.MODIFIED), listOf("a"), listOf("b"), hunks))!!
        val renamed = ReviewStats.of(
            ReviewFileDiff(file(ReviewChangeType.RENAMED, oldPath = "src/Old.kt"), listOf("a"), listOf("b"), hunks)
        )!!

        assertNotEquals(inPlace.hash, renamed.hash)
    }

    @Test
    fun `a binary file is counted as zero with a hash of its contents`() {
        fun statOf(content: String) = ReviewStats.of(
            ReviewFileDiff(file(ReviewChangeType.MODIFIED, binary = true), listOf("PNG\u0000"), listOf(content), emptyList())
        )!!

        assertEquals(0, statOf("PNG\u0001").added)
        assertEquals(0, statOf("PNG\u0001").removed)
        assertNotEquals(statOf("PNG\u0001").hash, statOf("PNG\u0002").hash)
    }

    @Test
    fun `stats mark a modified file without shown hunks unchanged and leave uncounted files alone`() {
        val items = listOf(
            HgFileItem(HgFileStatus.MODIFIED, "src/A.kt"),
            HgFileItem(HgFileStatus.MODIFIED, "src/B.kt"),
            HgFileItem(HgFileStatus.RENAMED, "src/C.kt", copiedFrom = "src/Old.kt"),
            HgFileItem(HgFileStatus.MODIFIED, "src/D.kt")
        )
        val stats = mapOf("src/a.kt" to HgDiffStat(2, 1, "h"), "src/b.kt" to null, "src/c.kt" to null)

        val counted = ReviewStats.apply(items, stats)

        assertEquals(
            listOf("M src/A.kt +2-1", "♦ src/B.kt +0-0", "→ src/C.kt +0-0", "M src/D.kt +0-0"),
            counted.map { "${it.status.letter} ${it.path} +${it.added}-${it.removed}" }
        )
        assertEquals(
            mapOf("src/a.kt" to "h", "src/b.kt" to DiffStatsPlan.NO_DIFF_HASH, "src/c.kt" to DiffStatsPlan.NO_DIFF_HASH),
            ReviewStats.hashes(stats)
        )
    }

    @Test
    fun `an unchanged file whose hunks show again is modified again`() {
        val item = HgFileItem(HgFileStatus.UNCHANGED, "src/A.kt", isUnchanged = true)

        val counted = ReviewStats.apply(listOf(item), mapOf("src/a.kt" to HgDiffStat(1, 0, "h"))).single()

        assertEquals(HgFileStatus.MODIFIED, counted.status)
        assertEquals(false, counted.isUnchanged)
    }

    private fun mergedReview(repo: FakeReviewRepo): ChangesetsReview {
        repo.commit("d0", null, changes = mapOf("A.kt" to text(base), "logo.png" to "PNG\u0000"))
        repo.commit("f1", "d0", "feature", changes = mapOf("A.kt" to text(edited(2 to "f1")), "logo.png" to "PNG\u0001"))
        repo.commit("d1", "d0", changes = mapOf("A.kt" to text(edited(8 to "d1"))))
        repo.merge("m1", "f1", "d1", "feature", mapOf("A.kt" to text(edited(2 to "f1", 8 to "d1"))))
        repo.commit("f2", "m1", "feature", changes = mapOf("A.kt" to text(edited(2 to "f1", 5 to "f2", 8 to "d1"))))
        val changes = ReviewChanges(repo)
        return ChangesetsReview(changes, changes.load(listOf("f1", "m1", "f2")))
    }

    @Test
    fun `the left side shows only the review's own hunks and is read once`() {
        val repo = FakeReviewRepo()
        val review = mergedReview(repo)
        val item = review.items.single { it.path == "A.kt" }

        val left = review.leftSide(item)
        val calls = repo.calls.size

        assertEquals(text(edited(8 to "d1")), left)
        assertEquals(left, review.leftSide(item))
        assertEquals(calls, repo.calls.size)
    }

    @Test
    fun `a binary file and an unknown path have no left side of the review`() {
        val review = mergedReview(FakeReviewRepo())

        assertNull(review.leftSide(review.items.single { it.path == "logo.png" }))
        assertNull(review.leftSide(HgFileItem(HgFileStatus.MODIFIED, "Elsewhere.kt")))
    }

    @Test
    fun `stats of a review count what its diff shows`() {
        val review = mergedReview(FakeReviewRepo())

        val stats = review.stats()

        assertEquals(setOf("a.kt", "logo.png"), stats.keys)
        assertEquals(HgDiffStat(2, 2), stats.getValue("a.kt")!!.copy(hash = ""))
        assertNotNull(stats.getValue("logo.png"))
        assertEquals(
            listOf("M A.kt +2-2", "M logo.png +0-0"),
            ReviewStats.apply(review.items, stats).map { "${it.status.letter} ${it.path} +${it.added}-${it.removed}" }
        )
    }
}
