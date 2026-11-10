package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgFileStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffSidesPlanTest {

    @Test
    fun `a new file has no base side`() {
        for (status in listOf(HgFileStatus.ADDED, HgFileStatus.UNTRACKED)) {
            val plan = DiffSidesPlan.of(HgFileItem(status = status, path = "a.kt"), "42")

            assertFalse(status.letter, plan.needsBase)
        }
    }

    @Test
    fun `a modified file is read from the panel base revision`() {
        val plan = DiffSidesPlan.of(HgFileItem(status = HgFileStatus.MODIFIED, path = "a.kt"), "42")

        assertTrue(plan.needsBase)
        assertEquals("42", plan.baseRev)
        assertEquals("a.kt", plan.basePath)
        assertFalse(plan.rightIsRevision)
    }

    @Test
    fun `a file renamed in the branch is read under its old path`() {
        val item = HgFileItem(status = HgFileStatus.ADDED, path = "b.kt", copiedFrom = "a.kt")

        val plan = DiffSidesPlan.of(item, "42")

        assertTrue(plan.needsBase)
        assertEquals("a.kt", plan.basePath)
        assertEquals("b.kt", plan.headPath)
    }

    @Test
    fun `an empty base revision leaves no base side`() {
        val plan = DiffSidesPlan.of(HgFileItem(status = HgFileStatus.MODIFIED, path = "a.kt"), "")

        assertFalse(plan.needsBase)
    }

    @Test
    fun `a missing base revision leaves no base side`() {
        val plan = DiffSidesPlan.of(HgFileItem(status = HgFileStatus.MODIFIED, path = "a.kt"), BranchRevisions.NO_REVISION)

        assertFalse(plan.needsBase)
    }

    @Test
    fun `a file with its own revisions makes the right side a revision`() {
        val item = HgFileItem(status = HgFileStatus.MODIFIED, path = "a.kt", baseRev = "7", headRev = "9")

        val plan = DiffSidesPlan.of(item, "42")

        assertTrue(plan.rightIsRevision)
        assertEquals("7", plan.baseRev)
        assertEquals("9", plan.headRev)
        assertEquals("p1(9)", plan.lastChangeBaseRev)
    }
}
