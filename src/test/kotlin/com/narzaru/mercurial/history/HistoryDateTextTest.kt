package com.narzaru.mercurial.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryDateTextTest {

    @Test
    fun `в колонке остаётся только день`() {
        assertEquals("2026-01-01", HistoryDateText.day("2026-01-01 12:00 +0300"))
    }

    @Test
    fun `дата без времени не меняется`() {
        assertEquals("2026-01-01", HistoryDateText.day("2026-01-01"))
    }

    @Test
    fun `полное значение идёт в тултип`() {
        assertEquals("2026-01-01 12:00 +0300", HistoryDateText.full("2026-01-01 12:00 +0300"))
    }

    @Test
    fun `у пустой даты тултипа нет`() {
        assertEquals("", HistoryDateText.day("   "))
        assertNull(HistoryDateText.full("   "))
    }
}
