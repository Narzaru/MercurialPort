package com.narzaru.mercurial.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTooltipTextTest {

    @Test
    fun `text wider than the limit is wrapped into a block`() {
        val html = HistoryTooltipText.wrapped("fix the thing", 420, textWidthPx = 900)

        assertEquals("<html><body><div width=\"420\">fix the thing</div></body></html>", html)
    }

    @Test
    fun `text that fits stays plain so the tooltip is no wider than its text`() {
        val html = HistoryTooltipText.wrapped("2026-08-07 15:46 +0700", 420, textWidthPx = 150)

        assertEquals("<html><body>2026-08-07 15:46 +0700</body></html>", html)
    }

    @Test
    fun `markup characters do not leak into the html`() {
        val html = HistoryTooltipText.wrapped("a < b & c > d", 100, textWidthPx = 300)

        assertTrue(html!!.contains("a &lt; b &amp; c &gt; d"))
    }

    @Test
    fun `blank text has no tooltip`() {
        assertNull(HistoryTooltipText.wrapped("   ", 420, textWidthPx = 10))
    }
}
