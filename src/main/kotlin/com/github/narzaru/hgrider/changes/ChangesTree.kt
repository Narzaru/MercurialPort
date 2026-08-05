package com.github.narzaru.hgrider.changes

import com.github.narzaru.hgrider.model.HgFileItem
import javax.swing.tree.DefaultMutableTreeNode

/** Каталог в дереве изменений. `name` может покрывать несколько уровней: `Cad.Toolware.Tests/RayTracing`. */
class DirNode(var name: String) {
    var fileCount: Int = 0
    var added: Int = 0
    var removed: Int = 0
    var reviewedCount: Int = 0

    override fun toString() = name
}

/** Файл в дереве изменений. */
class FileNode(val item: HgFileItem) {
    override fun toString() = item.name
}

/**
 * Строит дерево каталогов по списку файлов в стиле Upsource: цепочки каталогов
 * с единственным потомком схлопываются в одну строку (`Cad.Toolware.Tests/RayTracing`),
 * файлы каждого каталога идут по алфавиту после подкаталогов.
 */
object ChangesTreeBuilder {

    fun build(items: List<HgFileItem>, isReviewed: (HgFileItem) -> Boolean): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode(DirNode(""))
        val dirNodes = HashMap<String, DefaultMutableTreeNode>()
        dirNodes[""] = root

        for (item in items.sortedBy { it.path.lowercase() }) {
            val normalized = item.path.replace('\\', '/')
            val dirPath = normalized.substringBeforeLast('/', "")
            val parent = if (item.isTodoItem) root else dirNode(dirPath, dirNodes, root)
            parent.add(DefaultMutableTreeNode(FileNode(item)))
        }

        collapseChains(root)
        aggregate(root, isReviewed)
        return root
    }

    /** Возвращает (создавая при необходимости) узел каталога для пути `a/b/c`. */
    private fun dirNode(
        path: String,
        cache: HashMap<String, DefaultMutableTreeNode>,
        root: DefaultMutableTreeNode
    ): DefaultMutableTreeNode {
        if (path.isEmpty()) return root
        cache[path]?.let { return it }

        val parentPath = path.substringBeforeLast('/', "")
        val parent = dirNode(parentPath, cache, root)
        val node = DefaultMutableTreeNode(DirNode(path.substringAfterLast('/')))
        // Каталоги держим перед файлами.
        val insertAt = (0 until parent.childCount).firstOrNull {
            (parent.getChildAt(it) as DefaultMutableTreeNode).userObject is FileNode
        } ?: parent.childCount
        parent.insert(node, insertAt)
        cache[path] = node
        return node
    }

    /** Схлопывает каталоги, у которых ровно один потомок-каталог и нет файлов. */
    private fun collapseChains(node: DefaultMutableTreeNode) {
        for (i in 0 until node.childCount) {
            collapseChains(node.getChildAt(i) as DefaultMutableTreeNode)
        }
        val dir = node.userObject as? DirNode ?: return
        if (node.parent == null) return // корень не трогаем

        while (node.childCount == 1) {
            val child = node.getChildAt(0) as DefaultMutableTreeNode
            val childDir = child.userObject as? DirNode ?: break
            dir.name = "${dir.name}/${childDir.name}"
            val grandChildren = (0 until child.childCount).map { child.getChildAt(it) as DefaultMutableTreeNode }
            node.remove(child)
            grandChildren.forEach { node.add(it) }
        }
    }

    /** Считает по каталогам количество файлов, суммарные +/- и число просмотренных. */
    private fun aggregate(node: DefaultMutableTreeNode, isReviewed: (HgFileItem) -> Boolean): DirNode? {
        val dir = node.userObject as? DirNode ?: return null
        dir.fileCount = 0
        dir.added = 0
        dir.removed = 0
        dir.reviewedCount = 0

        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            when (val payload = child.userObject) {
                is FileNode -> {
                    dir.fileCount++
                    dir.added += payload.item.added
                    dir.removed += payload.item.removed
                    if (isReviewed(payload.item)) dir.reviewedCount++
                }
                is DirNode -> aggregate(child, isReviewed)?.let {
                    dir.fileCount += it.fileCount
                    dir.added += it.added
                    dir.removed += it.removed
                    dir.reviewedCount += it.reviewedCount
                }
            }
        }
        return dir
    }

    /** Все файлы поддерева в порядке отображения. */
    fun filesOf(node: DefaultMutableTreeNode): List<HgFileItem> {
        val result = ArrayList<HgFileItem>()
        collectFiles(node, result)
        return result
    }

    private fun collectFiles(node: DefaultMutableTreeNode, into: MutableList<HgFileItem>) {
        when (val payload = node.userObject) {
            is FileNode -> into.add(payload.item)
            is DirNode -> for (i in 0 until node.childCount) {
                collectFiles(node.getChildAt(i) as DefaultMutableTreeNode, into)
            }
        }
    }
}
