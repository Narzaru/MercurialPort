package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonStateTest {

    private fun item(path: String, unchanged: Boolean = false, line: Int = 0) = HgFileItem(
        status = "M",
        path = path,
        isUnchanged = unchanged,
        lineNumber = line
    )

    @Test
    fun `revisions are found by the file path`() {
        val state = ComparisonState(changesetRevs = mapOf("src/a.kt" to listOf("7", "9")))
        assertEquals(listOf("7", "9"), state.revsFor("src\\A.kt", "src/a.kt"))
    }

    @Test
    fun `revisions of a renamed file are found by its source path`() {
        val state = ComparisonState(changesetRevs = mapOf("old.kt" to listOf("7")))
        assertEquals(listOf("7"), state.revsFor("new.kt", "old.kt"))
    }

    @Test
    fun `an unknown file has no revisions`() {
        assertNull(ComparisonState().revsFor("new.kt", "new.kt"))
    }

    @Test
    fun `a changed file is found by its key`() {
        val state = ComparisonState().withItems(listOf(item("src/a.kt")))
        assertEquals("src/a.kt", state.changedItemAt("src/a.kt")?.path)
    }

    @Test
    fun `an unchanged file is not offered for a diff`() {
        val state = ComparisonState().withItems(listOf(item("src/a.kt", unchanged = true)))
        assertNull(state.changedItemAt("src/a.kt"))
    }

    @Test
    fun `todo rows stay out of the file index`() {
        val state = ComparisonState().withItems(listOf(item("src/a.kt", line = 12)))
        assertTrue(state.itemsByPath.isEmpty())
    }

    @Test
    fun `new items replace the previous ones`() {
        val state = ComparisonState().withItems(listOf(item("a.kt"))).withItems(listOf(item("b.kt")))
        assertNull(state.changedItemAt("a.kt"))
        assertEquals("b.kt", state.changedItemAt("b.kt")?.path)
    }
}
