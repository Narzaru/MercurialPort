package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HgRenameParserTest {

    @Test
    fun `takes the old name without the filelog hash`() {
        val out = "src/new.kt renamed from src/old.kt:0123456789abcdef0123456789abcdef01234567"

        assertEquals("src/old.kt", HgRenameParser.sourceOf(out))
    }

    @Test
    fun `a file that was not renamed gives null`() {
        assertNull(HgRenameParser.sourceOf("src\\a.kt not renamed"))
    }

    @Test
    fun `a path with backslashes is normalized`() {
        val out = "src\\new.kt renamed from src\\old.kt:abc"

        assertEquals("src/old.kt", HgRenameParser.sourceOf(out))
    }

    @Test
    fun `empty output gives null`() {
        assertNull(HgRenameParser.sourceOf(""))
    }
}
