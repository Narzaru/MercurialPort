package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefetchPlanTest {

    private val files = listOf("a", "b", "c", "d", "e")

    private fun around(key: String) = PrefetchPlan.around(files, key) { it }

    @Test
    fun `two files below and one above are prefetched`() {
        assertEquals(listOf("d", "e", "b"), around("c"))
    }

    @Test
    fun `the first file has nothing above it`() {
        assertEquals(listOf("b", "c"), around("a"))
    }

    @Test
    fun `the last file has nothing below it`() {
        assertEquals(listOf("d"), around("e"))
    }

    @Test
    fun `a single file has no neighbours`() {
        assertEquals(emptyList<String>(), PrefetchPlan.around(listOf("a"), "a") { it })
    }

    @Test
    fun `a file gone from the list prefetches nothing`() {
        assertEquals(emptyList<String>(), around("z"))
    }

    @Test
    fun `an empty list prefetches nothing`() {
        assertEquals(emptyList<String>(), PrefetchPlan.around(emptyList<String>(), "a") { it })
    }
}
