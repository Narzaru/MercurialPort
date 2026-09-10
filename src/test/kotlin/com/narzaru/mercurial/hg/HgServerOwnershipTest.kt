package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Test

class HgServerOwnershipTest {

    private fun unused(roots: List<String>, open: List<String>): List<String> =
        HgServerOwnership.unusedRoots(roots, open, '\\', true)

    @Test
    fun `a repository above the project that stays open is kept`() {
        assertEquals(
            emptyList<String>(),
            unused(listOf("D:\\repo"), listOf("D:\\repo\\src\\App"))
        )
    }

    @Test
    fun `a repository below the project that stays open is kept`() {
        assertEquals(
            emptyList<String>(),
            unused(listOf("D:\\work\\solution\\lib"), listOf("D:\\work\\solution"))
        )
    }

    @Test
    fun `a repository the project itself is kept`() {
        assertEquals(emptyList<String>(), unused(listOf("D:\\repo"), listOf("D:\\repo")))
    }

    @Test
    fun `a repository no open project reaches is released`() {
        assertEquals(
            listOf("D:\\foreign"),
            unused(listOf("D:\\foreign", "D:\\repo"), listOf("D:\\repo\\src"))
        )
    }

    @Test
    fun `every repository is released once the last project closes`() {
        assertEquals(
            listOf("D:\\repo", "D:\\foreign"),
            unused(listOf("D:\\repo", "D:\\foreign"), emptyList())
        )
    }

    @Test
    fun `a sibling directory with a shared prefix is not taken for a parent`() {
        assertEquals(
            listOf("D:\\repository"),
            unused(listOf("D:\\repository"), listOf("D:\\repo"))
        )
    }

    @Test
    fun `case is ignored where the file system ignores it`() {
        assertEquals(
            emptyList<String>(),
            unused(listOf("D:\\Repo"), listOf("d:\\repo\\src"))
        )
        assertEquals(
            listOf("D:\\Repo"),
            HgServerOwnership.unusedRoots(listOf("D:\\Repo"), listOf("d:\\repo\\src"), '\\', false)
        )
    }
}
