package com.narzaru.mercurial.review

import com.narzaru.mercurial.changes.DiffFragment
import com.narzaru.mercurial.changes.LineRange
import com.intellij.diff.comparison.ByLineRt
import com.intellij.diff.comparison.CancellationChecker
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException

object ReviewText {

    private const val BOM = '﻿'

    private const val NUL = '\u0000'

    fun lines(text: String?): List<String> {
        if (text == null) return emptyList()
        return text.removePrefix(BOM.toString()).replace("\r\n", "\n").split('\n')
    }

    fun isBinary(text: String?): Boolean = text != null && text.indexOf(NUL) >= 0
}

class ReviewLineDiff(private val checker: CancellationChecker = CancellationChecker.EMPTY) {

    fun compare(left: List<String>, right: List<String>): List<DiffFragment> {
        if (left == right) return emptyList()
        val ignoringWhitespace = changes(left, right, ComparisonPolicy.IGNORE_WHITESPACES) ?: return wholeText(left, right)
        return ReviewWhitespaceChanges.addTo(ignoringWhitespace, left, right)
    }

    fun similarity(left: List<String>, right: List<String>): Double {
        if (left == right) return 1.0
        val total = left.size + right.size
        val fragments = changes(left, right, ComparisonPolicy.DEFAULT) ?: wholeText(left, right)
        val changed = fragments.sumOf { (it.left.end - it.left.start) + (it.right.end - it.right.start) }
        return (total - changed).toDouble() / total
    }

    private fun changes(left: List<String>, right: List<String>, policy: ComparisonPolicy): List<DiffFragment>? =
        try {
            ByLineRt.compare(left, right, policy, checker).iterateChanges()
                .map { DiffFragment(LineRange(it.start1, it.end1), LineRange(it.start2, it.end2)) }
        } catch (e: DiffTooBigException) {
            null
        }

    private fun wholeText(left: List<String>, right: List<String>): List<DiffFragment> =
        listOf(DiffFragment(LineRange(0, left.size), LineRange(0, right.size)))
}

object ReviewWhitespaceChanges {

    fun addTo(fragments: List<DiffFragment>, left: List<String>, right: List<String>): List<DiffFragment> {
        val result = MergingFragments()
        var leftFrom = 0
        var rightFrom = 0
        for (fragment in fragments) {
            addGap(result, left, right, LineRange(leftFrom, fragment.left.start), LineRange(rightFrom, fragment.right.start))
            result.add(fragment)
            leftFrom = fragment.left.end
            rightFrom = fragment.right.end
        }
        addGap(result, left, right, LineRange(leftFrom, left.size), LineRange(rightFrom, right.size))
        return result.fragments
    }

    private fun addGap(result: MergingFragments, left: List<String>, right: List<String>, leftGap: LineRange, rightGap: LineRange) {
        var i = leftGap.start
        var j = rightGap.start
        while (i < leftGap.end && j < rightGap.end) {
            if (left[i] != right[j]) result.add(DiffFragment(LineRange(i, i + 1), LineRange(j, j + 1)))
            i++
            j++
        }
        for (rest in i until leftGap.end) result.add(DiffFragment(LineRange(rest, rest + 1), LineRange(rightGap.end, rightGap.end)))
        for (rest in j until rightGap.end) result.add(DiffFragment(LineRange(leftGap.end, leftGap.end), LineRange(rest, rest + 1)))
    }

    private class MergingFragments {

        val fragments = ArrayList<DiffFragment>()

        fun add(fragment: DiffFragment) {
            val last = fragments.lastOrNull()
            if (last != null && last.left.end == fragment.left.start && last.right.end == fragment.right.start &&
                kindOf(last) == kindOf(fragment)
            ) {
                fragments[fragments.size - 1] = DiffFragment(
                    LineRange(last.left.start, fragment.left.end),
                    LineRange(last.right.start, fragment.right.end)
                )
            } else {
                fragments.add(fragment)
            }
        }

        private fun kindOf(fragment: DiffFragment): Kind = when {
            fragment.left.isEmpty -> Kind.INSERTED
            fragment.right.isEmpty -> Kind.DELETED
            else -> Kind.MODIFIED
        }
    }

    private enum class Kind { INSERTED, DELETED, MODIFIED }
}
