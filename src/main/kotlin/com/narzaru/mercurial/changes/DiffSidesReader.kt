package com.narzaru.mercurial.changes

import com.narzaru.mercurial.hg.HgPaths
import com.narzaru.mercurial.model.HgFileItem

class DiffSides(
    val plan: DiffSidesPlan,
    val base: String?,
    val head: String?,
    val onlyLast: Boolean,
    val skippedHunks: Int
) {

    val baseTitleRev: String get() = if (onlyLast) plan.lastChangeBaseRev else plan.baseRev
}

class DiffSidesReader(
    private val builder: BranchBaseBuilder,
    private val skippedHunks: MutableMap<String, Int>
) {

    fun read(item: HgFileItem, panelBaseRev: String): DiffSides {
        val plan = DiffSidesPlan.of(item, panelBaseRev)
        if (!plan.rightIsRevision) {
            val base = if (plan.needsBase) builder.readRevision(plan.baseRev, plan.basePath) else null
            return sides(plan, item, base, head = null)
        }
        val head = builder.readRevision(plan.headRev, plan.headPath)
        if (!plan.needsBase) return sides(plan, item, base = null, head = head)
        branchBase(item, head)?.let { return sides(plan, item, it, head) }
        val lastChangeBase = builder.readRevision(plan.lastChangeBaseRev, plan.headPath)
        return sides(plan, item, lastChangeBase, head, onlyLast = true)
    }

    private fun branchBase(item: HgFileItem, head: String?): String? {
        val base = builder.read(item, head)
        val fileKey = HgPaths.key(item.path)
        base.skippedHunks?.let {
            if (it > 0) skippedHunks[fileKey] = it else skippedHunks.remove(fileKey)
        }
        return base.text
    }

    private fun sides(
        plan: DiffSidesPlan,
        item: HgFileItem,
        base: String?,
        head: String?,
        onlyLast: Boolean = false
    ) = DiffSides(plan, base, head, onlyLast, skippedHunks[HgPaths.key(item.path)] ?: 0)
}
