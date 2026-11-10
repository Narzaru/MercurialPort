package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgChangeRow
import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgTodoItem
import javax.swing.tree.DefaultMutableTreeNode

class DirNode(var name: String) {
    var fileCount: Int = 0
    var added: Int = 0
    var removed: Int = 0
    var reviewedCount: Int = 0
    var allReviewed: Boolean = false

    override fun toString() = name
}

class FileNode(val row: HgChangeRow) {

    val item: HgFileItem get() = row.file

    override fun toString() = item.name
}

object ChangesTreeBuilder {

    fun build(
        rows: List<HgChangeRow>,
        scope: List<HgChangeRow> = rows,
        isReviewed: (HgFileItem) -> Boolean
    ): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode(DirNode(""))
        val dirNodes = HashMap<String, DefaultMutableTreeNode>()
        dirNodes[""] = root

        for (row in rows.sortedBy { it.file.path.lowercase() }) {
            val parent = when (row) {
                is HgTodoItem -> root
                is HgFileItem -> dirNode(dirPathOf(row.path), dirNodes, root)
            }
            parent.add(DefaultMutableTreeNode(FileNode(row)))
        }

        collapseChains(root)
        aggregate(root, "", isReviewed, UnreviewedDirs.of(scope, isReviewed))
        return root
    }

    fun refresh(
        root: DefaultMutableTreeNode,
        rows: List<HgChangeRow>,
        scope: List<HgChangeRow> = rows,
        isReviewed: (HgFileItem) -> Boolean
    ) {
        val byKey = HashMap<String, HgChangeRow>(rows.size)
        for (row in rows) byKey[FileKeys.of(row)] = row
        replaceItems(root, byKey)
        aggregate(root, "", isReviewed, UnreviewedDirs.of(scope, isReviewed))
    }

    private fun replaceItems(node: DefaultMutableTreeNode, byKey: Map<String, HgChangeRow>) {
        when (val payload = node.userObject) {
            is FileNode -> byKey[FileKeys.of(payload.row)]?.let {
                node.userObject = FileNode(it)
            }
            is DirNode -> for (i in 0 until node.childCount) {
                replaceItems(node.getChildAt(i) as DefaultMutableTreeNode, byKey)
            }
        }
    }

    private fun dirPathOf(path: String): String = path.replace('\\', '/').substringBeforeLast('/', "")

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
        val insertAt = (0 until parent.childCount).firstOrNull {
            (parent.getChildAt(it) as DefaultMutableTreeNode).userObject is FileNode
        } ?: parent.childCount
        parent.insert(node, insertAt)
        cache[path] = node
        return node
    }

    private fun collapseChains(node: DefaultMutableTreeNode) {
        for (i in 0 until node.childCount) {
            collapseChains(node.getChildAt(i) as DefaultMutableTreeNode)
        }
        val dir = node.userObject as? DirNode ?: return
        if (node.parent == null) return

        while (node.childCount == 1) {
            val child = node.getChildAt(0) as DefaultMutableTreeNode
            val childDir = child.userObject as? DirNode ?: break
            dir.name = "${dir.name}/${childDir.name}"
            val grandChildren = (0 until child.childCount).map { child.getChildAt(it) as DefaultMutableTreeNode }
            node.remove(child)
            grandChildren.forEach { node.add(it) }
        }
    }

    private fun aggregate(
        node: DefaultMutableTreeNode,
        path: String,
        isReviewed: (HgFileItem) -> Boolean,
        unreviewed: UnreviewedDirs
    ): DirNode? {
        val dir = node.userObject as? DirNode ?: return null
        dir.fileCount = 0
        dir.added = 0
        dir.removed = 0
        dir.reviewedCount = 0
        dir.allReviewed = unreviewed.allReviewed(path)

        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            when (val payload = child.userObject) {
                is FileNode -> {
                    dir.fileCount++
                    dir.added += payload.item.added
                    dir.removed += payload.item.removed
                    if (payload.item.isUnchanged || isReviewed(payload.item)) dir.reviewedCount++
                }
                is DirNode -> aggregate(child, childPath(path, payload.name), isReviewed, unreviewed)?.let {
                    dir.fileCount += it.fileCount
                    dir.added += it.added
                    dir.removed += it.removed
                    dir.reviewedCount += it.reviewedCount
                }
            }
        }
        return dir
    }

    private fun childPath(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

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
