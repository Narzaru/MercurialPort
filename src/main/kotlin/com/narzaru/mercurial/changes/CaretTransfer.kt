package com.narzaru.mercurial.changes

data class DiffCaret(val line: Int, val column: Int)

object CaretTransfer {

    fun toDiff(fileLine: Int?, fileColumn: Int?): DiffCaret? {
        val line = fileLine?.takeIf { it >= 0 } ?: return null
        val column = fileColumn?.coerceAtLeast(0) ?: 0
        if (line == 0 && column == 0) return null
        return DiffCaret(line, column)
    }

    fun lineOnRightSide(fileLine: Int, showsFileDocument: Boolean, translate: (Int) -> Int?): Int {
        if (showsFileDocument) return fileLine
        return translate(fileLine)?.takeIf { it >= 0 } ?: fileLine
    }

    fun columnInLine(column: Int, lineLength: Int): Int = column.coerceIn(0, lineLength.coerceAtLeast(0))

    fun fileTarget(transferred: Int?, itemLineNumber: Int): Int? =
        transferred ?: (itemLineNumber - 1).takeIf { itemLineNumber > 0 }
}
