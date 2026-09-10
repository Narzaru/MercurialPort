package com.narzaru.mercurial.changes

enum class DiffSide { LEFT, RIGHT }

object CaretTransfer {

    fun toDiff(fileLine: Int?): Int? = fileLine?.takeIf { it > 0 }

    fun toFile(side: DiffSide, diffLine: Int?): Int? =
        if (side == DiffSide.RIGHT) diffLine?.takeIf { it >= 0 } else null

    fun fileTarget(transferred: Int?, itemLineNumber: Int): Int? =
        transferred ?: (itemLineNumber - 1).takeIf { itemLineNumber > 0 }
}
