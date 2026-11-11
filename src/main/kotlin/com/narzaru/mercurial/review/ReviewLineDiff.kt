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
        return try {
            ByLineRt.compare(left, right, ComparisonPolicy.DEFAULT, checker).iterateChanges()
                .map { DiffFragment(LineRange(it.start1, it.end1), LineRange(it.start2, it.end2)) }
        } catch (e: DiffTooBigException) {
            listOf(DiffFragment(LineRange(0, left.size), LineRange(0, right.size)))
        }
    }

    fun similarity(left: List<String>, right: List<String>): Double {
        val total = left.size + right.size
        if (total == 0) return 1.0
        val changed = compare(left, right).sumOf { (it.left.end - it.left.start) + (it.right.end - it.right.start) }
        return (total - changed).toDouble() / total
    }
}
