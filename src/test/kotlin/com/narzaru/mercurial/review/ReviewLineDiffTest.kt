package com.narzaru.mercurial.review

import com.narzaru.mercurial.changes.DiffFragment
import com.narzaru.mercurial.changes.LineRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `similarity is the share of lines both sides keep`() {
        assertEquals(1.0, diff.similarity(listOf("a", "b"), listOf("a", "b")), 1e-9)
        assertEquals(0.5, diff.similarity(listOf("a", "b"), listOf("a", "c")), 1e-9)
        assertEquals(0.0, diff.similarity(listOf("a"), listOf("b")), 1e-9)
    }
}
