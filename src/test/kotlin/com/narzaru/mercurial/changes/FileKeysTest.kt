package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FileKeysTest {

    @Test
    fun `slashes and case do not change the key`() {
        val windows = HgFileItem(status = "M", path = "Src\\A.kt")
        val unix = HgFileItem(status = "M", path = "src/a.kt")

        assertEquals(FileKeys.of(unix), FileKeys.of(windows))
    }

    @Test
    fun `TODO rows of one file differ by line number`() {
        val first = HgFileItem(status = "M", path = "src/a.kt", todoText = "one", lineNumber = 3)
        val second = HgFileItem(status = "M", path = "src/a.kt", todoText = "two", lineNumber = 7)

        assertNotEquals(FileKeys.of(first), FileKeys.of(second))
        assertEquals("src/a.kt:3", FileKeys.of(first))
    }

    @Test
    fun `arriving plus and minus counts do not change the key`() {
        val plain = HgFileItem(status = "M", path = "src/a.kt")
        val counted = plain.copy(added = 5, removed = 2)

        assertEquals(FileKeys.of(plain), FileKeys.of(counted))
    }
}
