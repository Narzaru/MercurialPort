package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchSizeGuardTest {

    private val template = listOf("--template", BranchRevisions.TEMPLATE + "\\n")

    private val log = listOf(
        "2547|d1d82b2|-1|Ivanov|2026-08-10 11:02 +0300|First",
        "2583|8fcae09|2582|Ivanov|2026-08-12 09:14 +0300|Merge with default",
        "2584|97fbf27|-1|Ivanov|2026-08-12 10:01 +0300|Second"
    )

    private val oldestFirst = BranchRevisions.parse(log.joinToString("\n"))

    private val newestFirst = BranchRevisions.parse(log.asReversed().joinToString("\n"))

    @Test
    fun `without a limit the branch is read whole and oldest first as before`() {
        val guard = BranchSizeGuard(0)

        assertEquals(listOf("log", "-r", BranchRevisions.OWN_REVS) + template, guard.logArgs())
        val read = guard.read(oldestFirst)
        assertFalse(read.tooLarge)
        assertEquals(oldestFirst, read.revisions)
    }

    @Test
    fun `a limit asks for one revision more than it allows, newest first`() {
        assertEquals(
            listOf("log", "-r", "reverse(${BranchRevisions.OWN_REVS})", "-l", "101") + template,
            BranchSizeGuard(100).logArgs()
        )
    }

    @Test
    fun `a branch within the limit comes back oldest first, the same as an unlimited read`() {
        for (limit in listOf(3, 4, 100)) {
            val read = BranchSizeGuard(limit).read(newestFirst)

            assertFalse(read.tooLarge)
            assertEquals(oldestFirst, read.revisions)
            assertEquals(BranchSizeGuard(0).read(oldestFirst).revisions, read.revisions)
        }
    }

    @Test
    fun `everything derived from the revisions matches the unlimited read`() {
        val limited = BranchSizeGuard(100).read(newestFirst).revisions
        val selection = RevisionSelection.fromStorage(listOf("8fcae09=1", "d1d82b2=0"))

        assertEquals(SelectionRevsets.base(oldestFirst, selection), SelectionRevsets.base(limited, selection))
        assertEquals(SelectionRevsets.selected(oldestFirst, selection), SelectionRevsets.selected(limited, selection))
        assertEquals(selection.selectedNodes(oldestFirst), selection.selectedNodes(limited))
        assertEquals(
            BranchRevisions.header(oldestFirst, RevisionSelection.DEFAULT),
            BranchRevisions.header(limited, RevisionSelection.DEFAULT)
        )
        assertEquals("p1(d1d82b2)", SelectionRevsets.base(limited, RevisionSelection.DEFAULT))
    }

    @Test
    fun `receiving one revision more than the limit marks the branch too large and keeps none`() {
        val read = BranchSizeGuard(2).read(newestFirst)

        assertTrue(read.tooLarge)
        assertTrue(read.revisions.isEmpty())
    }

    @Test
    fun `an empty or unreadable branch is not too large`() {
        val read = BranchSizeGuard(100).read(emptyList())

        assertFalse(read.tooLarge)
        assertTrue(read.revisions.isEmpty())
    }

    @Test
    fun `the refusal names the branch, the limit, the mode and the way out`() {
        assertEquals(
            "Branch 'default' has more than 100 revisions, too many for 'Changesets'. " +
                "Use 'Uncommitted' or 'Merge', or raise the limit in Settings → Tools → Mercurial Port.",
            BranchSizeGuard(100).tooLargeText("default", HgDisplayMode.CHANGESETS)
        )
        assertTrue(
            BranchSizeGuard(5).tooLargeText("", HgDisplayMode.BRANCH)
                .startsWith("This branch has more than 5 revisions, too many for 'Base'.")
        )
    }
}
