package com.narzaru.mercurial.history

class HistoryRenameTrail(
    private val revisionsBelow: (fromRow: Int) -> List<String>,
    private val renameSourceOf: (revision: String, path: String) -> String?,
    private val maxLookups: Int = DEFAULT_MAX_LOOKUPS
) {

    fun follow(path: String, fromRow: Int, readText: (path: String) -> String?): Found {
        var current = path
        readText(current)?.let { return Found(it, current) }

        for (revision in revisionsBelow(fromRow).take(maxLookups)) {
            val source = renameSourceOf(revision, current) ?: continue
            if (source.equals(current, ignoreCase = true)) continue
            current = source
            readText(current)?.let { return Found(it, current) }
        }
        return Found(null, current)
    }

    data class Found(val text: String?, val path: String)

    companion object {
        const val DEFAULT_MAX_LOOKUPS = 8
    }
}
