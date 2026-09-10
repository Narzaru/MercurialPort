package com.narzaru.mercurial.changes

object PrefetchPlan {

    val OFFSETS = listOf(1, 2, -1)

    fun <T> around(items: List<T>, key: String, keyOf: (T) -> String): List<T> {
        val index = items.indexOfFirst { keyOf(it) == key }
        if (index < 0) return emptyList()
        return OFFSETS.mapNotNull { items.getOrNull(index + it) }
    }
}
