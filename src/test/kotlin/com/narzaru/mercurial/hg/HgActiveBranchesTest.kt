package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Test

class HgActiveBranchesTest {

    @Test
    fun `only active branches are listed`() {
        val output = "a default\na фича\ni feature one\n"

        assertEquals(listOf("default", "фича"), HgActiveBranches.parse(output))
    }

    @Test
    fun `names keep their inner spaces`() {
        assertEquals(listOf("release 2.0 hotfix"), HgActiveBranches.parse("a release 2.0 hotfix\n"))
    }

    @Test
    fun `default comes first and the rest are sorted ignoring case`() {
        val output = "a zeta\na Beta\na default\na alpha\na Ёлка\n"

        assertEquals(listOf("default", "alpha", "Beta", "zeta", "Ёлка"), HgActiveBranches.parse(output))
    }

    @Test
    fun `blank lines and duplicates are dropped`() {
        assertEquals(listOf("default", "x"), HgActiveBranches.parse("\na x\na \na default\na x\n\n"))
    }

    @Test
    fun `no output means no branches`() {
        assertEquals(emptyList<String>(), HgActiveBranches.parse(""))
    }

    @Test
    fun `the template marks every branch as active or inactive`() {
        assertEquals(listOf("branches", "--template", "{if(active,'a','i')} {branch}\\n"), HgActiveBranches.ARGUMENTS)
    }

    @Test
    fun `decoded locale output parses like utf-8`() {
        val bytes = "a ветка\n".toByteArray(charset("windows-1251"))

        val decoded = HgOutputDecoder.decode(bytes, charset("windows-1251"))

        assertEquals(listOf("ветка"), HgActiveBranches.parse(decoded))
    }
}
