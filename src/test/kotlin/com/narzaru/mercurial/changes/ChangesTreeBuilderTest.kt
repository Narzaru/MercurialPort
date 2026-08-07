package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
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

    /** Строковое представление дерева — так проще увидеть форму целиком. */
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
    fun `файлы раскладываются по каталогам`() {
        val root = build(listOf(file("src/a.kt"), file("src/b.kt")))

        assertEquals("src/\n  a.kt\n  b.kt\n", render(root))
    }

    @Test
    fun `цепочка каталогов с одним потомком схлопывается`() {
        val root = build(listOf(file("Cad.Toolware.Tests/RayTracing/a.kt")))

        assertEquals("Cad.Toolware.Tests/RayTracing/\n  a.kt\n", render(root))
    }

    @Test
    fun `цепочка не схлопывается там, где ветвится`() {
        val root = build(listOf(file("a/b/one.kt"), file("a/c/two.kt")))

        assertEquals("a/\n  b/\n    one.kt\n  c/\n    two.kt\n", render(root))
    }

    @Test
    fun `каталоги идут перед файлами`() {
        val root = build(listOf(file("zzz/deep.kt"), file("aaa.kt")))

        assertEquals("zzz/\n  deep.kt\naaa.kt\n", render(root))
    }

    @Test
    fun `файлы одного каталога упорядочены по алфавиту без учёта регистра`() {
        val root = build(listOf(file("src/B.kt"), file("src/a.kt"), file("src/C.kt")))

        assertEquals(listOf("a.kt", "B.kt", "C.kt"), root.children.single().children.map { it.toString() })
    }

    @Test
    fun `обратные слэши в пути дают ту же структуру`() {
        val root = build(listOf(file("src\\sub\\a.kt")))

        assertEquals("src/sub/\n  a.kt\n", render(root))
    }

    @Test
    fun `каталог суммирует плюсы и минусы поддерева`() {
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
    fun `каталог считает просмотренные файлы`() {
        val root = build(
            listOf(file("src/a.kt"), file("src/b.kt")),
            reviewed = setOf("src/a.kt")
        )

        val src = root.children.single().dir
        assertEquals(2, src.fileCount)
        assertEquals(1, src.reviewedCount)
    }

    @Test
    fun `TODO-строки кладутся в корень плоским списком`() {
        val root = build(listOf(file("src/deep/a.kt", line = 10), file("src/deep/a.kt", line = 20)))

        assertEquals(2, root.childCount)
        assertTrue(root.children.all { it.userObject is FileNode })
    }

    @Test
    fun `файл в корне репозитория остаётся в корне дерева`() {
        val root = build(listOf(file("README.md")))

        assertEquals("README.md\n", render(root))
    }

    @Test
    fun `пустой список даёт пустое дерево`() {
        assertEquals(0, build(emptyList()).childCount)
    }

    @Test
    fun `filesOf собирает все файлы поддерева`() {
        val root = build(listOf(file("a/x/one.kt"), file("a/y/two.kt"), file("b/three.kt")))

        assertEquals(3, ChangesTreeBuilder.filesOf(root).size)
        assertEquals(2, ChangesTreeBuilder.filesOf(root.children.first()).size)
    }

    @Test
    fun `filesOf от узла-файла возвращает его один`() {
        val root = build(listOf(file("a.kt")))

        assertEquals(listOf("a.kt"), ChangesTreeBuilder.filesOf(root.children.single()).map { it.path })
    }
}
