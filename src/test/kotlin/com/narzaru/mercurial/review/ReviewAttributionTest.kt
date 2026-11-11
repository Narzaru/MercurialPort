package com.narzaru.mercurial.review

import com.narzaru.mercurial.changes.DiffFragment
import com.narzaru.mercurial.changes.LineRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewAttributionTest {

    private val diff = ReviewLineDiff()

    private fun rev(rev: Int, node: String, p1: String?, p2: String? = null) =
        ReviewRevision(rev, node, p1, p2, "feature")

    private fun graph(vararg revisions: ReviewRevision) = revisions.associateBy { it.node }

    @Test
    fun `a chain of review commits needs no attribution`() {
        val revisions = graph(rev(1, "a", "base"), rev(2, "b", "a"))

        assertTrue(ReviewChain.insideReview(revisions, "base", "b"))
    }

    @Test
    fun `a merge or a revision outside the review on the way needs attribution`() {
        assertFalse(ReviewChain.insideReview(graph(rev(1, "a", "base"), rev(3, "m", "a", "x")), "base", "m"))
        assertFalse(ReviewChain.insideReview(graph(rev(2, "b", "gap")), "base", "b"))
    }

    @Test
    fun `the walk leaves a merge through the parent that is in the review`() {
        val revisions = graph(
            rev(1, "a", "base"),
            rev(2, "x", "base"),
            rev(3, "m", "x", "a"),
            rev(4, "b", "m")
        )

        assertEquals(listOf("base", "a", "m", "b"), ReviewChain.walk(revisions, setOf("a", "m", "b"), "base", "b"))
    }

    @Test
    fun `the walk takes the first parent when both or neither parent is in the review`() {
        val revisions = graph(rev(1, "a", "base"), rev(2, "x", "base"), rev(3, "m", "a", "x"))

        assertEquals(listOf("base", "a", "m"), ReviewChain.walk(revisions, setOf("a", "x", "m"), "base", "m"))
        assertEquals(listOf("base", "a", "m"), ReviewChain.walk(revisions, setOf("m"), "base", "m"))
    }

    @Test
    fun `the walk ignores a parent that does not lead to the old side`() {
        val revisions = graph(
            rev(0, "older", null),
            rev(1, "base", "older"),
            rev(2, "a", "base"),
            rev(3, "m", "older", "a")
        )

        assertEquals(listOf("base", "a", "m"), ReviewChain.walk(revisions, setOf("m"), "base", "m"))
    }

    @Test
    fun `a walk that cannot reach the old side gives up`() {
        assertNull(ReviewChain.walk(graph(rev(1, "a", "elsewhere")), setOf("a"), "base", "a"))
    }

    @Test
    fun `replay labels every added and removed line with the step that did it`() {
        val old = listOf("1", "2", "3")
        val steps = listOf(
            ReviewStep("a", ReviewStepKind.REVIEW, listOf("1", "2a", "3")),
            ReviewStep("m", ReviewStepKind.REVIEW_MERGE, listOf("1", "2a", "3", "4"), differsFromBothParents = true)
        )

        val attribution = ReviewReplay.replay(old, steps, diff)

        assertEquals(listOf(null, 0, null), attribution.removedBy)
        assertEquals(listOf(null, 0, null, 1), attribution.addedBy)
    }

    @Test
    fun `a line a later step rewrites belongs to the later step`() {
        val steps = listOf(
            ReviewStep("a", ReviewStepKind.REVIEW, listOf("x1")),
            ReviewStep("o", ReviewStepKind.OTHER, listOf("x2"))
        )

        val attribution = ReviewReplay.replay(listOf("x0"), steps, diff)

        assertEquals(listOf(0), attribution.removedBy)
        assertEquals(listOf(1), attribution.addedBy)
    }

    @Test
    fun `a merge that differs from both parents and a revision outside the review are unrelated`() {
        assertTrue(ReviewStep("m", ReviewStepKind.REVIEW_MERGE, emptyList(), differsFromBothParents = true).unrelated)
        assertTrue(ReviewStep("m", ReviewStepKind.MERGE, emptyList(), differsFromBothParents = true).unrelated)
        assertTrue(ReviewStep("o", ReviewStepKind.OTHER, emptyList()).unrelated)
    }

    @Test
    fun `a merge that took the file from one parent and a review commit are related`() {
        assertFalse(ReviewStep("m", ReviewStepKind.REVIEW_MERGE, emptyList(), differsFromBothParents = false).unrelated)
        assertFalse(ReviewStep("m", ReviewStepKind.MERGE, emptyList(), differsFromBothParents = false).unrelated)
        assertFalse(ReviewStep("a", ReviewStepKind.REVIEW, emptyList()).unrelated)
    }

    @Test
    fun `step kinds follow the review and merge membership`() {
        val review = setOf("a", "m")

        assertEquals(ReviewStepKind.REVIEW, ReviewStep.kindOf(rev(1, "a", "base"), review))
        assertEquals(ReviewStepKind.REVIEW_MERGE, ReviewStep.kindOf(rev(2, "m", "a", "x"), review))
        assertEquals(ReviewStepKind.MERGE, ReviewStep.kindOf(rev(3, "y", "a", "x"), review))
        assertEquals(ReviewStepKind.OTHER, ReviewStep.kindOf(rev(4, "o", "a"), review))
    }

    @Test
    fun `a hunk is hidden only when all its lines come from unrelated steps`() {
        val steps = listOf(
            ReviewStep("a", ReviewStepKind.REVIEW, emptyList()),
            ReviewStep("m", ReviewStepKind.REVIEW_MERGE, emptyList(), differsFromBothParents = true)
        )
        val attribution = ReviewAttribution(removedBy = listOf(null, 1, 0, null), addedBy = listOf(null, 1, 1, null, 0))
        val fragments = listOf(
            DiffFragment(LineRange(1, 2), LineRange(1, 3)),
            DiffFragment(LineRange(2, 3), LineRange(4, 5))
        )

        val hunks = ReviewHunks.of(fragments, steps, attribution)

        assertEquals(listOf(false, true), hunks.map { it.shown })
    }

    @Test
    fun `a hunk with one line of the review in it is shown whole`() {
        val steps = listOf(
            ReviewStep("a", ReviewStepKind.REVIEW, emptyList()),
            ReviewStep("o", ReviewStepKind.OTHER, emptyList())
        )
        val attribution = ReviewAttribution(removedBy = listOf(1, 1), addedBy = listOf(1, 0))

        val hunks = ReviewHunks.of(listOf(DiffFragment(LineRange(0, 2), LineRange(0, 2))), steps, attribution)

        assertTrue(hunks.single().shown)
    }

    @Test
    fun `a line the replay could not place keeps its hunk shown`() {
        val steps = listOf(ReviewStep("o", ReviewStepKind.OTHER, emptyList()))
        val attribution = ReviewAttribution(removedBy = listOf(null), addedBy = listOf(0))

        val hunks = ReviewHunks.of(listOf(DiffFragment(LineRange(0, 1), LineRange(0, 1))), steps, attribution)

        assertTrue(hunks.single().shown)
    }

    @Test
    fun `without attribution every hunk is shown`() {
        val hunks = ReviewHunks.of(listOf(DiffFragment(LineRange(0, 1), LineRange(0, 1))), emptyList(), null)

        assertTrue(hunks.single().shown)
    }

    @Test
    fun `touching fragments make one hunk`() {
        val fragments = listOf(
            DiffFragment(LineRange(0, 1), LineRange(0, 1)),
            DiffFragment(LineRange(1, 2), LineRange(1, 1)),
            DiffFragment(LineRange(4, 5), LineRange(3, 4))
        )

        assertEquals(
            listOf(DiffFragment(LineRange(0, 2), LineRange(0, 1)), DiffFragment(LineRange(4, 5), LineRange(3, 4))),
            ReviewHunks.joinTouching(fragments)
        )
    }

    @Test
    fun `the left side keeps hidden hunks as they are on the right`() {
        val old = listOf("a", "b", "c", "d")
        val new = listOf("a", "B", "c", "D")
        val hunks = listOf(
            ReviewHunk(DiffFragment(LineRange(1, 2), LineRange(1, 2)), shown = true),
            ReviewHunk(DiffFragment(LineRange(3, 4), LineRange(3, 4)), shown = false)
        )

        assertEquals(listOf("a", "b", "c", "D"), ReviewHunks.leftSide(old, new, hunks))
    }
}
