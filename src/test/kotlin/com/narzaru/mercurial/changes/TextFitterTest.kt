package com.narzaru.mercurial.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFitterTest {

    private val monospace: (String) -> Int = { it.length }

    private fun fit(text: String, budget: Int) = TextFitter.fit(text, budget, monospace)

    @Test
    fun `a text that fits stays whole`() {
        val fitted = fit("abcdef", budget = 6)

        assertEquals("abcdef", fitted.text)
        assertFalse(fitted.truncated)
    }

    @Test
    fun `a long text is cut with an ellipsis`() {
        val fitted = fit("abcdefghij", budget = 5)

        assertEquals("abcd…", fitted.text)
        assertTrue(fitted.truncated)
        assertEquals(5, fitted.text.length)
    }

    @Test
    fun `a cut text is not wider than the budget`() {
        for (budget in 2..12) {
            val fitted = fit("abcdefghijklmnop", budget)

            assertTrue("бюджет $budget", monospace(fitted.text) <= budget)
        }
    }

    @Test
    fun `a zero or negative budget leaves the text as is`() {
        assertEquals("abc", fit("abc", budget = 0).text)
        assertFalse(fit("abc", budget = 0).truncated)
        assertEquals("abc", fit("abc", budget = -10).text)
    }

    @Test
    fun `an empty text does not break`() {
        assertEquals("", fit("", budget = 5).text)
    }
}
