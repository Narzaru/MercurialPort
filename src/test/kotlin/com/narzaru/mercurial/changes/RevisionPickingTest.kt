package com.narzaru.mercurial.changes

import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevisionPickingTest {

    private val LEGACY_BUTTON1_MASK = 16

    private val LEGACY_BUTTON2_MASK = 8

    private val log = listOf(
        "2547|d1d82b2|-1|Ivanov|2026-08-10 11:02 +0300|PSA-12517 fpz in zones",
        "2583|8fcae09|2582|Ivanov|2026-08-12 09:14 +0300|Merge with PSA-12506-new-properties",
        "2584|97fbf27|-1|Ivanov|2026-08-12 10:01 +0300|PSA-12517 zone properties",
        "2610|38e0420|2609|Ivanov|2026-08-14 18:20 +0300|Merge with default",
        "2611|702a733|-1|Ivanov|2026-08-14 18:41 +0300|PSA-12506 zones in the tree"
    ).joinToString("\n", postfix = "\n")

    private val revisions = BranchRevisions.parse(log)

    private fun revision(node: String) = revisions.first { it.node == node }

    private fun selectedNodes(pick: RevisionPick) = pick.selection.selectedNodes(revisions)

    private fun toggle(
        selection: RevisionSelection,
        anchor: String,
        node: String,
        desired: Boolean
    ) = RevisionPicking.apply(
        revisions, selection, anchor, revision(node), RevisionClickKind.TOGGLE, desired
    )

    private fun range(
        selection: RevisionSelection,
        anchor: String,
        node: String,
        desired: Boolean
    ) = RevisionPicking.apply(
        revisions, selection, anchor, revision(node), RevisionClickKind.RANGE, desired
    )

    @Test
    fun `a plain click is a toggle`() {
        assertEquals(RevisionClickKind.TOGGLE, RevisionGesture.kindOf(0))
    }

    @Test
    fun `a ctrl click is still a toggle`() {
        assertEquals(RevisionClickKind.TOGGLE, RevisionGesture.kindOf(ActionEvent.CTRL_MASK))
    }

    @Test
    fun `a shift click asks for a range`() {
        assertEquals(RevisionClickKind.RANGE, RevisionGesture.kindOf(ActionEvent.SHIFT_MASK))
    }

    @Test
    fun `a shift click reported with the extended mask asks for a range`() {
        assertEquals(RevisionClickKind.RANGE, RevisionGesture.kindOf(InputEvent.SHIFT_DOWN_MASK))
    }

    @Test
    fun `a shift click reported together with another modifier asks for a range`() {
        assertEquals(
            RevisionClickKind.RANGE,
            RevisionGesture.kindOf(ActionEvent.SHIFT_MASK or ActionEvent.ALT_MASK)
        )
    }

    @Test
    fun `a click of the first button alone is a toggle`() {
        assertEquals(RevisionClickKind.TOGGLE, RevisionGesture.kindOf(LEGACY_BUTTON1_MASK))
    }

    @Test
    fun `a click of the middle button alone is a toggle`() {
        assertEquals(RevisionClickKind.TOGGLE, RevisionGesture.kindOf(LEGACY_BUTTON2_MASK))
    }

    @Test
    fun `a ctrl click reported with the extended mask is still a toggle`() {
        assertEquals(RevisionClickKind.TOGGLE, RevisionGesture.kindOf(InputEvent.CTRL_DOWN_MASK))
    }

    @Test
    fun `an alt click reported with the extended mask is still a toggle`() {
        assertEquals(RevisionClickKind.TOGGLE, RevisionGesture.kindOf(InputEvent.ALT_DOWN_MASK))
    }

    @Test
    fun `a plain click changes one revision and sets the anchor`() {
        val pick = toggle(RevisionSelection.DEFAULT, "", "97fbf27", false)
        assertEquals(listOf("d1d82b2", "702a733"), selectedNodes(pick))
        assertEquals("97fbf27", pick.anchor)
    }

    @Test
    fun `a plain click on a merge selects it`() {
        val pick = toggle(RevisionSelection.DEFAULT, "", "8fcae09", true)
        assertEquals(listOf("d1d82b2", "8fcae09", "97fbf27", "702a733"), selectedNodes(pick))
        assertEquals("8fcae09", pick.anchor)
    }

    @Test
    fun `a shift click without an anchor works as a toggle`() {
        val pick = range(RevisionSelection.DEFAULT, "", "97fbf27", false)
        assertEquals(listOf("d1d82b2", "702a733"), selectedNodes(pick))
        assertEquals("97fbf27", pick.anchor)
    }

    @Test
    fun `a shift click down the list clears the whole range`() {
        val anchored = toggle(RevisionSelection.DEFAULT, "", "d1d82b2", false)
        val pick = range(anchored.selection, anchored.anchor, "97fbf27", false)
        assertEquals(listOf("702a733"), selectedNodes(pick))
        assertEquals("d1d82b2", pick.anchor)
    }

    @Test
    fun `a shift click up the list clears the same range`() {
        val anchored = toggle(RevisionSelection.DEFAULT, "", "97fbf27", false)
        val pick = range(anchored.selection, anchored.anchor, "d1d82b2", false)
        assertEquals(listOf("702a733"), selectedNodes(pick))
        assertEquals("97fbf27", pick.anchor)
    }

    @Test
    fun `a range covers the merges it runs over`() {
        val anchored = toggle(RevisionSelection.DEFAULT, "", "d1d82b2", true)
        val pick = range(anchored.selection, anchored.anchor, "702a733", true)
        assertEquals(
            listOf("d1d82b2", "8fcae09", "97fbf27", "38e0420", "702a733"),
            selectedNodes(pick)
        )
    }

    @Test
    fun `a range leaves the revisions outside of it alone`() {
        val anchored = toggle(RevisionSelection.DEFAULT, "", "8fcae09", true)
        val pick = range(anchored.selection, anchored.anchor, "97fbf27", true)
        assertEquals(listOf("d1d82b2", "8fcae09", "97fbf27", "702a733"), selectedNodes(pick))
    }

    @Test
    fun `a second shift click from the same anchor shrinks the range`() {
        val anchored = toggle(RevisionSelection.DEFAULT, "", "d1d82b2", false)
        val wide = range(anchored.selection, anchored.anchor, "702a733", false)
        assertEquals(emptyList<String>(), selectedNodes(wide))
        val narrow = range(wide.selection, wide.anchor, "8fcae09", true)
        assertEquals(listOf("d1d82b2", "8fcae09"), selectedNodes(narrow))
        assertEquals("d1d82b2", narrow.anchor)
    }

    @Test
    fun `an anchor lost with the branch falls back to a toggle`() {
        val pick = range(RevisionSelection.DEFAULT, "0000000", "97fbf27", false)
        assertEquals(listOf("d1d82b2", "702a733"), selectedNodes(pick))
        assertEquals("97fbf27", pick.anchor)
    }

    @Test
    fun `a click on a revision outside of the list changes nothing`() {
        val gone = HgRevision("2700", "abcdef0", false, "Ivanov", "2026-08-20 10:00 +0300", "gone")
        val pick = RevisionPicking.apply(
            revisions, RevisionSelection.DEFAULT, "d1d82b2", gone, RevisionClickKind.TOGGLE, false
        )
        assertEquals(listOf("d1d82b2", "97fbf27", "702a733"), selectedNodes(pick))
        assertEquals("d1d82b2", pick.anchor)
    }

    @Test
    fun `a range back to the anchor itself changes only the anchor`() {
        val anchored = toggle(RevisionSelection.DEFAULT, "", "97fbf27", false)
        val pick = range(anchored.selection, anchored.anchor, "97fbf27", true)
        assertEquals(listOf("d1d82b2", "97fbf27", "702a733"), selectedNodes(pick))
        assertEquals("97fbf27", pick.anchor)
    }

    @Test
    fun `a click with no revisions at hand changes nothing`() {
        val pick = RevisionPicking.apply(
            emptyList(), RevisionSelection.DEFAULT, "d1d82b2", revision("97fbf27"),
            RevisionClickKind.TOGGLE, false
        )
        assertEquals(RevisionSelection.DEFAULT, pick.selection)
        assertEquals("d1d82b2", pick.anchor)
    }

    @Test
    fun `a shift click with no revisions at hand keeps the anchor`() {
        val pick = RevisionPicking.apply(
            emptyList(), RevisionSelection.DEFAULT, "", revision("97fbf27"),
            RevisionClickKind.RANGE, false
        )
        assertEquals(emptyList<String>(), pick.selection.selectedNodes(emptyList()))
        assertEquals("", pick.anchor)
    }

    @Test
    fun `a range survives a trip through the stored overrides`() {
        val anchored = toggle(RevisionSelection.DEFAULT, "", "d1d82b2", false)
        val picked = range(anchored.selection, anchored.anchor, "38e0420", true)
        val restored = RevisionSelection.fromStorage(picked.selection.toStorage())
        assertEquals(selectedNodes(picked), restored.selectedNodes(revisions))
        assertEquals(picked.selection.toStorage(), restored.toStorage())
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
        assertEquals("", state.anchor)
    }

    @Test
    fun `a load of the same branch leaves the picked selection alone`() {
        val picked = toggle(RevisionSelection.DEFAULT, "", "8fcae09", true)
        val state = RevisionsState("feature", picked.selection, picked.anchor)
        val reloaded = RevisionsSync.afterLoad(state, "feature", RevisionSelection.DEFAULT)
        assertEquals(state, reloaded)
    }

    @Test
    fun `a load without a branch name leaves the picked selection alone`() {
        val picked = toggle(RevisionSelection.DEFAULT, "", "8fcae09", true)
        val state = RevisionsState("feature", picked.selection, picked.anchor)
        val reloaded = RevisionsSync.afterLoad(state, "", RevisionSelection.DEFAULT)
        assertEquals(state, reloaded)
    }

    @Test
    fun `a load without a branch name leaves the branch unknown`() {
        val picked = toggle(RevisionSelection.DEFAULT, "", "8fcae09", true)
        val state = RevisionsState("", picked.selection, picked.anchor)
        val reloaded = RevisionsSync.afterLoad(state, "", RevisionSelection.fromStorage(listOf("8fcae09=0")))
        assertEquals(state, reloaded)
        assertFalse(RevisionsSync.isBranchKnown(reloaded.branch))
    }

    @Test
    fun `another branch takes over the selection and drops the anchor`() {
        val picked = toggle(RevisionSelection.DEFAULT, "", "8fcae09", true)
        val state = RevisionsState("feature", picked.selection, picked.anchor)
        val stored = RevisionSelection.fromStorage(listOf("d1d82b2=0"))
        val reloaded = RevisionsSync.afterLoad(state, "other", stored)
        assertEquals(RevisionsState("other", stored, ""), reloaded)
        assertEquals(listOf("97fbf27", "702a733"), reloaded.selection.selectedNodes(revisions))
    }
}
