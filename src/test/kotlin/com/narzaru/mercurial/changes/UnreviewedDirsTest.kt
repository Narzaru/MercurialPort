package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreviewedDirsTest {

    private fun file(path: String, unchanged: Boolean = false) =
        HgFileItem(status = "M", path = path, isUnchanged = unchanged)

    private fun of(files: List<HgFileItem>, reviewed: Set<String> = emptySet()) =
        UnreviewedDirs.of(files) { it.path in reviewed }

    @Test
    fun `a directory with every file reviewed is reviewed`() {
        val dirs = of(listOf(file("src/a.kt"), file("src/b.kt")), reviewed = setOf("src/a.kt", "src/b.kt"))

        assertTrue(dirs.allReviewed("src"))
    }

    @Test
    fun `one unreviewed file keeps its directory and every ancestor unreviewed`() {
        val dirs = of(listOf(file("a/b/c/x.kt"), file("a/y.kt")), reviewed = setOf("a/y.kt"))

        assertFalse(dirs.allReviewed("a/b/c"))
        assertFalse(dirs.allReviewed("a/b"))
        assertFalse(dirs.allReviewed("a"))
    }

    @Test
    fun `an unreviewed file in a sibling directory does not affect the other one`() {
        val dirs = of(listOf(file("a/b/x.kt"), file("a/c/y.kt")), reviewed = setOf("a/b/x.kt"))

        assertTrue(dirs.allReviewed("a/b"))
        assertFalse(dirs.allReviewed("a/c"))
    }

    @Test
    fun `an unreviewed file next to a deeper directory does not affect that directory`() {
        val dirs = of(listOf(file("a/b/c/x.kt"), file("a/b/y.kt")), reviewed = setOf("a/b/c/x.kt"))

        assertTrue(dirs.allReviewed("a/b/c"))
        assertFalse(dirs.allReviewed("a/b"))
    }

    @Test
    fun `an unchanged file counts as reviewed`() {
        val dirs = of(listOf(file("src/a.kt"), file("src/same.kt", unchanged = true)), reviewed = setOf("src/a.kt"))

        assertTrue(dirs.allReviewed("src"))
    }

    @Test
    fun `a directory name prefix does not match another directory`() {
        val dirs = of(listOf(file("srcgen/a.kt")))

        assertTrue(dirs.allReviewed("src"))
        assertFalse(dirs.allReviewed("srcgen"))
    }

    @Test
    fun `paths are compared ignoring case and separators`() {
        val dirs = of(listOf(file("Src\\Sub\\a.kt")))

        assertFalse(dirs.allReviewed("src/sub"))
        assertFalse(dirs.allReviewed("SRC"))
    }
}
