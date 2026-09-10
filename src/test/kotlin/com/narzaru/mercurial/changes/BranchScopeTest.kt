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
        assertEquals(BranchScope.ownFiles("src/a.kt\n"), BranchScope.ownFiles("src\\a.kt\n"))
    }

    @Test
    fun `empty output gives an empty set`() {
        assertEquals(emptySet<String>(), BranchScope.ownFiles("\n \n"))
    }
}
