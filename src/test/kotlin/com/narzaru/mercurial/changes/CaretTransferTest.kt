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
    fun `a caret on the right side of the diff lands in the file`() {
        assertEquals(7, CaretTransfer.toFile(DiffSide.RIGHT, 7))
    }

    @Test
    fun `the first line of the right side is carried to the file`() {
        assertEquals(0, CaretTransfer.toFile(DiffSide.RIGHT, 0))
    }

    @Test
    fun `a caret on the left side has no line in the file`() {
        assertNull(CaretTransfer.toFile(DiffSide.LEFT, 7))
    }

    @Test
    fun `a missing caret in the diff has no line in the file`() {
        assertNull(CaretTransfer.toFile(DiffSide.RIGHT, null))
    }

    @Test
    fun `a line that could not be mapped has no line in the file`() {
        assertNull(CaretTransfer.toFile(DiffSide.RIGHT, -1))
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
