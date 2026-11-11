package com.narzaru.mercurial.review

import com.narzaru.mercurial.model.HgFileStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewLogTest {

    @Test
    fun `a revision brings its parents, branch and changed files`() {
        val stdout = "@aaa\n^7 bbb 000000000000\n^feature\n^\n" +
            "M src/Kept.kt\nA src/New.kt\nA src/Copy.kt\nR src/Old.kt\n" +
            "* src/New.kt\n= src/Old.kt\n* src/Copy.kt\n= src/Kept.kt\n"

        val revision = ReviewLog.parse(stdout).single()

        assertEquals(7, revision.rev)
        assertEquals("aaa", revision.node)
        assertEquals("bbb", revision.p1)
        assertNull(revision.p2)
        assertEquals("feature", revision.branch)
        assertFalse(revision.isMerge)
        assertEquals(
            listOf("M src/Kept.kt", "→ src/New.kt<src/Old.kt", "A src/Copy.kt<src/Kept.kt"),
            revision.changes.map { "${it.status.letter} ${it.path}" + if (it.copiedFrom.isEmpty()) "" else "<${it.copiedFrom}" }
        )
        assertEquals(setOf("src/Kept.kt", "src/New.kt", "src/Old.kt", "src/Copy.kt"), revision.touchedPaths())
    }

    @Test
    fun `a merge knows the branch of its second parent and leaves its files to status`() {
        val stdout = "@mmm\n^9 aaa ccc\n^feature\n^release\nM src/Resolved.kt\n"

        val revision = ReviewLog.parse(stdout).single()

        assertTrue(revision.isMerge)
        assertEquals(listOf("aaa", "ccc"), revision.parents)
        assertEquals("release", revision.p2Branch)
        assertEquals(emptyList<Any>(), revision.changes)
    }

    @Test
    fun `the root revision has no parents`() {
        val revision = ReviewLog.parse("@rrr\n^0 000000000000 000000000000\n^default\n^\nA a.txt\n").single()

        assertNull(revision.p1)
        assertEquals(listOf(HgFileStatus.ADDED), revision.changes.map { it.status })
    }

    @Test
    fun `several revisions are read in the order hg prints them`() {
        val stdout = "@b\n^2 a 000000000000\n^default\n^\nM x\n@a\n^1 r 000000000000\n^default\n^\nM y\n"

        assertEquals(listOf("b" to listOf("x"), "a" to listOf("y")), ReviewLog.parse(stdout).map { it.node to it.changes.map { c -> c.path } })
    }

    @Test
    fun `the log is asked once for a revset`() {
        assertEquals(listOf("log", "-r", "a+b", "--template", ReviewLog.TEMPLATE), ReviewLog.args("a+b"))
    }
}
