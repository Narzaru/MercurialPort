package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgFileStatus

data class DiffSidesPlan(
    val needsBase: Boolean,
    val baseRev: String,
    val basePath: String,
    val headRev: String,
    val headPath: String
) {

    val rightIsRevision: Boolean get() = headRev.isNotEmpty()

    val lastChangeBaseRev: String get() = "p1($headRev)"

    companion object {

        fun of(item: HgFileItem, panelBaseRev: String): DiffSidesPlan {
            val baseRev = item.baseRev.ifEmpty { panelBaseRev }
            return DiffSidesPlan(
                needsBase = hasBaseSide(item, baseRev),
                baseRev = baseRev,
                basePath = item.basePath,
                headRev = item.headRev,
                headPath = item.path
            )
        }

        fun isNew(item: HgFileItem): Boolean =
            (item.status == HgFileStatus.ADDED || item.status == HgFileStatus.UNTRACKED) &&
                item.copiedFrom.isEmpty()

        private fun hasBaseSide(item: HgFileItem, baseRev: String): Boolean =
            !isNew(item) && baseRev.isNotEmpty() && baseRev != BranchRevisions.NO_REVISION
    }
}
