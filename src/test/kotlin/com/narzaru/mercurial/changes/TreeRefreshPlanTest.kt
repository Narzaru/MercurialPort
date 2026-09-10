package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.tree.DefaultMutableTreeNode

class TreeRefreshPlanTest {

    private fun file(
        path: String,
        status: String = "M",
        added: Int = 0,
        removed: Int = 0,
        line: Int = 0
    ) = HgFileItem(status = status, path = path, added = added, removed = removed, lineNumber = line)

    @Test
    fun `the same set of files needs no rebuild`() {
        val shown = listOf(file("src/a.kt"), file("src/b.kt"))
        val next = listOf(file("src/a.kt"), file("src/b.kt"))

        assertTrue(TreeRefreshPlan.keepsStructure(shown, next))
    }

    @Test
    fun `added and removed lines that appeared need no rebuild`() {
        val shown = listOf(file("src/a.kt"))
        val next = listOf(file("src/a.kt", added = 7, removed = 2))

        assertTrue(TreeRefreshPlan.keepsStructure(shown, next))
    }

    @Test
    fun `a changed status needs no rebuild`() {
        val shown = listOf(file("src/a.kt"))
        val next = listOf(file("src/a.kt", status = DiffStatsPlan.UNCHANGED_STATUS))

        assertTrue(TreeRefreshPlan.keepsStructure(shown, next))
    }

    @Test
    fun `a file that is gone needs a rebuild`() {
        val shown = listOf(file("src/a.kt"), file("src/b.kt"))
        val next = listOf(file("src/a.kt"))

        assertFalse(TreeRefreshPlan.keepsStructure(shown, next))
    }

    @Test
    fun `an added file needs a rebuild`() {
        val shown = listOf(file("src/a.kt"))
        val next = listOf(file("src/a.kt"), file("src/b.kt"))

        assertFalse(TreeRefreshPlan.keepsStructure(shown, next))
    }

    @Test
    fun `a different order of files needs a rebuild`() {
        val shown = listOf(file("src/a.kt"), file("src/b.kt"))
        val next = listOf(file("src/b.kt"), file("src/a.kt"))

        assertFalse(TreeRefreshPlan.keepsStructure(shown, next))
    }

    @Test
    fun `replacing one file with another needs a rebuild`() {
        val shown = listOf(file("src/a.kt"))
        val next = listOf(file("src/c.kt"))

        assertFalse(TreeRefreshPlan.keepsStructure(shown, next))
    }

    @Test
    fun `two empty lists match`() {
        assertTrue(TreeRefreshPlan.keepsStructure(emptyList(), emptyList()))
    }

    @Test
    fun `slashes and path case do not count as a change`() {
        val shown = listOf(file("src\\A.kt"))
        val next = listOf(file("src/a.kt"))

        assertTrue(TreeRefreshPlan.keepsStructure(shown, next))
    }

    @Test
    fun `TODO lines of one file differ by line number`() {
        val shown = listOf(file("a.kt", line = 10))
        val next = listOf(file("a.kt", line = 20))

        assertFalse(TreeRefreshPlan.keepsStructure(shown, next))
    }

    @Test
    fun `a node key holds the path and the line number`() {
        assertEquals("src/a.kt#0", TreeRefreshPlan.nodeKey(file("src/A.kt")))
        assertEquals("src/a.kt#12", TreeRefreshPlan.nodeKey(file("src/A.kt", line = 12)))
    }

    private fun tree(vararg paths: String) =
        ChangesTreeBuilder.build(paths.map { file(it) }) { false }

    private fun dirs(node: DefaultMutableTreeNode): Map<String, DefaultMutableTreeNode> {
        val found = HashMap<String, DefaultMutableTreeNode>()
        for (index in 0 until node.childCount) {
            val child = node.getChildAt(index) as DefaultMutableTreeNode
            if (child.userObject !is DirNode) continue
            found[TreeRefreshPlan.dirPath(child)] = child
            found.putAll(dirs(child))
        }
        return found
    }

    @Test
    fun `a directory path is built from the names of its ancestors`() {
        val found = dirs(tree("a/b/one.kt", "a/c/two.kt"))

        assertEquals(setOf("a", "a/b", "a/c"), found.keys)
    }

    @Test
    fun `a collapsed chain of directories gives a single path`() {
        val found = dirs(tree("a/b/c/one.kt"))

        assertEquals(setOf("a/b/c"), found.keys)
    }

    @Test
    fun `a collapsed node stays collapsed`() {
        assertFalse(TreeRefreshPlan.expandsAfterRebuild("a/b", setOf("a/b")))
    }

    @Test
    fun `an expanded node stays expanded`() {
        assertTrue(TreeRefreshPlan.expandsAfterRebuild("a/b", setOf("a/c")))
    }

    @Test
    fun `a new node is expanded`() {
        assertTrue(TreeRefreshPlan.expandsAfterRebuild("a/new", setOf("a/b", "a/c")))
    }

    @Test
    fun `when there was no tree everything is expanded`() {
        assertTrue(TreeRefreshPlan.expandsAfterRebuild("a/b", null))
    }
}
