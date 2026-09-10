package com.narzaru.mercurial.history

import com.narzaru.mercurial.model.HgHistoryItem

class HistoryDiffSelection(
    val newest: HgHistoryItem,
    val newestRow: Int,
    val oldest: HgHistoryItem,
    val oldestRow: Int,
    val selectedCount: Int
) {

    val hasParent: Boolean = (oldest.parentRev.toIntOrNull() ?: -1) >= 0

    val parentRevision: String = oldest.parentRev

    fun tabKey(rightPath: String): String =
        "range|${oldest.parentRev}|${newest.revision}|$rightPath"

    fun leftLabel(leftPath: String, rightPath: String): String {
        val label = if (hasParent) HistoryStatusText.parentLabel(oldest.parentRev)
        else HistoryStatusText.NO_PARENT_REVISION
        if (leftPath == rightPath) return label
        return label + HistoryStatusText.renamedSuffix(leftPath)
    }

    fun rightLabel(): String =
        HistoryStatusText.revisionLabel(newest.revision, newest.author) +
            HistoryStatusText.revisionsSuffix(selectedCount)

    companion object {
        fun of(items: List<HgHistoryItem>, selectedRows: List<Int>): HistoryDiffSelection? {
            val rows = selectedRows.filter { it in items.indices }
            if (rows.isEmpty()) return null
            val newestRow = rows.min()
            val oldestRow = rows.max()
            return HistoryDiffSelection(
                items[newestRow], newestRow,
                items[oldestRow], oldestRow,
                rows.size
            )
        }
    }
}
