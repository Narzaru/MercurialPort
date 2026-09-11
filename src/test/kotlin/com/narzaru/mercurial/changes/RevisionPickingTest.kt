package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevisionPickingTest {

    private val log = listOf(
        "2547|d1d82b2|-1|Ivanov|2026-08-10 11:02 +0300|PSA-12517 fpz in zones",
        "2583|8fcae09|2582|Ivanov|2026-08-12 09:14 +0300|Merge with PSA-12506-new-properties",
        "2584|97fbf27|-1|Ivanov|2026-08-12 10:01 +0300|PSA-12517 zone properties",
        "2610|38e0420|2609|Ivanov|2026-08-14 18:20 +0300|Merge with default",
        "2611|702a733|-1|Ivanov|2026-08-14 18:41 +0300|PSA-12506 zones in the tree"
    ).joinToString("\n", postfix = "\n")

    private val revisions = BranchRevisions.parse(log)

    private val shown = revisions.map { it.node }

    private fun revision(node: String) = revisions.first { it.node == node }

    private fun selectedNodes(selection: RevisionSelection) = selection.selectedNodes(revisions)

    private fun pick(
        selection: RevisionSelection,
        desired: Boolean,
        vararg nodes: String
    ) = RevisionPicking.apply(revisions, selection, nodes.map { revision(it) }, desired)

    @Test
    fun `one revision drops out of the review`() {
        val selection = pick(RevisionSelection.DEFAULT, false, "97fbf27")
        assertEquals(listOf("d1d82b2", "702a733"), selectedNodes(selection))
    }

    @Test
    fun `one merge revision joins the review`() {
        val selection = pick(RevisionSelection.DEFAULT, true, "8fcae09")
        assertEquals(listOf("d1d82b2", "8fcae09", "97fbf27", "702a733"), selectedNodes(selection))
    }

    @Test
    fun `several revisions take the same state at once`() {
        val selection = pick(RevisionSelection.DEFAULT, false, "d1d82b2", "97fbf27", "702a733")
        assertEquals(emptyList<String>(), selectedNodes(selection))
    }

    @Test
    fun `a mixed set of revisions ends up in one state`() {
        val selection = pick(RevisionSelection.DEFAULT, true, "d1d82b2", "8fcae09", "38e0420")
        assertEquals(
            listOf("d1d82b2", "8fcae09", "97fbf27", "38e0420", "702a733"),
            selectedNodes(selection)
        )
    }

    @Test
    fun `the revisions left out keep their marks`() {
        val cleared = pick(RevisionSelection.DEFAULT, false, "d1d82b2")
        val selection = pick(cleared, true, "8fcae09")
        assertEquals(listOf("8fcae09", "97fbf27", "702a733"), selectedNodes(selection))
    }

    @Test
    fun `an empty set of revisions changes nothing`() {
        val selection = RevisionPicking.apply(revisions, RevisionSelection.DEFAULT, emptyList(), false)
        assertEquals(listOf("d1d82b2", "97fbf27", "702a733"), selectedNodes(selection))
        assertEquals(emptyList<String>(), selection.toStorage())
    }

    @Test
    fun `a revision outside of the list is skipped`() {
        val gone = HgRevision("2700", "abcdef0", false, "Ivanov", "2026-08-20 10:00 +0300", "gone")
        val selection = RevisionPicking.apply(revisions, RevisionSelection.DEFAULT, listOf(gone), false)
        assertEquals(listOf("d1d82b2", "97fbf27", "702a733"), selectedNodes(selection))
        assertEquals(emptyList<String>(), selection.toStorage())
    }

    @Test
    fun `the revisions of the list are kept even with an unknown one alongside`() {
        val gone = HgRevision("2700", "abcdef0", false, "Ivanov", "2026-08-20 10:00 +0300", "gone")
        val selection = RevisionPicking.apply(
            revisions, RevisionSelection.DEFAULT, listOf(gone, revision("97fbf27")), false
        )
        assertEquals(listOf("d1d82b2", "702a733"), selectedNodes(selection))
    }

    @Test
    fun `a pick with no revisions at hand changes nothing`() {
        val selection = RevisionPicking.apply(
            emptyList(), RevisionSelection.DEFAULT, listOf(revision("97fbf27")), false
        )
        assertEquals(RevisionSelection.DEFAULT, selection)
        assertEquals(emptyList<String>(), selection.toStorage())
    }

    @Test
    fun `a revision back in its default state leaves no override behind`() {
        val cleared = pick(RevisionSelection.DEFAULT, false, "97fbf27")
        assertEquals(listOf("97fbf27=0"), cleared.toStorage())
        val restored = pick(cleared, true, "97fbf27")
        assertEquals(emptyList<String>(), restored.toStorage())
        assertTrue(restored.isDefaultFor(revisions))
    }

    @Test
    fun `a pick survives a trip through the stored overrides`() {
        val picked = pick(RevisionSelection.DEFAULT, true, "d1d82b2", "8fcae09", "38e0420")
        val restored = RevisionSelection.fromStorage(picked.toStorage())
        assertEquals(selectedNodes(picked), restored.selectedNodes(revisions))
        assertEquals(picked.toStorage(), restored.toStorage())
    }

    @Test
    fun `a toggle inside the selection takes the whole selection`() {
        val toggle = RevisionToggling.targets(
            shown, "97fbf27", listOf("d1d82b2", "8fcae09", "97fbf27"), listOf("97fbf27"), false
        )
        assertEquals(listOf("d1d82b2", "8fcae09", "97fbf27"), toggle.nodes)
        assertTrue(toggle.restoresSelection)
    }

    @Test
    fun `a toggle outside the selection takes the row alone`() {
        val toggle = RevisionToggling.targets(
            shown, "38e0420", listOf("d1d82b2", "8fcae09"), listOf("38e0420"), false
        )
        assertEquals(listOf("38e0420"), toggle.nodes)
        assertFalse(toggle.restoresSelection)
    }

    @Test
    fun `a toggle with nothing selected takes the row alone`() {
        val toggle = RevisionToggling.targets(shown, "38e0420", emptyList(), emptyList(), false)
        assertEquals(listOf("38e0420"), toggle.nodes)
        assertFalse(toggle.restoresSelection)
    }

    @Test
    fun `a toggled revision gone from the list toggles nothing`() {
        val toggle = RevisionToggling.targets(
            shown, "abcdef0", listOf("d1d82b2", "abcdef0"), listOf("abcdef0"), false
        )
        assertEquals(emptyList<String>(), toggle.nodes)
        assertFalse(toggle.restoresSelection)
    }

    @Test
    fun `the revisions gone from the list drop out of the targets`() {
        val toggle = RevisionToggling.targets(
            shown, "97fbf27", listOf("abcdef0", "97fbf27", "702a733"), listOf("97fbf27"), false
        )
        assertEquals(listOf("97fbf27", "702a733"), toggle.nodes)
        assertTrue(toggle.restoresSelection)
    }

    @Test
    fun `a gesture with modifiers takes the selection it left behind`() {
        val toggle = RevisionToggling.targets(
            shown, "702a733", listOf("d1d82b2", "8fcae09", "97fbf27"), listOf("d1d82b2", "8fcae09", "97fbf27", "702a733"), true
        )
        assertEquals(listOf("d1d82b2", "8fcae09", "97fbf27", "702a733"), toggle.nodes)
        assertFalse(toggle.restoresSelection)
    }

    @Test
    fun `a gesture with modifiers that drops the row out toggles the row alone`() {
        val toggle = RevisionToggling.targets(
            shown, "8fcae09", listOf("d1d82b2", "8fcae09", "97fbf27"), listOf("d1d82b2", "97fbf27"), true
        )
        assertEquals(listOf("8fcae09"), toggle.nodes)
        assertFalse(toggle.restoresSelection)
    }

    @Test
    fun `an unknown branch name is not a branch name`() {
        assertFalse(RevisionsSync.isBranchKnown(""))
        assertTrue(RevisionsSync.isBranchKnown("feature"))
    }

    @Test
    fun `the first known branch brings its stored selection along`() {
        val stored = RevisionSelection.fromStorage(listOf("97fbf27=0"))
        val state = RevisionsSync.afterLoad(RevisionsState(), "feature", stored)
        assertEquals("feature", state.branch)
        assertEquals(listOf("d1d82b2", "702a733"), state.selection.selectedNodes(revisions))
    }

    @Test
    fun `a load of the same branch leaves the picked selection alone`() {
        val state = RevisionsState("feature", pick(RevisionSelection.DEFAULT, true, "8fcae09"))
        val reloaded = RevisionsSync.afterLoad(state, "feature", RevisionSelection.DEFAULT)
        assertEquals(state, reloaded)
    }

    @Test
    fun `a load without a branch name leaves the picked selection alone`() {
        val state = RevisionsState("feature", pick(RevisionSelection.DEFAULT, true, "8fcae09"))
        val reloaded = RevisionsSync.afterLoad(state, "", RevisionSelection.DEFAULT)
        assertEquals(state, reloaded)
    }

    @Test
    fun `a load without a branch name leaves the branch unknown`() {
        val state = RevisionsState("", pick(RevisionSelection.DEFAULT, true, "8fcae09"))
        val reloaded = RevisionsSync.afterLoad(state, "", RevisionSelection.fromStorage(listOf("8fcae09=0")))
        assertEquals(state, reloaded)
        assertFalse(RevisionsSync.isBranchKnown(reloaded.branch))
    }

    @Test
    fun `another branch takes over the selection`() {
        val state = RevisionsState("feature", pick(RevisionSelection.DEFAULT, true, "8fcae09"))
        val stored = RevisionSelection.fromStorage(listOf("d1d82b2=0"))
        val reloaded = RevisionsSync.afterLoad(state, "other", stored)
        assertEquals(RevisionsState("other", stored), reloaded)
        assertEquals(listOf("97fbf27", "702a733"), reloaded.selection.selectedNodes(revisions))
    }
}
