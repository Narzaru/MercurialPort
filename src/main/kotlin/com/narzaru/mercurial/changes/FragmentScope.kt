package com.narzaru.mercurial.changes

data class LineRange(val start: Int, val end: Int) {

    val isEmpty: Boolean get() = end <= start

    fun overlaps(other: LineRange): Boolean =
        if (isEmpty || other.isEmpty) start <= other.end && other.start <= end
        else start < other.end && other.start < end
}

data class DiffFragment(val left: LineRange, val right: LineRange)

object FragmentScope {

    fun leftSide(
        base: List<String>,
        head: List<String>,
        fragments: List<DiffFragment>,
        own: List<LineRange>,
        gap: Int = CONTEXT_LINES
    ): List<String> {
        val result = ArrayList<String>(head.size)
        var copiedTo = 0
        for (fragment in join(fragments, gap)) {
            if (fragment.right.start < copiedTo) continue
            for (i in copiedTo until fragment.right.start) result.add(head[i])
            val touched = own.any { it.overlaps(fragment.right) }
            if (touched) {
                for (i in fragment.left.start until fragment.left.end) result.add(base[i])
            } else {
                for (i in fragment.right.start until fragment.right.end) result.add(head[i])
            }
            copiedTo = fragment.right.end
        }
        for (i in copiedTo until head.size) result.add(head[i])
        return result
    }

    fun join(fragments: List<DiffFragment>, gap: Int = CONTEXT_LINES): List<DiffFragment> {
        val sorted = fragments.sortedBy { it.right.start }
        val result = ArrayList<DiffFragment>(sorted.size)
        for (fragment in sorted) {
            val last = result.lastOrNull()
            if (last != null && fragment.right.start - last.right.end <= gap) {
                result[result.size - 1] = DiffFragment(
                    LineRange(last.left.start, maxOf(last.left.end, fragment.left.end)),
                    LineRange(last.right.start, maxOf(last.right.end, fragment.right.end))
                )
            } else {
                result.add(fragment)
            }
        }
        return result
    }

    private const val CONTEXT_LINES = 2
}
