package com.narzaru.mercurial.history

class HistoryRenameTrail(
    private val revisionsBelow: (fromRow: Int) -> List<String>,
    private val renameSourceOf: (revision: String, path: String) -> String?,
    private val maxLookups: Int = DEFAULT_MAX_LOOKUPS
) {

    fun <T : Any> follow(path: String, fromRow: Int, read: (path: String) -> T?): Found<T> {
        var current = path
        read(current)?.let { return Found(it, current) }

        for (revision in revisionsBelow(fromRow).take(maxLookups)) {
            val source = renameSourceOf(revision, current) ?: continue
            if (source.equals(current, ignoreCase = true)) continue
            current = source
            read(current)?.let { return Found(it, current) }
        }
        return Found(null, current)
    }

    data class Found<T : Any>(val content: T?, val path: String)

    companion object {
        const val DEFAULT_MAX_LOOKUPS = 8
    }
}
