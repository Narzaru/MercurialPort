package com.narzaru.mercurial.hg

class HgContentCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxEntryChars: Int = DEFAULT_MAX_ENTRY_CHARS
) {

    class Entry(val text: String?)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Entry>) = size > maxEntries
    }

    @Synchronized
    fun get(key: String): Entry? = entries[key]

    @Synchronized
    fun put(key: String, text: String?) {
        if (text != null && text.length > maxEntryChars) return
        entries[key] = Entry(text)
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        const val DEFAULT_MAX_ENTRIES = 64
        const val DEFAULT_MAX_ENTRY_CHARS = 1_000_000

        fun key(rev: String, path: String): String = "$rev|${HgPaths.key(path)}"
    }
}
