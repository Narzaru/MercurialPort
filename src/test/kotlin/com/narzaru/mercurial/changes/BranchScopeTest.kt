package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Test

class BranchScopeTest {

    @Test
    fun `own files are read line by line as lowercase keys`() {
        val log = "src/Main.kt\r\nsrc/Other.kt\n\n"

        assertEquals(setOf("src/main.kt", "src/other.kt"), BranchScope.ownFiles(log))
    }

    @Test
    fun `windows separators do not make a second key`() {
        val own = BranchScope.ownFiles("src/a.kt\n")
        val contributed = BranchScope.contributedFiles(listOf("src\\a.kt"))

        assertEquals(own, BranchScope.narrow(own, contributed))
    }

    @Test
    fun `narrow keeps only files the branch left content in`() {
        val own = BranchScope.ownFiles("src/mine.kt\nsrc/mergedIn.kt\n")
        val contributed = BranchScope.contributedFiles(listOf("src/mine.kt"))

        assertEquals(setOf("src/mine.kt"), BranchScope.narrow(own, contributed))
    }

    @Test
    fun `nothing survives when the diff against the merge base is empty`() {
        val own = BranchScope.ownFiles("src/mine.kt\n")

        assertEquals(emptySet<String>(), BranchScope.narrow(own, emptySet()))
    }
}
