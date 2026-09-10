package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMismatchTest {

    @Test
    fun `a difference in the first character is reported at zero`() {
        val text = TextMismatch.describe("abc", "xbc")

        assertTrue(text.contains("first difference at 0"))
        assertTrue(text.contains("document [abc]"))
        assertTrue(text.contains("revision [xbc]"))
    }

    @Test
    fun `both lengths are reported`() {
        val text = TextMismatch.describe("abc", "abcdef")

        assertTrue(text.startsWith("document=3 chars, revision=6 chars, "))
        assertTrue(text.contains("first difference at 3"))
    }

    @Test
    fun `equal texts report a difference past their end`() {
        val text = TextMismatch.describe("abc", "abc")

        assertTrue(text.contains("first difference at 3"))
        assertTrue(text.contains("document [abc]"))
    }

    @Test
    fun `only the window around the difference is shown`() {
        val local = "a".repeat(100) + "X" + "b".repeat(100)
        val revision = "a".repeat(100) + "Y" + "b".repeat(100)

        val text = TextMismatch.describe(local, revision, window = 3)

        assertTrue(text.contains("first difference at 100"))
        assertTrue(text.contains("document [aaaXbb]"))
        assertTrue(text.contains("revision [aaaYbb]"))
    }

    @Test
    fun `a difference at the very end is not cut off`() {
        val text = TextMismatch.describe("abcdefX", "abcdefY", window = 2)

        assertTrue(text.contains("document [efX]"))
        assertTrue(text.contains("revision [efY]"))
    }

    @Test
    fun `line breaks are shown as markers`() {
        val text = TextMismatch.describe("a\r\nb", "a\nb")

        assertTrue(text.contains("document [a<CR><LF>b]"))
        assertTrue(text.contains("revision [a<LF>b]"))
        assertFalse(text.contains("\n"))
    }

    @Test
    fun `the window defaults to twenty characters on each side`() {
        val local = "a".repeat(50) + "X"
        val revision = "a".repeat(50) + "Y"

        assertEquals(
            TextMismatch.describe(local, revision, window = TextMismatch.DEFAULT_WINDOW),
            TextMismatch.describe(local, revision)
        )
    }
}
