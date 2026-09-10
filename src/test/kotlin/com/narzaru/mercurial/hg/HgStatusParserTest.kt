package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HgStatusParserTest {

    @Test
    fun `parses the status letter and the path`() {
        val items = HgStatusParser.parse("M src/main.kt\nA src/new.kt\n")

        assertEquals(listOf("M", "A"), items.map { it.status })
        assertEquals(listOf("src/main.kt", "src/new.kt"), items.map { it.path })
    }

    @Test
    fun `CRLF is understood and blank lines are skipped`() {
        val items = HgStatusParser.parse("M a.kt\r\n\r\nR b.kt\r\n")

        assertEquals(listOf("a.kt", "b.kt"), items.map { it.path })
    }

    @Test
    fun `a one letter file name is a valid status line`() {
        val items = HgStatusParser.parse("M a\n")

        assertEquals("M", items.single().status)
        assertEquals("a", items.single().path)
    }

    @Test
    fun `a line that is not shaped like a status is ignored`() {
        val items = HgStatusParser.parse("abort\nM a.kt\n??\n")

        assertEquals(listOf("a.kt"), items.map { it.path })
    }

    @Test
    fun `spaces inside the path are kept`() {
        val items = HgStatusParser.parse("M dir with space/file name.kt")

        assertEquals("dir with space/file name.kt", items.single().path)
    }

    @Test
    fun `files deleted outside hg get the missing status`() {
        val items = HgStatusParser.parse("! gone.kt")

        assertEquals("!", items.single().status)
    }

    @Test
    fun `empty output gives an empty list`() {
        assertTrue(HgStatusParser.parse("").isEmpty())
        assertTrue(HgStatusParser.parse("\n\n").isEmpty())
    }

    @Test
    fun `an indented line is the copy source of the previous file`() {
        val items = HgStatusParser.parse("A src/new.kt\n  src/old.kt\nM other.kt\n")

        assertEquals(listOf("src/new.kt", "other.kt"), items.map { it.path })
        assertEquals("src/old.kt", items[0].copiedFrom)
        assertEquals("", items[1].copiedFrom)
    }

    @Test
    fun `a rename is folded into a single row`() {
        val items = HgStatusParser.foldRenames(
            HgStatusParser.parse("A src/new.kt\n  src/old.kt\nR src/old.kt\n")
        )

        val item = items.single()
        assertEquals(HgStatusParser.RENAMED_STATUS, item.status)
        assertEquals("src/new.kt", item.path)
        assertEquals("src/old.kt", item.copiedFrom)
    }

    @Test
    fun `two files copied from the same removed source both survive`() {
        val items = HgStatusParser.foldRenames(
            HgStatusParser.parse("A src/new1.kt\n  src/old.kt\nA src/new2.kt\n  src/old.kt\nR src/old.kt\n")
        )

        assertEquals(listOf("src/new1.kt", "src/new2.kt"), items.map { it.path })
        assertEquals(
            listOf(HgStatusParser.RENAMED_STATUS, HgStatusParser.RENAMED_STATUS),
            items.map { it.status }
        )
    }

    @Test
    fun `a copy stays an addition and its source stays in place`() {
        val items = HgStatusParser.foldRenames(
            HgStatusParser.parse("A src/copy.kt\n  src/orig.kt\nM src/orig.kt\n")
        )

        assertEquals(listOf("A", "M"), items.map { it.status })
        assertEquals("src/orig.kt", items[0].copiedFrom)
    }

    @Test
    fun `a removal without a matching addition stays in the list`() {
        val items = HgStatusParser.foldRenames(HgStatusParser.parse("R src/gone.kt\nA src/new.kt\n"))

        assertEquals(listOf("R", "A"), items.map { it.status })
    }

    @Test
    fun `the untracked flag is added only on demand`() {
        assertEquals("-mard", HgStatusParser.statusFlags(includeUntracked = false))
        assertEquals("-mardu", HgStatusParser.statusFlags(includeUntracked = true))
    }
}
