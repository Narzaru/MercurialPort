package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgDiffStat
import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffStatsPlanTest {

    private fun item(path: String, base: String = "", head: String = "", copiedFrom: String = "") =
        HgFileItem(status = "M", path = path, copiedFrom = copiedFrom, baseRev = base, headRev = head)

    @Test
    fun `a file without its own revisions falls back to the target revision`() {
        assertEquals(DiffRange("42", ""), DiffStatsPlan.rangeOf(item("a.kt"), "42"))
    }

    @Test
    fun `a file carries its own range`() {
        assertEquals(DiffRange("7", "9"), DiffStatsPlan.rangeOf(item("a.kt", "7", "9"), "42"))
    }

    @Test
    fun `no files means no paths`() {
        assertTrue(DiffStatsPlan.pathsByRange(listOf(DiffRange("42", "")), emptyList(), "42").isEmpty())
    }

    @Test
    fun `files of one range are grouped together`() {
        val range = DiffRange("42", "")
        val files = listOf(item("a.kt"), item("b.kt"))

        assertEquals(
            mapOf(range to listOf("a.kt", "b.kt")),
            DiffStatsPlan.pathsByRange(listOf(range), files, "42")
        )
    }

    @Test
    fun `files of different ranges are grouped apart`() {
        val first = DiffRange("7", "9")
        val second = DiffRange("9", "11")
        val files = listOf(item("a.kt", "7", "9"), item("b.kt", "9", "11"))

        assertEquals(
            mapOf(first to listOf("a.kt"), second to listOf("b.kt")),
            DiffStatsPlan.pathsByRange(listOf(first, second), files, "42")
        )
    }

    @Test
    fun `a range that was not asked for is skipped`() {
        val known = DiffRange("7", "9")
        val files = listOf(item("a.kt", "7", "9"), item("b.kt", "9", "11"))

        assertEquals(
            mapOf(known to listOf("a.kt")),
            DiffStatsPlan.pathsByRange(listOf(known), files, "42")
        )
    }

    @Test
    fun `a renamed file brings its source path along`() {
        val range = DiffRange("42", "")
        val files = listOf(item("b.kt", copiedFrom = "a.kt"))

        assertEquals(
            mapOf(range to listOf("b.kt", "a.kt")),
            DiffStatsPlan.pathsByRange(listOf(range), files, "42")
        )
    }

    @Test
    fun `too many files drop the path filter altogether`() {
        val range = DiffRange("42", "")
        val files = (1..DiffStatsPlan.MAX_DIFF_PATHS + 1).map { item("file$it.kt") }

        assertTrue(DiffStatsPlan.pathsByRange(listOf(range), files, "42").isEmpty())
    }

    @Test
    fun `files right at the limit keep the path filter`() {
        val range = DiffRange("42", "")
        val files = (1..DiffStatsPlan.MAX_DIFF_PATHS).map { item("file$it.kt") }

        assertEquals(
            DiffStatsPlan.MAX_DIFF_PATHS,
            DiffStatsPlan.pathsByRange(listOf(range), files, "42")[range]?.size
        )
    }

    @Test
    fun `an open range is diffed against the working copy`() {
        assertEquals(
            listOf("diff", "--git", "--rev", "42", "path:a.kt"),
            DiffStatsPlan.arguments(DiffRange("42", ""), listOf("a.kt"), false)
        )
    }

    @Test
    fun `a closed range passes both revisions`() {
        assertEquals(
            listOf("diff", "--git", "--rev", "7", "--rev", "9"),
            DiffStatsPlan.arguments(DiffRange("7", "9"), emptyList(), false)
        )
    }

    @Test
    fun `end of line changes can be ignored`() {
        assertEquals(
            listOf("diff", "--git", "--rev", "42", DiffStatsPlan.IGNORE_EOL_FLAG, "path:a.kt"),
            DiffStatsPlan.arguments(DiffRange("42", ""), listOf("a.kt"), true)
        )
    }

    @Test
    fun `counted lines land on the file`() {
        val stats = mapOf(DiffRange("42", "") to mapOf("a.kt" to HgDiffStat(3, 1)))
        val counted = DiffStatsPlan.applyStats(listOf(item("a.kt")), stats, "42").single()
        assertEquals(3, counted.added)
        assertEquals(1, counted.removed)
        assertFalse(counted.isUnchanged)
        assertEquals("M", counted.status)
    }

    @Test
    fun `a modified file without stats becomes unchanged`() {
        val counted = DiffStatsPlan.applyStats(listOf(item("a.kt")), emptyMap(), "42").single()
        assertTrue(counted.isUnchanged)
        assertEquals(DiffStatsPlan.UNCHANGED_STATUS, counted.status)
        assertEquals(0, counted.added)
        assertEquals(0, counted.removed)
    }

    @Test
    fun `an added file without stats keeps its status`() {
        val added = HgFileItem(status = "A", path = "a.kt")
        val counted = DiffStatsPlan.applyStats(listOf(added), emptyMap(), "42").single()
        assertFalse(counted.isUnchanged)
        assertEquals("A", counted.status)
    }

    @Test
    fun `a renamed file is counted under the path it came from`() {
        val stats = mapOf(DiffRange("42", "") to mapOf("old.kt" to HgDiffStat(5, 2)))
        val renamed = item("new.kt", copiedFrom = "old.kt")
        val counted = DiffStatsPlan.applyStats(listOf(renamed), stats, "42").single()
        assertEquals(5, counted.added)
        assertEquals(2, counted.removed)
        assertFalse(counted.isUnchanged)
    }

    @Test
    fun `stats of another range are not applied`() {
        val stats = mapOf(DiffRange("7", "9") to mapOf("a.kt" to HgDiffStat(5, 2)))
        val counted = DiffStatsPlan.applyStats(listOf(item("a.kt")), stats, "42").single()
        assertEquals(0, counted.added)
        assertTrue(counted.isUnchanged)
    }

    @Test
    fun `windows separators match the reported path`() {
        val stats = mapOf(DiffRange("42", "") to mapOf("src/a.kt" to HgDiffStat(1, 0)))
        val counted = DiffStatsPlan.applyStats(listOf(item("src\\a.kt")), stats, "42").single()
        assertEquals(1, counted.added)
    }
}
