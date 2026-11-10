package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentKeysTest {

    @Test
    fun `a new key goes first and the eldest past the limit are evicted`() {
        assertEquals(
            RecentKeys(listOf("c", "a"), listOf("b")),
            RecentKeys.promote(listOf("a", "b"), "c", 2)
        )
    }

    @Test
    fun `a known key moves to the front without evictions`() {
        assertEquals(
            RecentKeys(listOf("b", "a", "c"), emptyList()),
            RecentKeys.promote(listOf("a", "b", "c"), "b", 3)
        )
    }

    @Test
    fun `duplicates in the stored order are collapsed and never evicted twice`() {
        assertEquals(
            RecentKeys(listOf("c", "a"), listOf("b")),
            RecentKeys.promote(listOf("a", "a", "b", "c", "b"), "c", 2)
        )
    }
}
