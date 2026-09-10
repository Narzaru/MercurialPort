package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileChangeScopeTest {

    private val root = "C:/repo"

    private fun file(path: String, status: String = "M") = HgFileItem(status = status, path = path)

    private val files = listOf(file("src/a.kt"), file("src/b.kt"), file("docs/c.md"))

    @Test
    fun `a changed file of the list is reacted to`() {
        val reaction = FileChangeScope.of(root, listOf("C:/repo/src/a.kt"), files, emptySet())

        assertEquals(listOf("src/a.kt"), reaction.changedKeys)
        assertTrue(reaction.rescanTodos)
        assertTrue(reaction.diffKeys.isEmpty())
    }

    @Test
    fun `a file outside the list is left alone`() {
        val reaction = FileChangeScope.of(root, listOf("C:/repo/src/other.kt"), files, setOf("src/a.kt"))

        assertTrue(reaction.isEmpty)
        assertFalse(reaction.rescanTodos)
    }

    @Test
    fun `a file outside the repository is left alone`() {
        val reaction = FileChangeScope.of(root, listOf("C:/elsewhere/src/a.kt"), files, setOf("src/a.kt"))

        assertTrue(reaction.isEmpty)
    }

    @Test
    fun `an open diff of a changed file is redrawn`() {
        val reaction = FileChangeScope.of(
            root, listOf("C:/repo/src/a.kt"), files, setOf("src/a.kt", "docs/c.md")
        )

        assertEquals(listOf("src/a.kt"), reaction.diffKeys)
    }

    @Test
    fun `a changed file with no diff open redraws nothing`() {
        val reaction = FileChangeScope.of(root, listOf("C:/repo/src/b.kt"), files, setOf("src/a.kt"))

        assertEquals(listOf("src/b.kt"), reaction.changedKeys)
        assertTrue(reaction.diffKeys.isEmpty())
    }

    @Test
    fun `several files of one event are collected`() {
        val reaction = FileChangeScope.of(
            root,
            listOf("C:/repo/src/a.kt", "C:/repo/docs/c.md", "C:/repo/src/gone.kt"),
            files,
            setOf("src/a.kt", "docs/c.md")
        )

        assertEquals(listOf("src/a.kt", "docs/c.md"), reaction.changedKeys)
        assertEquals(listOf("src/a.kt", "docs/c.md"), reaction.diffKeys)
    }

    @Test
    fun `the same file reported twice is reacted to once`() {
        val reaction = FileChangeScope.of(
            root, listOf("C:/repo/src/a.kt", "C:\\repo\\src\\a.kt"), files, setOf("src/a.kt")
        )

        assertEquals(listOf("src/a.kt"), reaction.changedKeys)
        assertEquals(listOf("src/a.kt"), reaction.diffKeys)
    }

    @Test
    fun `a deleted file of the list is reacted to`() {
        val reaction = FileChangeScope.of(root, listOf("C:/repo/docs/c.md"), files, setOf("docs/c.md"))

        assertEquals(listOf("docs/c.md"), reaction.diffKeys)
    }

    @Test
    fun `a rename reacts on the old path and ignores the new one`() {
        val reaction = FileChangeScope.of(
            root, listOf("C:/repo/src/a.kt", "C:/repo/src/renamed.kt"), files, setOf("src/a.kt")
        )

        assertEquals(listOf("src/a.kt"), reaction.changedKeys)
        assertEquals(listOf("src/a.kt"), reaction.diffKeys)
    }

    @Test
    fun `the repository root itself is not a changed file`() {
        val reaction = FileChangeScope.of(root, listOf("C:/repo", "C:/repo/"), files, emptySet())

        assertTrue(reaction.isEmpty)
    }

    @Test
    fun `an empty list of files needs no reaction`() {
        val reaction = FileChangeScope.of(root, listOf("C:/repo/src/a.kt"), emptyList(), setOf("src/a.kt"))

        assertTrue(reaction.isEmpty)
    }

    @Test
    fun `a path differing in case matches the listed file`() {
        val reaction = FileChangeScope.of(root, listOf("C:/Repo/Src/A.kt"), files, setOf("src/a.kt"))

        assertEquals(listOf("src/a.kt"), reaction.diffKeys)
    }
}
