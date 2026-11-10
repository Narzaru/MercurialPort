package com.narzaru.mercurial.changes

object BranchSuggestions {

    const val NONE = -1

    fun matching(names: List<String>, typed: String): List<String> {
        val query = typed.trim()
        if (query.isEmpty()) return names
        val (prefixed, inner) = names
            .filter { it.contains(query, ignoreCase = true) }
            .partition { it.startsWith(query, ignoreCase = true) }
        return prefixed + inner
    }

    fun moved(highlighted: Int, count: Int, down: Boolean): Int {
        if (count <= 0) return NONE
        val current = highlighted.coerceIn(NONE, count - 1)
        return if (down) minOf(current + 1, count - 1) else maxOf(current - 1, NONE)
    }
}
