package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgFailure
import com.narzaru.mercurial.hg.HgResult
import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTextFormatterTest {

    private val current = "feature|a1b2c3|Добавил фичу"
    private val base = "default|000111|Базовый коммит"

    @Test
    fun `uncommitted changes show only the current revision`() {
        val text = StatusTextFormatter.branchInfo(HgDisplayMode.UNCOMMITTED, current, base)

        assertEquals("Uncommitted in: feature (a1b2c3) \"Добавил фичу\"", text)
    }

    @Test
    fun `a branch shows the comparison with its parent`() {
        val text = StatusTextFormatter.branchInfo(HgDisplayMode.BRANCH, current, base)

        assertEquals(
            "Branch: feature (a1b2c3) \"Добавил фичу\" vs Branch point: default (000111) \"Базовый коммит\"",
            text
        )
    }

    @Test
    fun `the mode sets the wording of the comparison`() {
        assertTrue(
            StatusTextFormatter.branchInfo(HgDisplayMode.BRANCH, current, base)
                .contains(" vs Branch point: ")
        )
        assertTrue(
            StatusTextFormatter.branchInfo(HgDisplayMode.CUSTOM_BRANCH, current, base)
                .contains(" vs Branch: ")
        )
    }

    @Test
    fun `an unrecognized revision description is shown as is`() {
        val text = StatusTextFormatter.branchInfo(HgDisplayMode.UNCOMMITTED, "мусор", base)

        assertEquals("Uncommitted in: мусор", text)
    }

    @Test
    fun `a commit title with a vertical bar is not cut`() {
        val text = StatusTextFormatter.branchInfo(HgDisplayMode.UNCOMMITTED, "b|node|fix a|b", base)

        assertEquals("Uncommitted in: b (node) \"fix a|b\"", text)
    }

    @Test
    fun `the summary sums up the added and removed lines`() {
        val files = listOf(
            HgFileItem(status = "M", path = "a.kt", added = 3, removed = 1),
            HgFileItem(status = "M", path = "b.kt", added = 4, removed = 2)
        )

        val text = StatusTextFormatter.summary(files, reviewed = 1, statsPending = false)

        assertEquals("2 files  +7  −3  ·  1/2 reviewed", text)
    }

    @Test
    fun `while the statistics are counted a mark is shown instead of numbers`() {
        val files = listOf(HgFileItem(status = "M", path = "a.kt"))

        val text = StatusTextFormatter.summary(files, reviewed = 0, statsPending = true)

        assertTrue(text.contains("counting ±"))
        assertTrue(text.startsWith("1 files"))
    }

    @Test
    fun `an empty list gives an empty summary`() {
        assertEquals(" ", StatusTextFormatter.summary(emptyList(), reviewed = 0, statsPending = false))
        assertEquals(" ", StatusTextFormatter.summary(emptyList(), reviewed = 0, statsPending = true))
    }

    @Test
    fun `merge mode says which merged revision the branch is compared against`() {
        val text = StatusTextFormatter.branchInfo(
            HgDisplayMode.MERGE, "feature|abc123|work", "default|def456|other work"
        )

        assertTrue(text.contains("vs Last merged in:"))
        assertTrue(text.contains("default (def456)"))
    }

    @Test
    fun `merge mode reports that nothing of the parent branch was ever merged in`() {
        val problem = StatusTextFormatter.mergeBaseProblem(
            "default", "feature|abc123|work", StatusTextFormatter.UNKNOWN_REVISION
        )

        assertTrue(problem != null && problem.contains("nothing has been merged in"))
    }

    @Test
    fun `merge mode reports standing on the parent branch itself`() {
        val problem = StatusTextFormatter.mergeBaseProblem(
            "default", "default|abc123|work", "default|abc123|work"
        )

        assertTrue(problem != null && problem.contains("Uncommitted"))
    }

    @Test
    fun `merge mode has no complaint once a merged revision is found`() {
        assertNull(
            StatusTextFormatter.mergeBaseProblem(
                "default", "feature|abc123|work", "default|def456|other work"
            )
        )
    }

    @Test
    fun `every root branch marker in stderr is recognized`() {
        for (marker in listOf("revision 0", "unknown revision", "empty revision")) {
            val text = StatusTextFormatter.statusError(
                HgDisplayMode.BRANCH, failure("abort: $marker is not usable")
            )

            assertTrue(marker, text.startsWith("ROOT BRANCH DETECTED (No Parent)."))
            assertTrue(marker, text.contains("Details: abort: $marker is not usable"))
        }
    }

    @Test
    fun `an unrecognized failure is reported as is`() {
        val text = StatusTextFormatter.statusError(HgDisplayMode.BRANCH, failure("abort: no such file"))

        assertEquals("${HgFailure.HG_ERROR_PREFIX}: abort: no such file", text)
    }

    @Test
    fun `a root branch marker is ignored in a mode without revisions`() {
        val text = StatusTextFormatter.statusError(
            HgDisplayMode.UNCOMMITTED, failure("abort: unknown revision")
        )

        assertEquals("${HgFailure.HG_ERROR_PREFIX}: abort: unknown revision", text)
    }

    @Test
    fun `an hg that could not be started is never taken for a root branch`() {
        val text = StatusTextFormatter.statusError(
            HgDisplayMode.BRANCH,
            HgResult(-1, "", "unknown revision", failedToStart = true)
        )

        assertEquals("${HgFailure.NOT_STARTED_PREFIX}: unknown revision", text)
    }

    private fun failure(stderr: String) = HgResult(255, "", stderr)
}
