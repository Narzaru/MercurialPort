package com.narzaru.mercurial.history

import com.narzaru.mercurial.model.HgHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryTableModelTest {

    private fun item(revision: String, message: String = "msg") = HgHistoryItem(
        revision = revision,
        node = "node$revision",
        author = "author$revision",
        date = "2026-01-0$revision 12:00 +0300",
        message = message,
        path = "src/App.kt",
        parentRev = (revision.toInt() - 1).toString()
    )

    @Test
    fun `an empty model has no rows and no items`() {
        val model = HistoryTableModel()

        assertEquals(0, model.rowCount)
        assertEquals(emptyList<HgHistoryItem>(), model.items())
        assertNull(model.itemAt(0))
    }

    @Test
    fun `items are returned by row`() {
        val model = HistoryTableModel()
        model.setItems(listOf(item("2"), item("1")))

        assertEquals("2", model.itemAt(0)?.revision)
        assertEquals("1", model.itemAt(1)?.revision)
        assertEquals(2, model.rowCount)
    }

    @Test
    fun `a row outside the model has no item`() {
        val model = HistoryTableModel()
        model.setItems(listOf(item("1")))

        assertNull(model.itemAt(-1))
        assertNull(model.itemAt(1))
        assertNull(model.itemAt(100))
    }

    @Test
    fun `every column shows its own field`() {
        val model = HistoryTableModel()
        model.setItems(listOf(item("3", message = "fix the thing")))

        assertEquals("3", model.getValueAt(0, HistoryTableModel.COL_REVISION))
        assertEquals("node3", model.getValueAt(0, HistoryTableModel.COL_NODE))
        assertEquals("2026-01-03 12:00 +0300", model.getValueAt(0, HistoryTableModel.COL_DATE))
        assertEquals("author3", model.getValueAt(0, HistoryTableModel.COL_AUTHOR))
        assertEquals("fix the thing", model.getValueAt(0, 4))
    }

    @Test
    fun `columns are named and read only`() {
        val model = HistoryTableModel()

        assertEquals(5, model.columnCount)
        assertEquals("Rev", model.getColumnName(HistoryTableModel.COL_REVISION))
        assertEquals("Date", model.getColumnName(HistoryTableModel.COL_DATE))
        assertEquals(false, model.isCellEditable(0, 0))
    }

    @Test
    fun `new items replace the previous ones`() {
        val model = HistoryTableModel()
        model.setItems(listOf(item("1"), item("2")))
        model.setItems(listOf(item("3")))

        assertEquals(1, model.rowCount)
        assertEquals("3", model.itemAt(0)?.revision)
        assertNull(model.itemAt(1))
    }
}
