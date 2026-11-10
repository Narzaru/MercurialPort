package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenedFileRowTest {

    private val rows = listOf(null, "a.kt", "b.kt", "b.kt", "b.kt", "c.kt")

    @Test
    fun `a file missing from the list selects nothing`() {
        assertNull(OpenedFileRow.of(rows, setOf(1), "d.kt"))
    }

    @Test
    fun `an unselected file gets its first row selected`() {
        assertEquals(OpenedFileRow(2, select = true), OpenedFileRow.of(rows, setOf(1), "b.kt"))
    }

    @Test
    fun `nothing selected selects the first row of the file`() {
        assertEquals(OpenedFileRow(1, select = true), OpenedFileRow.of(rows, emptySet(), "a.kt"))
    }

    @Test
    fun `a selected row of the same file keeps the selection`() {
        assertEquals(OpenedFileRow(4, select = false), OpenedFileRow.of(rows, setOf(4), "b.kt"))
    }

    @Test
    fun `a selection of several rows narrows to the selected row of the file`() {
        assertEquals(OpenedFileRow(3, select = true), OpenedFileRow.of(rows, setOf(1, 3, 5), "b.kt"))
    }
}
