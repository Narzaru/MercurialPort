package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class HgPathsTest {

    @Test
    fun `backslashes are turned into forward slashes`() {
        assertEquals("src/main/a.kt", HgPaths.normalize("src\\main\\a.kt"))
    }

    @Test
    fun `key is normalized and lowercased`() {
        assertEquals("src/a.kt", HgPaths.key("SRC\\A.kt"))
    }

    @Test
    fun `path inside the root becomes relative`() {
        assertEquals("src/a.kt", HgPaths.relativize("/repo/src/a.kt", "/repo"))
    }

    @Test
    fun `root case does not matter`() {
        assertEquals("src\\a.kt", HgPaths.relativize("D:\\Repo\\src\\a.kt", "d:\\repo"))
    }

    @Test
    fun `path outside the root is not relative`() {
        assertNull(HgPaths.relativize("/other/a.kt", "/repo"))
    }

    @Test
    fun `sibling directory sharing the root prefix is not relative`() {
        assertNull(HgPaths.relativize("/repo2/src/a.kt", "/repo"))
        assertNull(HgPaths.relativize("D:\\repo-old\\src\\a.kt", "D:\\repo"))
    }

    @Test
    fun `path equal to the root is relativized to nothing`() {
        assertEquals("", HgPaths.relativize("/repo", "/repo"))
        assertEquals("", HgPaths.relativize("D:\\repo", "D:\\repo"))
    }

    @Test
    fun `trailing separator in the root is ignored`() {
        assertEquals("src/a.kt", HgPaths.relativize("/repo/src/a.kt", "/repo/"))
        assertEquals("src\\a.kt", HgPaths.relativize("D:\\repo\\src\\a.kt", "D:\\repo\\"))
    }

    @Test
    fun `a path inside the repository becomes a key`() {
        val root = File("/repo")

        assertEquals("src/a.kt", HgPaths.keyRelativeTo(File(root, "src/a.kt").absolutePath, root))
    }

    @Test
    fun `a key ignores the case of both the root and the path`() {
        val root = File("/Repo")
        val path = File("/repo/SRC/A.kt").absolutePath

        assertEquals("src/a.kt", HgPaths.keyRelativeTo(path, root))
    }

    @Test
    fun `a key uses forward slashes whichever separator the path has`() {
        val root = File("/repo")
        val rootPath = root.absolutePath

        assertEquals(
            HgPaths.keyRelativeTo("$rootPath/src/a.kt", root),
            HgPaths.keyRelativeTo("$rootPath\\src\\a.kt", root)
        )
    }

    @Test
    fun `a path outside the repository has no key`() {
        assertNull(HgPaths.keyRelativeTo(File("/other/a.kt").absolutePath, File("/repo")))
    }

    @Test
    fun `a file outside the root keeps its name only`() {
        val relative = HgPaths.relativize(File("/other/a.kt"), File("/repo"))

        assertEquals("a.kt", relative)
    }
}
