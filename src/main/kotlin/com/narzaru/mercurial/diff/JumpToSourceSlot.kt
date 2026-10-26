package com.narzaru.mercurial.diff

object JumpToSourceSlot {

    fun <T> fill(actions: List<T>, isJumpToSource: (T) -> Boolean, replacement: T): List<T> {
        val index = actions.indexOfFirst(isJumpToSource)
        if (index < 0) return actions + replacement
        return actions.toMutableList().also { it[index] = replacement }
    }
}
