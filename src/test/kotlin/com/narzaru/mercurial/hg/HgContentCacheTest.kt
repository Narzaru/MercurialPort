package com.narzaru.mercurial.hg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HgContentCacheTest {

    private val cache = HgContentCache()

    @Test
    fun `stored content is returned back`() {
        cache.put("k", "текст")

        assertEquals("текст", cache.get("k")?.text)
    }

    @Test
    fun `an unknown key is not found`() {
        assertNull(cache.get("k"))
    }

    @Test
    fun `a negative answer is cached and differs from a missing entry`() {
        cache.put("k", null)

        val entry = cache.get("k")
        assertNotNull(entry)
        assertNull(entry?.text)
    }

    @Test
    fun `the revision is part of the key`() {
        assertEquals("12|src/a.kt", HgContentCache.key("12", "src/a.kt"))
        assert(HgContentCache.key("12", "a.kt") != HgContentCache.key("13", "a.kt"))
    }

    @Test
    fun `path separators in the key are normalized`() {
        assertEquals(HgContentCache.key("12", "src/a.kt"), HgContentCache.key("12", "src\\a.kt"))
    }

    @Test
    fun `paths differing only in case share one key`() {
        assertEquals(HgContentCache.key("12", "src/a.kt"), HgContentCache.key("12", "Src/A.kt"))
    }

    @Test
    fun `the cache is bounded and evicts the oldest entries`() {
        val small = HgContentCache(maxEntries = 3)

        for (i in 1..5) small.put("k$i", "v$i")

        assertNull(small.get("k1"))
        assertEquals("v5", small.get("k5")?.text)
    }

    @Test
    fun `reading an entry extends its life`() {
        val small = HgContentCache(maxEntries = 2)
        small.put("a", "1")
        small.put("b", "2")

        small.get("a")
        small.put("c", "3")

        assertEquals("1", small.get("a")?.text)
        assertNull(small.get("b"))
    }

    @Test
    fun `a file above the size limit is not cached`() {
        val small = HgContentCache(maxEntryChars = 10)

        small.put("k", "x".repeat(11))

        assertNull(small.get("k"))
    }

    @Test
    fun `a file exactly at the size limit is cached`() {
        val small = HgContentCache(maxEntryChars = 10)

        small.put("k", "x".repeat(10))

        assertNotNull(small.get("k"))
    }

    @Test
    fun `clearing removes everything`() {
        cache.put("a", "1")
        cache.put("b", null)

        cache.clear()

        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
    }
}
