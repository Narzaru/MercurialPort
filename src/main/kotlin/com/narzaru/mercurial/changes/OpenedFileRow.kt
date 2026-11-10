package com.narzaru.mercurial.changes

data class OpenedFileRow(val row: Int, val select: Boolean) {

    companion object {

        fun of(rowKeys: List<String?>, selectedRows: Set<Int>, key: String): OpenedFileRow? {
            val matching = rowKeys.indices.filter { rowKeys[it] == key }
            if (matching.isEmpty()) return null
            val selectedMatch = matching.firstOrNull { it in selectedRows }
                ?: return OpenedFileRow(matching.first(), select = true)
            return OpenedFileRow(selectedMatch, select = selectedRows.size > 1)
        }
    }
}
