package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.tree.DefaultMutableTreeNode

class ChangesTreeBuilderTest {

    private fun file(path: String, added: Int = 0, removed: Int = 0, line: Int = 0) =
        HgFileItem(status = "M", path = path, added = added, removed = removed, lineNumber = line)

    private fun build(items: List<HgFileItem>, reviewed: Set<String> = emptySet()) =
        ChangesTreeBuilder.build(items) { it.path in reviewed }

    private val DefaultMutableTreeNode.children: List<DefaultMutableTreeNode>
        get() = (0 until childCount).map { getChildAt(it) as DefaultMutableTreeNode }

    private val DefaultMutableTreeNode.dir: DirNode get() = userObject as DirNode

    private fun render(node: DefaultMutableTreeNode, depth: Int = 0): String = buildString {
        for (child in node.children) {
            append("  ".repeat(depth))
            when (val payload = child.userObject) {
                is DirNode -> append(payload.name).append("/\n")
                is FileNode -> append(payload.item.name).append('\n')
            }
            append(render(child, depth + 1))
        }
    }

    @Test
    fun `files are laid out by directory`() {
        val root = build(listOf(file("src/a.kt"), file("src/b.kt")))

        assertEquals("src/\n  a.kt\n  b.kt\n", render(root))
    }

    @Test
    fun `a chain of single child directories is collapsed`() {
        val root = build(listOf(file("Cad.Toolware.Tests/RayTracing/a.kt")))

        assertEquals("Cad.Toolware.Tests/RayTracing/\n  a.kt\n", render(root))
    }

    @Test
    fun `a chain is not collapsed where it branches`() {
        val root = build(listOf(file("a/b/one.kt"), file("a/c/two.kt")))

        assertEquals("a/\n  b/\n    one.kt\n  c/\n    two.kt\n", render(root))
    }

    @Test
    fun `directories come before files`() {
        val root = build(listOf(file("zzz/deep.kt"), file("aaa.kt")))

        assertEquals("zzz/\n  deep.kt\naaa.kt\n", render(root))
    }

    @Test
    fun `files of one directory are sorted alphabetically ignoring case`() {
        val root = build(listOf(file("src/B.kt"), file("src/a.kt"), file("src/C.kt")))

        assertEquals(listOf("a.kt", "B.kt", "C.kt"), root.children.single().children.map { it.toString() })
    }

    @Test
    fun `backslashes in a path give the same structure`() {
        val root = build(listOf(file("src\\sub\\a.kt")))

        assertEquals("src/sub/\n  a.kt\n", render(root))
    }

    @Test
    fun `a directory sums up the added and removed lines of its subtree`() {
        val root = build(
            listOf(
                file("a/x/one.kt", added = 3, removed = 1),
                file("a/y/two.kt", added = 4, removed = 2)
            )
        )

        val a = root.children.single().dir
        assertEquals(2, a.fileCount)
        assertEquals(7, a.added)
        assertEquals(3, a.removed)
    }

    @Test
    fun `a directory counts the reviewed files`() {
        val root = build(
            listOf(file("src/a.kt"), file("src/b.kt")),
            reviewed = setOf("src/a.kt")
        )

        val src = root.children.single().dir
        assertEquals(2, src.fileCount)
        assertEquals(1, src.reviewedCount)
    }

    @Test
    fun `TODO lines go to the root as a flat list`() {
        val root = build(listOf(file("src/deep/a.kt", line = 10), file("src/deep/a.kt", line = 20)))

        assertEquals(2, root.childCount)
        assertTrue(root.children.all { it.userObject is FileNode })
    }

    @Test
    fun `a file in the repository root stays in the tree root`() {
        val root = build(listOf(file("README.md")))

        assertEquals("README.md\n", render(root))
    }

    @Test
    fun `an empty list gives an empty tree`() {
        assertEquals(0, build(emptyList()).childCount)
    }

    @Test
    fun `filesOf collects every file of a subtree`() {
        val root = build(listOf(file("a/x/one.kt"), file("a/y/two.kt"), file("b/three.kt")))

        assertEquals(3, ChangesTreeBuilder.filesOf(root).size)
        assertEquals(2, ChangesTreeBuilder.filesOf(root.children.first()).size)
    }

    @Test
    fun `refresh carries the added and removed lines into a built tree`() {
        val root = build(listOf(file("a/x/one.kt"), file("a/y/two.kt")))

        ChangesTreeBuilder.refresh(
            root,
            listOf(file("a/x/one.kt", added = 3, removed = 1), file("a/y/two.kt", added = 4, removed = 2))
        ) { false }

        val a = root.children.single().dir
        assertEquals(7, a.added)
        assertEquals(3, a.removed)
        assertEquals(2, a.fileCount)
    }

    @Test
    fun `refresh keeps the tree nodes`() {
        val root = build(listOf(file("src/a.kt")))
        val before = root.children.single().children.single()

        ChangesTreeBuilder.refresh(root, listOf(file("src/a.kt", added = 5))) { false }

        val after = root.children.single().children.single()
        assertSame(before, after)
        assertEquals(5, (after.userObject as FileNode).item.added)
    }

    @Test
    fun `refresh recounts the reviewed files`() {
        val root = build(listOf(file("src/a.kt"), file("src/b.kt")))
        assertEquals(0, root.children.single().dir.reviewedCount)

        ChangesTreeBuilder.refresh(root, listOf(file("src/a.kt"), file("src/b.kt"))) { it.path == "src/a.kt" }

        assertEquals(1, root.children.single().dir.reviewedCount)
    }

    @Test
    fun `refresh leaves alone the files missing from the list`() {
        val root = build(listOf(file("src/a.kt", added = 9)))

        ChangesTreeBuilder.refresh(root, emptyList()) { false }

        val item = (root.children.single().children.single().userObject as FileNode).item
        assertEquals(9, item.added)
    }

    @Test
    fun `filesOf of a file node returns that file alone`() {
        val root = build(listOf(file("a.kt")))

        assertEquals(listOf("a.kt"), ChangesTreeBuilder.filesOf(root.children.single()).map { it.path })
    }
}
