package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaretTransferTest {

    @Test
    fun `a caret in a file lands on the right side of the diff`() {
        assertEquals(41, CaretTransfer.toDiff(41))
    }

    @Test
    fun `a caret on the first line of a file is not carried to the diff`() {
        assertNull(CaretTransfer.toDiff(0))
    }

    @Test
    fun `a missing caret is not carried to the diff`() {
        assertNull(CaretTransfer.toDiff(null))
    }

    @Test
    fun `a caret on a side showing the file itself keeps its line`() {
        assertEquals(7, CaretTransfer.toFile(7, true) { error("must not translate") })
    }

    @Test
    fun `the first line of a side showing the file is carried to the file`() {
        assertEquals(0, CaretTransfer.toFile(0, true) { error("must not translate") })
    }

    @Test
    fun `a caret on a side showing a revision is translated into the file`() {
        assertEquals(12, CaretTransfer.toFile(7, false) { it + 5 })
    }

    @Test
    fun `a revision line that cannot be translated keeps its own number`() {
        assertEquals(7, CaretTransfer.toFile(7, false) { null })
    }

    @Test
    fun `a translation outside the file keeps the revision line`() {
        assertEquals(7, CaretTransfer.toFile(7, false) { -1 })
    }

    @Test
    fun `a missing caret in the diff has no line in the file`() {
        assertNull(CaretTransfer.toFile(null, true) { it })
    }

    @Test
    fun `a line that could not be mapped has no line in the file`() {
        assertNull(CaretTransfer.toFile(-1, false) { it })
    }

    @Test
    fun `a carried line wins over the line of the item`() {
        assertEquals(3, CaretTransfer.fileTarget(3, 10))
    }

    @Test
    fun `the line of the item is used when nothing was carried`() {
        assertEquals(9, CaretTransfer.fileTarget(null, 10))
    }

    @Test
    fun `a file without a line is opened where it was left`() {
        assertNull(CaretTransfer.fileTarget(null, 0))
    }
}
