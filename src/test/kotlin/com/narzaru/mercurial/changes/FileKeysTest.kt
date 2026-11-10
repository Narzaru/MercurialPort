package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import com.narzaru.mercurial.model.HgFileStatus
import com.narzaru.mercurial.model.HgTodoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FileKeysTest {

    @Test
    fun `slashes and case do not change the key`() {
        val windows = HgFileItem(status = HgFileStatus.MODIFIED, path = "Src\\A.kt")
        val unix = HgFileItem(status = HgFileStatus.MODIFIED, path = "src/a.kt")

        assertEquals(FileKeys.of(unix), FileKeys.of(windows))
    }

    @Test
    fun `TODO rows of one file differ by line number`() {
        val file = HgFileItem(status = HgFileStatus.MODIFIED, path = "src/a.kt")
        val first = HgTodoItem(file, lineNumber = 3, text = "one")
        val second = HgTodoItem(file, lineNumber = 7, text = "two")

        assertNotEquals(FileKeys.of(first), FileKeys.of(second))
        assertEquals("src/a.kt:3", FileKeys.of(first))
    }

    @Test
    fun `a TODO row and the row of its file have different keys`() {
        val file = HgFileItem(status = HgFileStatus.MODIFIED, path = "Src\\A.kt")

        assertEquals("src/a.kt", FileKeys.of(file))
        assertEquals("src/a.kt:1", FileKeys.of(HgTodoItem(file, lineNumber = 1, text = "one")))
    }

    @Test
    fun `arriving plus and minus counts do not change the key`() {
        val plain = HgFileItem(status = HgFileStatus.MODIFIED, path = "src/a.kt")
        val counted = plain.copy(added = 5, removed = 2)

        assertEquals(FileKeys.of(plain), FileKeys.of(counted))
    }
}
