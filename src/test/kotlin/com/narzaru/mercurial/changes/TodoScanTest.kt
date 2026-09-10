package com.narzaru.mercurial.changes

import com.narzaru.mercurial.model.HgFileItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoScanTest {

    private fun todo(path: String, line: Int, text: String, status: String = "M") =
        HgFileItem(status = status, path = path, todoText = text, lineNumber = line)

    @Test
    fun `the same set of TODOs needs no repaint`() {
        val first = listOf(todo("src/a.kt", 3, "fix"), todo("src/b.kt", 9, "drop"))
        val second = listOf(todo("src/a.kt", 3, "fix"), todo("src/b.kt", 9, "drop"))

        assertFalse(TodoScan.changed(first, second))
    }

    @Test
    fun `an edited TODO text is noticed`() {
        val first = listOf(todo("src/a.kt", 3, "fix"))
        val second = listOf(todo("src/a.kt", 3, "fix later"))

        assertTrue(TodoScan.changed(first, second))
    }

    @Test
    fun `a TODO moved to another line is noticed`() {
        assertTrue(TodoScan.changed(listOf(todo("src/a.kt", 3, "fix")), listOf(todo("src/a.kt", 4, "fix"))))
    }

    @Test
    fun `arriving plus and minus counts leave the set alone`() {
        val first = listOf(todo("src/a.kt", 3, "fix"))
        val second = listOf(todo("src/a.kt", 3, "fix").copy(added = 4, removed = 1))

        assertFalse(TodoScan.changed(first, second))
    }

    @Test
    fun `an added and a removed TODO are noticed`() {
        assertTrue(TodoScan.changed(emptyList(), listOf(todo("src/a.kt", 3, "fix"))))
        assertTrue(TodoScan.changed(listOf(todo("src/a.kt", 3, "fix")), emptyList()))
    }

    @Test
    fun `editing a listed file starts a scan`() {
        val files = listOf(HgFileItem(status = "M", path = "src/a.kt"))

        assertTrue(TodoScan.covers(files, "D:/repo", "D:/repo/src/a.kt"))
        assertTrue(TodoScan.covers(files, "D:\\repo", "D:\\repo\\Src\\A.kt"))
    }

    @Test
    fun `editing a file outside the list starts nothing`() {
        val files = listOf(HgFileItem(status = "M", path = "src/a.kt"))

        assertFalse(TodoScan.covers(files, "D:/repo", "D:/repo/src/other.kt"))
        assertFalse(TodoScan.covers(files, "D:/repo", "D:/elsewhere/src/a.kt"))
    }
}
