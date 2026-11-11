package com.narzaru.mercurial.review

enum class ReviewStepKind { REVIEW, REVIEW_MERGE, MERGE, OTHER }

class ReviewStep(
    val node: String,
    val kind: ReviewStepKind,
    val lines: List<String>,
    val differsFromBothParents: Boolean = false
) {

    val unrelated: Boolean
        get() = when (kind) {
            ReviewStepKind.REVIEW -> false
            ReviewStepKind.OTHER -> true
            ReviewStepKind.REVIEW_MERGE, ReviewStepKind.MERGE -> differsFromBothParents
        }

    companion object {

        fun kindOf(revision: ReviewRevision, review: Set<String>): ReviewStepKind = when {
            revision.isMerge && revision.node in review -> ReviewStepKind.REVIEW_MERGE
            revision.isMerge -> ReviewStepKind.MERGE
            revision.node in review -> ReviewStepKind.REVIEW
            else -> ReviewStepKind.OTHER
        }
    }
}

class ReviewAttribution(
    val removedBy: List<Int?>,
    val addedBy: List<Int?>
)

object ReviewChain {

    fun insideReview(revisions: Map<String, ReviewRevision>, from: String?, to: String): Boolean {
        var node = to
        while (node != from) {
            val revision = revisions[node] ?: return false
            if (revision.isMerge) return false
            node = revision.p1 ?: return from == null
        }
        return true
    }

    fun walk(graph: Map<String, ReviewRevision>, review: Set<String>, from: String, to: String): List<String>? {
        val descendants = descendantsOf(graph, from, to)
        val chain = arrayListOf(to)
        var node = to
        while (node != from) {
            val revision = graph[node] ?: return null
            val parents = revision.parents.filter { it == from || it in descendants }
            node = when (parents.size) {
                0 -> return null
                1 -> parents[0]
                else -> parents.singleOrNull { it in review } ?: parents[0]
            }
            chain.add(node)
        }
        return chain.asReversed()
    }

    private fun descendantsOf(graph: Map<String, ReviewRevision>, from: String, to: String): Set<String> {
        val ancestors = HashSet<String>()
        val pending = ArrayDeque(listOf(to))
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (node == from || node !in graph || !ancestors.add(node)) continue
            graph[node]?.parents?.forEach { pending.addLast(it) }
        }
        val descendants = HashSet<String>()
        for (revision in ancestors.mapNotNull { graph[it] }.sortedBy { it.rev }) {
            if (revision.parents.any { it == from || it in descendants }) descendants.add(revision.node)
        }
        return descendants
    }
}

object ReviewReplay {

    fun replay(old: List<String>, steps: List<ReviewStep>, diff: ReviewLineDiff): ReviewAttribution {
        val removedBy = arrayOfNulls<Int>(old.size)
        var origins: List<Origin> = old.indices.map { Origin(it, null) }
        var content = old
        for ((index, step) in steps.withIndex()) {
            val next = ArrayList<Origin>(step.lines.size)
            var copied = 0
            for (fragment in diff.compare(content, step.lines)) {
                for (i in copied until fragment.left.start) next.add(origins[i])
                for (i in fragment.left.start until fragment.left.end) {
                    origins[i].oldLine?.let { removedBy[it] = index }
                }
                for (j in fragment.right.start until fragment.right.end) next.add(Origin(null, index))
                copied = fragment.left.end
            }
            for (i in copied until content.size) next.add(origins[i])
            origins = next
            content = step.lines
        }
        return ReviewAttribution(removedBy.toList(), origins.map { it.step })
    }

    private class Origin(val oldLine: Int?, val step: Int?)
}
