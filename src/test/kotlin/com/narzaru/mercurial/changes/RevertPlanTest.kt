package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevertPlanTest {

    private val single = listOf(HgFileItem(status = "M", path = "src/a.kt"))

    private fun many(count: Int) =
        (1..count).map { HgFileItem(status = "M", path = "src/file$it.kt") }

    @Test
    fun `a prompt for uncommitted changes does not mention a revision`() {
        val text = RevertPrompt.text(single, HgDisplayMode.UNCOMMITTED, "42")

        assertTrue(text.startsWith("Revert uncommitted changes in 1 file(s)?"))
        assertFalse(text.contains("42"))
        assertTrue(text.contains("src/a.kt"))
    }

    @Test
    fun `a prompt for a branch warns about the base revision`() {
        val text = RevertPrompt.text(single, HgDisplayMode.BRANCH, "42")

        assertTrue(text.contains("Restore 1 file(s) to the base revision (42)?"))
        assertTrue(text.contains("will be lost"))
    }

    @Test
    fun `a long list of files is cut short`() {
        val files = many(RevertPrompt.PREVIEW_LIMIT + 3)

        val text = RevertPrompt.text(files, HgDisplayMode.UNCOMMITTED, "42")

        assertTrue(text.contains("src/file${RevertPrompt.PREVIEW_LIMIT}.kt"))
        assertFalse(text.contains("src/file${RevertPrompt.PREVIEW_LIMIT + 1}.kt"))
        assertTrue(text.contains("... and 3 more"))
    }

    @Test
    fun `a list within the limit is listed in full`() {
        val files = many(RevertPrompt.PREVIEW_LIMIT)

        val text = RevertPrompt.text(files, HgDisplayMode.UNCOMMITTED, "42")

        assertTrue(text.contains("src/file${RevertPrompt.PREVIEW_LIMIT}.kt"))
        assertFalse(text.contains("more"))
    }

    @Test
    fun `uncommitted changes are reverted without a revision`() {
        val args = RevertPlan.arguments(single, HgDisplayMode.UNCOMMITTED, "42")

        assertEquals(listOf("revert", "--no-backup", "src/a.kt"), args)
    }

    @Test
    fun `every other mode reverts to the base revision`() {
        for (mode in HgDisplayMode.entries.filter { it != HgDisplayMode.UNCOMMITTED }) {
            assertEquals(
                mode.name,
                listOf("revert", "--no-backup", "-r", "42", "src/a.kt"),
                RevertPlan.arguments(single, mode, "42")
            )
        }
    }

    @Test
    fun `a renamed file carries its source along`() {
        val renamed = listOf(HgFileItem(status = "A", path = "src/b.kt", copiedFrom = "src/a.kt"))

        assertEquals(listOf("src/b.kt", "src/a.kt"), RevertPlan.affectedPaths(renamed))
        assertEquals(
            listOf("revert", "--no-backup", "src/b.kt", "src/a.kt"),
            RevertPlan.arguments(renamed, HgDisplayMode.UNCOMMITTED, "42")
        )
    }
}
