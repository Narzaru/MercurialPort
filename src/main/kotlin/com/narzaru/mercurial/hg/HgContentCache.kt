package com.narzaru.mercurial.hg

/**
 * Кэш содержимого файлов на фиксированной ревизии.
 *
 * Каждый `hg cat` — это запуск Mercurial целиком: только старт интерпретатора занимает
 * порядка четверти секунды, на фоне которой само чтение файла бесплатно. Содержимое
 * ревизии при этом неизменно, так что повторно спрашивать его незачем.
 *
 * Кэшируется и отрицательный ответ: «файла в этой ревизии не было» — такой же
 * стабильный факт, а стоит его выяснение ровно столько же.
 */
class HgContentCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxEntryChars: Int = DEFAULT_MAX_ENTRY_CHARS
) {

    /** Обёртка отличает «закэширован null» от «записи нет». */
    class Entry(val text: String?)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Entry>) = size > maxEntries
    }

    /** @return запись или null, если содержимое ещё не запрашивали. */
    @Synchronized
    fun get(key: String): Entry? = entries[key]

    @Synchronized
    fun put(key: String, text: String?) {
        // Огромный файл вытеснил бы весь остальной кэш, а открывают такие редко.
        if (text != null && text.length > maxEntryChars) return
        entries[key] = Entry(text)
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size

    companion object {
        const val DEFAULT_MAX_ENTRIES = 64
        const val DEFAULT_MAX_ENTRY_CHARS = 1_000_000

        /** Ревизия входит в ключ: у разных ревизий содержимое одного пути разное. */
        fun key(rev: String, path: String): String = "$rev|${HgPaths.normalize(path)}"
    }
}
