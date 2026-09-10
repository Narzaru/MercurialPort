package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgFileItem
import javax.swing.tree.DefaultMutableTreeNode

object TreeRefreshPlan {

    fun nodeKey(item: HgFileItem): String = "${HgPaths.key(item.path)}#${item.lineNumber}"

    fun dirPath(node: DefaultMutableTreeNode): String {
        val names = ArrayList<String>()
        var current: DefaultMutableTreeNode? = node
        while (current != null && current.parent != null) {
            val dir = current.userObject as? DirNode ?: break
            names.add(dir.name)
            current = current.parent as? DefaultMutableTreeNode
        }
        names.reverse()
        return names.joinToString("/")
    }

    fun expandsAfterRebuild(dirPath: String, collapsed: Set<String>?): Boolean =
        collapsed == null || dirPath !in collapsed

    fun keepsStructure(shown: List<HgFileItem>, next: List<HgFileItem>): Boolean {
        if (shown.size != next.size) return false
        for (index in shown.indices) {
            if (nodeKey(shown[index]) != nodeKey(next[index])) return false
        }
        return true
    }
}
