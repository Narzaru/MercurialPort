package com.narzaru.mercurial.changes

data class RecentKeys(val kept: List<String>, val evicted: List<String>) {

    companion object {

        fun promote(order: List<String>, key: String, max: Int): RecentKeys {
            val updated = (listOf(key) + order.filter { it != key }).distinct()
            return RecentKeys(updated.take(max), updated.drop(max))
        }
    }
}
