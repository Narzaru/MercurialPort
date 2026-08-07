package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HgContentCacheTest {

    private val cache = HgContentCache()

    @Test
    fun `отдаёт положенное содержимое`() {
        cache.put("k", "текст")

        assertEquals("текст", cache.get("k")?.text)
    }

    @Test
    fun `незнакомый ключ не найден`() {
        assertNull(cache.get("k"))
    }

    @Test
    fun `отрицательный ответ кэшируется и отличим от отсутствия записи`() {
        // «Файла в этой ревизии не было» — такой же стабильный факт, и стоит он того же
        // запуска hg; повторно выяснять его незачем.
        cache.put("k", null)

        val entry = cache.get("k")
        assertNotNull(entry)
        assertNull(entry?.text)
    }

    @Test
    fun `ревизия входит в ключ`() {
        assertEquals("12|src/a.kt", HgContentCache.key("12", "src/a.kt"))
        assert(HgContentCache.key("12", "a.kt") != HgContentCache.key("13", "a.kt"))
    }

    @Test
    fun `разделители пути в ключе нормализуются`() {
        assertEquals(HgContentCache.key("12", "src/a.kt"), HgContentCache.key("12", "src\\a.kt"))
    }

    @Test
    fun `размер ограничен, старые записи вытесняются`() {
        val small = HgContentCache(maxEntries = 3)

        for (i in 1..5) small.put("k$i", "v$i")

        assertEquals(3, small.size())
        assertNull(small.get("k1"))
        assertEquals("v5", small.get("k5")?.text)
    }

    @Test
    fun `обращение продлевает жизнь записи`() {
        val small = HgContentCache(maxEntries = 2)
        small.put("a", "1")
        small.put("b", "2")

        small.get("a")   // «a» снова самая свежая, вытесниться должна «b»
        small.put("c", "3")

        assertEquals("1", small.get("a")?.text)
        assertNull(small.get("b"))
    }

    @Test
    fun `слишком большой файл не кэшируется`() {
        // Один такой вытеснил бы весь остальной кэш, а открывают их редко.
        val small = HgContentCache(maxEntryChars = 10)

        small.put("k", "x".repeat(11))

        assertNull(small.get("k"))
    }

    @Test
    fun `файл на границе размера кэшируется`() {
        val small = HgContentCache(maxEntryChars = 10)

        small.put("k", "x".repeat(10))

        assertNotNull(small.get("k"))
    }

    @Test
    fun `очистка убирает всё`() {
        cache.put("a", "1")
        cache.put("b", null)

        cache.clear()

        assertEquals(0, cache.size())
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
    }
}
