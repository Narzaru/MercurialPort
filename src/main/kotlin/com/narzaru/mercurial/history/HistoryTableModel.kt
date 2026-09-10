package com.narzaru.mercurial.history

import com.narzaru.mercurial.model.HgHistoryItem
import javax.swing.table.AbstractTableModel

class HistoryTableModel : AbstractTableModel() {

    private val columns = arrayOf("Rev", "Node", "Date", "Author", "Message")

    private var rows: List<HgHistoryItem> = emptyList()

    fun setItems(items: List<HgHistoryItem>) {
        rows = items
        fireTableDataChanged()
    }

    fun items(): List<HgHistoryItem> = rows

    fun itemAt(row: Int): HgHistoryItem? = rows.getOrNull(row)

    override fun getRowCount() = rows.size

    override fun getColumnCount() = columns.size

    override fun getColumnName(column: Int) = columns[column]

    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val item = rows[rowIndex]
        return when (columnIndex) {
            COL_REVISION -> item.revision
            COL_NODE -> item.node
            COL_DATE -> item.date
            COL_AUTHOR -> item.author
            else -> item.message
        }
    }

    companion object {
        const val COL_REVISION = 0
        const val COL_NODE = 1
        const val COL_DATE = 2
        const val COL_AUTHOR = 3
    }
}
