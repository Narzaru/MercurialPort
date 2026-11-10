package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ChangesDiffSourceTest {

    private val repoRoot = File("repo").absoluteFile

    private val changed = HgFileItem(status = "M", path = "src/A.kt")

    private val state = ComparisonState(repoRoot = repoRoot).withItems(
        listOf(changed, HgFileItem(status = "M", path = "same.kt", isUnchanged = true))
    )

    @Test
    fun `a changed file is found by its path on disk`() {
        assertEquals(changed, ChangesDiffSource.changedItem(state, File(repoRoot, "src/a.kt").path))
    }

    @Test
    fun `a file without real changes has no diff`() {
        assertNull(ChangesDiffSource.changedItem(state, File(repoRoot, "same.kt").path))
    }

    @Test
    fun `a file outside the repository has no diff`() {
        assertNull(ChangesDiffSource.changedItem(state, File(repoRoot.parentFile, "elsewhere/src/A.kt").path))
    }

    @Test
    fun `nothing is found before the changes are loaded`() {
        assertNull(ChangesDiffSource.changedItem(ComparisonState(), File(repoRoot, "src/A.kt").path))
    }
}
