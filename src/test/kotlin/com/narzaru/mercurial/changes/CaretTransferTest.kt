package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaretTransferTest {

    @Test
    fun `a caret in a file lands on the same line and column of the diff`() {
        assertEquals(DiffCaret(41, 12), CaretTransfer.toDiff(41, 12))
    }

    @Test
    fun `a caret at the start of a line keeps column zero`() {
        assertEquals(DiffCaret(41, 0), CaretTransfer.toDiff(41, 0))
    }

    @Test
    fun `a caret at the very start of a file is not carried to the diff`() {
        assertNull(CaretTransfer.toDiff(0, 0))
    }

    @Test
    fun `a caret inside the first line of a file is carried to the diff`() {
        assertEquals(DiffCaret(0, 5), CaretTransfer.toDiff(0, 5))
    }

    @Test
    fun `a missing caret is not carried to the diff`() {
        assertNull(CaretTransfer.toDiff(null, 3))
    }

    @Test
    fun `a missing column lands at the start of the line`() {
        assertEquals(DiffCaret(7, 0), CaretTransfer.toDiff(7, null))
    }

    @Test
    fun `a right side showing the file itself keeps the file line`() {
        assertEquals(7, CaretTransfer.lineOnRightSide(7, true) { error("must not translate") })
    }

    @Test
    fun `a right side showing a revision gets the translated line`() {
        assertEquals(4, CaretTransfer.lineOnRightSide(7, false) { it - 3 })
    }

    @Test
    fun `a line that cannot be translated stays as it is`() {
        assertEquals(7, CaretTransfer.lineOnRightSide(7, false) { null })
    }

    @Test
    fun `a translation outside the revision keeps the file line`() {
        assertEquals(7, CaretTransfer.lineOnRightSide(7, false) { -1 })
    }

    @Test
    fun `a column within the line is kept`() {
        assertEquals(4, CaretTransfer.columnInLine(4, 10))
    }

    @Test
    fun `a column past a shorter line lands at its end`() {
        assertEquals(3, CaretTransfer.columnInLine(12, 3))
    }

    @Test
    fun `a column on an empty line lands at its start`() {
        assertEquals(0, CaretTransfer.columnInLine(5, 0))
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
