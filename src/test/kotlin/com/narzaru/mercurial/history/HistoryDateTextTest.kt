package com.narzaru.mercurial.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryDateTextTest {

    @Test
    fun `the column keeps only the day`() {
        assertEquals("2026-01-01", HistoryDateText.day("2026-01-01 12:00 +0300"))
    }

    @Test
    fun `a date without a time is left as is`() {
        assertEquals("2026-01-01", HistoryDateText.day("2026-01-01"))
    }

    @Test
    fun `the full value goes into the tooltip`() {
        assertEquals("2026-01-01 12:00 +0300", HistoryDateText.full("2026-01-01 12:00 +0300"))
    }

    @Test
    fun `a blank date has no tooltip`() {
        assertEquals("", HistoryDateText.day("   "))
        assertNull(HistoryDateText.full("   "))
    }
}
