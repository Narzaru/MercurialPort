package com.narzaru.mercurial.review

import com.narzaru.mercurial.changes.DiffFragment
import com.narzaru.mercurial.changes.LineRange

data class ReviewHunk(val fragment: DiffFragment, val shown: Boolean)

object ReviewHunks {

    fun of(fragments: List<DiffFragment>, steps: List<ReviewStep>, attribution: ReviewAttribution?): List<ReviewHunk> =
        joinTouching(fragments).map { ReviewHunk(it, attribution == null || isShown(it, steps, attribution)) }

    fun joinTouching(fragments: List<DiffFragment>): List<DiffFragment> {
        val result = ArrayList<DiffFragment>(fragments.size)
        for (fragment in fragments) {
            val last = result.lastOrNull()
            if (last != null && last.left.end == fragment.left.start && last.right.end == fragment.right.start) {
                result[result.size - 1] = DiffFragment(
                    LineRange(last.left.start, fragment.left.end),
                    LineRange(last.right.start, fragment.right.end)
                )
            } else {
                result.add(fragment)
            }
        }
        return result
    }

    fun leftSide(old: List<String>, new: List<String>, hunks: List<ReviewHunk>): List<String> {
        val result = ArrayList<String>(new.size)
        var copied = 0
        for (hunk in hunks) {
            val right = hunk.fragment.right
            for (j in copied until right.start) result.add(new[j])
            if (hunk.shown) {
                for (i in hunk.fragment.left.start until hunk.fragment.left.end) result.add(old[i])
            } else {
                for (j in right.start until right.end) result.add(new[j])
            }
            copied = right.end
        }
        for (j in copied until new.size) result.add(new[j])
        return result
    }

    private fun isShown(fragment: DiffFragment, steps: List<ReviewStep>, attribution: ReviewAttribution): Boolean {
        fun related(step: Int?): Boolean = step == null || !steps[step].unrelated
        val removed = (fragment.left.start until fragment.left.end).any { related(attribution.removedBy.getOrNull(it)) }
        val added = (fragment.right.start until fragment.right.end).any { related(attribution.addedBy.getOrNull(it)) }
        return removed || added
    }
}
