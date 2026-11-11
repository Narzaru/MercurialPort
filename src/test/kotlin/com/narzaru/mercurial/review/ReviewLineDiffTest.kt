package com.narzaru.mercurial.review

import com.narzaru.mercurial.changes.DiffFragment
import com.narzaru.mercurial.changes.LineRange
import com.intellij.diff.comparison.ByLineRt
import com.intellij.diff.comparison.CancellationChecker
import com.intellij.diff.comparison.ComparisonPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReviewLineDiffTest {

    private val diff = ReviewLineDiff()

    @Test
    fun `text loses the byte order mark and carriage returns`() {
        assertEquals(listOf("a", "b", ""), ReviewText.lines("﻿a\r\nb\r\n"))
    }

    @Test
    fun `a file of N lines ending with a newline has N plus one lines`() {
        assertEquals(listOf("one", "two", "three", ""), ReviewText.lines("one\ntwo\nthree\n"))
        assertEquals(listOf("one", "two"), ReviewText.lines("one\ntwo"))
    }

    @Test
    fun `an empty file is one empty line and a missing file has none`() {
        assertEquals(listOf(""), ReviewText.lines(""))
        assertEquals(emptyList<String>(), ReviewText.lines(null))
    }

    @Test
    fun `a lone carriage return stays part of the line`() {
        assertEquals(listOf("a\rb", ""), ReviewText.lines("a\rb\n"))
    }

    @Test
    fun `a zero character marks the content as binary`() {
        assertTrue(ReviewText.isBinary("GIF\u0000\u0001"))
        assertFalse(ReviewText.isBinary("plain"))
        assertFalse(ReviewText.isBinary(null))
    }

    @Test
    fun `an added file is one fragment over all its lines`() {
        val added = ReviewText.lines("a\nb\n")

        assertEquals(listOf(DiffFragment(LineRange(0, 0), LineRange(0, 3))), diff.compare(emptyList(), added))
    }

    @Test
    fun `whitespace is significant`() {
        val fragments = diff.compare(listOf("a", "  b", "c"), listOf("a", "b", "c"))

        assertEquals(listOf(DiffFragment(LineRange(1, 2), LineRange(1, 2))), fragments)
    }

    @Test
    fun `identical sides have no fragments`() {
        assertEquals(emptyList<DiffFragment>(), diff.compare(listOf("a", "b"), listOf("a", "b")))
    }

    @Test
    fun `unchanged braces are aligned the way the IDE aligns them`() {
        val old = listOf("class A {", "    fun a() {", "    }", "}")
        val new = listOf("class A {", "    fun a() {", "    }", "", "    fun b() {", "    }", "}")

        assertEquals(listOf(DiffFragment(LineRange(3, 3), LineRange(3, 6))), diff.compare(old, new))
    }

    @Test
    fun `a reindented block is one modified fragment`() {
        val old = listOf("if (x) {", "call()", "next()", "}")
        val new = listOf("if (x) {", "    call()", "    next()", "}")

        assertEquals(listOf(fragment(1, 3, 1, 3)), diff.compare(old, new))
    }

    @Test
    fun `a closing brace pairs with the brace at another indent inside the changed block`() {
        val old = listOf("fun a() {", "    one()", "    two()", "}", "")
        val new = listOf("fun a() {", "    if (x) {", "        one()", "        two()", "    }", "}", "")

        assertEquals(
            listOf(fragment(1, 1, 1, 2), fragment(1, 4, 2, 5), fragment(4, 4, 5, 6)),
            diff.compare(old, new)
        )
    }

    @Test
    fun `a missing final newline on one side is a removed empty line`() {
        assertEquals(listOf(fragment(2, 3, 2, 2)), diff.compare(ReviewText.lines("a\nb\n"), ReviewText.lines("a\nb")))
    }

    @Test
    fun `a whitespace change before a missing final newline stays a separate modified line`() {
        val fragments = diff.compare(ReviewText.lines("a\n  b"), ReviewText.lines("a\nb\n"))

        assertEquals(listOf(fragment(1, 2, 1, 2), fragment(2, 2, 2, 3)), fragments)
    }

    @Test
    fun `a whitespace change merges with an adjacent modified fragment`() {
        assertEquals(listOf(fragment(1, 3, 1, 3)), diff.compare(listOf("a", " b", "c"), listOf("a", "b", "x")))
    }

    @Test
    fun `leftover lines of a gap become removed or added lines`() {
        assertEquals(
            listOf(fragment(1, 2, 1, 2), fragment(2, 3, 2, 2)),
            ReviewWhitespaceChanges.addTo(emptyList(), listOf("a", "b", "c"), listOf("a", "x"))
        )
        assertEquals(
            listOf(fragment(1, 1, 1, 3)),
            ReviewWhitespaceChanges.addTo(emptyList(), listOf("a"), listOf("a", "b", "c"))
        )
    }

    @Test
    fun `without whitespace differences the result equals the plain comparison`() {
        val random = Random(7)
        val alphabet = listOf("a", "b", "c", "{", "}", "", "x();")
        fun text() = List(random.nextInt(0, 12)) { alphabet[random.nextInt(alphabet.size)] }
        repeat(500) {
            val left = text()
            val right = text()
            val plain = ByLineRt.compare(left, right, ComparisonPolicy.DEFAULT, CancellationChecker.EMPTY).iterateChanges()
                .map { fragment(it.start1, it.end1, it.start2, it.end2) }

            assertEquals("$left -> $right", plain, diff.compare(left, right))
        }
    }

    @Test
    fun `similarity is the share of lines both sides keep`() {
        assertEquals(1.0, diff.similarity(listOf("a", "b"), listOf("a", "b")), 1e-9)
        assertEquals(0.5, diff.similarity(listOf("a", "b"), listOf("a", "c")), 1e-9)
        assertEquals(0.0, diff.similarity(listOf("a"), listOf("b")), 1e-9)
        assertEquals(1.0, diff.similarity(emptyList(), emptyList()), 1e-9)
    }

    @Test
    fun `similarity counts changes the plain way, without pairing lines that differ in whitespace`() {
        val old = listOf("fun a() {", "    one()", "    two()", "}", "")
        val new = listOf("fun a() {", "    if (x) {", "        one()", "        two()", "    }", "}", "")

        assertEquals(0.5, diff.similarity(old, new), 1e-9)
    }

    private fun fragment(leftStart: Int, leftEnd: Int, rightStart: Int, rightEnd: Int) =
        DiffFragment(LineRange(leftStart, leftEnd), LineRange(rightStart, rightEnd))
}
