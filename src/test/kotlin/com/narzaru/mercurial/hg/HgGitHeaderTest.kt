package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HgGitHeaderTest {

    @Test
    fun `a plain header names the same file on both sides`() {
        assertEquals(
            HgGitHeader.Names("src/A.cs", "src/A.cs"),
            HgGitHeader.names("diff --git a/src/A.cs b/src/A.cs")
        )
    }

    @Test
    fun `a path containing a b-slash is split in the middle`() {
        assertEquals(
            HgGitHeader.Names("Plan b/x.kt", "Plan b/x.kt"),
            HgGitHeader.names("diff --git a/Plan b/x.kt b/Plan b/x.kt")
        )
    }

    @Test
    fun `a header with different names is split at the separator`() {
        assertEquals(
            HgGitHeader.Names("old.txt", "new.txt"),
            HgGitHeader.names("diff --git a/old.txt b/new.txt")
        )
    }

    @Test
    fun `a header without a second name is not read`() {
        assertNull(HgGitHeader.names("diff --git a/x.txt"))
    }

    @Test
    fun `rename and copy lines give the source and the target`() {
        assertEquals("Plan b/old.kt", HgGitHeader.source("rename from Plan b/old.kt"))
        assertEquals("a.txt", HgGitHeader.source("copy from a.txt"))
        assertEquals("Plan b/new.kt", HgGitHeader.target("rename to Plan b/new.kt"))
        assertEquals("b.txt", HgGitHeader.target("copy to b.txt"))
        assertNull(HgGitHeader.source("copy to b.txt"))
        assertNull(HgGitHeader.target("--- a/b.txt"))
    }
}
