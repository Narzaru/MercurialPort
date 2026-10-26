package com.narzaru.mercurial.changes

object CaretTransfer {

    fun toDiff(fileLine: Int?): Int? = fileLine?.takeIf { it > 0 }

    fun toFile(diffLine: Int?, showsFileDocument: Boolean, translate: (Int) -> Int?): Int? {
        val line = diffLine?.takeIf { it >= 0 } ?: return null
        if (showsFileDocument) return line
        return translate(line)?.takeIf { it >= 0 } ?: line
    }

    fun fileTarget(transferred: Int?, itemLineNumber: Int): Int? =
        transferred ?: (itemLineNumber - 1).takeIf { itemLineNumber > 0 }
}
