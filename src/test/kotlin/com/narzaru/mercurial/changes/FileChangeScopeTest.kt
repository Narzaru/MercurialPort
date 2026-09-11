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
    fun `a file outside the list is reported for a check`() {
        val reaction = FileChangeScope.of(root, listOf("C:/repo/src/other.kt"), files, setOf("src/a.kt"))

        assertEquals(listOf("src/other.kt"), reaction.unlistedKeys)
        assertTrue(reaction.changedKeys.isEmpty())
        assertTrue(reaction.diffKeys.isEmpty())
        assertFalse(reaction.rescanTodos)
    }

    @Test
    fun `a listed file needs no check`() {
        val reaction = FileChangeScope.of(root, listOf("C:/repo/src/a.kt"), files, emptySet())

        assertEquals(listOf("src/a.kt"), reaction.changedKeys)
        assertTrue(reaction.unlistedKeys.isEmpty())
        assertFalse(reaction.repoStateTouched)
    }

    @Test
    fun `a write into the working directory state asks for a check`() {
        for (name in listOf("dirstate", "dirstate.a1b2c3", "branch", "bookmarks", "bookmarks.current")) {
            val reaction = FileChangeScope.of(root, listOf("C:/repo/.hg/$name"), files, emptySet())

            assertTrue(name, reaction.repoStateTouched)
            assertTrue(name, reaction.changedKeys.isEmpty())
            assertTrue(name, reaction.unlistedKeys.isEmpty())
        }
    }

    @Test
    fun `edited ignore rules are told apart`() {
        for (path in listOf("C:/repo/.hgignore", "C:/repo/sub/.hgignore")) {
            val reaction = FileChangeScope.of(root, listOf(path), files, emptySet())

            assertTrue(path, reaction.ignoreRulesChanged)
        }
        assertFalse(FileChangeScope.of(root, listOf("C:/repo/src/a.kt"), files, emptySet()).ignoreRulesChanged)
    }

    @Test
    fun `a tracked ignore file is both a change and a rules edit`() {
        val listed = files + file(".hgignore")
        val reaction = FileChangeScope.of(root, listOf("C:/repo/.hgignore"), listed, emptySet())

        assertEquals(listOf(".hgignore"), reaction.changedKeys)
        assertTrue(reaction.ignoreRulesChanged)
    }

    @Test
    fun `a noisy directory is noise on its own`() {
        for (path in listOf("C:/repo/build", "C:/repo/module/out", "C:/repo/node_modules")) {
            val reaction = FileChangeScope.of(root, listOf(path), files, emptySet())

            assertTrue(path, reaction.isEmpty)
        }
    }

    @Test
    fun `the housekeeping mercurial does on its own is ignored`() {
        val paths = listOf(
            "C:/repo/.hg/cache/branch2",
            "C:/repo/.hg/wcache/checklink",
            "C:/repo/.hg/store/lock",
            "C:/repo/.hg/wlock",
            "C:/repo/.hg/journal.dirstate",
            "C:/repo/.hg/undo.dirstate",
            "C:/repo/.hg"
        )
        for (path in paths) {
            val reaction = FileChangeScope.of(root, listOf(path), files, emptySet())

            assertTrue(path, reaction.isEmpty)
        }
    }

    @Test
    fun `build output and editor leftovers are ignored`() {
        val paths = listOf(
            "C:/repo/build/classes/A.class",
            "C:/repo/module/build/libs/a.jar",
            "C:/repo/.gradle/7.0/fileHashes.bin",
            "C:/repo/.idea/workspace.xml",
            "C:/repo/node_modules/left-pad/index.js",
            "C:/repo/src/a.kt___jb_tmp___"
        )
        for (path in paths) {
            val reaction = FileChangeScope.of(root, listOf(path), files, emptySet())

            assertTrue(path, reaction.isEmpty)
        }
    }

    @Test
    fun `an unlisted file is told apart from a listed one in the same batch`() {
        val reaction = FileChangeScope.of(
            root, listOf("C:/repo/src/a.kt", "C:/repo/src/fresh.kt"), files, setOf("src/a.kt")
        )

        assertEquals(listOf("src/a.kt"), reaction.changedKeys)
        assertEquals(listOf("src/fresh.kt"), reaction.unlistedKeys)
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
        assertEquals(listOf("src/gone.kt"), reaction.unlistedKeys)
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
    fun `a rename reacts on the old path and checks the new one`() {
        val reaction = FileChangeScope.of(
            root, listOf("C:/repo/src/a.kt", "C:/repo/src/renamed.kt"), files, setOf("src/a.kt")
        )

        assertEquals(listOf("src/a.kt"), reaction.changedKeys)
        assertEquals(listOf("src/a.kt"), reaction.diffKeys)
        assertEquals(listOf("src/renamed.kt"), reaction.unlistedKeys)
    }

    @Test
    fun `the repository root itself is not a changed file`() {
        val reaction = FileChangeScope.of(root, listOf("C:/repo", "C:/repo/"), files, emptySet())

        assertTrue(reaction.isEmpty)
    }

    @Test
    fun `an edit made while the list is empty is still checked`() {
        val reaction = FileChangeScope.of(root, listOf("C:/repo/src/a.kt"), emptyList(), setOf("src/a.kt"))

        assertEquals(listOf("src/a.kt"), reaction.unlistedKeys)
        assertTrue(reaction.changedKeys.isEmpty())
    }

    @Test
    fun `nothing changed means no reaction`() {
        assertTrue(FileChangeScope.of(root, emptyList(), files, emptySet()).isEmpty)
    }

    @Test
    fun `a path differing in case matches the listed file`() {
        val reaction = FileChangeScope.of(root, listOf("C:/Repo/Src/A.kt"), files, setOf("src/a.kt"))

        assertEquals(listOf("src/a.kt"), reaction.diffKeys)
    }
}
