package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Test

class HgOpenBranchesTest {

    @Test
    fun `every listed branch is kept`() {
        assertEquals(listOf("default", "feature one", "фича"), HgOpenBranches.parse("default\nфича\nfeature one\n"))
    }

    @Test
    fun `names keep their inner spaces`() {
        assertEquals(listOf("release 2.0 hotfix"), HgOpenBranches.parse("release 2.0 hotfix\n"))
    }

    @Test
    fun `default comes first and the rest are sorted ignoring case`() {
        val output = "zeta\nBeta\ndefault\nalpha\nЁлка\n"

        assertEquals(listOf("default", "alpha", "Beta", "zeta", "Ёлка"), HgOpenBranches.parse(output))
    }

    @Test
    fun `a branch with several heads is listed once`() {
        assertEquals(listOf("default", "x"), HgOpenBranches.parse("x\ndefault\nx\ndefault\n"))
    }

    @Test
    fun `blank lines are dropped`() {
        assertEquals(listOf("x"), HgOpenBranches.parse("\nx\n  \n\n"))
    }

    @Test
    fun `a repository without default lists only its own branches`() {
        assertEquals(listOf("only"), HgOpenBranches.parse("only\n"))
    }

    @Test
    fun `no output means no branches`() {
        assertEquals(emptyList<String>(), HgOpenBranches.parse(""))
    }

    @Test
    fun `one log call prints the branch of every open head not merged into default`() {
        assertEquals(
            listOf(
                "log",
                "-r",
                "head() and (branch('re:^default\$') or " +
                    "(not closed() and not ancestors(head() and branch('re:^default\$'))))",
                "--template",
                "{branch}\\n"
            ),
            HgOpenBranches.ARGUMENTS
        )
    }

    @Test
    fun `decoded locale output parses like utf-8`() {
        val bytes = "ветка\n".toByteArray(charset("windows-1251"))

        val decoded = HgOutputDecoder.decode(bytes, charset("windows-1251"))

        assertEquals(listOf("ветка"), HgOpenBranches.parse(decoded))
    }
}
