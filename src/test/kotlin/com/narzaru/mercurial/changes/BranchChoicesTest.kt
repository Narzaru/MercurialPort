package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchChoicesTest {

    private val choices = BranchChoices()

    @Test
    fun `the first opening fetches the branches`() {
        val stamp = choices.startFetch()

        assertNotNull(stamp)
        assertTrue(choices.fetched(stamp!!, listOf("default", "stable")))
        assertEquals(listOf("default", "stable"), choices.names)
    }

    @Test
    fun `a fetch is not started twice at once`() {
        choices.startFetch()

        assertNull(choices.startFetch())
    }

    @Test
    fun `fetched branches are reused until invalidated`() {
        choices.fetched(choices.startFetch()!!, listOf("default"))

        assertNull(choices.startFetch())

        choices.invalidate()
        assertNotNull(choices.startFetch())
    }

    @Test
    fun `a failed fetch is retried on the next opening`() {
        choices.fetched(choices.startFetch()!!, listOf("default"))
        choices.invalidate()

        assertFalse(choices.fetched(choices.startFetch()!!, null))
        assertEquals(listOf("default"), choices.names)
        assertNotNull(choices.startFetch())
    }

    @Test
    fun `a fetch overtaken by an invalidation is shown but fetched again`() {
        val stamp = choices.startFetch()!!
        choices.invalidate()

        assertTrue(choices.fetched(stamp, listOf("default")))
        assertNotNull(choices.startFetch())
    }

    @Test
    fun `an unchanged list needs no update`() {
        choices.fetched(choices.startFetch()!!, listOf("default"))
        choices.invalidate()

        assertFalse(choices.fetched(choices.startFetch()!!, listOf("default")))
    }
}
