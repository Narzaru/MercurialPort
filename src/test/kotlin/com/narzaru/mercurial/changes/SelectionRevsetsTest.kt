package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionRevsetsTest {

    private fun node(i: Int) = "c%011d".format(i)

    private fun revision(i: Int, merge: Boolean = false) =
        HgRevision((1000 + i).toString(), node(i), merge, "Bob", "2026-01-01 10:00 +0000", "Work $i")

    private val long = (0 until 200).map { revision(it, merge = it % 5 == 4) }

    private val commits = long.filterNot { it.isMerge }

    private val merges = long.filter { it.isMerge }

    private val own = BranchRevisions.OWN_REVS

    private val ownCommits = SelectionRevsets.OWN_COMMITS

    @Test
    fun `the default selection of a long branch is the branch commits without merges`() {
        assertEquals("$own and not merge()", ownCommits)
        assertEquals(ownCommits, SelectionRevsets.selected(long, RevisionSelection.DEFAULT))
    }

    @Test
    fun `the default selection is a revset however short the branch is`() {
        val short = listOf(revision(1), revision(2))

        assertEquals(ownCommits, SelectionRevsets.selected(short, RevisionSelection.DEFAULT))
    }

    @Test
    fun `a branch without merges still uses the expression without merges`() {
        assertEquals(ownCommits, SelectionRevsets.selected(commits, RevisionSelection.DEFAULT))
    }

    @Test
    fun `a single revision left is named directly`() {
        val short = listOf(revision(1), revision(2), revision(3))
        val selection = RevisionPicking.apply(short, RevisionSelection.DEFAULT, short.take(2), false)

        assertEquals(node(3), SelectionRevsets.selected(short, selection))
    }

    @Test
    fun `a deselected commit is subtracted from the default`() {
        val selection = RevisionSelection.DEFAULT.with(commits[3], false).with(commits[7], false)

        assertEquals(
            "($ownCommits) - (${commits[3].node}+${commits[7].node})",
            SelectionRevsets.selected(long, selection)
        )
    }

    @Test
    fun `a selected merge is added to the default`() {
        val selection = RevisionSelection.DEFAULT.with(merges[2], true)

        assertEquals("($ownCommits) + ${merges[2].node}", SelectionRevsets.selected(long, selection))
    }

    @Test
    fun `a deselected commit and a selected merge are both kept`() {
        val selection = RevisionSelection.DEFAULT.with(commits[0], false).with(merges[0], true)

        assertEquals(
            "(($ownCommits) - (${commits[0].node})) + ${merges[0].node}",
            SelectionRevsets.selected(long, selection)
        )
    }

    @Test
    fun `selecting every merge falls back to the whole branch`() {
        val selection = RevisionPicking.apply(long, RevisionSelection.DEFAULT, merges, true)

        assertEquals(own, SelectionRevsets.selected(long, selection))
    }

    @Test
    fun `a few selected revisions are listed instead of subtracting the rest`() {
        val kept = listOf(commits[10], commits[11])
        val selection = RevisionPicking.apply(long, RevisionSelection.DEFAULT, commits - kept.toSet(), false)

        assertEquals("${kept[0].node}+${kept[1].node}", SelectionRevsets.selected(long, selection))
    }

    @Test
    fun `overrides of revisions no longer on the branch do not count`() {
        val selection = RevisionSelection.fromStorage(listOf("deadbeef0000=0", "feedface0000=1"))

        assertEquals(ownCommits, SelectionRevsets.selected(long, selection))
    }

    @Test
    fun `the base is the first parent of the selected revision with the lowest number`() {
        val shuffled = listOf(revision(5), revision(2), revision(9))

        assertEquals("p1(${node(2)})", SelectionRevsets.base(shuffled, RevisionSelection.DEFAULT))
        assertEquals(
            "p1(${node(5)})",
            SelectionRevsets.base(shuffled, RevisionSelection.DEFAULT.with(shuffled[1], false))
        )
    }

    @Test
    fun `merges are skipped when looking for the base`() {
        assertEquals("p1(${commits[0].node})", SelectionRevsets.base(long, RevisionSelection.DEFAULT))
        assertEquals(
            "p1(${merges[0].node})",
            SelectionRevsets.base(long.drop(4), RevisionSelection.DEFAULT.with(merges[0], true))
        )
    }

    @Test
    fun `nothing selected has no base`() {
        assertEquals(BranchRevisions.NO_REVISION, SelectionRevsets.base(emptyList(), RevisionSelection.DEFAULT))
    }

    @Test
    fun `a review adds the branch merges between the first and the last selected revision`() {
        val first = commits.first().node
        val last = commits.last().node

        assertEquals(
            "($ownCommits) + (($first::$last) and merge() and ($own))",
            SelectionRevsets.review(long, RevisionSelection.DEFAULT)
        )
    }

    @Test
    fun `the merge window of a review follows a narrowed selection`() {
        val kept = listOf(commits[10], commits[20])
        val selection = RevisionPicking.apply(long, RevisionSelection.DEFAULT, commits - kept.toSet(), false)

        assertEquals(
            "(${kept[0].node}+${kept[1].node}) + ((${kept[0].node}::${kept[1].node}) and merge() and ($own))",
            SelectionRevsets.review(long, selection)
        )
    }

    @Test
    fun `the merge window is ordered by revision number, not by list order`() {
        val shuffled = listOf(revision(9), revision(2), revision(5))

        assertEquals(
            "($ownCommits) + ((${node(2)}::${node(9)}) and merge() and ($own))",
            SelectionRevsets.review(shuffled, RevisionSelection.DEFAULT)
        )
    }

    @Test
    fun `a review of one revision has no merges to add`() {
        val short = listOf(revision(1), revision(2), revision(3))
        val selection = RevisionPicking.apply(short, RevisionSelection.DEFAULT, short.take(2), false)

        assertEquals(node(3), SelectionRevsets.review(short, selection))
    }

    @Test
    fun `a selected merge stays in the review whatever the window`() {
        val selection = RevisionSelection.DEFAULT.with(merges[2], true)

        assertEquals(
            "(($ownCommits) + ${merges[2].node}) + ((${commits.first().node}::${commits.last().node}) " +
                "and merge() and ($own))",
            SelectionRevsets.review(long, selection)
        )
    }

    @Test
    fun `nothing selected is no review`() {
        assertEquals(null, SelectionRevsets.review(emptyList(), RevisionSelection.DEFAULT))
    }

    @Test
    fun `a subset covering the whole universe is the universe itself`() {
        val members = commits.map { it.node }

        assertEquals(ownCommits, SelectionRevsets.subset(ownCommits, members, members))
    }

    @Test
    fun `a subset missing a few members is the universe minus them`() {
        val members = commits.map { it.node }
        val wanted = members.drop(2)

        assertEquals(
            "($ownCommits) - (${members[0]}+${members[1]})",
            SelectionRevsets.subset(ownCommits, members, wanted)
        )
    }

    @Test
    fun `a small subset is listed node by node`() {
        val members = commits.map { it.node }

        assertEquals(
            "${members[4]}+${members[9]}",
            SelectionRevsets.subset(ownCommits, members, listOf(members[4], members[9]))
        )
    }

    @Test
    fun `a subset of a listed universe stays a list`() {
        assertEquals("n1+n2", SelectionRevsets.subset("n1+n2", listOf("n1", "n2"), listOf("n1", "n2")))
        assertEquals("n2", SelectionRevsets.subset("n1+n2", listOf("n1", "n2"), listOf("n2")))
    }

    @Test
    fun `the selection of a large branch stays short`() {
        val huge = (0 until 20_000).map { revision(it, merge = it % 3 == 0) }
        val dropped = huge.filterNot { it.isMerge }.take(50)
        val selection = RevisionPicking.apply(huge, RevisionSelection.DEFAULT, dropped, false)

        assertEquals(ownCommits, SelectionRevsets.selected(huge, RevisionSelection.DEFAULT))
        assertTrue(SelectionRevsets.selected(huge, selection).length < 1_000)
    }
}
