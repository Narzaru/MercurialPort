package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgDisplayMode
import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartialStatsPlanTest {

    private fun item(
        path: String,
        status: String = "M",
        base: String = "",
        head: String = "",
        copiedFrom: String = "",
        unchanged: Boolean = false
    ) = HgFileItem(
        status = status,
        path = path,
        isUnchanged = unchanged,
        copiedFrom = copiedFrom,
        baseRev = base,
        headRev = head
    )

    private fun of(
        files: List<HgFileItem>,
        keys: List<String>,
        mode: HgDisplayMode = HgDisplayMode.UNCOMMITTED
    ) = PartialStatsPlan.of(mode, "42", files, keys)

    @Test
    fun `only the changed file is counted`() {
        val action = of(listOf(item("a.kt"), item("b.kt")), listOf("a.kt"))

        assertEquals(
            PartialStatsAction.Count(DiffRange("42", ""), listOf("a.kt"), setOf("a.kt")),
            action
        )
    }

    @Test
    fun `a file hidden as unchanged is counted again`() {
        val action = of(listOf(item("a.kt", status = "♦", unchanged = true)), listOf("a.kt"))

        assertEquals(
            PartialStatsAction.Count(DiffRange("42", ""), listOf("a.kt"), setOf("a.kt")),
            action
        )
    }

    @Test
    fun `a renamed file is counted under both of its paths`() {
        val action = of(listOf(item("new.kt", copiedFrom = "old.kt")), listOf("old.kt"))

        assertEquals(
            PartialStatsAction.Count(DiffRange("42", ""), listOf("new.kt", "old.kt"), setOf("new.kt")),
            action
        )
    }

    @Test
    fun `windows separators and case match the listed path`() {
        val action = of(listOf(item("src/a.kt")), listOf("SRC\\A.kt"))

        assertEquals(
            PartialStatsAction.Count(DiffRange("42", ""), listOf("src/a.kt"), setOf("src/a.kt")),
            action
        )
    }

    @Test
    fun `changesets compare two revisions and are left alone`() {
        assertEquals(
            PartialStatsAction.Skip,
            of(listOf(item("a.kt", base = "7", head = "9")), listOf("a.kt"), HgDisplayMode.CHANGESETS)
        )
    }

    @Test
    fun `a file whose right side is a revision is left alone`() {
        assertEquals(PartialStatsAction.Skip, of(listOf(item("a.kt", base = "7", head = "9")), listOf("a.kt")))
    }

    @Test
    fun `a path outside the list is ignored`() {
        assertEquals(PartialStatsAction.Skip, of(listOf(item("a.kt")), listOf("other.kt")))
    }

    @Test
    fun `nothing changed means nothing to do`() {
        assertEquals(PartialStatsAction.Skip, of(listOf(item("a.kt")), emptyList()))
        assertEquals(PartialStatsAction.Skip, of(emptyList(), listOf("a.kt")))
    }

    @Test
    fun `a batch over the path limit is counted in full`() {
        val files = (1..DiffStatsPlan.MAX_DIFF_PATHS + 1).map { item("file$it.kt") }

        assertEquals(PartialStatsAction.CountAll, of(files, files.map { it.path }))
    }

    @Test
    fun `an untouched file keeps the batch small`() {
        val files = (1..DiffStatsPlan.MAX_DIFF_PATHS + 1).map { item("file$it.kt") }

        assertTrue(of(files, listOf("file1.kt")) is PartialStatsAction.Count)
    }

    @Test
    fun `an untracked file is hashed by content without a diff`() {
        val action = of(listOf(item("a.kt"), item("new.txt", status = "?")), listOf("new.txt"))

        assertEquals(
            PartialStatsAction.Count(DiffRange("42", ""), emptyList(), setOf("new.txt"), listOf("new.txt")),
            action
        )
        assertFalse((action as PartialStatsAction.Count).needsDiff)
    }

    @Test
    fun `untracked files edited along with tracked ones stay out of the diff`() {
        val action = of(listOf(item("a.kt"), item("new.txt", status = "?")), listOf("a.kt", "new.txt"))

        assertEquals(
            PartialStatsAction.Count(DiffRange("42", ""), listOf("a.kt"), setOf("a.kt", "new.txt"), listOf("new.txt")),
            action
        )
        assertTrue((action as PartialStatsAction.Count).needsDiff)
    }

    @Test
    fun `changed files of different ranges are counted in full`() {
        val files = listOf(item("a.kt", base = "7"), item("b.kt", base = "9"))

        assertEquals(PartialStatsAction.CountAll, of(files, listOf("a.kt", "b.kt")))
    }
}
